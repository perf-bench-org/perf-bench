package perfbench.loadtest;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Logger;
import us.abstracta.jmeter.javadsl.core.DslTestPlan;

/**
 * Точка входа.
 *
 * <p><b>Гарантия завершения процесса.</b> JMeter в embedded-режиме создаёт не-daemon потоки, и
 * JVM может не выйти сама. Контейнер запускается через {@code docker compose run --rm}, поэтому
 * повисший процесс заблокирует следующий прогон. {@code System.exit} вызывается явно последней
 * строкой во всех ветках.
 *
 * <p>Порядок обязателен: полный teardown плана → сбор метрик → отчёт → выход.
 */
public final class Main {

  private static final String DEFAULT_CONFIG = "/config/test.properties";

  private Main() {
  }

  public static void main(String[] args) {
    String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG;
    int code;
    try {
      code = run(configPath);
    } catch (Exception e) {
      // До инициализации логирования писать некуда, кроме stderr.
      System.err.println("ОШИБКА: " + e.getMessage());
      e.printStackTrace(System.err);
      code = 1;
    }
    System.out.flush();
    System.err.flush();
    System.exit(code);
  }

  private static int run(String configPath) {
    try {
      Config.load(configPath);
    } catch (Config.ConfigException e) {
      System.err.println(e.getMessage());
      return 1;
    }

    VmClient.init(Config.vmUrl());

    return Config.mode() == LoadMode.REPORT ? recalculate() : execute();
  }

  // --- прогон нагрузки -------------------------------------------------------------------

  private static int execute() {
    Run.init();
    Logger log = Logging.log();

    List<Stage> stages = Config.stages();
    int threads = Threads.total(stages);
    log.info("Прогон {}: режим {}, ступеней {}, потоков всего {}",
        Run.id(), Config.mode().lower(), stages.size(), threads);
    stages.forEach(stage -> log.info("  ступень: {}, потоков {}, такт {} с",
        stage, Threads.required(stage.targetRpm()),
        String.format(java.util.Locale.ROOT, "%.1f", Threads.pacingSec(stage.targetRpm()))));
    Config.warnings().forEach(w -> log.warn("конфигурация: {}", w));

    Preflight.Result preflight;
    DslTestPlan plan;
    try {
      Dataset.load(Config.datasetPath(), Config.datasetDelimiter(), Config.seed());
      log.info("Датасет: {} промптов, sha256 {}", Dataset.size(), Dataset.sha256());

      preflight = Preflight.check();
      preflight.warnings().forEach(w -> log.warn("preflight: {}", w));
      log.info("Модель: {}, vLLM {}", preflight.model(), preflight.vllmVersion());
      log.info("Имена метрик: {}", VllmMetrics.names());

      plan = TestPlan.build();
    } catch (RuntimeException e) {
      // Нагрузка не пошла, результатов нет — каталог убирается, чтобы тот же LT_RUN_ID можно
      // было запустить снова, починив причину.
      Run.discardIfNoResults();
      throw e;
    }

    Instant startedAt = Instant.now();
    Instant endedAt;
    boolean runFailed = false;
    try {
      log.info("Нагрузка пошла. Общая длительность профиля {} с плюс слив запросов, "
              + "остающихся в полёте на момент конца профиля.",
          stages.stream().mapToLong(s -> s.total().toSeconds()).sum());
      plan.run();
      endedAt = Instant.now();
      log.info("Прогон завершён за {} с", Duration.between(startedAt, endedAt).toSeconds());
    } catch (Exception e) {
      endedAt = Instant.now();
      runFailed = true;
      log.error("Прогон завершился с ошибкой, отчёт будет сформирован по имеющимся данным: {}",
          e.toString(), e);
    }

    // Окна ступеней отсчитываются от старта первого запроса: между вызовом plan.run() и первым
    // запросом проходит построение и клонирование дерева плана, и на коротком разгоне это
    // смещение уже заметно.
    Instant loadStart = Files.isRegularFile(Run.jtl())
        ? JtlStats.loadStart(Run.jtl()).orElse(startedAt)
        : startedAt;
    if (loadStart.equals(startedAt)) {
      log.warn("В {} нет ни одного сэмпла, окна ступеней отсчитываются от момента запуска движка",
          Run.RESULTS_JTL);
    }
    List<HoldWindow> windows = HoldWindow.of(loadStart, stages);
    windows.forEach(w -> log.info("Окно удержания: {}", w));

    HoldWindow last = windows.get(windows.size() - 1);
    if (last.end().isAfter(endedAt)) {
      log.warn("Прогон закончился раньше конца профиля ({} против {}): окна удержания частично "
          + "пусты, фактический RPM и оценка занижены", endedAt, last.end());
    }

    RunMeta meta = new RunMeta(Run.id(), Config.mode(), threads, startedAt, loadStart, endedAt,
        windows, Dataset.sha256(), Dataset.size(), preflight.model(), preflight.vllmVersion(),
        VllmMetrics.names());
    Run.writeMeta(meta);

    int reportCode = buildReport(meta, false, log);
    return runFailed ? 1 : reportCode;
  }

  // --- пересчёт отчёта -------------------------------------------------------------------

  private static int recalculate() {
    Run.open(Config.runId());
    Logger log = Logging.log();

    RunMeta meta = Run.readMeta();
    log.info("Пересчёт отчёта прогона {}: режим {}, ступеней {}",
        meta.runId(), meta.mode().lower(), meta.windows().size());

    if (meta.metricNames().isEmpty()) {
      log.warn("В {} нет разрешённых имён метрик — серверная часть отчёта будет пустой",
          Run.RUN_META);
    }
    // Имена берутся из артефактов прогона, а не из живого vLLM: пересчёт должен работать и при
    // выключенной модели, и после её обновления, воспроизводя исходный прогон, а не текущий стенд.
    VllmMetrics.adopt(meta.metricNames());

    return buildReport(meta, true, log);
  }

  // --- общая часть -----------------------------------------------------------------------

  private static int buildReport(RunMeta meta, boolean recalculation, Logger log) {
    try {
      awaitStorage(meta, recalculation, log);

      Map<Integer, JtlStats.Result> clientByStage = Files.isRegularFile(Run.jtl())
          ? JtlStats.collect(Run.jtl(), meta.windows())
          : Map.of();
      if (clientByStage.isEmpty()) {
        log.warn("{} отсутствует: клиентская часть отчёта будет пустой", Run.RESULTS_JTL);
      }

      List<StageReport> stages = new ArrayList<>(meta.windows().size());
      for (HoldWindow window : meta.windows()) {
        JtlStats.Result client =
            clientByStage.getOrDefault(window.stageNumber(), JtlStats.Result.empty());
        VllmMetrics.ServerMetrics server = collectServer(window, log);
        Scoring.ScoreResult score =
            Scoring.evaluate(window, client, server, Config.e2eSlaSec());
        stages.add(new StageReport(window, client, server, score));
      }

      java.nio.file.Path file = Run.reportFile(recalculation);
      Report.write(file, meta, stages, Config.e2eSlaSec(), recalculation);
      log.info("Отчёт: {}", file);
      stages.forEach(s -> log.info("  ступень {} ({} RPM): оценка {}",
          s.window().stageNumber(), s.window().targetRpm(), s.score().score()));
      return 0;
    } catch (RuntimeException e) {
      log.error("Отчёт не сформирован: {}", e.toString(), e);
      return 1;
    }
  }

  private static VllmMetrics.ServerMetrics collectServer(HoldWindow window, Logger log) {
    if (VllmMetrics.names().isEmpty()) {
      return VllmMetrics.ServerMetrics.unavailable();
    }
    try {
      return VllmMetrics.collect(window);
    } catch (VmClient.VmException e) {
      log.error("Серверные метрики ступени {} не собраны: {}", window.stageNumber(), e.getMessage());
      return VllmMetrics.ServerMetrics.unavailable();
    }
  }

  /**
   * Ждёт, пока последний скрейп окна удержания доедет до хранилища.
   *
   * <p>VictoriaMetrics по умолчанию не отдаёт данные за последние 30 с, а vmagent досылает
   * последний скрейп не мгновенно. Без паузы хвост окна оказался бы пустым, и отчёт получился бы
   * по неполным данным, не сообщив об этом.
   */
  private static void awaitStorage(RunMeta meta, boolean recalculation, Logger log) {
    if (recalculation || meta.windows().isEmpty()) {
      return;
    }
    Instant ready = meta.windows().get(meta.windows().size() - 1).end().plus(Config.vmSettle());
    Duration wait = Duration.between(Instant.now(), ready);
    if (wait.isNegative() || wait.isZero()) {
      return;
    }
    log.info("Ожидание {} с, чтобы последний скрейп окна удержания доехал до хранилища",
        wait.toSeconds());
    try {
      Thread.sleep(wait.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Ожидание прервано, метрики хвоста окна могут отсутствовать");
    }
  }
}

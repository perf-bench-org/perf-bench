package perfbench.loadtest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Формирование {@code report.txt} — основного и единственного результата работы стенда.
 *
 * <p>Величины подписываются тем, чем являются. «Максимум p95» никогда не сокращается до «Max»:
 * это разные вещи, и вторая по бакетам гистограммы не вычисляется в принципе. Единственная строка
 * с настоящим максимумом — {@code E2E Latency Max}, она считается по сырым наблюдениям клиента.
 */
public final class Report {

  /** Интервал скрейпа vmagent. Ограничивает Queue Max и KV Cache Max, поэтому печатается. */
  public static final int SCRAPE_INTERVAL_SEC = 5;

  private static final String WIDE = "=".repeat(80);
  private static final String THIN = "-".repeat(80);
  private static final String UNAVAILABLE = "недоступно";

  private Report() {
  }

  /**
   * Пишет отчёт.
   *
   * @param file           куда писать
   * @param meta           параметры прогона
   * @param stages         результаты по ступеням
   * @param e2eSlaSec      порог штрафа за время отклика
   * @param recalculation  пересчёт по уже собранным данным, а не свежий прогон
   */
  public static void write(Path file, RunMeta meta, List<StageReport> stages, double e2eSlaSec,
      boolean recalculation) {
    StringBuilder out = new StringBuilder();
    header(out, meta, e2eSlaSec, recalculation);
    // Разгон в артефактах отдельно не хранится, но однозначно восстанавливается: он занимает всё
    // время от старта нагрузки (или от конца предыдущей ступени) до начала окна удержания.
    Instant previousEnd = meta.loadStart();
    for (StageReport stage : stages) {
      stage(out, stage, previousEnd);
      previousEnd = stage.window().end();
    }
    if (stages.size() > 1) {
      summary(out, stages);
    }
    notes(out);

    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      writer.write(out.toString());
    } catch (IOException e) {
      throw new ReportException("отчёт " + file + " не пишется: " + e.getMessage(), e);
    }
  }

  private static void header(StringBuilder out, RunMeta meta, double e2eSlaSec,
      boolean recalculation) {
    line(out, WIDE);
    line(out, " vLLM BENCHMARK REPORT");
    line(out, WIDE);
    kv(out, "Run ID", meta.runId());
    kv(out, "Mode", meta.mode().lower());
    kv(out, "Report", recalculation ? "пересчёт по собранным данным" : "по свежему прогону");
    kv(out, "Model", orUnknown(meta.model()));
    kv(out, "vLLM version", orUnknown(meta.vllmVersion()));
    kv(out, "Started at", str(meta.startedAt()));
    kv(out, "Load started at", str(meta.loadStart()));
    kv(out, "Ended at", str(meta.endedAt()));
    kv(out, "Generated at", Instant.now().toString());
    out.append(System.lineSeparator());

    line(out, " Load generator");
    kv2(out, "Engine", "штатная JMeter Thread Group + Constant Throughput Timer"
        + " (all active threads in current thread group)");
    kv2(out, "Threads (total)", meta.threads()
        + "  (сумма по ступеням; RPM / 60 x " + Threads.ITERATION_SEC
        + " s x " + Threads.SAFETY + " на ступень)");
    kv2(out, "Dataset", meta.datasetSize() + " промптов, sha256 " + shortSha(meta.datasetSha()));
    kv2(out, "Request", "max_tokens=" + RequestBody.MAX_TOKENS
        + ", ignore_eos=" + RequestBody.IGNORE_EOS
        + ", temperature=" + RequestBody.TEMPERATURE
        + ", top_p=" + RequestBody.TOP_P
        + ", stream=" + RequestBody.STREAM);
    kv2(out, "E2E SLA threshold", fmt(e2eSlaSec, 1) + " s  (scoring.e2eSlaSec)");
    out.append(System.lineSeparator());

    line(out, " Metric names resolved from vLLM");
    // Сортировка обязательна: имена приходят из неупорядоченных Map, и без неё отчёт того же
    // прогона, пересчитанный режимом report, отличался бы от исходного порядком строк.
    meta.metricNames().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> kv2(out, entry.getKey(), entry.getValue()));
    if (!meta.metricNames().containsKey(VllmMetrics.M_ITL)) {
      kv2(out, VllmMetrics.M_ITL, UNAVAILABLE + " — в этой версии vLLM отдельной гистограммы нет");
    }
    out.append(System.lineSeparator());

    line(out, " Aggregation");
    kv2(out, "Histogram quantile", "p" + (int) (VllmMetrics.QUANTILE * 100));
    kv2(out, "Sub-window", VllmMetrics.SUBWINDOW_SEC + " s"
        + "  (влияет на p95 avg/max и на Throughput Max)");
    kv2(out, "Scrape interval", SCRAPE_INTERVAL_SEC + " s"
        + "  (ограничивает Queue Max и KV Cache Max)");
    out.append(System.lineSeparator());
  }

  private static void stage(StringBuilder out, StageReport stage, Instant rampStart) {
    HoldWindow window = stage.window();
    JtlStats.Result client = stage.client();
    VllmMetrics.ServerMetrics server = stage.server();

    line(out, THIN);
    line(out, " STAGE " + window.stageNumber() + " — target " + window.targetRpm() + " RPM");
    line(out, THIN);
    kv(out, "Hold window", window.start() + " .. " + window.end()
        + "  (" + window.duration().toSeconds() + " s)");
    if (rampStart != null && rampStart.isBefore(window.start())) {
      kv(out, "Ramp", java.time.Duration.between(rampStart, window.start()).toSeconds() + " s"
          + "   (кратен такту потока; в расчётах не участвует)");
    }
    kv(out, "Threads", window.threads() + ", такт "
        + fmt(Threads.pacingSec(window.targetRpm()), 1) + " s"
        + "   (ответ медленнее такта делает целевой RPM недостижимым)");
    out.append(System.lineSeparator());

    line(out, " Client — results.jtl, сырые наблюдения за окно удержания");
    if (client.isEmpty()) {
      kv2(out, "Requests", "за окно удержания не пришло ни одного запроса");
    } else {
      double deviation = client.actualRpm() / window.targetRpm() * 100 - 100;
      kv2(out, "Throughput", fmt(client.actualRpm(), 1) + " RPM   (target "
          + window.targetRpm() + " RPM, " + signed(deviation, 1) + " %)");
      kv2(out, "Total Requests", String.valueOf(client.total()));
      kv2(out, "Successful", String.valueOf(client.successful()));
      // При недоборе RPM это решающая строка: пик у числа потоков ступени означает, что все
      // потоки заняты и упёрлись мы в генератор, а не в модель.
      kv2(out, "Threads (peak observed)", client.peakThreads() + " из " + window.threads()
          + (client.peakThreads() >= window.threads()
          ? "   ВНИМАНИЕ: заняты все потоки ступени, темп ограничен генератором" : ""));
      kv2(out, "Errors", client.errors() + "  (" + fmt(client.errorRate() * 100, 2) + " %)");
      kv2(out, "E2E Latency Avg", fmt(client.e2eAvgSec(), 2) + " s");
      kv2(out, "E2E Latency p95", fmt(client.e2eP95Sec(), 2) + " s");
      kv2(out, "E2E Latency Min", fmt(client.e2eMinSec(), 2) + " s");
      kv2(out, "E2E Latency Max", fmt(client.e2eMaxSec(), 2) + " s"
          + "   (истинный максимум, по отдельным наблюдениям)");
      if (!client.errorReasons().isEmpty()) {
        line(out, "   Error reasons:");
        client.errorReasons().forEach((reason, count) ->
            line(out, "     " + count + " x " + reason));
      }
    }
    out.append(System.lineSeparator());

    line(out, " Server — VictoriaMetrics, окно удержания");
    kv2(out, "KV Cache Avg", opt(server.kvCacheAvgPct(), 1, " %"));
    kv2(out, "KV Cache Max", opt(server.kvCacheMaxPct(), 1, " %")
        + "   (максимум среди снятых значений)");
    kv2(out, "Queue Max", opt(server.queueMax(), 0, "")
        + "   (максимум среди снятых значений)");
    String sub = "   (подокно " + VllmMetrics.SUBWINDOW_SEC + " s)";
    kv2(out, "TTFT p95 (avg)", opt(server.ttftP95AvgSec(), 3, " s") + sub);
    kv2(out, "TTFT p95 (max)", opt(server.ttftP95MaxSec(), 3, " s") + sub);
    kv2(out, "TPOT p95 (avg)", opt(server.tpotP95AvgSec(), 3, " s") + sub);
    kv2(out, "TPOT p95 (max)", opt(server.tpotP95MaxSec(), 3, " s") + sub);
    if (server.itlAvailable()) {
      kv2(out, "ITL p95 (avg)", opt(server.itlP95AvgSec(), 3, " s") + sub);
      kv2(out, "ITL p95 (max)", opt(server.itlP95MaxSec(), 3, " s") + sub);
    } else {
      // Строка помечается недоступной и НЕ заполняется значением TPOT: величины считаются
      // по-разному (TPOT взвешен по запросам, ITL — по токенам), подмена исказит вывод.
      kv2(out, "ITL p95 (avg)", UNAVAILABLE + "   (отдельной гистограммы ITL нет в этой версии)");
      kv2(out, "ITL p95 (max)", UNAVAILABLE);
    }
    kv2(out, "Prompt Throughput Avg", opt(server.promptTpsAvg(), 1, " tok/s"));
    kv2(out, "Prompt Throughput Max", opt(server.promptTpsMax(), 1, " tok/s") + sub);
    kv2(out, "Generation Throughput Avg", opt(server.generationTpsAvg(), 1, " tok/s"));
    kv2(out, "Generation Throughput Max", opt(server.generationTpsMax(), 1, " tok/s") + sub);
    out.append(System.lineSeparator());

    Scoring.ScoreResult score = stage.score();
    line(out, " Score: " + score.score() + " / " + Scoring.MAX_SCORE);
    if (score.penalties().isEmpty()) {
      line(out, "   штрафов нет");
    } else {
      score.penalties().forEach(p -> line(out, "   " + p));
    }
    if (!score.unavailable().isEmpty()) {
      line(out, "   Недоступные метрики (штраф не начислялся):");
      score.unavailable().forEach(u -> line(out, "     - " + u));
    }
    out.append(System.lineSeparator());
  }

  private static void summary(StringBuilder out, List<StageReport> stages) {
    line(out, THIN);
    line(out, " SUMMARY");
    line(out, THIN);
    line(out, String.format(Locale.ROOT, " %-6s %11s %11s %8s %11s %10s %8s %7s",
        "Stage", "Target RPM", "Actual RPM", "Errors", "E2E p95", "Queue Max", "KV Max", "Score"));
    for (StageReport stage : stages) {
      JtlStats.Result client = stage.client();
      line(out, String.format(Locale.ROOT, " %-6d %11d %11s %8s %11s %10s %8s %7d",
          stage.window().stageNumber(),
          stage.window().targetRpm(),
          client.isEmpty() ? "-" : fmt(client.actualRpm(), 1),
          client.isEmpty() ? "-" : fmt(client.errorRate() * 100, 2) + "%",
          client.isEmpty() ? "-" : fmt(client.e2eP95Sec(), 1) + "s",
          stage.server().queueMax().map(v -> fmt(v, 0)).orElse("-"),
          stage.server().kvCacheMaxPct().map(v -> fmt(v, 1) + "%").orElse("-"),
          stage.score().score()));
    }
    out.append(System.lineSeparator());
  }

  private static void notes(StringBuilder out) {
    line(out, THIN);
    line(out, " NOTES");
    line(out, THIN);
    line(out, " - Все величины посчитаны только за окно удержания. Разгон не участвует нигде.");
    line(out, " - С гистограмм снимается единственный квантиль p"
        + (int) (VllmMetrics.QUANTILE * 100) + ". «p95 (max)» — худший наблюдённый участок");
    line(out, "   прогона, а не максимум отдельных наблюдений: последнего в бакетах не");
    line(out, "   существует. Значения зависят от подокна " + VllmMetrics.SUBWINDOW_SEC
        + " s: на более коротком ряд шумнее");
    line(out, "   и его максимум выше. Разрешение p95 ограничено границами бакетов гистограммы.");
    line(out, " - Queue Max и KV Cache Max — максимумы среди снятых значений. Всплеск короче");
    line(out, "   интервала скрейпа (" + SCRAPE_INTERVAL_SEC + " s) в данных не существует.");
    line(out, " - Throughput Max — максимум производной счётчика по подокну "
        + VllmMetrics.SUBWINDOW_SEC + " s. На другом");
    line(out, "   подокне получится другое число, оба формально верны.");
    line(out, " - TPOT и ITL — обе величины стадии decode, различаются способом усреднения:");
    line(out, "   TPOT взвешен по запросам, ITL — по токенам. Стадию prefill не отражает ни одна");
    line(out, "   из них, за неё отвечает TTFT.");
    line(out, " - E2E Latency считается по успешным запросам: оборванный по таймауту запрос дал бы");
    line(out, "   не время отклика модели, а значение request.responseTimeoutSec.");
    line(out, " - Пороги штрафов заданы константами Scoring и почти наверняка требуют настройки.");
    line(out, "   Пересчитать отчёт по уже собранным данным: LT_MODE=report LT_RUN_ID=<run>.");
  }

  private static void line(StringBuilder out, String text) {
    out.append(text).append(System.lineSeparator());
  }

  private static void kv(StringBuilder out, String key, String value) {
    out.append(String.format(Locale.ROOT, " %-18s: %s", key, value))
        .append(System.lineSeparator());
  }

  private static void kv2(StringBuilder out, String key, String value) {
    out.append(String.format(Locale.ROOT, "   %-26s: %s", key, value))
        .append(System.lineSeparator());
  }

  private static String opt(Optional<Double> value, int decimals, String unit) {
    return value.map(v -> fmt(v, decimals) + unit).orElse(UNAVAILABLE);
  }

  private static String fmt(double value, int decimals) {
    return String.format(Locale.ROOT, "%." + decimals + "f", value);
  }

  private static String signed(double value, int decimals) {
    return (value >= 0 ? "+" : "") + fmt(value, decimals);
  }

  private static String str(Instant instant) {
    return instant == null ? UNAVAILABLE : instant.toString();
  }

  private static String orUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static String shortSha(String sha) {
    if (sha == null || sha.isBlank()) {
      return UNAVAILABLE;
    }
    return sha.length() <= 16 ? sha : sha.substring(0, 16) + "...";
  }

  /** Ошибка формирования отчёта. */
  public static class ReportException extends RuntimeException {

    public ReportException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

package perfbench.loadtest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Каталог прогона и его артефакты.
 *
 * <p>Каталог прогона — единственная копия конфигурации, при которой получены его результаты,
 * поэтому повторный запуск с уже использованным идентификатором падает на старте, а не
 * перезаписывает данные.
 */
public final class Run {

  public static final String RESULTS_JTL = "results.jtl";
  public static final String CONFIG_SNAPSHOT = "config.snapshot.properties";
  public static final String RUN_META = "run.meta.properties";
  public static final String REPORT = "report.txt";

  private static final DateTimeFormatter RUN_ID_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private static volatile String id;
  private static volatile Path dir;

  private Run() {
  }

  /**
   * Создаёт каталог прогона и пишет снапшот конфигурации.
   *
   * @throws RunException если каталог уже существует и непуст
   */
  public static void init() {
    id = Config.runId().isEmpty() ? RUN_ID_FORMAT.format(Instant.now()) : Config.runId();
    dir = Config.resultsDir().resolve(id);

    if (Files.exists(dir)) {
      try (Stream<Path> entries = Files.list(dir)) {
        if (entries.findAny().isPresent()) {
          throw new RunException("каталог прогона " + dir + " уже существует и непуст; "
              + "выберите другой " + Config.envName(Config.K_RUN_ID)
              + " — артефакты прошлого прогона не перезаписываются");
        }
      } catch (IOException e) {
        throw new RunException("каталог прогона " + dir + " не читается: " + e.getMessage(), e);
      }
    }

    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new RunException("каталог прогона " + dir + " не создаётся: " + e.getMessage(), e);
    }

    Logging.redirectTo(dir);
    writeConfigSnapshot();
  }

  /**
   * Удаляет каталог прогона, если нагрузка так и не пошла.
   *
   * <p>Падение на конфигурации, датасете или preflight оставляет каталог с одним лишь снапшотом:
   * результатов в нём нет, и защита от перезаписи здесь только мешает — оператор чинит причину и
   * запускает тот же {@code LT_RUN_ID} заново.
   *
   * <p>Признак «нагрузка пошла» — наличие {@code results.jtl}. Если он есть, каталог не трогается
   * ни при каких обстоятельствах.
   */
  public static void discardIfNoResults() {
    if (dir == null || !Files.isDirectory(dir) || Files.exists(dir.resolve(RESULTS_JTL))) {
      return;
    }
    try (Stream<Path> entries = Files.walk(dir)) {
      entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          // Каталог всё равно останется — это хуже неудобства при повторе, но не потеря данных.
        }
      });
    } catch (IOException e) {
      // Молча: исходная причина падения важнее неудачной уборки.
    }
  }

  /** Открывает каталог существующего прогона для режима {@code report}. */
  public static void open(String runId) {
    id = runId;
    dir = Config.resultsDir().resolve(runId);
    if (!Files.isDirectory(dir)) {
      throw new RunException("каталог прогона " + dir + " не найден");
    }
    if (!Files.isRegularFile(dir.resolve(RUN_META))) {
      throw new RunException("в каталоге прогона " + dir + " нет " + RUN_META
          + " — прогон не был завершён или создан другой версией стенда");
    }
    Logging.redirectTo(dir);
  }

  public static String id() {
    checkOpened();
    return id;
  }

  public static Path dir() {
    checkOpened();
    return dir;
  }

  public static Path jtl() {
    return dir().resolve(RESULTS_JTL);
  }

  private static void checkOpened() {
    if (dir == null) {
      throw new IllegalStateException("Run.init()/Run.open() не вызван");
    }
  }

  private static void writeConfigSnapshot() {
    Properties effective = Config.effective();
    Map<String, String> ordered = new LinkedHashMap<>();
    for (String key : Config.keys()) {
      ordered.put(key, effective.getProperty(key, ""));
    }
    write(dir.resolve(CONFIG_SNAPSHOT), "эффективная конфигурация прогона " + id, ordered);
  }

  /** Пишет {@code run.meta.properties}. */
  public static void writeMeta(RunMeta meta) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("run.id", meta.runId());
    values.put("mode", meta.mode().lower());
    values.put("threads", String.valueOf(meta.threads()));
    values.put("started.at", str(meta.startedAt()));
    values.put("load.start.at", str(meta.loadStart()));
    values.put("ended.at", str(meta.endedAt()));
    values.put("dataset.sha256", nullToEmpty(meta.datasetSha()));
    values.put("dataset.size", String.valueOf(meta.datasetSize()));
    values.put("model", nullToEmpty(meta.model()));
    values.put("vllm.version", nullToEmpty(meta.vllmVersion()));
    values.put("stages.count", String.valueOf(meta.windows().size()));
    for (HoldWindow window : meta.windows()) {
      String prefix = "stage." + window.stageNumber() + ".";
      values.put(prefix + "targetRpm", String.valueOf(window.targetRpm()));
      values.put(prefix + "threads", String.valueOf(window.threads()));
      values.put(prefix + "hold.start", str(window.start()));
      values.put(prefix + "hold.end", str(window.end()));
      values.put(prefix + "hold.sec", String.valueOf(window.duration().toSeconds()));
    }
    meta.metricNames().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> values.put("metric." + e.getKey(), e.getValue()));

    write(dir().resolve(RUN_META), "границы окон удержания и параметры прогона " + meta.runId(),
        values);
  }

  /** Читает {@code run.meta.properties} прогона, открытого через {@link #open(String)}. */
  public static RunMeta readMeta() {
    Properties props = new Properties();
    Path file = dir().resolve(RUN_META);
    try (InputStream in = Files.newInputStream(file)) {
      props.load(in);
    } catch (IOException e) {
      throw new RunException(file + " не читается: " + e.getMessage(), e);
    }

    int stageCount = intValue(props, "stages.count", file);
    List<HoldWindow> windows = new ArrayList<>(stageCount);
    for (int i = 1; i <= stageCount; i++) {
      String prefix = "stage." + i + ".";
      windows.add(new HoldWindow(
          i,
          intValue(props, prefix + "targetRpm", file),
          intValue(props, prefix + "threads", file),
          instant(props, prefix + "hold.start", file),
          instant(props, prefix + "hold.end", file)));
    }

    Map<String, String> metrics = new LinkedHashMap<>();
    for (String key : props.stringPropertyNames()) {
      if (key.startsWith("metric.")) {
        metrics.put(key.substring("metric.".length()), props.getProperty(key));
      }
    }

    return new RunMeta(
        props.getProperty("run.id", id),
        LoadMode.parse(props.getProperty("mode", "flat")),
        intValue(props, "threads", file),
        instant(props, "started.at", file),
        instant(props, "load.start.at", file),
        instant(props, "ended.at", file),
        windows,
        props.getProperty("dataset.sha256", ""),
        intValue(props, "dataset.size", file),
        props.getProperty("model", ""),
        props.getProperty("vllm.version", ""),
        metrics);
  }

  /**
   * Имя файла для нового отчёта.
   *
   * <p>В режиме пересчёта исходный {@code report.txt} не перезаписывается: он единственное
   * свидетельство того, какая оценка была получена до правки порогов.
   */
  public static Path reportFile(boolean recalculation) {
    Path base = dir().resolve(REPORT);
    if (!recalculation) {
      return base;
    }
    for (int i = 1; ; i++) {
      Path candidate = dir().resolve("report.recalc-" + i + ".txt");
      if (!Files.exists(candidate)) {
        return candidate;
      }
    }
  }

  private static void write(Path file, String comment, Map<String, String> values) {
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      writer.write("# " + comment);
      writer.newLine();
      for (Map.Entry<String, String> entry : values.entrySet()) {
        writer.write(entry.getKey() + "=" + escape(entry.getValue()));
        writer.newLine();
      }
    } catch (IOException e) {
      throw new RunException(file + " не пишется: " + e.getMessage(), e);
    }
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\");
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String str(Instant instant) {
    return instant == null ? "" : instant.toString();
  }

  private static int intValue(Properties props, String key, Path file) {
    String raw = props.getProperty(key);
    if (raw == null || raw.isBlank()) {
      throw new RunException(file + ": отсутствует ключ " + key);
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new RunException(file + ": ключ " + key + " не целое число: '" + raw + "'");
    }
  }

  private static Instant instant(Properties props, String key, Path file) {
    String raw = props.getProperty(key);
    if (raw == null || raw.isBlank()) {
      throw new RunException(file + ": отсутствует ключ " + key);
    }
    try {
      return Instant.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new RunException(file + ": ключ " + key + " не момент времени ISO-8601: '" + raw + "'");
    }
  }

  /** Только для тестов. */
  static void openUnchecked(String runId, Path directory) {
    id = runId;
    dir = directory;
  }

  /** Только для тестов. */
  static void reset() {
    id = null;
    dir = null;
  }

  /** Ошибка работы с каталогом прогона. */
  public static class RunException extends RuntimeException {

    public RunException(String message) {
      super(message);
    }

    public RunException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

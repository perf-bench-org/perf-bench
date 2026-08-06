package perfbench.loadtest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Эффективная конфигурация прогона: файл {@code .properties}, поверх него переменные окружения с
 * префиксом {@code LT_}.
 *
 * <p>Инициализируется один раз в {@link Main} до построения тест-плана, дальше только читается.
 *
 * <p>Правило имени переменной окружения: {@code LT_} + ключ в верхнем регистре с точками,
 * заменёнными на подчёркивания. {@code flat.rpm} → {@code LT_FLAT_RPM},
 * {@code scoring.e2eSlaSec} → {@code LT_SCORING_E2ESLASEC}.
 *
 * <p>Валидация выполняется до создания тест-плана и собирает <b>все</b> ошибки сразу: разбираться
 * с конфигурацией по одной ошибке за запуск в закрытом контуре слишком дорого.
 */
public final class Config {

  public static final String ENV_PREFIX = "LT_";

  // --- target ---
  public static final String K_TARGET_URL = "target.url";
  public static final String K_TARGET_API_KEY = "target.apiKey";
  public static final String K_VLLM_BASE_URL = "vllm.baseUrl";

  // --- metrics backend ---
  public static final String K_VM_URL = "vm.url";
  public static final String K_VM_SETTLE_SEC = "vm.settleSec";

  // --- load ---
  public static final String K_MODE = "mode";
  public static final String K_STEP_PROFILE = "step.profile";
  public static final String K_FLAT_RPM = "flat.rpm";
  public static final String K_FLAT_RAMP = "flat.ramp";
  public static final String K_FLAT_HOLD = "flat.hold";

  // --- dataset ---
  public static final String K_DATASET_PATH = "dataset.path";
  public static final String K_DATASET_DELIMITER = "dataset.delimiter";
  public static final String K_DATASET_SEED = "dataset.seed";

  // --- engine ---
  public static final String K_CONNECT_TIMEOUT = "request.connectTimeoutSec";
  public static final String K_RESPONSE_TIMEOUT = "request.responseTimeoutSec";

  // --- scoring ---
  public static final String K_E2E_SLA_SEC = "scoring.e2eSlaSec";

  // --- run ---
  public static final String K_RUN_ID = "run.id";
  public static final String K_RESULTS_DIR = "run.resultsDir";

  /** Ключ → значение по умолчанию. Порядок сохраняется в снапшоте конфигурации. */
  private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

  static {
    DEFAULTS.put(K_TARGET_URL, "http://vllm:8000/v1/chat/completions");
    DEFAULTS.put(K_VLLM_BASE_URL, "http://vllm:8000");
    DEFAULTS.put(K_TARGET_API_KEY, "");
    DEFAULTS.put(K_VM_URL, "http://victoria-metrics:8428");
    DEFAULTS.put(K_VM_SETTLE_SEC, "35");
    DEFAULTS.put(K_MODE, "flat");
    DEFAULTS.put(K_STEP_PROFILE, "");
    DEFAULTS.put(K_FLAT_RPM, "180");
    DEFAULTS.put(K_FLAT_RAMP, "300");
    DEFAULTS.put(K_FLAT_HOLD, "1800");
    DEFAULTS.put(K_DATASET_PATH, "/data/prompts.csv");
    DEFAULTS.put(K_DATASET_DELIMITER, ",");
    DEFAULTS.put(K_DATASET_SEED, "42");
    DEFAULTS.put(K_CONNECT_TIMEOUT, "10");
    DEFAULTS.put(K_RESPONSE_TIMEOUT, "600");
    DEFAULTS.put(K_E2E_SLA_SEC, "360");
    DEFAULTS.put(K_RUN_ID, "");
    DEFAULTS.put(K_RESULTS_DIR, "/results");
  }

  private static volatile boolean loaded;

  private static String targetUrl;
  private static String targetApiKey;
  private static String vllmBaseUrl;
  private static String vmUrl;
  private static Duration vmSettle;
  private static LoadMode mode;
  private static List<Stage> stages;
  private static Path datasetPath;
  private static char datasetDelimiter;
  private static long seed;
  private static Duration connectTimeout;
  private static Duration responseTimeout;
  private static double e2eSlaSec;
  private static String runId;
  private static Path resultsDir;
  private static Properties effective;
  private static List<String> warnings = List.of();


  private Config() {
  }

  /**
   * Читает файл конфигурации, накладывает {@code LT_*}, приводит типы и валидирует.
   *
   * @throws ConfigException со списком всех обнаруженных ошибок
   */
  public static void load(String path) {
    load(path, System.getenv());
  }

  /** Вариант с явно переданным окружением — для тестов. */
  static void load(String path, Map<String, String> env) {
    Properties raw = new Properties();
    DEFAULTS.forEach(raw::setProperty);

    List<String> errors = new ArrayList<>();

    if (path == null || path.isBlank()) {
      errors.add("не указан путь к файлу конфигурации");
    } else {
      Path file = Path.of(path);
      if (!Files.isRegularFile(file)) {
        errors.add("файл конфигурации не найден: " + file.toAbsolutePath());
      } else {
        try (InputStream in = Files.newInputStream(file)) {
          Properties fromFile = new Properties();
          fromFile.load(in);
          for (String key : fromFile.stringPropertyNames()) {
            if (!DEFAULTS.containsKey(key)) {
              errors.add("неизвестный ключ в файле конфигурации: " + key);
            } else {
              raw.setProperty(key, fromFile.getProperty(key));
            }
          }
        } catch (IOException e) {
          errors.add("файл конфигурации не читается: " + e.getMessage());
        }
      }
    }

    applyEnv(raw, env, errors);

    if (!errors.isEmpty()) {
      // Дальше приводить типы бессмысленно: источник значений уже под вопросом.
      throw new ConfigException(errors);
    }

    List<String> collectedWarnings = new ArrayList<>();
    parse(raw, errors, collectedWarnings);

    if (!errors.isEmpty()) {
      throw new ConfigException(errors);
    }

    effective = raw;
    warnings = List.copyOf(collectedWarnings);
    loaded = true;
  }

  private static void applyEnv(Properties raw, Map<String, String> env, List<String> errors) {
    Map<String, String> envToKey = new LinkedHashMap<>();
    DEFAULTS.keySet().forEach(key -> envToKey.put(envName(key), key));

    Set<String> unknown = new TreeSet<>();
    for (Map.Entry<String, String> entry : env.entrySet()) {
      String name = entry.getKey();
      if (!name.startsWith(ENV_PREFIX)) {
        continue;
      }
      String key = envToKey.get(name);
      if (key == null) {
        unknown.add(name);
      } else {
        raw.setProperty(key, entry.getValue());
      }
    }
    for (String name : unknown) {
      errors.add("неизвестная переменная окружения " + name
          + " — опечатка? допустимые: " + String.join(", ", new TreeSet<>(envToKey.keySet())));
    }
  }

  /** Имя переменной окружения, переопределяющей заданный ключ конфигурации. */
  public static String envName(String key) {
    return ENV_PREFIX + key.toUpperCase().replace('.', '_');
  }

  private static void parse(Properties raw, List<String> errors, List<String> warnings) {
    targetUrl = requireUrl(raw, K_TARGET_URL, errors);
    vllmBaseUrl = stripTrailingSlash(requireUrl(raw, K_VLLM_BASE_URL, errors));
    vmUrl = stripTrailingSlash(requireUrl(raw, K_VM_URL, errors));
    targetApiKey = raw.getProperty(K_TARGET_API_KEY, "").trim();

    vmSettle = Duration.ofSeconds(nonNegativeInt(raw, K_VM_SETTLE_SEC, errors));

    mode = null;
    try {
      mode = LoadMode.parse(raw.getProperty(K_MODE));
    } catch (IllegalArgumentException e) {
      errors.add(K_MODE + ": " + e.getMessage());
    }

    stages = List.of();
    if (mode == LoadMode.STEP) {
      try {
        stages = ProfileParser.parseStep(raw.getProperty(K_STEP_PROFILE));
      } catch (IllegalArgumentException e) {
        errors.add(K_STEP_PROFILE + ": " + e.getMessage());
      }
    } else if (mode == LoadMode.FLAT) {
      int rpm = positiveInt(raw, K_FLAT_RPM, errors);
      int ramp = positiveInt(raw, K_FLAT_RAMP, errors);
      int hold = positiveInt(raw, K_FLAT_HOLD, errors);
      if (rpm > 0 && ramp > 0 && hold > 0) {
        stages = ProfileParser.flat(rpm, ramp, hold);
      }
    }

    datasetPath = Path.of(raw.getProperty(K_DATASET_PATH, "").trim());
    if (raw.getProperty(K_DATASET_PATH, "").isBlank()) {
      errors.add(K_DATASET_PATH + ": не задан");
    } else if (mode != null && mode.isLoadGenerating() && !Files.isRegularFile(datasetPath)) {
      errors.add(K_DATASET_PATH + ": файл не найден: " + datasetPath.toAbsolutePath());
    }

    String delimiter = raw.getProperty(K_DATASET_DELIMITER, ",");
    if (delimiter.length() != 1) {
      errors.add(K_DATASET_DELIMITER + ": ожидался ровно один символ, получено '" + delimiter + "'");
      datasetDelimiter = ',';
    } else {
      datasetDelimiter = delimiter.charAt(0);
    }

    seed = longValue(raw, K_DATASET_SEED, errors);

    connectTimeout = Duration.ofSeconds(positiveInt(raw, K_CONNECT_TIMEOUT, errors));
    responseTimeout = Duration.ofSeconds(positiveInt(raw, K_RESPONSE_TIMEOUT, errors));

    e2eSlaSec = positiveDouble(raw, K_E2E_SLA_SEC, errors);

    runId = raw.getProperty(K_RUN_ID, "").trim();
    if (!runId.isEmpty() && !runId.matches("[A-Za-z0-9._-]+")) {
      errors.add(K_RUN_ID + ": допустимы только буквы, цифры, '.', '_' и '-', получено '"
          + runId + "'");
    }
    if (runId.isEmpty() && mode == LoadMode.REPORT) {
      errors.add(K_RUN_ID + ": в режиме report обязателен идентификатор существующего прогона");
    }

    String results = raw.getProperty(K_RESULTS_DIR, "").trim();
    if (results.isEmpty()) {
      errors.add(K_RESULTS_DIR + ": не задан");
      resultsDir = Path.of(".");
    } else {
      resultsDir = Path.of(results);
    }

    if (mode != null && mode.isLoadGenerating() && !stages.isEmpty()) {
      // Разгон правится до того, как профиль уйдёт куда бы то ни было: тест-план, окна удержания
      // и смещения ступеней должны считаться по одним и тем же величинам.
      stages = Threads.effectiveStages(stages, warnings);

      // Ответ, который не успевает прийти до таймаута, попадает в статистику как ошибка и рушит
      // и error rate, и фактический RPM. Разойтись с ожидаемой длительностью итерации здесь
      // легко, а обнаружить это — только по результатам сорокаминутного прогона.
      if (responseTimeout.toSeconds() < Threads.ITERATION_SEC) {
        errors.add(K_RESPONSE_TIMEOUT + " (" + responseTimeout.toSeconds()
            + " с) меньше ожидаемой длительности итерации Threads.ITERATION_SEC ("
            + Threads.ITERATION_SEC + " с): запросы будут обрываться по таймауту");
      }
      if (e2eSlaSec > 0 && e2eSlaSec <= Threads.ITERATION_SEC) {
        errors.add(K_E2E_SLA_SEC + " (" + fmt(e2eSlaSec)
            + " с) не больше ожидаемой длительности итерации Threads.ITERATION_SEC ("
            + Threads.ITERATION_SEC + " с): штраф будет срабатывать по построению, "
            + "порог задаётся с запасом над ожидаемой длительностью");
      }
      // Такт потока — это и есть максимальное время ответа, при котором целевой RPM ещё
      // достижим. Если порог SLA выше такта, появляется зона, где ответ формально укладывается
      // в SLA, а RPM уже недобирается: отчёт покажет штраф за недобор без единой строки о том,
      // почему он возник.
      double pacing = Threads.minPacingSec(stages);
      if (e2eSlaSec > pacing) {
        warnings.add(K_E2E_SLA_SEC + " (" + fmt(e2eSlaSec) + " с) выше такта потока ("
            + String.format(java.util.Locale.ROOT, "%.1f", pacing) + " с = число потоков x 60 / RPM): "
            + "ответы в диапазоне между этими значениями уложатся в SLA, но целевой RPM "
            + "недоберётся — поднимите Threads.SAFETY либо опустите порог");
      }
    }
  }

  private static String fmt(double v) {
    return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
  }

  private static String requireUrl(Properties raw, String key, List<String> errors) {
    String value = raw.getProperty(key, "").trim();
    if (value.isEmpty()) {
      errors.add(key + ": не задан");
      return value;
    }
    try {
      URI uri = URI.create(value);
      if (uri.getScheme() == null || uri.getHost() == null) {
        errors.add(key + ": не абсолютный URL: '" + value + "'");
      }
    } catch (IllegalArgumentException e) {
      errors.add(key + ": некорректный URL '" + value + "': " + e.getMessage());
    }
    return value;
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static int positiveInt(Properties raw, String key, List<String> errors) {
    String value = raw.getProperty(key, "").trim();
    try {
      int parsed = Integer.parseInt(value);
      if (parsed <= 0) {
        errors.add(key + ": ожидалось положительное число, получено " + parsed);
        return 0;
      }
      return parsed;
    } catch (NumberFormatException e) {
      errors.add(key + ": '" + value + "' не целое число");
      return 0;
    }
  }

  private static int nonNegativeInt(Properties raw, String key, List<String> errors) {
    String value = raw.getProperty(key, "").trim();
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 0) {
        errors.add(key + ": ожидалось неотрицательное число, получено " + parsed);
        return 0;
      }
      return parsed;
    } catch (NumberFormatException e) {
      errors.add(key + ": '" + value + "' не целое число");
      return 0;
    }
  }

  private static long longValue(Properties raw, String key, List<String> errors) {
    String value = raw.getProperty(key, "").trim();
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      errors.add(key + ": '" + value + "' не целое число");
      return 0;
    }
  }

  private static double positiveDouble(Properties raw, String key, List<String> errors) {
    String value = raw.getProperty(key, "").trim();
    try {
      double parsed = Double.parseDouble(value);
      if (!(parsed > 0)) {
        errors.add(key + ": ожидалось положительное число, получено " + value);
        return 0;
      }
      return parsed;
    } catch (NumberFormatException e) {
      errors.add(key + ": '" + value + "' не число");
      return 0;
    }
  }

  private static void checkLoaded() {
    if (!loaded) {
      throw new IllegalStateException("Config.load() не вызван");
    }
  }

  public static String targetUrl() {
    checkLoaded();
    return targetUrl;
  }

  public static String targetApiKey() {
    checkLoaded();
    return targetApiKey;
  }

  public static String vllmBaseUrl() {
    checkLoaded();
    return vllmBaseUrl;
  }

  public static String vmUrl() {
    checkLoaded();
    return vmUrl;
  }

  /**
   * Пауза между концом окна Hold и запросами к VictoriaMetrics.
   *
   * <p>Нужна, потому что VictoriaMetrics по умолчанию не отдаёт данные за последние 30 с
   * ({@code -search.latencyOffset}), а vmagent досылает последний скрейп не мгновенно. Без паузы
   * хвост окна Hold может оказаться пустым — и отчёт получится по неполным данным, не сообщив
   * об этом.
   */
  public static Duration vmSettle() {
    checkLoaded();
    return vmSettle;
  }

  public static LoadMode mode() {
    checkLoaded();
    return mode;
  }

  public static List<Stage> stages() {
    checkLoaded();
    return stages;
  }

  public static Path datasetPath() {
    checkLoaded();
    return datasetPath;
  }

  public static char datasetDelimiter() {
    checkLoaded();
    return datasetDelimiter;
  }

  public static long seed() {
    checkLoaded();
    return seed;
  }

  public static Duration connectTimeout() {
    checkLoaded();
    return connectTimeout;
  }

  public static Duration responseTimeout() {
    checkLoaded();
    return responseTimeout;
  }

  public static double e2eSlaSec() {
    checkLoaded();
    return e2eSlaSec;
  }

  /** Идентификатор прогона из конфигурации; пустая строка означает «сгенерировать». */
  public static String runId() {
    checkLoaded();
    return runId;
  }

  public static Path resultsDir() {
    checkLoaded();
    return resultsDir;
  }

  /**
   * Эффективная конфигурация для снапшота. Значение {@code target.apiKey} маскируется: снапшот
   * лежит рядом с результатами и переживает прогон.
   */
  public static Properties effective() {
    checkLoaded();
    Properties snapshot = new Properties();
    for (String key : DEFAULTS.keySet()) {
      String value = effective.getProperty(key, "");
      if (K_TARGET_API_KEY.equals(key) && !value.isEmpty()) {
        value = "***";
      }
      snapshot.setProperty(key, value);
    }
    return snapshot;
  }

  /** Ключи в порядке объявления — снапшот пишется в этом порядке. */
  public static Set<String> keys() {
    return new LinkedHashSet<>(DEFAULTS.keySet());
  }

  /** Замечания, не мешающие прогону, но влияющие на трактовку результата. */
  public static List<String> warnings() {
    checkLoaded();
    return warnings;
  }

  /** Сбрасывает состояние. Только для тестов. */
  static void reset() {
    loaded = false;
    effective = null;
    stages = List.of();
    warnings = List.of();
  }

  /** Ошибка конфигурации со списком всех обнаруженных проблем. */
  public static class ConfigException extends RuntimeException {

    private final transient List<String> errors;

    public ConfigException(List<String> errors) {
      super("конфигурация некорректна (" + errors.size() + "):"
          + System.lineSeparator()
          + String.join(System.lineSeparator(), errors.stream().map(e -> "  - " + e).toList()));
      this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
      return errors;
    }
  }
}

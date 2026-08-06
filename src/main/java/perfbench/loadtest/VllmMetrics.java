package perfbench.loadtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Серверная часть отчёта: имена метрик vLLM, запросы к VictoriaMetrics за окно удержания и
 * маппинг результатов в поля отчёта.
 *
 * <p><b>Это единственное место, которое правится при смене версии vLLM.</b> Имена метрик между
 * версиями расходятся, поэтому каждое логическое имя описано списком кандидатов: первый
 * найденный в {@code /metrics} и используется. Разрешение выполняет {@link Preflight} до подачи
 * нагрузки, результат попадает в {@code run.meta.properties} — режим пересчёта отчёта работает
 * по нему и в vLLM не ходит вовсе.
 */
public final class VllmMetrics {

  /**
   * Единственный квантиль, снимаемый с гистограмм.
   *
   * <p>Истинного максимума отдельных наблюдений в бакетах не существует, а «максимум p95 за
   * прогон» — корректно определённая величина. Отчёт называет её именно так.
   */
  public static final double QUANTILE = 0.95;

  /**
   * Скользящее подокно, по которому строятся ряд p95 и производная счётчика токенов.
   *
   * <p><b>Результат от него зависит:</b> на коротком подокне ряд p95 шумнее и его максимум выше,
   * на длинном — глаже и ниже. Поэтому значение зафиксировано константой и печатается в отчёте
   * рядом с величинами, которые от него зависят.
   */
  public static final int SUBWINDOW_SEC = 30;

  /**
   * Шаг подзапроса при построении ряда p95.
   *
   * <p>Совпадает с подокном: соседние точки ряда считаются по непересекающимся интервалам.
   */
  public static final int SUBQUERY_STEP_SEC = SUBWINDOW_SEC;

  // Логические имена метрик. Ими же они попадают в run.meta.properties.
  public static final String M_KV_CACHE = "kvCacheUsage";
  public static final String M_QUEUE = "queueWaiting";
  public static final String M_TTFT = "ttft";
  public static final String M_TPOT = "tpot";
  public static final String M_ITL = "itl";
  public static final String M_PROMPT_TOKENS = "promptTokens";
  public static final String M_GENERATION_TOKENS = "generationTokens";

  /**
   * Кандидаты на каждое логическое имя, в порядке предпочтения — от актуального к историческому.
   *
   * <p>Имена приведены в том виде, в каком vLLM отдаёт их в {@code /metrics}: счётчики уже с
   * суффиксом {@code _total}, гистограммы — базовым именем без {@code _bucket}.
   */
  private static final Map<String, List<String>> CANDIDATES = new LinkedHashMap<>();

  static {
    // Утилизация KV-кэша, доля 0..1. В отчёт уходит в процентах.
    CANDIDATES.put(M_KV_CACHE, List.of(
        "vllm:kv_cache_usage_perc",      // V1
        "vllm:gpu_cache_usage_perc"));   // V0
    CANDIDATES.put(M_QUEUE, List.of(
        "vllm:num_requests_waiting"));
    CANDIDATES.put(M_TTFT, List.of(
        "vllm:time_to_first_token_seconds"));
    // TPOT — время на выходной токен, взвешенное по запросам.
    CANDIDATES.put(M_TPOT, List.of(
        "vllm:request_time_per_output_token_seconds",  // V1
        "vllm:time_per_output_token_seconds"));        // V0
    // ITL — тот же интервал между выходными токенами, но взвешенный по токенам.
    // Отдельной гистограммы нет в части версий: там раздельный ITL считает только
    // benchmark_serving, а не эндпоинт /metrics.
    CANDIDATES.put(M_ITL, List.of(
        "vllm:inter_token_latency_seconds"));
    CANDIDATES.put(M_PROMPT_TOKENS, List.of(
        "vllm:prompt_tokens_total"));
    CANDIDATES.put(M_GENERATION_TOKENS, List.of(
        "vllm:generation_tokens_total"));

  }

  /** Метрики, без которых прогон не имеет смысла. */
  private static final Set<String> REQUIRED = Set.of(
      M_KV_CACHE, M_QUEUE, M_TTFT, M_TPOT, M_PROMPT_TOKENS, M_GENERATION_TOKENS);

  private static volatile Map<String, String> resolved = Map.of();

  private VllmMetrics() {
  }

  /**
   * Сопоставляет логические имена с фактическими по списку метрик, которые реально отдаёт vLLM.
   *
   * @param exported имена метрик из {@code /metrics} (гистограммы — базовым именем)
   */
  public static Resolution resolve(Set<String> exported) {
    Map<String, String> found = new LinkedHashMap<>();
    List<String> missingRequired = new ArrayList<>();
    List<String> missingOptional = new ArrayList<>();

    for (Map.Entry<String, List<String>> entry : CANDIDATES.entrySet()) {
      String logical = entry.getKey();
      String match = entry.getValue().stream()
          .filter(exported::contains)
          .findFirst()
          .orElse(null);
      if (match != null) {
        found.put(logical, match);
      } else if (REQUIRED.contains(logical)) {
        missingRequired.add(logical + " (искали: " + String.join(", ", entry.getValue()) + ")");
      } else {
        missingOptional.add(logical + " (искали: " + String.join(", ", entry.getValue()) + ")");
      }
    }
    return new Resolution(Map.copyOf(found), List.copyOf(missingRequired),
        List.copyOf(missingOptional));
  }

  /** Фиксирует разрешённые имена: из {@link Preflight} либо из {@code run.meta.properties}. */
  public static void adopt(Map<String, String> names) {
    resolved = Map.copyOf(names);
  }

  public static Map<String, String> names() {
    return resolved;
  }

  public static boolean itlAvailable() {
    return resolved.containsKey(M_ITL);
  }

  private static String name(String logical) {
    String value = resolved.get(logical);
    if (value == null) {
      throw new IllegalStateException("имя метрики '" + logical + "' не разрешено; "
          + "Preflight.check() не отработал или метрика отсутствует");
    }
    return value;
  }

  /** Собирает серверную часть отчёта за окно удержания. */
  public static ServerMetrics collect(HoldWindow window) {
    java.time.Instant at = window.end();
    long windowSec = window.duration().toSeconds();
    String w = windowSec + "s";

    Optional<Double> kvAvg = fraction(
        VmClient.instantOpt("max(avg_over_time(" + name(M_KV_CACHE) + "[" + w + "]))", at));
    Optional<Double> kvMax = fraction(
        VmClient.instantOpt("max(max_over_time(" + name(M_KV_CACHE) + "[" + w + "]))", at));

    // max_over_time поверх сырого range-селектора видит каждое снятое значение: подзапрос со
    // своим шагом мог бы проредить ряд и потерять всплеск, ради которого метрика и снимается.
    Optional<Double> queueMax =
        VmClient.instantOpt("max(max_over_time(" + name(M_QUEUE) + "[" + w + "]))", at);

    Optional<Double> ttftAvg = VmClient.instantOpt(quantileSeries("avg_over_time", M_TTFT, w), at);
    Optional<Double> ttftMax = VmClient.instantOpt(quantileSeries("max_over_time", M_TTFT, w), at);
    Optional<Double> tpotAvg = VmClient.instantOpt(quantileSeries("avg_over_time", M_TPOT, w), at);
    Optional<Double> tpotMax = VmClient.instantOpt(quantileSeries("max_over_time", M_TPOT, w), at);

    Optional<Double> itlAvg = Optional.empty();
    Optional<Double> itlMax = Optional.empty();
    if (itlAvailable()) {
      itlAvg = VmClient.instantOpt(quantileSeries("avg_over_time", M_ITL, w), at);
      itlMax = VmClient.instantOpt(quantileSeries("max_over_time", M_ITL, w), at);
    }

    Optional<Double> promptAvg = VmClient.instantOpt(counterAvg(M_PROMPT_TOKENS, w, windowSec), at);
    Optional<Double> promptMax = VmClient.instantOpt(counterMax(M_PROMPT_TOKENS, w), at);
    Optional<Double> genAvg = VmClient.instantOpt(counterAvg(M_GENERATION_TOKENS, w, windowSec), at);
    Optional<Double> genMax = VmClient.instantOpt(counterMax(M_GENERATION_TOKENS, w), at);

    return new ServerMetrics(kvAvg, kvMax, queueMax, ttftAvg, ttftMax, tpotAvg, tpotMax,
        itlAvg, itlMax, promptAvg, promptMax, genAvg, genMax, itlAvailable());
  }

  /**
   * Ряд p95 гистограммы, свёрнутый агрегатом за окно удержания.
   *
   * <p>{@code sum by (le)} схлопывает лейблы {@code model_name}/{@code engine}/{@code instance}:
   * без него histogram_quantile посчитает квантиль по каждой серии отдельно, и агрегат окажется
   * не по гистограмме целиком, а по её случайному куску.
   */
  private static String quantileSeries(String aggregate, String logical, String window) {
    String series = "histogram_quantile(" + QUANTILE + ", sum by (le) (rate("
        + name(logical) + "_bucket[" + SUBWINDOW_SEC + "s])))";
    return aggregate + "((" + series + ")[" + window + ":" + SUBQUERY_STEP_SEC + "s])";
  }

  /** Средняя скорость: прирост счётчика за окно, делённый на длительность окна. */
  private static String counterAvg(String logical, String window, long windowSec) {
    return "sum(increase(" + name(logical) + "[" + window + "])) / " + windowSec;
  }

  /** Максимум производной счётчика по подокну {@link #SUBWINDOW_SEC}. */
  private static String counterMax(String logical, String window) {
    return "max_over_time((sum(rate(" + name(logical) + "[" + SUBWINDOW_SEC + "s])))["
        + window + ":" + SUBQUERY_STEP_SEC + "s])";
  }

  /** Запрос, по которому проверяется, что метрика доехала до хранилища. */
  public static String presenceQuery(String logical, boolean histogram) {
    String metric = name(logical);
    return histogram ? metric + "_bucket" : metric;
  }

  /** Гистограмма ли метрика с этим логическим именем. */
  public static boolean isHistogram(String logical) {
    return M_TTFT.equals(logical) || M_TPOT.equals(logical) || M_ITL.equals(logical);
  }

  private static Optional<Double> fraction(Optional<Double> value) {
    return value.map(v -> v * 100.0);
  }

  /** Результат сопоставления логических имён с фактическими. */
  public record Resolution(
      Map<String, String> resolved,
      List<String> missingRequired,
      List<String> missingOptional) {

    public boolean isComplete() {
      return missingRequired.isEmpty();
    }
  }

  /**
   * Серверная часть отчёта за одно окно удержания.
   *
   * <p>Пустой {@link Optional} означает «данных нет»: отчёт помечает такую строку недоступной,
   * а оценка за неё штрафа не начисляет. Заполнять пропуск соседней метрикой нельзя.
   *
   * @param kvCacheAvgPct    средняя утилизация KV-кэша, проценты
   * @param kvCacheMaxPct    максимальная утилизация KV-кэша среди снятых значений, проценты
   * @param queueMax         максимум очереди среди снятых значений, запросов
   * @param ttftP95AvgSec    среднее ряда p95 TTFT, секунды
   * @param ttftP95MaxSec    максимум ряда p95 TTFT, секунды
   * @param tpotP95AvgSec    среднее ряда p95 TPOT, секунды
   * @param tpotP95MaxSec    максимум ряда p95 TPOT, секунды
   * @param itlP95AvgSec     среднее ряда p95 ITL, секунды
   * @param itlP95MaxSec     максимум ряда p95 ITL, секунды
   * @param promptTpsAvg     средняя скорость приёма входных токенов, токенов в секунду
   * @param promptTpsMax     максимум скорости приёма входных токенов по подокну
   * @param generationTpsAvg средняя скорость генерации выходных токенов, токенов в секунду
   * @param generationTpsMax максимум скорости генерации по подокну
   * @param itlAvailable     присутствует ли в этой версии vLLM отдельная гистограмма ITL
   */
  public record ServerMetrics(
      Optional<Double> kvCacheAvgPct,
      Optional<Double> kvCacheMaxPct,
      Optional<Double> queueMax,
      Optional<Double> ttftP95AvgSec,
      Optional<Double> ttftP95MaxSec,
      Optional<Double> tpotP95AvgSec,
      Optional<Double> tpotP95MaxSec,
      Optional<Double> itlP95AvgSec,
      Optional<Double> itlP95MaxSec,
      Optional<Double> promptTpsAvg,
      Optional<Double> promptTpsMax,
      Optional<Double> generationTpsAvg,
      Optional<Double> generationTpsMax,
      boolean itlAvailable) {

    public static ServerMetrics unavailable() {
      Optional<Double> none = Optional.empty();
      return new ServerMetrics(none, none, none, none, none, none, none, none, none, none, none,
          none, none, false);
    }
  }

  /** Только для тестов. */
  static void reset() {
    resolved = Map.of();
  }
}

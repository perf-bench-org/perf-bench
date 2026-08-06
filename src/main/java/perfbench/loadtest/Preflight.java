package perfbench.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Проверки перед подачей нагрузки.
 *
 * <p>Смысл этапа — обнаружить отсутствующую метрику или неотвечающее хранилище за секунды, а не
 * после сорока минут прогона на полупустом отчёте. Поэтому проверки собирают <b>все</b> проблемы
 * сразу и падают одним списком.
 */
public final class Preflight {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private Preflight() {
  }

  /**
   * Результат preflight.
   *
   * @param model        имя модели, как его отдаёт vLLM
   * @param vllmVersion  версия vLLM либо {@code "unknown"}
   * @param warnings     некритичные замечания, которые стоит увидеть в логе
   */
  public record Result(String model, String vllmVersion, List<String> warnings) {
  }

  public static Result check() {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    String metricsBody = null;
    try {
      metricsBody = get(client, Config.vllmBaseUrl() + "/metrics");
    } catch (PreflightException e) {
      errors.add("vLLM /metrics недоступен: " + e.getMessage());
    }

    if (metricsBody != null) {
      Set<String> exported = parseMetricNames(metricsBody);
      VllmMetrics.Resolution resolution = VllmMetrics.resolve(exported);
      if (!resolution.isComplete()) {
        errors.add("в /metrics vLLM нет обязательных метрик:"
            + System.lineSeparator()
            + resolution.missingRequired().stream()
            .map(m -> "      " + m)
            .reduce((a, b) -> a + System.lineSeparator() + b).orElse(""));
      }
      // Отсутствие ITL — не ошибка: в части версий vLLM отдельной гистограммы просто нет.
      // Прогон продолжается, а строка отчёта будет помечена недоступной, а не заполнена TPOT.
      for (String missing : resolution.missingOptional()) {
        warnings.add("метрика отсутствует, соответствующая строка отчёта будет помечена "
            + "недоступной: " + missing);
      }
      VllmMetrics.adopt(resolution.resolved());
    }

    String model = "";
    try {
      List<String> models = fetchModels(client);
      RequestBody.resolveModel(models);
      model = RequestBody.model();
    } catch (PreflightException | IllegalStateException e) {
      errors.add("имя модели не определено: " + e.getMessage());
    }

    String version = "unknown";
    try {
      String body = get(client, Config.vllmBaseUrl() + "/version");
      JsonNode root = MAPPER.readTree(body);
      String parsed = root.path("version").asText("");
      if (parsed.isBlank()) {
        warnings.add("vLLM /version не содержит поля version, в отчёт уйдёт 'unknown'");
      } else {
        version = parsed;
      }
    } catch (PreflightException | IOException e) {
      warnings.add("версия vLLM не определена (" + Failures.describe(e)
          + "), в отчёт уйдёт 'unknown'");
    }

    try {
      VmClient.ping();
      if (!VllmMetrics.names().isEmpty()) {
        checkStorageHasSeries(errors);
        checkSubqueriesSupported(errors);
      }
    } catch (VmClient.VmException e) {
      errors.add(e.getMessage());
    }

    if (!errors.isEmpty()) {
      throw new PreflightException("preflight не пройден (" + errors.size() + "):"
          + System.lineSeparator()
          + String.join(System.lineSeparator(), errors.stream().map(e -> "  - " + e).toList()));
    }

    return new Result(model, version, List.copyOf(warnings));
  }

  /**
   * Проверяет, что метрики действительно доехали до хранилища.
   *
   * <p>Метрика может присутствовать в {@code /metrics} и при этом отсутствовать в VictoriaMetrics —
   * ровно так выглядит неработающий vmagent, и обнаруживается это иначе только по пустому отчёту.
   */
  private static void checkStorageHasSeries(List<String> errors) {
    Instant now = Instant.now();
    List<String> absent = new ArrayList<>();
    for (String logical : VllmMetrics.names().keySet()) {
      String query = VllmMetrics.presenceQuery(logical, VllmMetrics.isHistogram(logical));
      if (!VmClient.hasData(query, now)) {
        absent.add(query);
      }
    }
    if (!absent.isEmpty()) {
      errors.add("в VictoriaMetrics нет серий " + String.join(", ", absent)
          + " — vmagent не скрейпит vLLM либо скрейпит другую цель");
    }
  }

  /**
   * Проверяет поддержку подзапросов {@code expr[окно:шаг]}: на них построен весь расчёт p95 и
   * максимума производной счётчиков.
   */
  private static void checkSubqueriesSupported(List<String> errors) {
    String metric = VllmMetrics.names().get(VllmMetrics.M_PROMPT_TOKENS);
    if (metric == null) {
      return;
    }
    String query = "max_over_time((sum(rate(" + metric + "[" + VllmMetrics.SUBWINDOW_SEC + "s])))["
        + (VllmMetrics.SUBWINDOW_SEC * 2) + "s:" + VllmMetrics.SUBQUERY_STEP_SEC + "s])";
    try {
      VmClient.hasData(query, Instant.now());
    } catch (VmClient.VmException e) {
      errors.add("VictoriaMetrics не выполняет подзапросы вида expr[окно:шаг], на которых "
          + "построен расчёт p95: " + e.getMessage());
    }
  }

  private static List<String> fetchModels(HttpClient client) {
    String body = get(client, Config.vllmBaseUrl() + "/v1/models");
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (IOException e) {
      throw new PreflightException("/v1/models вернул не JSON: " + Failures.describe(e));
    }
    List<String> models = new ArrayList<>();
    for (JsonNode item : root.path("data")) {
      String id = item.path("id").asText("");
      if (!id.isBlank()) {
        models.add(id);
      }
    }
    return models;
  }

  /**
   * Имена метрик, экспортируемых vLLM.
   *
   * <p>Берутся из строк {@code # TYPE}: там имя приведено ровно в том виде, в каком метрика
   * попадает в хранилище — счётчики с суффиксом {@code _total}, гистограммы базовым именем без
   * {@code _bucket}. Разбирать сами сэмплы для этого не нужно и вредно.
   */
  static Set<String> parseMetricNames(String metricsBody) {
    Set<String> names = new LinkedHashSet<>();
    for (String line : metricsBody.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.startsWith("# TYPE ")) {
        continue;
      }
      String[] parts = trimmed.substring("# TYPE ".length()).trim().split("\\s+");
      if (parts.length >= 1 && !parts[0].isEmpty()) {
        names.add(parts[0]);
      }
    }
    return names;
  }

  private static String get(HttpClient client, String url) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
        .timeout(TIMEOUT)
        .GET();
    if (!Config.targetApiKey().isEmpty()) {
      builder.header("Authorization", "Bearer " + Config.targetApiKey());
    }
    try {
      HttpResponse<String> response = client.send(builder.build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        throw new PreflightException(url + " ответил " + response.statusCode());
      }
      return response.body();
    } catch (IOException e) {
      throw new PreflightException(url + ": " + Failures.describe(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PreflightException(url + ": запрос прерван");
    }
  }

  /** Preflight не пройден: нагрузка не подаётся. */
  public static class PreflightException extends RuntimeException {

    public PreflightException(String message) {
      super(message);
    }
  }
}

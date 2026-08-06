package perfbench.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Тонкая обёртка над HTTP API VictoriaMetrics.
 *
 * <p>Хранилище используется как движок агрегации: единственный его потребитель — код, считающий
 * средние, максимумы и приросты за окно удержания.
 *
 * <p>Отсутствие данных — не исключение, а пустой результат: отчёт должен формироваться и при
 * частично недоступных метриках, помечая пропуски. Исключение бросается только тогда, когда
 * недоступно само хранилище или запрос синтаксически неверен — это ошибка стенда, а не данных.
 */
public final class VmClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private static volatile HttpClient client;
  private static volatile String baseUrl;

  private VmClient() {
  }

  public static void init(String vmBaseUrl) {
    baseUrl = vmBaseUrl;
    client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  /** Мгновенный запрос. Отсутствие данных — {@link Optional#empty()}. */
  public static Optional<Double> instantOpt(String query, Instant at) {
    JsonNode result = query(query, at);
    if (result.isEmpty()) {
      return Optional.empty();
    }
    // Запросы отчёта агрегируют по всем лейблам, поэтому серия ожидается одна. Если хранилище
    // вернуло больше, берём первую — но это признак того, что запрос забыл про агрегацию.
    JsonNode value = result.get(0).path("value");
    if (!value.isArray() || value.size() < 2) {
      return Optional.empty();
    }
    String raw = value.get(1).asText();
    try {
      double parsed = Double.parseDouble(raw);
      return Double.isFinite(parsed) ? Optional.of(parsed) : Optional.empty();
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** Мгновенный запрос, для метрик, отсутствие которых означает поломку стенда. */
  public static double instant(String query, Instant at) {
    return instantOpt(query, at).orElseThrow(() ->
        new VmException("запрос не вернул данных: " + query));
  }

  /** Есть ли в хранилище хотя бы одна точка по запросу. */
  public static boolean hasData(String query, Instant at) {
    return !query(query, at).isEmpty();
  }

  /** Отвечает ли хранилище вообще. */
  public static void ping() {
    HttpRequest request = HttpRequest.newBuilder(URI.create(requireBaseUrl() + "/health"))
        .timeout(TIMEOUT)
        .GET()
        .build();
    try {
      HttpResponse<String> response = requireClient().send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        throw new VmException("VictoriaMetrics " + baseUrl + " ответил " + response.statusCode()
            + " на /health");
      }
    } catch (IOException e) {
      throw new VmException("VictoriaMetrics " + baseUrl + " недоступен: "
          + Failures.describe(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VmException("запрос к VictoriaMetrics прерван", e);
    }
  }

  /** Массив {@code data.result} ответа {@code /api/v1/query}. */
  private static JsonNode query(String query, Instant at) {
    String form = "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
        + "&time=" + at.getEpochSecond();
    HttpRequest request = HttpRequest.newBuilder(URI.create(requireBaseUrl() + "/api/v1/query"))
        .timeout(TIMEOUT)
        .header("Content-Type", "application/x-www-form-urlencoded")
        // POST, а не GET: запросы отчёта длинные и легко упираются в лимит длины URL.
        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
        .build();

    HttpResponse<String> response;
    try {
      response = requireClient().send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new VmException("запрос к VictoriaMetrics не выполнен: " + Failures.describe(e)
          + System.lineSeparator() + "  query: " + query, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VmException("запрос к VictoriaMetrics прерван", e);
    }

    JsonNode root;
    try {
      root = MAPPER.readTree(response.body());
    } catch (IOException e) {
      throw new VmException("ответ VictoriaMetrics не разбирается (HTTP " + response.statusCode()
          + "): " + abbreviate(response.body()), e);
    }

    if (response.statusCode() / 100 != 2 || !"success".equals(root.path("status").asText())) {
      throw new VmException("VictoriaMetrics отклонил запрос (HTTP " + response.statusCode() + "): "
          + root.path("error").asText(abbreviate(response.body()))
          + System.lineSeparator() + "  query: " + query);
    }

    return root.path("data").path("result");
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() > 300 ? body.substring(0, 297) + "..." : body;
  }

  private static HttpClient requireClient() {
    HttpClient current = client;
    if (current == null) {
      throw new IllegalStateException("VmClient.init() не вызван");
    }
    return current;
  }

  private static String requireBaseUrl() {
    String current = baseUrl;
    if (current == null) {
      throw new IllegalStateException("VmClient.init() не вызван");
    }
    return current;
  }

  /** Ошибка обращения к хранилищу метрик. */
  public static class VmException extends RuntimeException {

    public VmException(String message) {
      super(message);
    }

    public VmException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

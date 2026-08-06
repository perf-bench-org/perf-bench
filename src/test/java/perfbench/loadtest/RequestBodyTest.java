package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestBodyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeEach
  void setUp() {
    RequestBody.setResolvedModel("test-model");
  }

  @AfterEach
  void tearDown() {
    RequestBody.setResolvedModel(null);
  }

  @Test
  @DisplayName("тело — валидный JSON с ожидаемыми полями")
  void buildsValidJson() throws Exception {
    JsonNode body = MAPPER.readTree(RequestBody.build("Привет"));

    assertEquals("test-model", body.get("model").asText());
    assertEquals(RequestBody.MAX_TOKENS, body.get("max_tokens").asInt());
    assertEquals(RequestBody.IGNORE_EOS, body.get("ignore_eos").asBoolean());
    assertEquals(RequestBody.STREAM, body.get("stream").asBoolean());
    assertEquals(RequestBody.TEMPERATURE, body.get("temperature").asDouble());
    assertEquals(RequestBody.TOP_P, body.get("top_p").asDouble());
    assertEquals(1, body.get("messages").size());
    assertEquals(RequestBody.ROLE, body.get("messages").get(0).get("role").asText());
    assertEquals("Привет", body.get("messages").get(0).get("content").asText());
  }

  @Test
  @DisplayName("кавычки, слэши, переводы строк и юникод экранируются сериализатором")
  void escapesSpecialCharacters() throws Exception {
    String prompt = "Кавычка \" слэш \\ перевод\nстроки\tтаб  и {\"json\": true}";

    JsonNode body = MAPPER.readTree(RequestBody.build(prompt));

    assertEquals(prompt, body.get("messages").get(0).get("content").asText());
  }

  @Test
  @DisplayName("тело каждый раз собирается заново и не подмешивает предыдущий промпт")
  void doesNotLeakBetweenCalls() throws Exception {
    RequestBody.build("первый");
    JsonNode body = MAPPER.readTree(RequestBody.build("второй"));

    assertEquals("второй", body.get("messages").get(0).get("content").asText());
  }

  @Test
  @DisplayName("без разрешённого имени модели тело не собирается")
  void failsWithoutModel() {
    RequestBody.setResolvedModel(null);

    assertThrows(IllegalStateException.class, () -> RequestBody.build("что угодно"));
  }

  @Test
  @DisplayName("несколько моделей от vLLM — падение с указанием выбрать вручную")
  void failsOnAmbiguousModel() {
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> RequestBody.resolveModel(List.of("model-a", "model-b")));

    assertTrue(e.getMessage().contains("RequestBody.MODEL"), e.getMessage());
  }

  @Test
  @DisplayName("пустой список моделей — падение")
  void failsOnNoModels() {
    assertThrows(IllegalStateException.class, () -> RequestBody.resolveModel(List.of()));
  }

  @Test
  @DisplayName("единственная модель подставляется")
  void resolvesSingleModel() {
    RequestBody.resolveModel(List.of("Qwen/Qwen2.5-7B-Instruct"));

    assertEquals("Qwen/Qwen2.5-7B-Instruct", RequestBody.model());
  }
}

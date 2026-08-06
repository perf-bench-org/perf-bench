package perfbench.loadtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Единственное место, где собирается тело запроса к {@code /v1/chat/completions}.
 *
 * <p>Снаружи передаётся <b>только текст промпта</b>. Всё остальное — константы этого класса.
 * Изменение любого параметра требует пересборки образа; взамен на вопрос «а с какими параметрами
 * был вот этот прогон» отвечает сам факт версии образа плюс {@code config.snapshot.properties}.
 *
 * <p>Экранирование выполняет Jackson. Ручной сборки JSON-строк здесь нет и быть не должно:
 * промпт приходит из БД и содержит кавычки, переводы строк и обратные слэши.
 */
public final class RequestBody {

  /**
   * Имя модели в теле запроса.
   *
   * <p>Пустая строка означает «взять имя, под которым модель отдана самим vLLM»: {@link Preflight}
   * запрашивает {@code /v1/models} и подставляет единственную найденную модель. Если vLLM отдаёт
   * несколько моделей, preflight падает — выбирать за оператора нельзя.
   *
   * <p>Чтобы зафиксировать имя жёстко, впишите его сюда.
   */
  public static final String MODEL = "";

  /**
   * Фиксированная длина ответа.
   *
   * <p>Вместе с {@link #IGNORE_EOS} даёт ровно {@code MAX_TOKENS} выходных токенов на каждый
   * запрос. Величина должна быть согласована с {@link Threads#ITERATION_SEC}: именно
   * {@code MAX_TOKENS} и определяет, сколько длится итерация.
   */
  public static final int MAX_TOKENS = 1024;

  /**
   * Отключает остановку по EOS.
   *
   * <p>Принципиален: без него длина ответа плавает вместе с содержанием промпта, время итерации
   * плавает вслед за ней, и ступени становятся несравнимыми между собой.
   */
  public static final boolean IGNORE_EOS = true;

  public static final double TEMPERATURE = 0.0;
  public static final double TOP_P = 1.0;

  /** Стриминг выключен: TTFT снимается с сервера, а не с клиента. */
  public static final boolean STREAM = false;

  public static final String ROLE = "user";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Имя модели, разрешённое на preflight. Пишется один раз до старта нагрузки. */
  private static volatile String resolvedModel;

  private RequestBody() {
  }

  /**
   * Фиксирует имя модели для тела запроса.
   *
   * <p>Вызывается {@link Preflight} до построения тест-плана. Если {@link #MODEL} задан, аргумент
   * игнорируется — константа сильнее.
   *
   * @param servedModels имена моделей, которые отдал vLLM
   */
  public static void resolveModel(List<String> servedModels) {
    if (!MODEL.isBlank()) {
      resolvedModel = MODEL;
      return;
    }
    if (servedModels == null || servedModels.isEmpty()) {
      throw new IllegalStateException(
          "RequestBody.MODEL пуст, и vLLM не отдал ни одной модели через /v1/models — "
              + "впишите имя модели в константу RequestBody.MODEL");
    }
    if (servedModels.size() > 1) {
      throw new IllegalStateException(
          "RequestBody.MODEL пуст, а vLLM отдаёт несколько моделей " + servedModels
              + " — впишите нужное имя в константу RequestBody.MODEL");
    }
    resolvedModel = servedModels.get(0);
  }

  /** Имя модели, которое уйдёт в теле запроса. */
  public static String model() {
    String model = resolvedModel;
    if (model == null || model.isBlank()) {
      throw new IllegalStateException("имя модели не разрешено: Preflight.check() не отработал");
    }
    return model;
  }

  /** Собирает тело запроса для заданного промпта. */
  public static String build(String prompt) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model());

    ArrayNode messages = body.putArray("messages");
    ObjectNode message = messages.addObject();
    message.put("role", ROLE);
    message.put("content", prompt);

    body.put("max_tokens", MAX_TOKENS);
    body.put("temperature", TEMPERATURE);
    body.put("top_p", TOP_P);
    body.put("stream", STREAM);
    body.put("ignore_eos", IGNORE_EOS);

    try {
      return MAPPER.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      // Сериализация ObjectNode из примитивов и строк не может упасть по данным.
      throw new IllegalStateException("не удалось сериализовать тело запроса", e);
    }
  }

  /** Подставляет имя модели напрямую. Только для тестов. */
  static void setResolvedModel(String model) {
    resolvedModel = model;
  }
}

package perfbench.loadtest;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Датасет промптов: загружается целиком в память один раз при старте, дальше только читается.
 *
 * <p>Наружу отдаётся только текст промпта — остальные колонки CSV игнорируются.
 *
 * <p>Единственная точка конкурентного доступа во всём стенде — {@link #next()}: атомарный счётчик
 * поверх неизменяемого списка, без блокировок.
 */
public final class Dataset {

  /** Единственная значимая колонка. Маппинг по заголовку, порядок колонок не важен. */
  public static final String PROMPT_COLUMN = "prompt";

  private static volatile List<String> prompts = List.of();
  private static volatile String sha256 = "";
  private static final AtomicInteger CURSOR = new AtomicInteger();

  private Dataset() {
  }

  /**
   * Читает CSV, отбрасывает пустые промпты, считает SHA-256 файла и перемешивает список заданным
   * seed'ом.
   *
   * @throws DatasetException если файла нет, в нём нет колонки {@code prompt} или нет ни одной
   *                          непустой строки
   */
  public static void load(Path path, char delimiter, long seed) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(path);
    } catch (IOException e) {
      throw new DatasetException("датасет не читается: " + path + ": " + Failures.describe(e), e);
    }
    sha256 = sha256(bytes);

    List<String> loaded = new ArrayList<>();
    CSVFormat format = CSVFormat.DEFAULT.builder()
        .setDelimiter(delimiter)
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreSurroundingSpaces(false)
        .setIgnoreEmptyLines(true)
        .build();
    try (Reader reader = new StringReader(decode(bytes));
         CSVParser parser = CSVParser.parse(reader, format)) {
      if (!parser.getHeaderMap().containsKey(PROMPT_COLUMN)) {
        throw new DatasetException("в датасете " + path + " нет колонки '" + PROMPT_COLUMN
            + "'; найдены: " + parser.getHeaderMap().keySet());
      }
      for (CSVRecord record : parser) {
        if (!record.isSet(PROMPT_COLUMN)) {
          continue;
        }
        String prompt = record.get(PROMPT_COLUMN);
        if (prompt != null && !prompt.isBlank()) {
          loaded.add(prompt);
        }
      }
    } catch (IOException e) {
      throw new DatasetException("датасет не разбирается: " + path + ": " + e.getMessage(), e);
    } catch (IllegalArgumentException e) {
      throw new DatasetException("датасет не разбирается: " + path + ": " + e.getMessage(), e);
    }

    if (loaded.isEmpty()) {
      throw new DatasetException("датасет " + path + " не содержит ни одного непустого промпта");
    }

    // Фиксированный seed: порядок обхода случайный, но воспроизводимый между прогонами.
    Collections.shuffle(loaded, new Random(seed));
    prompts = List.copyOf(loaded);
    CURSOR.set(0);
  }

  /** Очередной промпт. Обход циклический, порядок зафиксирован seed'ом. */
  public static String next() {
    List<String> current = prompts;
    int size = current.size();
    if (size == 0) {
      throw new IllegalStateException("Dataset.load() не вызван");
    }
    int index = Math.floorMod(CURSOR.getAndIncrement(), size);
    return current.get(index);
  }

  public static int size() {
    return prompts.size();
  }

  /** SHA-256 файла датасета в hex. Попадает в артефакты прогона. */
  public static String sha256() {
    return sha256;
  }

  /**
   * Декодирует файл как UTF-8, отбрасывая BOM.
   *
   * <p>Выгрузка из БД под Windows-инструментами регулярно приезжает с BOM, и тогда первая колонка
   * заголовка называется не {@code prompt}, а {@code <BOM>prompt} — датасет «без колонки prompt»
   * при визуально корректном файле.
   */
  private static String decode(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    return text.isEmpty() || text.charAt(0) != '\uFEFF' ? text : text.substring(1);
  }

  private static String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 недоступен", e);
    }
  }

  /** Сбрасывает состояние. Только для тестов. */
  static void reset() {
    prompts = List.of();
    sha256 = "";
    CURSOR.set(0);
  }

  /** Ошибка загрузки датасета: прогон не начинается. */
  public static class DatasetException extends RuntimeException {

    public DatasetException(String message) {
      super(message);
    }

    public DatasetException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

package perfbench.loadtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Клиентская часть отчёта: всё, что считается по сырому {@code results.jtl}.
 *
 * <p>Расчёт ведётся по отдельным наблюдениям, а не по интервальным агрегатам, поэтому величины за
 * произвольное окно получаются точными. Здесь же — единственный настоящий максимум в отчёте:
 * на клиенте есть каждое отдельное наблюдение, а не бакеты гистограммы.
 *
 * <p>Чтение потоковое: файл прогона может быть крупным.
 */
public final class JtlStats {

  /** Момент старта сэмпла. JMeter пишет сюда именно старт: {@code sampleresult.timestamp.start=true}. */
  public static final String COL_TIMESTAMP = "timeStamp";
  /** Полное время отклика в миллисекундах. */
  public static final String COL_ELAPSED = "elapsed";
  public static final String COL_SUCCESS = "success";
  public static final String COL_RESPONSE_CODE = "responseCode";
  public static final String COL_RESPONSE_MESSAGE = "responseMessage";
  public static final String COL_FAILURE_MESSAGE = "failureMessage";
  /** Число активных потоков генератора на момент сэмпла. */
  public static final String COL_ALL_THREADS = "allThreads";

  /** Сколько разных причин ошибок показывать в отчёте. */
  private static final int TOP_ERRORS = 5;

  private static final double QUANTILE = 0.95;

  private JtlStats() {
  }

  /**
   * Результаты за одно окно удержания.
   *
   * @param total        всего запросов, стартовавших в окне
   * @param successful   из них успешных
   * @param errors       из них ошибочных
   * @param errorRate    доля ошибок, 0..1
   * @param actualRpm    фактическая частота: успешные запросы окна, делённые на его длительность
   * @param e2eAvgSec    среднее время отклика по успешным запросам, секунды
   * @param e2eP95Sec    p95 времени отклика по успешным запросам, секунды
   * @param e2eMinSec    минимальное время отклика, секунды
   * @param e2eMaxSec    <b>истинный</b> максимум времени отклика, секунды
   * @param peakThreads  наибольшее число активных потоков генератора, наблюдённое в окне;
   *                     при недоборе RPM отвечает на вопрос, упёрлись ли в ёмкость генератора
   *                     или в сервер
   * @param errorReasons причины ошибок с числом вхождений, по убыванию
   */
  public record Result(
      int total,
      int successful,
      int errors,
      double errorRate,
      double actualRpm,
      double e2eAvgSec,
      double e2eP95Sec,
      double e2eMinSec,
      double e2eMaxSec,
      int peakThreads,
      Map<String, Integer> errorReasons) {

    public boolean isEmpty() {
      return total == 0;
    }

    static Result empty() {
      return new Result(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }
  }

  /**
   * Момент старта первого запроса прогона.
   *
   * <p>От него отсчитываются окна ступеней. Это точнее, чем момент вызова {@code plan.run()}:
   * между запуском движка и первым запросом проходит построение и клонирование дерева плана.
   *
   * <p>Строки в JTL идут в порядке <i>завершения</i> сэмплов, а не старта, поэтому берётся минимум
   * по всему файлу, а не первая строка.
   */
  public static Optional<Instant> loadStart(Path jtl) {
    long min = Long.MAX_VALUE;
    try (CSVParser parser = open(jtl)) {
      for (CSVRecord record : parser) {
        long ts = longAt(record, COL_TIMESTAMP);
        if (ts > 0 && ts < min) {
          min = ts;
        }
      }
    } catch (IOException e) {
      throw new JtlException(jtl + " не читается: " + e.getMessage(), e);
    }
    return min == Long.MAX_VALUE ? Optional.empty() : Optional.of(Instant.ofEpochMilli(min));
  }

  /**
   * Считает результаты по всем окнам за один проход по файлу.
   *
   * @return номер ступени → результат; для ступеней без сэмплов возвращается пустой результат
   */
  public static Map<Integer, Result> collect(Path jtl, List<HoldWindow> windows) {
    List<Accumulator> accumulators = windows.stream().map(Accumulator::new).toList();

    try (CSVParser parser = open(jtl)) {
      for (CSVRecord record : parser) {
        long ts = longAt(record, COL_TIMESTAMP);
        if (ts <= 0) {
          continue;
        }
        Instant at = Instant.ofEpochMilli(ts);
        for (Accumulator accumulator : accumulators) {
          if (accumulator.window.contains(at)) {
            accumulator.add(record);
            // Окна ступеней не пересекаются: дальше искать нечего.
            break;
          }
        }
      }
    } catch (IOException e) {
      throw new JtlException(jtl + " не читается: " + e.getMessage(), e);
    }

    Map<Integer, Result> results = new LinkedHashMap<>();
    for (Accumulator accumulator : accumulators) {
      results.put(accumulator.window.stageNumber(), accumulator.build());
    }
    return results;
  }

  private static CSVParser open(Path jtl) throws IOException {
    if (!Files.isRegularFile(jtl)) {
      throw new JtlException(jtl + " не найден: прогон не оставил результатов");
    }
    CSVFormat format = CSVFormat.DEFAULT.builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreEmptyLines(true)
        // Прогон мог быть прерван на середине строки — недописанный хвост не должен ронять отчёт.
        .setAllowMissingColumnNames(true)
        .build();
    BufferedReader reader = Files.newBufferedReader(jtl, StandardCharsets.UTF_8);
    try {
      return CSVParser.parse(reader, format);
    } catch (IOException | RuntimeException e) {
      reader.close();
      throw e;
    }
  }

  private static long longAt(CSVRecord record, String column) {
    if (!record.isSet(column)) {
      return -1;
    }
    try {
      return Long.parseLong(record.get(column).trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Копит наблюдения одного окна. */
  private static final class Accumulator {

    private final HoldWindow window;
    private long[] elapsed = new long[1024];
    private int successful;
    private int total;
    private int errors;
    private long sum;
    private int peakThreads;
    private final Map<String, Integer> reasons = new TreeMap<>();

    Accumulator(HoldWindow window) {
      this.window = window;
    }

    void add(CSVRecord record) {
      total++;
      peakThreads = (int) Math.max(peakThreads, longAt(record, COL_ALL_THREADS));
      boolean ok = record.isSet(COL_SUCCESS) && "true".equalsIgnoreCase(record.get(COL_SUCCESS).trim());
      if (!ok) {
        errors++;
        reasons.merge(reason(record), 1, Integer::sum);
        return;
      }
      long value = longAt(record, COL_ELAPSED);
      if (value < 0) {
        return;
      }
      if (successful == elapsed.length) {
        elapsed = Arrays.copyOf(elapsed, elapsed.length * 2);
      }
      elapsed[successful++] = value;
      sum += value;
    }

    private String reason(CSVRecord record) {
      String code = record.isSet(COL_RESPONSE_CODE) ? record.get(COL_RESPONSE_CODE).trim() : "";
      String failure =
          record.isSet(COL_FAILURE_MESSAGE) ? record.get(COL_FAILURE_MESSAGE).trim() : "";
      if (failure.isEmpty() && record.isSet(COL_RESPONSE_MESSAGE)) {
        failure = record.get(COL_RESPONSE_MESSAGE).trim();
      }
      String reason = code.isEmpty() ? "(без кода)" : code;
      if (!failure.isEmpty()) {
        reason = reason + " " + (failure.length() > 120 ? failure.substring(0, 117) + "..." : failure);
      }
      return reason;
    }

    Result build() {
      if (total == 0) {
        return Result.empty();
      }
      double windowSec = window.duration().toSeconds();
      double actualRpm = windowSec > 0 ? successful / windowSec * 60.0 : 0;
      double errorRate = (double) errors / total;

      if (successful == 0) {
        return new Result(total, 0, errors, errorRate, 0, 0, 0, 0, 0, peakThreads, topReasons());
      }

      long[] values = Arrays.copyOf(elapsed, successful);
      Arrays.sort(values);
      double avgSec = sum / (double) successful / 1000.0;
      return new Result(
          total,
          successful,
          errors,
          errorRate,
          actualRpm,
          avgSec,
          percentile(values, QUANTILE) / 1000.0,
          values[0] / 1000.0,
          values[values.length - 1] / 1000.0,
          peakThreads,
          topReasons());
    }

    private Map<String, Integer> topReasons() {
      Map<String, Integer> top = new LinkedHashMap<>();
      reasons.entrySet().stream()
          .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
              .reversed())
          .limit(TOP_ERRORS)
          .forEach(e -> top.put(e.getKey(), e.getValue()));
      return top;
    }
  }

  /**
   * Перцентиль методом ближайшего ранга по возрастающему массиву.
   *
   * <p>Считается по сырым наблюдениям окна — это математически корректное значение за ступень,
   * в отличие от усреднения интервальных агрегатов.
   */
  static double percentile(long[] sorted, double quantile) {
    if (sorted.length == 0) {
      return 0;
    }
    int rank = (int) Math.ceil(quantile * sorted.length);
    int index = Math.min(sorted.length, Math.max(1, rank)) - 1;
    return sorted[index];
  }

  /** Только для тестов: перцентиль по несортированному списку. */
  static double percentile(List<Long> values, double quantile) {
    long[] array = new long[values.size()];
    for (int i = 0; i < array.length; i++) {
      array[i] = values.get(i);
    }
    Arrays.sort(array);
    return percentile(array, quantile);
  }

  /** Ошибка чтения результатов прогона. */
  public static class JtlException extends RuntimeException {

    public JtlException(String message) {
      super(message);
    }

    public JtlException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

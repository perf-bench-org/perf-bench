package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JtlStatsTest {

  private static final String HEADER = "timeStamp,elapsed,label,responseCode,responseMessage,"
      + "threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,"
      + "Latency,IdleTime,Connect";

  private static final Instant T0 = Instant.parse("2026-08-05T12:00:00Z");

  @TempDir
  Path temp;

  private Path jtl(List<String> rows) throws IOException {
    Path file = temp.resolve("results.jtl");
    Files.writeString(file, HEADER + "\n" + String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
    return file;
  }

  /** Строка JTL: смещение от T0 в секундах, время отклика в мс, успех. */
  private String row(long offsetSec, long elapsedMs, boolean success) {
    return row(offsetSec, elapsedMs, success, success ? "200" : "500", "");
  }

  private String row(long offsetSec, long elapsedMs, boolean success, String code, String failure) {
    return row(offsetSec, elapsedMs, success, code, failure, 1);
  }

  private String row(long offsetSec, long elapsedMs, boolean success, String code, String failure,
      int allThreads) {
    long ts = T0.plusSeconds(offsetSec).toEpochMilli();
    return ts + "," + elapsedMs + ",chat-completions," + code + ",OK,tg 1-1,text," + success + ","
        + failure + ",100,200," + allThreads + "," + allThreads
        + ",http://vllm:8000/v1/chat/completions," + elapsedMs + ",0,1";
  }

  private HoldWindow window(long fromSec, long toSec) {
    return new HoldWindow(1, 60, 375, T0.plusSeconds(fromSec), T0.plusSeconds(toSec));
  }

  @Test
  @DisplayName("сэмплы вне окна удержания не попадают в расчёт")
  void filtersByWindow() throws IOException {
    Path file = jtl(List.of(
        row(10, 1000, true),    // до окна
        row(100, 2000, true),   // в окне
        row(150, 3000, true),   // в окне
        row(200, 9000, true),   // ровно на правой границе — исключается
        row(250, 9000, true))); // после окна

    JtlStats.Result result = JtlStats.collect(file, List.of(window(100, 200))).get(1);

    assertEquals(2, result.total());
    assertEquals(2, result.successful());
    assertEquals(2.5, result.e2eAvgSec(), 1e-9);
    assertEquals(2.0, result.e2eMinSec(), 1e-9);
    assertEquals(3.0, result.e2eMaxSec(), 1e-9);
  }

  @Test
  @DisplayName("левая граница окна включается, правая исключается")
  void windowIsHalfOpen() throws IOException {
    Path file = jtl(List.of(
        row(100, 1000, true),
        row(199, 1000, true),
        row(200, 1000, true)));

    JtlStats.Result result = JtlStats.collect(file, List.of(window(100, 200))).get(1);

    assertEquals(2, result.total());
  }

  @Test
  @DisplayName("фактический RPM считается по успешным и длительности окна, а не по числу строк")
  void actualRpmUsesWindowDuration() throws IOException {
    List<String> rows = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      rows.add(row(100 + i, 1000, true));
    }
    Path file = jtl(rows);

    // 30 успешных за окно 60 с -> 30 RPM
    JtlStats.Result result = JtlStats.collect(file, List.of(window(100, 160))).get(1);

    assertEquals(30.0, result.actualRpm(), 1e-9);
  }

  @Test
  @DisplayName("ошибки считаются, но в latency не входят")
  void errorsExcludedFromLatency() throws IOException {
    Path file = jtl(List.of(
        row(100, 1000, true),
        row(110, 1000, true),
        row(120, 600000, false, "500", "Internal Server Error"),
        row(130, 1000, true)));

    JtlStats.Result result = JtlStats.collect(file, List.of(window(100, 200))).get(1);

    assertEquals(4, result.total());
    assertEquals(3, result.successful());
    assertEquals(1, result.errors());
    assertEquals(0.25, result.errorRate(), 1e-9);
    assertEquals(1.0, result.e2eMaxSec(), 1e-9);
    assertEquals(Map.of("500 Internal Server Error", 1), result.errorReasons());
  }

  @Test
  @DisplayName("p95 методом ближайшего ранга на известном наборе")
  void percentileNearestRank() {
    long[] values = new long[100];
    for (int i = 0; i < 100; i++) {
      values[i] = i + 1;
    }

    // ceil(0.95 * 100) = 95 -> 95-й элемент
    assertEquals(95, JtlStats.percentile(values, 0.95));
    assertEquals(100, JtlStats.percentile(values, 1.0));
    assertEquals(1, JtlStats.percentile(new long[]{1}, 0.95));
    assertEquals(0, JtlStats.percentile(new long[0], 0.95));
  }

  @Test
  @DisplayName("p95 не зависит от порядка строк в файле")
  void percentileIgnoresOrder() throws IOException {
    Path file = jtl(List.of(
        row(105, 5000, true),
        row(101, 1000, true),
        row(104, 4000, true),
        row(102, 2000, true),
        row(103, 3000, true)));

    JtlStats.Result result = JtlStats.collect(file, List.of(window(100, 200))).get(1);

    assertEquals(1.0, result.e2eMinSec(), 1e-9);
    assertEquals(5.0, result.e2eMaxSec(), 1e-9);
    assertEquals(5.0, result.e2eP95Sec(), 1e-9);
    assertEquals(3.0, result.e2eAvgSec(), 1e-9);
  }

  @Test
  @DisplayName("окна нескольких ступеней разбираются за один проход и не пересекаются")
  void splitsAcrossStages() throws IOException {
    Path file = jtl(List.of(
        row(100, 1000, true),
        row(150, 1000, true),
        row(300, 2000, true),
        row(350, 2000, true),
        row(360, 2000, true)));

    List<HoldWindow> windows = List.of(
        new HoldWindow(1, 60, 375, T0.plusSeconds(100), T0.plusSeconds(200)),
        new HoldWindow(2, 90, 563, T0.plusSeconds(300), T0.plusSeconds(400)));

    Map<Integer, JtlStats.Result> results = JtlStats.collect(file, windows);

    assertEquals(2, results.get(1).total());
    assertEquals(3, results.get(2).total());
  }

  @Test
  @DisplayName("окно без сэмплов даёт пустой результат, а не падение")
  void emptyWindowIsEmptyResult() throws IOException {
    Path file = jtl(List.of(row(10, 1000, true)));

    JtlStats.Result result = JtlStats.collect(file, List.of(window(100, 200))).get(1);

    assertTrue(result.isEmpty());
    assertEquals(0, result.actualRpm());
  }

  @Test
  @DisplayName("момент старта нагрузки — минимум по файлу, а не первая строка")
  void loadStartIsMinimum() throws IOException {
    // JTL пишется в порядке завершения сэмплов, поэтому первая строка не обязана быть самой ранней.
    Path file = jtl(List.of(
        row(50, 1000, true),
        row(10, 9000, true),
        row(70, 1000, true)));

    assertEquals(T0.plusSeconds(10), JtlStats.loadStart(file).orElseThrow());
  }

  @Test
  @DisplayName("пик активных потоков берётся по окну, а не по всему файлу")
  void tracksPeakThreadsWithinWindow() throws IOException {
    Path file = jtl(List.of(
        row(50, 1000, true, "200", "", 900),   // до окна: пик разгона не должен утечь в ступень
        row(120, 1000, true, "200", "", 40),
        row(130, 1000, true, "200", "", 75),
        row(140, 1000, true, "200", "", 60)));

    JtlStats.Result result = JtlStats.collect(file, List.of(window(100, 200))).get(1);

    assertEquals(75, result.peakThreads());
  }

  @Test
  @DisplayName("отсутствующий файл результатов — понятная ошибка")
  void failsOnMissingFile() {
    assertThrows(JtlStats.JtlException.class,
        () -> JtlStats.collect(temp.resolve("нет.jtl"), List.of(window(0, 10))));
  }
}

package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {

  private static final Instant T0 = Instant.parse("2026-08-05T12:00:00Z");

  @TempDir
  Path temp;

  private RunMeta meta(int stageCount) {
    Map<String, String> names = new LinkedHashMap<>();
    names.put(VllmMetrics.M_KV_CACHE, "vllm:kv_cache_usage_perc");
    names.put(VllmMetrics.M_QUEUE, "vllm:num_requests_waiting");
    names.put(VllmMetrics.M_TTFT, "vllm:time_to_first_token_seconds");
    names.put(VllmMetrics.M_TPOT, "vllm:request_time_per_output_token_seconds");
    names.put(VllmMetrics.M_PROMPT_TOKENS, "vllm:prompt_tokens_total");
    names.put(VllmMetrics.M_GENERATION_TOKENS, "vllm:generation_tokens_total");

    List<HoldWindow> windows = new java.util.ArrayList<>();
    for (int i = 1; i <= stageCount; i++) {
      windows.add(new HoldWindow(i, 100 * i, Threads.required(100 * i),
          T0.plusSeconds(300L * i), T0.plusSeconds(300L * i + 1800)));
    }
    return new RunMeta("confirm-180", stageCount > 1 ? LoadMode.STEP : LoadMode.FLAT, 1125,
        T0, T0, T0.plusSeconds(5000), windows, "a".repeat(64), 12000,
        "Qwen/Qwen2.5-7B-Instruct", "0.11.0", names);
  }

  private StageReport stage(HoldWindow window, boolean withItl) {
    JtlStats.Result client = new JtlStats.Result(5361, 5358, 3, 3.0 / 5361, 178.4,
        298.11, 330.40, 201.05, 402.77, 900, Map.of("500 Internal Server Error", 3));
    VllmMetrics.ServerMetrics server = new VllmMetrics.ServerMetrics(
        Optional.of(72.4), Optional.of(88.1), Optional.of(12.0),
        Optional.of(4.21), Optional.of(18.9),
        Optional.of(0.18), Optional.of(0.41),
        withItl ? Optional.of(0.17) : Optional.empty(),
        withItl ? Optional.of(0.39) : Optional.empty(),
        Optional.of(1024.5), Optional.of(2210.0),
        Optional.of(3050.1), Optional.of(3900.2),
        withItl);
    Scoring.ScoreResult score = Scoring.evaluate(window, client, server, 360);
    return new StageReport(window, client, server, score);
  }

  @Test
  @DisplayName("отчёт flat содержит все обязательные строки")
  void writesFlatReport() throws IOException {
    RunMeta meta = meta(1);
    Path file = temp.resolve("report.txt");

    Report.write(file, meta, List.of(stage(meta.windows().get(0), true)), 360, false);

    String text = Files.readString(file);
    assertTrue(text.contains("vLLM BENCHMARK REPORT"), text);
    assertTrue(text.contains("confirm-180"));
    assertTrue(text.contains("Qwen/Qwen2.5-7B-Instruct"));
    assertTrue(text.contains("STAGE 1 — target 100 RPM"));
    assertTrue(text.contains("Throughput"));
    assertTrue(text.contains("E2E Latency Max"));
    assertTrue(text.contains("истинный максимум"));
    assertTrue(text.contains("Generation Throughput Avg"));
    assertTrue(text.contains("Score:"));
    assertTrue(text.contains("NOTES"));
    // Разгон восстановлен из границ окон, а не сохранён отдельно.
    assertTrue(text.contains("Ramp"), text);
  }

  @Test
  @DisplayName("величины гистограмм подписаны как p95, а не как Max")
  void labelsQuantilesHonestly() throws IOException {
    RunMeta meta = meta(1);
    Path file = temp.resolve("report.txt");

    Report.write(file, meta, List.of(stage(meta.windows().get(0), true)), 360, false);

    String text = Files.readString(file);
    assertTrue(text.contains("TTFT p95 (avg)"));
    assertTrue(text.contains("TTFT p95 (max)"));
    assertFalse(text.contains("TTFT Max"), "гистограммной величине нельзя приписывать Max");
    assertFalse(text.contains("TPOT Max"), "гистограммной величине нельзя приписывать Max");
    // Подокно печатается рядом со всеми зависящими от него величинами.
    assertTrue(text.contains("подокно " + VllmMetrics.SUBWINDOW_SEC + " s"));
  }

  @Test
  @DisplayName("отсутствующий ITL помечается недоступным и не заполняется значением TPOT")
  void missingItlIsNotFilledWithTpot() throws IOException {
    RunMeta meta = meta(1);
    Path file = temp.resolve("report.txt");

    Report.write(file, meta, List.of(stage(meta.windows().get(0), false)), 360, false);

    String text = Files.readString(file);
    String itlLine = text.lines()
        .filter(l -> l.contains("ITL p95 (avg)"))
        .findFirst()
        .orElseThrow();
    assertTrue(itlLine.contains("недоступно"), itlLine);
    assertFalse(itlLine.contains("0.18"), itlLine);
  }

  @Test
  @DisplayName("step-прогон даёт секцию на ступень и сводную таблицу")
  void writesStepReport() throws IOException {
    RunMeta meta = meta(3);
    List<StageReport> stages = meta.windows().stream().map(w -> stage(w, true)).toList();
    Path file = temp.resolve("report.txt");

    Report.write(file, meta, stages, 360, false);

    String text = Files.readString(file);
    assertTrue(text.contains("STAGE 1 — target 100 RPM"));
    assertTrue(text.contains("STAGE 2 — target 200 RPM"));
    assertTrue(text.contains("STAGE 3 — target 300 RPM"));
    assertTrue(text.contains("SUMMARY"));
    assertTrue(text.contains("Target RPM"));
  }

  @Test
  @DisplayName("пересчёт помечается в шапке")
  void marksRecalculation() throws IOException {
    RunMeta meta = meta(1);
    Path file = temp.resolve("report.recalc-1.txt");

    Report.write(file, meta, List.of(stage(meta.windows().get(0), true)), 360, true);

    assertTrue(Files.readString(file).contains("пересчёт по собранным данным"));
  }

  @Test
  @DisplayName("порядок имён метрик не зависит от порядка обхода Map")
  void metricNamesAreOrderStable() throws IOException {
    RunMeta original = meta(1);
    // Тот же набор имён, но в другом порядке вставки — режим report читает их из properties,
    // где порядок не сохраняется.
    Map<String, String> shuffled = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
    shuffled.putAll(original.metricNames());
    RunMeta reordered = new RunMeta(original.runId(), original.mode(), original.threads(),
        original.startedAt(), original.loadStart(), original.endedAt(), original.windows(),
        original.datasetSha(), original.datasetSize(), original.model(), original.vllmVersion(),
        shuffled);

    Path first = temp.resolve("a.txt");
    Path second = temp.resolve("b.txt");
    Report.write(first, original, List.of(stage(original.windows().get(0), true)), 360, false);
    Report.write(second, reordered, List.of(stage(reordered.windows().get(0), true)), 360, false);

    assertEquals(
        Files.readAllLines(first).stream().filter(l -> !l.contains("Generated at")).toList(),
        Files.readAllLines(second).stream().filter(l -> !l.contains("Generated at")).toList());
  }

  @Test
  @DisplayName("пустое окно не роняет формирование отчёта")
  void survivesEmptyStage() throws IOException {
    RunMeta meta = meta(1);
    HoldWindow window = meta.windows().get(0);
    StageReport empty = new StageReport(window, JtlStats.Result.empty(),
        VllmMetrics.ServerMetrics.unavailable(),
        Scoring.evaluate(window, JtlStats.Result.empty(),
            VllmMetrics.ServerMetrics.unavailable(), 360));
    Path file = temp.resolve("report.txt");

    Report.write(file, meta, List.of(empty), 360, false);

    String text = Files.readString(file);
    assertTrue(text.contains("не пришло ни одного запроса"), text);
    assertTrue(text.contains("недоступно"));
  }
}

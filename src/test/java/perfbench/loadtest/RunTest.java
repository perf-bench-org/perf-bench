package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunTest {

  private static final Instant T0 = Instant.parse("2026-08-05T12:00:00Z");

  @TempDir
  Path temp;

  @AfterEach
  void tearDown() {
    Config.reset();
    Run.reset();
  }

  private void loadConfig(String runId) throws IOException {
    Path dataset = temp.resolve("prompts.csv");
    Files.writeString(dataset, "prompt\nтекст\n", StandardCharsets.UTF_8);
    Path config = temp.resolve("test.properties");
    Files.writeString(config, String.join("\n",
        "target.url=http://vllm:8000/v1/chat/completions",
        "vllm.baseUrl=http://vllm:8000",
        "vm.url=http://victoria-metrics:8428",
        "mode=flat",
        "dataset.path=" + dataset,
        "request.responseTimeoutSec=600",
        "run.id=" + runId,
        "run.resultsDir=" + temp.resolve("results")) + "\n", StandardCharsets.UTF_8);
    Config.load(config.toString(), Map.of());
  }

  @Test
  @DisplayName("создаёт каталог прогона и пишет снапшот конфигурации")
  void createsRunDirectory() throws IOException {
    loadConfig("run-1");

    Run.init();

    assertEquals("run-1", Run.id());
    assertTrue(Files.isDirectory(Run.dir()));
    assertTrue(Files.isRegularFile(Run.dir().resolve(Run.CONFIG_SNAPSHOT)));
    assertTrue(Files.readString(Run.dir().resolve(Run.CONFIG_SNAPSHOT)).contains("mode=flat"));
  }

  @Test
  @DisplayName("повторный запуск с занятым идентификатором падает, артефакты целы")
  void refusesToOverwriteExistingRun() throws IOException {
    loadConfig("run-1");
    Run.init();
    Path report = Run.dir().resolve(Run.REPORT);
    Files.writeString(report, "исходный отчёт", StandardCharsets.UTF_8);

    Run.reset();
    Run.RunException e = assertThrows(Run.RunException.class, Run::init);

    assertTrue(e.getMessage().contains("LT_RUN_ID"), e.getMessage());
    assertEquals("исходный отчёт", Files.readString(report));
  }

  @Test
  @DisplayName("пустой существующий каталог не мешает старту")
  void allowsEmptyExistingDirectory() throws IOException {
    loadConfig("run-1");
    Files.createDirectories(temp.resolve("results").resolve("run-1"));

    Run.init();

    assertTrue(Files.isRegularFile(Run.dir().resolve(Run.CONFIG_SNAPSHOT)));
  }

  @Test
  @DisplayName("run.meta.properties переживает запись и чтение без потерь")
  void metaRoundTrip() throws IOException {
    Path dir = temp.resolve("run-2");
    Files.createDirectories(dir);
    Run.openUnchecked("run-2", dir);

    RunMeta written = new RunMeta("run-2", LoadMode.STEP, 1250,
        T0, T0.plusSeconds(2), T0.plusSeconds(4000),
        List.of(
            new HoldWindow(1, 100, 625, T0.plusSeconds(300), T0.plusSeconds(600)),
            new HoldWindow(2, 120, 750, T0.plusSeconds(900), T0.plusSeconds(1200))),
        "abc123", 42, "Qwen/Qwen2.5-7B-Instruct", "0.11.0",
        Map.of(VllmMetrics.M_KV_CACHE, "vllm:kv_cache_usage_perc",
            VllmMetrics.M_QUEUE, "vllm:num_requests_waiting"));

    Run.writeMeta(written);
    RunMeta read = Run.readMeta();

    assertEquals(written.runId(), read.runId());
    assertEquals(written.mode(), read.mode());
    assertEquals(written.threads(), read.threads());
    assertEquals(written.loadStart(), read.loadStart());
    assertEquals(written.endedAt(), read.endedAt());
    assertEquals(written.windows(), read.windows());
    assertEquals(written.model(), read.model());
    assertEquals(written.vllmVersion(), read.vllmVersion());
    assertEquals(written.metricNames(), read.metricNames());
    assertEquals(written.datasetSha(), read.datasetSha());
    assertEquals(written.datasetSize(), read.datasetSize());
  }

  @Test
  @DisplayName("пересчёт не перезаписывает исходный отчёт")
  void recalculationGetsItsOwnFile() throws IOException {
    Path dir = temp.resolve("run-3");
    Files.createDirectories(dir);
    Run.openUnchecked("run-3", dir);
    Files.writeString(dir.resolve(Run.REPORT), "исходный", StandardCharsets.UTF_8);

    Path first = Run.reportFile(true);
    Files.writeString(first, "пересчёт 1", StandardCharsets.UTF_8);
    Path second = Run.reportFile(true);

    assertEquals(dir.resolve(Run.REPORT), Run.reportFile(false));
    assertEquals("report.recalc-1.txt", first.getFileName().toString());
    assertEquals("report.recalc-2.txt", second.getFileName().toString());
    assertEquals("исходный", Files.readString(dir.resolve(Run.REPORT)));
  }

  @Test
  @DisplayName("каталог без результатов убирается, чтобы тот же run.id можно было повторить")
  void discardsRunWithoutResults() throws IOException {
    loadConfig("run-4");
    Run.init();
    Path dir = Run.dir();

    Run.discardIfNoResults();

    assertFalse(Files.exists(dir));
    Run.reset();
    Run.init();
    assertTrue(Files.isRegularFile(Run.dir().resolve(Run.CONFIG_SNAPSHOT)));
  }

  @Test
  @DisplayName("каталог с результатами не убирается ни при каких обстоятельствах")
  void keepsRunWithResults() throws IOException {
    loadConfig("run-5");
    Run.init();
    Files.writeString(Run.jtl(), "timeStamp,elapsed\n", StandardCharsets.UTF_8);

    Run.discardIfNoResults();

    assertTrue(Files.isRegularFile(Run.jtl()));
  }

  @Test
  @DisplayName("открытие несуществующего прогона — понятная ошибка")
  void openMissingRunFails() throws IOException {
    loadConfig("");

    assertThrows(Run.RunException.class, () -> Run.open("нет-такого"));
  }
}

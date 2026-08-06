package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

  @TempDir
  Path temp;

  @AfterEach
  void tearDown() {
    Config.reset();
  }

  private Path dataset() throws IOException {
    Path file = temp.resolve("prompts.csv");
    Files.writeString(file, "prompt\nтекст\n", StandardCharsets.UTF_8);
    return file;
  }

  private Path config(String... lines) throws IOException {
    Path file = temp.resolve("test.properties");
    Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    return file;
  }

  private Path minimalConfig() throws IOException {
    return config(
        "target.url=http://vllm:8000/v1/chat/completions",
        "vllm.baseUrl=http://vllm:8000",
        "vm.url=http://victoria-metrics:8428",
        "mode=flat",
        "flat.rpm=180",
        "flat.ramp=300",
        "flat.hold=1800",
        "dataset.path=" + dataset(),
        "request.responseTimeoutSec=600",
        "scoring.e2eSlaSec=360",
        "run.resultsDir=" + temp);
  }

  @Test
  @DisplayName("значения из файла читаются и приводятся к типам")
  void readsFile() throws IOException {
    Config.load(minimalConfig().toString(), Map.of());

    assertEquals(LoadMode.FLAT, Config.mode());
    assertEquals(1, Config.stages().size());
    assertEquals(180, Config.stages().get(0).targetRpm());
    // Заданные 300 с разгона подняты до 375 с — такта потока: при меньшем разгоне после него
    // образуется провал без запросов внутри окна удержания.
    assertEquals(375, Config.stages().get(0).ramp().toSeconds());
    assertEquals(1800, Config.stages().get(0).hold().toSeconds());
    assertEquals(360.0, Config.e2eSlaSec());
  }

  @Test
  @DisplayName("LT_* перекрывают файл")
  void envOverridesFile() throws IOException {
    Config.load(minimalConfig().toString(), Map.of(
        "LT_FLAT_RPM", "220",
        "LT_RUN_ID", "confirm-220",
        "PATH", "/usr/bin"));

    assertEquals(220, Config.stages().get(0).targetRpm());
    assertEquals("confirm-220", Config.runId());
  }

  @Test
  @DisplayName("LT_MODE=step включает разбор ступенчатого профиля")
  void envSwitchesMode() throws IOException {
    Path file = config(
        "target.url=http://vllm:8000/v1/chat/completions",
        "vllm.baseUrl=http://vllm:8000",
        "vm.url=http://victoria-metrics:8428",
        "mode=flat",
        "step.profile=100 300 300; 120 300 300",
        "dataset.path=" + dataset(),
        "request.responseTimeoutSec=600",
        "run.resultsDir=" + temp);

    Config.load(file.toString(), Map.of("LT_MODE", "step"));

    assertEquals(LoadMode.STEP, Config.mode());
    assertEquals(2, Config.stages().size());
  }

  @Test
  @DisplayName("переопределения попадают в снапшот конфигурации")
  void snapshotReflectsOverrides() throws IOException {
    Config.load(minimalConfig().toString(), Map.of("LT_FLAT_RPM", "220"));

    assertEquals("220", Config.effective().getProperty("flat.rpm"));
  }

  @Test
  @DisplayName("ключ API в снапшоте маскируется")
  void snapshotMasksApiKey() throws IOException {
    Config.load(minimalConfig().toString(), Map.of("LT_TARGET_APIKEY", "секрет"));

    assertEquals("секрет", Config.targetApiKey());
    assertEquals("***", Config.effective().getProperty("target.apiKey"));
  }

  @Test
  @DisplayName("опечатка в имени LT_-переменной — ошибка, а не молчаливое игнорирование")
  void unknownEnvIsAnError() throws IOException {
    Path file = minimalConfig();

    Config.ConfigException e = assertThrows(Config.ConfigException.class,
        () -> Config.load(file.toString(), Map.of("LT_FLAT_RMP", "220")));

    assertTrue(e.getMessage().contains("LT_FLAT_RMP"), e.getMessage());
  }

  @Test
  @DisplayName("неизвестный ключ в файле — ошибка")
  void unknownFileKeyIsAnError() throws IOException {
    Path file = config(
        "target.url=http://vllm:8000/v1/chat/completions",
        "flat.threads=100");

    Config.ConfigException e = assertThrows(Config.ConfigException.class,
        () -> Config.load(file.toString(), Map.of()));

    assertTrue(e.getMessage().contains("flat.threads"), e.getMessage());
  }

  @Test
  @DisplayName("собираются все ошибки валидации сразу, а не первая")
  void collectsAllErrors() throws IOException {
    Path file = config(
        "target.url=не-урл",
        "vllm.baseUrl=",
        "vm.url=http://victoria-metrics:8428",
        "mode=турбо",
        "dataset.path=/нет/такого.csv",
        "dataset.delimiter=;;",
        "dataset.seed=абв",
        "request.connectTimeoutSec=-1",
        "scoring.e2eSlaSec=0",
        "run.resultsDir=" + temp);

    Config.ConfigException e = assertThrows(Config.ConfigException.class,
        () -> Config.load(file.toString(), Map.of()));

    List<String> errors = e.errors();
    assertTrue(errors.size() >= 6, errors.toString());
    assertTrue(errors.stream().anyMatch(m -> m.startsWith("target.url")), errors.toString());
    assertTrue(errors.stream().anyMatch(m -> m.startsWith("vllm.baseUrl")), errors.toString());
    assertTrue(errors.stream().anyMatch(m -> m.startsWith("mode")), errors.toString());
    assertTrue(errors.stream().anyMatch(m -> m.startsWith("dataset.delimiter")), errors.toString());
    assertTrue(errors.stream().anyMatch(m -> m.startsWith("dataset.seed")), errors.toString());
    assertTrue(errors.stream().anyMatch(m -> m.startsWith("request.connectTimeoutSec")),
        errors.toString());
  }

  @Test
  @DisplayName("таймаут ответа короче ожидаемой длительности итерации — ошибка")
  void rejectsTooShortResponseTimeout() throws IOException {
    Path file = config(
        "target.url=http://vllm:8000/v1/chat/completions",
        "vllm.baseUrl=http://vllm:8000",
        "vm.url=http://victoria-metrics:8428",
        "mode=flat",
        "dataset.path=" + dataset(),
        "request.responseTimeoutSec=180",
        "run.resultsDir=" + temp);

    Config.ConfigException e = assertThrows(Config.ConfigException.class,
        () -> Config.load(file.toString(), Map.of()));

    assertTrue(e.getMessage().contains("request.responseTimeoutSec"), e.getMessage());
  }

  @Test
  @DisplayName("порог SLA не выше ожидаемой длительности итерации — ошибка")
  void rejectsSlaEqualToIterationTime() throws IOException {
    Path file = config(
        "target.url=http://vllm:8000/v1/chat/completions",
        "vllm.baseUrl=http://vllm:8000",
        "vm.url=http://victoria-metrics:8428",
        "mode=flat",
        "dataset.path=" + dataset(),
        "request.responseTimeoutSec=600",
        "scoring.e2eSlaSec=300",
        "run.resultsDir=" + temp);

    Config.ConfigException e = assertThrows(Config.ConfigException.class,
        () -> Config.load(file.toString(), Map.of()));

    assertTrue(e.getMessage().contains("scoring.e2eSlaSec"), e.getMessage());
  }

  @Test
  @DisplayName("режим report без идентификатора прогона — ошибка")
  void reportModeRequiresRunId() throws IOException {
    Path file = minimalConfig();

    Config.ConfigException e = assertThrows(Config.ConfigException.class,
        () -> Config.load(file.toString(), Map.of("LT_MODE", "report")));

    assertTrue(e.getMessage().contains("run.id"), e.getMessage());
  }

  @Test
  @DisplayName("режим report не требует наличия датасета")
  void reportModeIgnoresDataset() throws IOException {
    Path file = config(
        "target.url=http://vllm:8000/v1/chat/completions",
        "vllm.baseUrl=http://vllm:8000",
        "vm.url=http://victoria-metrics:8428",
        "mode=report",
        "dataset.path=/нет/такого.csv",
        "request.responseTimeoutSec=600",
        "run.id=confirm-180",
        "run.resultsDir=" + temp);

    Config.load(file.toString(), Map.of());

    assertEquals(LoadMode.REPORT, Config.mode());
  }

  @Test
  @DisplayName("отсутствующий файл конфигурации — ошибка")
  void missingConfigFile() {
    assertThrows(Config.ConfigException.class,
        () -> Config.load(temp.resolve("нет.properties").toString(), Map.of()));
  }

  @Test
  @DisplayName("порог SLA выше такта потока — предупреждение, а не отказ")
  void warnsWhenSlaExceedsPacing() throws IOException {
    // Такт при 180 RPM — 375 с. Порог 400 с создаёт зону, где ответ укладывается в SLA,
    // а целевой RPM уже недобирается.
    Config.load(minimalConfig().toString(), Map.of("LT_SCORING_E2ESLASEC", "400"));

    assertTrue(Config.warnings().stream().anyMatch(w -> w.contains("выше такта потока")),
        Config.warnings().toString());
  }

  @Test
  @DisplayName("разгон короче такта поднимается, и об этом сообщается")
  void warnsWhenRampRaised() throws IOException {
    Config.load(minimalConfig().toString(), Map.of("LT_FLAT_RAMP", "300"));

    assertEquals(375, Config.stages().get(0).ramp().toSeconds());
    assertTrue(Config.warnings().stream().anyMatch(w -> w.contains("разгон поднят с 300 с до 375")),
        Config.warnings().toString());
  }

  @Test
  @DisplayName("согласованный профиль предупреждений не даёт")
  void noWarningsForConsistentProfile() throws IOException {
    // Разгон уже кратен такту (375 с), порог SLA ниже такта.
    Config.load(minimalConfig().toString(), Map.of("LT_FLAT_RAMP", "375"));

    assertEquals(375, Config.stages().get(0).ramp().toSeconds());
    assertTrue(Config.e2eSlaSec() < Threads.minPacingSec(Config.stages()));
    assertTrue(Config.warnings().isEmpty(), Config.warnings().toString());
  }

  @Test
  @DisplayName("правило имени переменной окружения")
  void envNameRule() {
    assertEquals("LT_FLAT_RPM", Config.envName("flat.rpm"));
    assertEquals("LT_MODE", Config.envName("mode"));
    assertEquals("LT_RUN_ID", Config.envName("run.id"));
    assertEquals("LT_SCORING_E2ESLASEC", Config.envName("scoring.e2eSlaSec"));
  }
}

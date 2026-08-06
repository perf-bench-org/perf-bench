package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Проверяет, во что именно DSL разворачивает профиль.
 *
 * <p>Тест смотрит на сохранённый JMX, а не на объекты DSL: значение имеет ровно то, что попадёт в
 * движок. Здесь же ловится подмена штатной группы потоков плагинной — этого по сигнатурам DSL
 * не видно.
 */
class TestPlanTest {

  @TempDir
  Path temp;

  @AfterEach
  void tearDown() {
    Config.reset();
    Run.reset();
    Dataset.reset();
    RequestBody.setResolvedModel(null);
  }

  private String jmxFor(String mode, String... extra) throws IOException {
    Path dataset = temp.resolve("prompts.csv");
    Files.writeString(dataset, "prompt\nтекст\n", StandardCharsets.UTF_8);

    Path config = temp.resolve("test.properties");
    StringBuilder props = new StringBuilder(String.join("\n",
        "target.url=http://vllm:8000/v1/chat/completions",
        "vllm.baseUrl=http://vllm:8000",
        "vm.url=http://victoria-metrics:8428",
        "mode=" + mode,
        "flat.rpm=180",
        "flat.ramp=300",
        "flat.hold=1800",
        "step.profile=100 300 300; 120 300 600",
        "dataset.path=" + dataset,
        "request.responseTimeoutSec=600",
        "run.resultsDir=" + temp.resolve("results")));
    for (String line : extra) {
      props.append("\n").append(line);
    }
    Files.writeString(config, props + "\n", StandardCharsets.UTF_8);

    Config.load(config.toString(), Map.of());
    Dataset.load(Config.datasetPath(), Config.datasetDelimiter(), Config.seed());
    RequestBody.setResolvedModel("fake/model");
    Run.openUnchecked("plan-test", Files.createDirectories(temp.resolve("results/plan-test")));

    Path jmx = temp.resolve("plan.jmx");
    TestPlan.build().saveAsJmx(jmx.toString());
    return Files.readString(jmx);
  }

  /**
   * Значения свойства по всему документу, в порядке появления.
   *
   * <p>JMX знает две формы записи: компактную {@code <intProp name="X">N</intProp>} и вложенную
   * {@code <doubleProp><name>X</name><value>N</value>…}. Вторую использует, в частности,
   * {@code throughput} у Constant Throughput Timer.
   */
  private List<String> props(String jmx, String name) {
    Map<Integer, String> byPosition = new java.util.TreeMap<>();
    for (Pattern pattern : List.of(
        Pattern.compile("<\\w+Prop name=\"" + Pattern.quote(name) + "\">([^<]*)</"),
        Pattern.compile("<\\w+Prop>\\s*<name>" + Pattern.quote(name)
            + "</name>\\s*<value>([^<]*)</value>"))) {
      Matcher matcher = pattern.matcher(jmx);
      while (matcher.find()) {
        byPosition.put(matcher.start(), matcher.group(1));
      }
    }
    return List.copyOf(byPosition.values());
  }

  @Test
  @DisplayName("flat разворачивается в штатную ThreadGroup, а не в плагинную")
  void flatUsesStandardThreadGroup() throws IOException {
    String jmx = jmxFor("flat");

    assertTrue(jmx.contains("<ThreadGroup "), jmx);
    assertTrue(jmx.contains("ThreadGroupGui"), jmx);
    assertFalse(jmx.contains("ConcurrencyThreadGroup"), "плагинная группа потоков вернулась");
    assertFalse(jmx.contains("UltimateThreadGroup"), "плагинная группа потоков вернулась");
    assertFalse(jmx.contains("VariableThroughputTimer"), "плагинный таймер вернулся");
  }

  @Test
  @DisplayName("flat: число потоков, разгон и длительность считаются по ITERATION_SEC и RPM")
  void flatThreadGroupNumbers() throws IOException {
    String jmx = jmxFor("flat");

    // 180 / 60 * 300 * 1.25 = 1125
    assertEquals(List.of("1125"), props(jmx, "ThreadGroup.num_threads"));
    // Заданные 300 с подняты до такта потока 375 с.
    assertEquals(List.of("375"), props(jmx, "ThreadGroup.ramp_time"));
    // Длительность включает разгон: 375 + 1800.
    assertEquals(List.of("2175"), props(jmx, "ThreadGroup.duration"));
    assertEquals(List.of("-1"), props(jmx, "LoopController.loops"));
    assertEquals(List.of("true"), props(jmx, "ThreadGroup.scheduler"));
  }

  @Test
  @DisplayName("разгон равен такту потока: волны запросов смыкаются встык")
  void rampEqualsPacing() throws IOException {
    String jmx = jmxFor("flat");

    long ramp = Long.parseLong(props(jmx, "ThreadGroup.ramp_time").get(0));
    int threads = Integer.parseInt(props(jmx, "ThreadGroup.num_threads").get(0));
    double perThreadRpm = Double.parseDouble(props(jmx, "throughput").get(0));

    // Такт = 60 / (RPM на поток). Разгон обязан быть его кратным, иначе после разгона
    // образуется провал без запросов внутри окна удержания.
    double pacing = 60.0 / perThreadRpm;
    assertEquals(0, ramp % Math.round(pacing), "разгон " + ramp + " не кратен такту " + pacing);
    // И суммарно потоки дают ровно целевой RPM.
    assertEquals(180.0, perThreadRpm * threads, 1e-9);
  }

  @Test
  @DisplayName("flat: таймер получает такт на поток, дающий в сумме целевой RPM")
  void flatThroughputTimer() throws IOException {
    String jmx = jmxFor("flat");

    assertTrue(jmx.contains("<ConstantThroughputTimer "), jmx);
    // 180 RPM / 1125 потоков = 0.16 запроса в минуту на поток, то есть такт 375 с.
    assertEquals(List.of("0.16"), props(jmx, "throughput"));
  }

  @Test
  @DisplayName("режим таймера — this thread only: такт не зависит от числа активных потоков")
  void timerUsesPerThreadMode() throws IOException {
    String jmx = jmxFor("flat");

    // Режимы all active threads считают паузу от живого счётчика потоков, который растёт во
    // время разгона; разделяемые режимы при длинных запросах вовсе перестают держать темп.
    assertEquals(List.of(String.valueOf(
            org.apache.jmeter.timers.ConstantThroughputTimer.Mode.ThisThreadOnly.ordinal())),
        props(jmx, "calcMode"));
  }

  @Test
  @DisplayName("step: по группе на ступень, каждая со своим числом потоков")
  void stepBuildsGroupPerStage() throws IOException {
    String jmx = jmxFor("step");

    assertEquals(List.of("625", "750"), props(jmx, "ThreadGroup.num_threads"));
    // 100/625 и 120/750 — обе дают такт 375 с, поэтому значение на поток совпадает.
    assertEquals(List.of("0.16", "0.16"), props(jmx, "throughput"));
  }

  @Test
  @DisplayName("step: ступени расставлены встык задержкой старта, без пауз")
  void stepStagesAreBackToBack() throws IOException {
    String jmx = jmxFor("step");

    // Разгоны обеих ступеней подняты с 300 до 375 с.
    assertEquals(List.of("375", "375"), props(jmx, "ThreadGroup.ramp_time"));
    assertEquals(List.of("675", "975"), props(jmx, "ThreadGroup.duration"));
    // Первая стартует сразу, вторая — ровно когда кончается первая: 375 + 300 = 675 с.
    assertEquals(List.of("675"), props(jmx, "ThreadGroup.delay"));
  }

  @Test
  @DisplayName("проверка ответа и запись результатов на месте")
  void keepsAssertionAndJtlWriter() throws IOException {
    String jmx = jmxFor("flat");

    assertTrue(jmx.contains("ResponseAssertion"), jmx);
    assertTrue(jmx.contains("choices"), jmx);
    assertTrue(jmx.contains("ResultCollector"), jmx);
    assertTrue(jmx.contains(Run.RESULTS_JTL), jmx);
  }
}

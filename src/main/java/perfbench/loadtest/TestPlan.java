package perfbench.loadtest;

import static us.abstracta.jmeter.javadsl.JmeterDsl.httpDefaults;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.responseAssertion;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;
import static us.abstracta.jmeter.javadsl.JmeterDsl.throughputTimer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.entity.ContentType;
import us.abstracta.jmeter.javadsl.core.DslTestPlan;
import us.abstracta.jmeter.javadsl.core.DslTestPlan.TestPlanChild;
import us.abstracta.jmeter.javadsl.core.assertions.DslResponseAssertion.TargetField;
import us.abstracta.jmeter.javadsl.core.threadgroups.DslDefaultThreadGroup;
import us.abstracta.jmeter.javadsl.core.timers.DslThroughputTimer;
import us.abstracta.jmeter.javadsl.http.DslHttpSampler;

/**
 * Построение тест-плана: штатная Thread Group плюс Constant Throughput Timer.
 *
 * <p>Модель нагрузки открытая: задаётся частота поступления запросов. Темп держит таймер, число
 * потоков задаёт только потолок этого темпа — см. {@link Threads}.
 *
 * <p>Пересчёта RPM в секундную частоту в проекте больше нет нигде: Constant Throughput Timer
 * принимает величину в запросах в минуту.
 *
 * <h2>Ступени</h2>
 *
 * <p>Каждая ступень — отдельная группа потоков со своим числом потоков, своим разгоном и своим
 * таймером на своём RPM. Ступени расставлены по времени штатным планировщиком JMeter: группа
 * ступени i стартует с задержкой, равной сумме длительностей всех предыдущих ступеней, и живёт
 * ровно {@code ramp + hold}. Пауз между ступенями нет.
 *
 * <p>Разгон кратен такту потока (см. {@link Threads}), поэтому частота поступления держится на
 * целевой с первой секунды ступени, а на границе ступеней просто переключается с одного значения
 * на другое. Провала между ступенями нет; нарастает за время разгона не частота, а загрузка
 * сервера — она выходит на установившуюся за время одного ответа.
 *
 * <p>Хвост ступени обрабатывает сам JMeter: при включённом планировщике поток, у которого
 * очередной такт приходится уже за концом группы, не выпускает лишний запрос, а завершается
 * ({@code JMeterThread.delay}, обработка {@code adjustDelay}). Поэтому запросы одной ступени не
 * протекают в окно следующей и на границе нет всплеска из разом проснувшихся потоков.
 */
public final class TestPlan {

  /** Метка сэмплера. Под ней запросы попадают в {@code results.jtl}. */
  public static final String SAMPLER_NAME = "chat-completions";

  /**
   * Время жизни соединения в пуле.
   *
   * <p>Дефолтная минута короче одной итерации, из-за чего соединение закрывается сразу после
   * каждого ответа и keep-alive не работает вовсе.
   */
  private static final Duration CONNECTION_TTL = Duration.ofMinutes(30);

  private TestPlan() {
  }

  public static DslTestPlan build() {
    List<Stage> stages = Config.stages();
    List<TestPlanChild> children = new ArrayList<>();

    children.add(httpDefaults()
        .connectionTimeout(Config.connectTimeout())
        .responseTimeout(Config.responseTimeout())
        .connectionsTtl(CONNECTION_TTL)
        .resetConnectionsBetweenIterations(false));

    Duration startDelay = Duration.ZERO;
    for (int i = 0; i < stages.size(); i++) {
      Stage stage = stages.get(i);
      children.add(threadGroupFor(stage, i + 1, startDelay));
      startDelay = startDelay.plus(stage.total());
    }

    // Тела ответов и заголовки jtlWriter не пишет по умолчанию — при 200 RPM и часовом прогоне
    // они дали бы десятки гигабайт, не добавив ничего к отчёту.
    children.add(jtlWriter(Run.dir().toString(), Run.RESULTS_JTL));

    return testPlan(children.toArray(new TestPlanChild[0]));
  }

  /**
   * Группа потоков одной ступени.
   *
   * <p>Сочетание {@code rampTo(0, задержка) + rampTo(N, разгон) + holdFor(удержание)} DSL
   * раскладывает в поля штатного {@code org.apache.jmeter.threads.ThreadGroup}: задержка старта,
   * ramp-up, длительность (разгон плюс удержание) при бесконечном числе итераций. Никаких
   * плагинных групп потоков здесь не появляется — это важно, менять формулировку нельзя.
   */
  private static DslDefaultThreadGroup threadGroupFor(Stage stage, int number, Duration startDelay) {
    int threads = Threads.required(stage.targetRpm());
    DslDefaultThreadGroup group = threadGroup("stage-" + number + "-" + stage.targetRpm() + "rpm");
    if (!startDelay.isZero()) {
      group.rampTo(0, startDelay);
    }
    return group
        .rampTo(threads, stage.ramp())
        .holdFor(stage.hold())
        .children(throughputTimerFor(stage), sampler());
  }

  /**
   * Constant Throughput Timer ступени.
   *
   * <p>Величина задаётся в запросах в минуту <b>на поток</b>: {@code RPM / число потоков}. При
   * {@code N} потоках это даёт ровно целевой RPM суммарно, а такт каждого потока равен
   * {@code N × 60/RPM} секунд — {@link Threads#pacingSec(int)}.
   *
   * <p>Режим «this thread only» выбран не по вкусу, а по двум причинам; обе меняют цифры в отчёте.
   *
   * <p><b>Разделяемые режимы («shared») здесь молча перестают работать.</b> Пауза считается от
   * момента последнего вызова <i>этого же потока</i>, а за время запроса в
   * {@link Threads#ITERATION_SEC} секунд эта отметка протухает: расчётный момент оказывается в
   * прошлом, таймер возвращает ноль. Темп тогда определяется не целевым RPM, а числом потоков, и
   * прогон идёт на четверть быстрее заказанного.
   *
   * <p><b>Режимы «all active threads…» считают паузу от числа <i>активных</i> потоков</b>
   * ({@code AbstractThreadGroup.getNumberOfThreads()} возвращает счётчик живых потоков, не
   * настроенный). Пока идёт разгон, это число растёт, такт вместе с ним плывёт, и потоки,
   * стартовавшие раньше, получают более короткие паузы. Переходный процесс от этого выходит за
   * конец разгона и портит начало окна удержания. Режим «на поток» даёт постоянный такт с первой
   * секунды — при разгоне, кратном такту, темп получается равномерным с самого старта.
   */
  private static DslThroughputTimer throughputTimerFor(Stage stage) {
    double perThreadRpm = (double) stage.targetRpm() / Threads.required(stage.targetRpm());
    return throughputTimer(perThreadRpm).perThread();
  }

  /** Новый экземпляр на каждую ступень: элементы DSL хранят состояние построенного узла. */
  private static DslHttpSampler sampler() {
    DslHttpSampler sampler = httpSampler(SAMPLER_NAME, Config.targetUrl())
        .post(vars -> RequestBody.build(Dataset.next()), ContentType.APPLICATION_JSON)
        .header("Accept", "application/json")
        .useKeepAlive(true)
        .followRedirects(false);
    if (!Config.targetApiKey().isEmpty()) {
      sampler.header("Authorization", "Bearer " + Config.targetApiKey());
    }
    // Без проверки тела ошибочный ответ с кодом 200 попадёт в статистику как успешный.
    // Код ответа проверять отдельно не нужно: JMeter сам помечает не-2xx как ошибку.
    sampler.children(
        responseAssertion()
            .fieldToTest(TargetField.RESPONSE_BODY)
            .containsSubstrings("\"choices\""));
    return sampler;
  }
}

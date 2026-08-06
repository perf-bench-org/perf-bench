package perfbench.loadtest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Расчёт числа потоков генератора по закону Литтла.
 *
 * <p>В конфигурации число потоков не задаётся: оно однозначно следует из целевого RPM ступени и
 * ожидаемой длительности итерации.
 *
 * <h2>Как это работает вместе с Constant Throughput Timer</h2>
 *
 * <p>Таймер выдерживает такт одного потока — {@code N × 60/RPM} секунд. При N потоках это даёт
 * {@code N / (N × 60/RPM) = RPM/60} запросов в секунду, то есть ровно целевой RPM.
 *
 * <p>Отсюда прямой смысл {@link #SAFETY}: такт равен {@code ITERATION_SEC × SAFETY} секунд, и это
 * <b>максимальное время ответа, при котором целевой RPM ещё достижим</b>. Медленнее — и генератор
 * физически не успевает, фактический RPM проседает.
 *
 * <h2>Почему разгон кратен такту</h2>
 *
 * <p>Constant Throughput Timer выпускает <b>первый запрос каждого потока без задержки</b> — это
 * его штатное поведение, а не настройка. Дальше поток повторяется строго через такт. Значит
 * моменты запросов образуют «волны»: потоки, стартовавшие за время разгона R, повторяются через
 * такт P, ещё через P и так далее.
 *
 * <p>Если {@code R < P}, волны не смыкаются: во время разгона темп равен {@code N/R} — выше
 * целевого, — а между концом разгона и первой волной повторов образуется <b>провал длиной
 * {@code P - R}, попадающий внутрь окна измерения</b>. При штатном профиле (разгон 300 с, такт
 * 375 с) это 75 секунд без единого запроса в начале удержания.
 *
 * <p>Если R не кратно P, волны накладываются неравномерно и темп колеблется между
 * {@code floor(R/P)·N/R} и {@code ceil(R/P)·N/R}.
 *
 * <p>Ровно при кратности волны укладываются встык и темп постоянен с первой секунды. Поэтому
 * заданный в профиле разгон трактуется как <i>минимальный</i> и поднимается до ближайшего кратного
 * такту — см. {@link #effectiveStages(List, List)}.
 */
public final class Threads {

  /**
   * Ожидаемая длительность одной итерации (запроса), секунды.
   *
   * <p><b>Не путать с {@code scoring.e2eSlaSec}.</b> Эта константа описывает <i>ожидание</i> и
   * нужна только для расчёта размера пула. {@code scoring.e2eSlaSec} описывает <i>допустимое</i>
   * и задаётся с запасом над этим значением, но ниже {@link #pacingSec(int)} — иначе появится
   * зона, где ответ формально укладывается в SLA, а RPM уже недобирается.
   */
  public static final int ITERATION_SEC = 300;

  /**
   * Запас над расчётным минимумом.
   *
   * <p>Умножается на {@link #ITERATION_SEC} и даёт такт одного потока. Ответ, приходящий за
   * меньшее время, не мешает выдержать целевой RPM; ответ медленнее — мешает.
   */
  public static final double SAFETY = 1.25;

  private Threads() {
  }

  /** Число потоков для ступени с заданным целевым RPM. Не меньше 1. */
  public static int required(int targetRpm) {
    if (targetRpm <= 0) {
      throw new IllegalArgumentException("целевой RPM должен быть положительным: " + targetRpm);
    }
    double threads = Math.ceil(targetRpm / 60.0 * ITERATION_SEC * SAFETY);
    return (int) Math.max(1, threads);
  }

  /**
   * Суммарное число потоков, которое движок создаёт на старте прогона.
   *
   * <p>У каждой ступени своя группа потоков, и штатный Thread Group создаёт все свои потоки сразу,
   * даже если группа ещё ждёт своей очереди по расписанию. В {@code flat} это просто число потоков
   * единственной ступени, в {@code step} — сумма по всем.
   */
  public static int total(List<Stage> stages) {
    if (stages == null || stages.isEmpty()) {
      throw new IllegalArgumentException("профиль не содержит ступеней");
    }
    return stages.stream().mapToInt(stage -> required(stage.targetRpm())).sum();
  }

  /**
   * Такт одного потока для заданного целевого RPM, секунды.
   *
   * <p>Это же — максимальное время ответа, при котором целевой RPM ещё достижим. Считается по
   * фактическому (округлённому вверх) числу потоков, а не по формуле, поэтому чуть больше
   * {@code ITERATION_SEC × SAFETY}.
   */
  public static double pacingSec(int targetRpm) {
    return required(targetRpm) * 60.0 / targetRpm;
  }

  /** Наименьший такт по всем ступеням профиля — самая жёсткая граница прогона. */
  public static double minPacingSec(List<Stage> stages) {
    return stages.stream().mapToDouble(stage -> pacingSec(stage.targetRpm())).min().orElseThrow();
  }

  /**
   * Разгон, при котором волны запросов укладываются встык: ближайшее кратное такту, не меньшее
   * заданного.
   */
  public static long rampSec(int targetRpm, long configuredRampSec) {
    double pacing = pacingSec(targetRpm);
    long multiples = Math.max(1, (long) Math.ceil(configuredRampSec / pacing));
    return (long) Math.ceil(multiples * pacing);
  }

  /**
   * Профиль с разгоном, поднятым до кратного такту.
   *
   * <p>Применяется один раз в {@link Config}, поэтому тест-план, границы окон удержания и смещения
   * ступеней считаются по одним и тем же величинам. Каждая правка попадает в {@code notes} и
   * оттуда в лог: молча менять заданную длительность прогона нельзя.
   */
  public static List<Stage> effectiveStages(List<Stage> stages, List<String> notes) {
    List<Stage> effective = new ArrayList<>(stages.size());
    for (Stage stage : stages) {
      long configured = stage.ramp().toSeconds();
      long adjusted = rampSec(stage.targetRpm(), configured);
      if (adjusted == configured) {
        effective.add(stage);
        continue;
      }
      notes.add("ступень " + stage.targetRpm() + " RPM: разгон поднят с " + configured + " с до "
          + adjusted + " с — кратного такту потока ("
          + String.format(Locale.ROOT, "%.1f", pacingSec(stage.targetRpm())) + " с). "
          + "При меньшем разгоне первые запросы потоков идут без задержки, и после разгона "
          + "образуется провал без запросов внутри окна удержания");
      effective.add(new Stage(stage.targetRpm(), Duration.ofSeconds(adjusted), stage.hold()));
    }
    return List.copyOf(effective);
  }
}

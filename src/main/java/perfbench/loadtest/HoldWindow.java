package perfbench.loadtest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Окно удержания нагрузки одной ступени — единственный интервал, за который считается всё в
 * отчёте.
 *
 * <p>Разгон не участвует ни в одном расчёте: во время ramp система в переходном состоянии, и
 * включение этих данных занижает throughput и завышает задержки.
 *
 * @param stageNumber номер ступени, с 1
 * @param targetRpm   целевой RPM ступени
 * @param threads     число потоков группы этой ступени
 * @param start       начало удержания, включительно
 * @param end         конец удержания, исключительно
 */
public record HoldWindow(int stageNumber, int targetRpm, int threads, Instant start, Instant end) {

  public HoldWindow {
    if (start == null || end == null) {
      throw new IllegalArgumentException("границы окна не заданы");
    }
    if (!end.isAfter(start)) {
      throw new IllegalArgumentException("конец окна не позже начала: " + start + " .. " + end);
    }
  }

  public Duration duration() {
    return Duration.between(start, end);
  }

  public boolean contains(Instant at) {
    return !at.isBefore(start) && at.isBefore(end);
  }

  /**
   * Раскладывает профиль на окна удержания, отсчитывая от момента фактического начала нагрузки.
   *
   * <p>Ступени идут встык: окно ступени i начинается после всех предыдущих ступеней целиком плюс
   * собственный разгон.
   */
  public static List<HoldWindow> of(Instant loadStart, List<Stage> stages) {
    List<HoldWindow> windows = new ArrayList<>(stages.size());
    Instant cursor = loadStart;
    for (int i = 0; i < stages.size(); i++) {
      Stage stage = stages.get(i);
      Instant holdStart = cursor.plus(stage.ramp());
      Instant holdEnd = holdStart.plus(stage.hold());
      windows.add(new HoldWindow(i + 1, stage.targetRpm(), Threads.required(stage.targetRpm()),
          holdStart, holdEnd));
      cursor = holdEnd;
    }
    return List.copyOf(windows);
  }

  @Override
  public String toString() {
    return "ступень " + stageNumber + " (" + targetRpm + " RPM): " + start + " .. " + end
        + " (" + duration().toSeconds() + "s)";
  }
}

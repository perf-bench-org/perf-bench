package perfbench.loadtest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Разбор профиля ступенчатой нагрузки и построение одноступенчатого профиля.
 */
public final class ProfileParser {

  private ProfileParser() {
  }

  /**
   * Разбирает профиль вида {@code "100 300 300; 120 300 300"} — RPM, разгон (с), удержание (с).
   *
   * <p>Терпим к произвольным пробелам, переводам строк и завершающей {@code ;}. Каждая группа
   * обязана состоять ровно из трёх положительных целых.
   */
  public static List<Stage> parseStep(String profile) {
    if (profile == null || profile.isBlank()) {
      throw new IllegalArgumentException("профиль пуст");
    }
    List<Stage> stages = new ArrayList<>();
    String[] groups = profile.split(";");
    for (int i = 0; i < groups.length; i++) {
      String group = groups[i].trim();
      if (group.isEmpty()) {
        continue;
      }
      String[] parts = group.split("\\s+");
      if (parts.length != 3) {
        throw new IllegalArgumentException(
            "ступень " + (i + 1) + " ('" + group + "'): ожидалось три числа "
                + "'<rpm> <ramp_sec> <hold_sec>', получено " + parts.length);
      }
      int rpm = positiveInt(parts[0], i + 1, "rpm");
      int ramp = positiveInt(parts[1], i + 1, "ramp");
      int hold = positiveInt(parts[2], i + 1, "hold");
      stages.add(new Stage(rpm, Duration.ofSeconds(ramp), Duration.ofSeconds(hold)));
    }
    if (stages.isEmpty()) {
      throw new IllegalArgumentException("профиль не содержит ни одной ступени");
    }
    return List.copyOf(stages);
  }

  /** Одноступенчатый профиль режима {@code flat}. */
  public static List<Stage> flat(int rpm, int rampSec, int holdSec) {
    return List.of(new Stage(rpm, Duration.ofSeconds(rampSec), Duration.ofSeconds(holdSec)));
  }

  private static int positiveInt(String raw, int stageNo, String field) {
    int value;
    try {
      value = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "ступень " + stageNo + ", " + field + ": '" + raw + "' не целое число");
    }
    if (value <= 0) {
      throw new IllegalArgumentException(
          "ступень " + stageNo + ", " + field + ": ожидалось положительное число, получено " + value);
    }
    return value;
  }
}

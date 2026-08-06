package perfbench.loadtest;

import java.time.Duration;

/**
 * Ступень профиля нагрузки.
 *
 * <p>Единица измерения нагрузки во всём проекте — RPM. Пересчёта в секундную частоту нет ни
 * здесь, ни где-либо ещё: Constant Throughput Timer принимает величину в запросах в минуту.
 *
 * <p>{@code ramp} трактуется как <b>минимальный</b> разгон: {@link Config} поднимает его до
 * кратного такту потока, иначе таймер оставляет провал без запросов внутри окна удержания
 * (см. {@link Threads}).
 *
 * @param targetRpm целевая частота поступления запросов, запросов в минуту
 * @param ramp      длительность разгона до {@code targetRpm}
 * @param hold      длительность удержания {@code targetRpm} после разгона
 */
public record Stage(int targetRpm, Duration ramp, Duration hold) {

  public Stage {
    if (targetRpm <= 0) {
      throw new IllegalArgumentException("targetRpm должен быть положительным: " + targetRpm);
    }
    if (ramp == null || ramp.isZero() || ramp.isNegative()) {
      throw new IllegalArgumentException("ramp должен быть положительным: " + ramp);
    }
    if (hold == null || hold.isZero() || hold.isNegative()) {
      throw new IllegalArgumentException("hold должен быть положительным: " + hold);
    }
  }

  public Duration total() {
    return ramp.plus(hold);
  }

  @Override
  public String toString() {
    return targetRpm + " RPM, ramp " + ramp.toSeconds() + "s, hold " + hold.toSeconds() + "s";
  }
}

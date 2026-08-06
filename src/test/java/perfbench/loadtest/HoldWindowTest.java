package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HoldWindowTest {

  private static final Instant T0 = Instant.parse("2026-08-05T12:00:00Z");

  @Test
  @DisplayName("окно flat: старт плюс разгон, длиной в удержание")
  void singleStage() {
    List<HoldWindow> windows = HoldWindow.of(T0,
        List.of(new Stage(180, Duration.ofSeconds(300), Duration.ofSeconds(1800))));

    assertEquals(1, windows.size());
    assertEquals(T0.plusSeconds(300), windows.get(0).start());
    assertEquals(T0.plusSeconds(2100), windows.get(0).end());
    assertEquals(180, windows.get(0).targetRpm());
  }

  @Test
  @DisplayName("ступени идут встык: каждая следующая начинается сразу за предыдущей")
  void stagesAreBackToBack() {
    List<HoldWindow> windows = HoldWindow.of(T0, List.of(
        new Stage(100, Duration.ofSeconds(60), Duration.ofSeconds(120)),
        new Stage(120, Duration.ofSeconds(60), Duration.ofSeconds(120))));

    assertEquals(T0.plusSeconds(60), windows.get(0).start());
    assertEquals(T0.plusSeconds(180), windows.get(0).end());
    // Разгон второй ступени начинается там же, где кончилось удержание первой.
    assertEquals(T0.plusSeconds(240), windows.get(1).start());
    assertEquals(T0.plusSeconds(360), windows.get(1).end());
  }

  @Test
  @DisplayName("окна не пересекаются")
  void windowsDoNotOverlap() {
    List<HoldWindow> windows = HoldWindow.of(T0, List.of(
        new Stage(100, Duration.ofSeconds(60), Duration.ofSeconds(120)),
        new Stage(120, Duration.ofSeconds(60), Duration.ofSeconds(120))));

    assertTrue(windows.get(0).end().isBefore(windows.get(1).start())
        || windows.get(0).end().equals(windows.get(1).start()));
    assertFalse(windows.get(1).contains(windows.get(0).end().minusMillis(1)));
  }

  @Test
  @DisplayName("границы: левая включена, правая исключена")
  void boundariesAreHalfOpen() {
    HoldWindow window = new HoldWindow(1, 100, 625, T0, T0.plusSeconds(10));

    assertTrue(window.contains(T0));
    assertTrue(window.contains(T0.plusSeconds(9)));
    assertFalse(window.contains(T0.plusSeconds(10)));
    assertFalse(window.contains(T0.minusMillis(1)));
  }

  @Test
  @DisplayName("вырожденное окно отвергается")
  void rejectsDegenerateWindow() {
    assertThrows(IllegalArgumentException.class, () -> new HoldWindow(1, 100, 625, T0, T0));
    assertThrows(IllegalArgumentException.class,
        () -> new HoldWindow(1, 100, 625, T0, T0.minusSeconds(1)));
  }
}

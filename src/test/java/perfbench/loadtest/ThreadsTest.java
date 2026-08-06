package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThreadsTest {

  @Test
  @DisplayName("формула закона Литтла с запасом")
  void appliesLittlesLaw() {
    // 200 / 60 * 300 * 1.25 = 1250
    assertEquals(1250, Threads.required(200));
    // 180 / 60 * 300 * 1.25 = 1125
    assertEquals(1125, Threads.required(180));
  }

  @Test
  @DisplayName("округляет вверх и не опускается ниже одного потока")
  void roundsUp() {
    // 1 / 60 * 300 * 1.25 = 6.25 -> 7
    assertEquals(7, Threads.required(1));
    // 7 / 60 * 300 * 1.25 = 43.75 -> 44
    assertEquals(44, Threads.required(7));
  }

  @Test
  @DisplayName("такт потока — это ITERATION_SEC x SAFETY, то есть предельное время ответа")
  void pacingIsTheResponseTimeCeiling() {
    // Такт = N * 60 / RPM. Округление вверх делает его чуть больше 375 с.
    assertEquals(375.0, Threads.pacingSec(200), 1e-9);
    assertEquals(375.0, Threads.pacingSec(180), 1e-9);
    assertEquals(Threads.ITERATION_SEC * Threads.SAFETY, Threads.pacingSec(120), 1e-9);
  }

  @Test
  @DisplayName("такт никогда не меньше ITERATION_SEC x SAFETY: округление только добавляет запас")
  void pacingNeverBelowNominal() {
    double nominal = Threads.ITERATION_SEC * Threads.SAFETY;
    for (int rpm = 1; rpm <= 500; rpm++) {
      double pacing = Threads.pacingSec(rpm);
      org.junit.jupiter.api.Assertions.assertTrue(pacing >= nominal - 1e-9,
          "RPM " + rpm + ": такт " + pacing + " меньше номинального " + nominal);
    }
  }

  @Test
  @DisplayName("суммарное число потоков — сумма по ступеням: движок создаёт их все на старте")
  void totalIsSumOverStages() {
    List<Stage> stages = List.of(
        new Stage(100, Duration.ofSeconds(300), Duration.ofSeconds(300)),
        new Stage(200, Duration.ofSeconds(300), Duration.ofSeconds(300)));

    assertEquals(Threads.required(100) + Threads.required(200), Threads.total(stages));
  }

  @Test
  @DisplayName("самая жёсткая граница прогона — наименьший такт по ступеням")
  void minPacingOverStages() {
    List<Stage> stages = List.of(
        new Stage(100, Duration.ofSeconds(300), Duration.ofSeconds(300)),
        new Stage(7, Duration.ofSeconds(300), Duration.ofSeconds(300)));

    assertEquals(Math.min(Threads.pacingSec(100), Threads.pacingSec(7)),
        Threads.minPacingSec(stages), 1e-9);
  }

  @Test
  @DisplayName("пустой профиль и неположительный RPM отвергаются")
  void rejectsInvalidInput() {
    assertThrows(IllegalArgumentException.class, () -> Threads.total(List.of()));
    assertThrows(IllegalArgumentException.class, () -> Threads.required(0));
    assertThrows(IllegalArgumentException.class, () -> Threads.required(-1));
  }
}

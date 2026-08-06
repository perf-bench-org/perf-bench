package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileParserTest {

  @Test
  @DisplayName("разбирает профиль из нескольких ступеней")
  void parsesMultipleStages() {
    List<Stage> stages = ProfileParser.parseStep("100 300 300; 120 240 600");

    assertEquals(2, stages.size());
    assertEquals(new Stage(100, Duration.ofSeconds(300), Duration.ofSeconds(300)), stages.get(0));
    assertEquals(new Stage(120, Duration.ofSeconds(240), Duration.ofSeconds(600)), stages.get(1));
  }

  @Test
  @DisplayName("терпим к пробелам, переводам строк и завершающей точке с запятой")
  void toleratesWhitespace() {
    List<Stage> stages = ProfileParser.parseStep("  100   300  300 ;\n 120 300 300 ;  \n");

    assertEquals(2, stages.size());
    assertEquals(120, stages.get(1).targetRpm());
  }

  @Test
  @DisplayName("одна ступень без разделителя разбирается")
  void parsesSingleStage() {
    assertEquals(1, ProfileParser.parseStep("180 300 1800").size());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      "   ",
      ";;",
      "100 300",              // мало чисел
      "100 300 300 300",      // много чисел
      "100 300 abc",          // не число
      "0 300 300",            // нулевой RPM
      "100 0 300",            // нулевой разгон: rampTo(_, ZERO) DSL игнорирует молча
      "100 300 0",            // нулевое удержание: считать будет нечего
      "-100 300 300",
  })
  @DisplayName("мусор и нули отвергаются")
  void rejectsGarbage(String profile) {
    assertThrows(IllegalArgumentException.class, () -> ProfileParser.parseStep(profile));
  }

  @Test
  @DisplayName("сообщение об ошибке называет номер ступени")
  void errorNamesStage() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ProfileParser.parseStep("100 300 300; 120 300"));

    assertTrue(e.getMessage().contains("ступень 2"), e.getMessage());
  }

  @Test
  @DisplayName("flat даёт одну ступень")
  void flatBuildsSingleStage() {
    List<Stage> stages = ProfileParser.flat(180, 300, 1800);

    assertEquals(1, stages.size());
    assertEquals(180, stages.get(0).targetRpm());
    assertEquals(Duration.ofSeconds(2100), stages.get(0).total());
  }
}

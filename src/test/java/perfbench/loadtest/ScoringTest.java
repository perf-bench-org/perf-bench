package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScoringTest {

  private static final Instant T0 = Instant.parse("2026-08-05T12:00:00Z");
  private static final HoldWindow WINDOW =
      new HoldWindow(1, 100, 625, T0, T0.plusSeconds(600));
  private static final double SLA = 360;

  /** Клиентская часть без единого повода для штрафа. */
  private JtlStats.Result perfectClient() {
    return new JtlStats.Result(1000, 1000, 0, 0, 100.0, 250, 300, 200, 320, 500, Map.of());
  }

  /** Серверная часть без единого повода для штрафа. */
  private VllmMetrics.ServerMetrics perfectServer() {
    return new VllmMetrics.ServerMetrics(
        Optional.of(50.0), Optional.of(80.0), Optional.of(0.0),
        Optional.of(2.0), Optional.of(5.0),
        Optional.of(0.1), Optional.of(0.3),
        Optional.of(0.1), Optional.of(0.3),
        Optional.of(1000.0), Optional.of(2000.0),
        Optional.of(3000.0), Optional.of(4000.0),
        true);
  }

  private VllmMetrics.ServerMetrics serverWith(Optional<Double> queueMax,
      Optional<Double> kvMax, Optional<Double> ttftAvg, Optional<Double> tpotAvg) {
    VllmMetrics.ServerMetrics base = perfectServer();
    return new VllmMetrics.ServerMetrics(
        base.kvCacheAvgPct(), kvMax, queueMax,
        ttftAvg, base.ttftP95MaxSec(),
        tpotAvg, base.tpotP95MaxSec(),
        base.itlP95AvgSec(), base.itlP95MaxSec(),
        base.promptTpsAvg(), base.promptTpsMax(),
        base.generationTpsAvg(), base.generationTpsMax(),
        base.itlAvailable());
  }

  @Test
  @DisplayName("идеальный прогон — 100 без штрафов")
  void perfectRunScores100() {
    Scoring.ScoreResult result =
        Scoring.evaluate(WINDOW, perfectClient(), perfectServer(), SLA);

    assertEquals(100, result.score());
    assertTrue(result.penalties().isEmpty(), result.penalties().toString());
    assertTrue(result.unavailable().isEmpty(), result.unavailable().toString());
  }

  @Test
  @DisplayName("недобор RPM штрафуется по ступеням, худшая сработавшая")
  void rpmShortfallTiers() {
    assertEquals(100, scoreWithRpm(100.0));   // ровно цель
    assertEquals(100, scoreWithRpm(99.5));    // недобор 0.5 % — ниже первого порога
    assertEquals(95, scoreWithRpm(98.0));     // недобор 2 %
    assertEquals(85, scoreWithRpm(93.0));     // недобор 7 %
    assertEquals(70, scoreWithRpm(85.0));     // недобор 15 %
    assertEquals(50, scoreWithRpm(70.0));     // недобор 30 %
  }

  @Test
  @DisplayName("недобор RPM штрафуется сильнее любой другой отдельной метрики")
  void rpmIsHeaviestPenalty() {
    int rpmPenalty = 100 - scoreWithRpm(70.0);
    int queuePenalty = 100 - Scoring.evaluate(WINDOW, perfectClient(),
        serverWith(Optional.of(500.0), Optional.of(80.0), Optional.of(2.0), Optional.of(0.1)),
        SLA).score();

    assertTrue(rpmPenalty > queuePenalty, rpmPenalty + " vs " + queuePenalty);
  }

  private int scoreWithRpm(double actualRpm) {
    JtlStats.Result client =
        new JtlStats.Result(1000, 1000, 0, 0, actualRpm, 250, 300, 200, 320, 500, Map.of());
    return Scoring.evaluate(WINDOW, client, perfectServer(), SLA).score();
  }

  @Test
  @DisplayName("ошибки штрафуются на граничных значениях")
  void errorRateTiers() {
    assertEquals(100, scoreWithErrorRate(0.0));
    assertEquals(95, scoreWithErrorRate(0.001));
    assertEquals(85, scoreWithErrorRate(0.02));
    assertEquals(70, scoreWithErrorRate(0.10));
  }

  private int scoreWithErrorRate(double rate) {
    int total = 1000;
    int errors = (int) Math.round(total * rate);
    JtlStats.Result client = new JtlStats.Result(total, total - errors, errors, rate, 100.0,
        250, 300, 200, 320, 500, Map.of());
    return Scoring.evaluate(WINDOW, client, perfectServer(), SLA).score();
  }

  @Test
  @DisplayName("e2e p95 сравнивается с порогом из конфигурации")
  void e2eComparedToSla() {
    assertEquals(100, scoreWithP95(SLA));          // ровно порог — не превышение
    assertEquals(85, scoreWithP95(SLA + 1));       // чуть выше порога
    assertEquals(75, scoreWithP95(SLA * 1.6));     // больше полутора порогов
  }

  private int scoreWithP95(double p95) {
    JtlStats.Result client =
        new JtlStats.Result(1000, 1000, 0, 0, 100.0, 250, p95, 200, p95 + 10, 500, Map.of());
    return Scoring.evaluate(WINDOW, client, perfectServer(), SLA).score();
  }

  @Test
  @DisplayName("очередь и KV-кэш штрафуются на граничных значениях")
  void queueAndKvTiers() {
    assertEquals(100, score(0.0, 90.0));
    assertEquals(97, score(1.0, 90.0));
    assertEquals(92, score(11.0, 90.0));
    assertEquals(85, score(51.0, 90.0));
    assertEquals(95, score(0.0, 91.0));
    assertEquals(90, score(0.0, 96.0));
    assertEquals(85, score(0.0, 99.5));
  }

  private int score(double queueMax, double kvMax) {
    return Scoring.evaluate(WINDOW, perfectClient(),
        serverWith(Optional.of(queueMax), Optional.of(kvMax), Optional.of(2.0), Optional.of(0.1)),
        SLA).score();
  }

  @Test
  @DisplayName("отсутствующая метрика не штрафуется, но помечается недоступной")
  void missingMetricIsNotAPenalty() {
    Scoring.ScoreResult result = Scoring.evaluate(WINDOW, perfectClient(),
        serverWith(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), SLA);

    assertEquals(100, result.score());
    assertTrue(result.penalties().isEmpty(), result.penalties().toString());
    assertTrue(result.unavailable().contains("Queue Max"), result.unavailable().toString());
    assertTrue(result.unavailable().contains("KV Cache Max"), result.unavailable().toString());
    assertTrue(result.unavailable().contains("TTFT p95 (avg)"), result.unavailable().toString());
    assertTrue(result.unavailable().contains("TPOT p95 (avg)"), result.unavailable().toString());
  }

  @Test
  @DisplayName("отсутствие ITL помечается отдельной строкой с причиной")
  void missingItlIsExplained() {
    VllmMetrics.ServerMetrics base = perfectServer();
    VllmMetrics.ServerMetrics noItl = new VllmMetrics.ServerMetrics(
        base.kvCacheAvgPct(), base.kvCacheMaxPct(), base.queueMax(),
        base.ttftP95AvgSec(), base.ttftP95MaxSec(),
        base.tpotP95AvgSec(), base.tpotP95MaxSec(),
        Optional.empty(), Optional.empty(),
        base.promptTpsAvg(), base.promptTpsMax(),
        base.generationTpsAvg(), base.generationTpsMax(),
        false);

    Scoring.ScoreResult result = Scoring.evaluate(WINDOW, perfectClient(), noItl, SLA);

    assertEquals(100, result.score());
    assertTrue(result.unavailable().stream().anyMatch(u -> u.startsWith("ITL")),
        result.unavailable().toString());
  }

  @Test
  @DisplayName("оценка не уходит ниже нуля")
  void scoreNeverNegative() {
    JtlStats.Result terrible = new JtlStats.Result(1000, 100, 900, 0.9, 10.0,
        900, 1200, 500, 1500, 500, Map.of("500", 900));
    VllmMetrics.ServerMetrics terribleServer =
        serverWith(Optional.of(999.0), Optional.of(100.0), Optional.of(120.0), Optional.of(9.0));

    Scoring.ScoreResult result = Scoring.evaluate(WINDOW, terrible, terribleServer, SLA);

    assertEquals(0, result.score());
    assertFalse(result.penalties().isEmpty());
  }

  @Test
  @DisplayName("пустое окно — нулевая оценка с явной причиной")
  void emptyWindowScoresZero() {
    Scoring.ScoreResult result =
        Scoring.evaluate(WINDOW, JtlStats.Result.empty(), perfectServer(), SLA);

    assertEquals(0, result.score());
    assertEquals(1, result.penalties().size());
  }

  @Test
  @DisplayName("каждый штраф несёт фактическое значение, а не только название правила")
  void penaltiesCarryActualValues() {
    Scoring.ScoreResult result = Scoring.evaluate(WINDOW, perfectClient(),
        serverWith(Optional.of(132.0), Optional.of(80.0), Optional.of(2.0), Optional.of(0.1)),
        SLA);

    assertTrue(result.penalties().stream().anyMatch(p -> p.contains("132")),
        result.penalties().toString());
  }
}

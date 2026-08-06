package perfbench.loadtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Итоговая оценка ступени: одно число 0–100 по системе штрафов.
 *
 * <p>Начальное значение 100, за каждое ухудшение характеристик вычитается фиксированная величина.
 * Подход выбран потому, что он объясним — видно, за что снято, — легко правится и не требует
 * нормализации разнородных метрик.
 *
 * <p>Пороги почти наверняка потребуют настройки после первых прогонов. Правятся они здесь,
 * а пересчитать отчёт по уже собранным данным можно режимом {@code report}, не повторяя прогон.
 *
 * <p>Метрика, данных по которой нет, штрафа не даёт, но попадает в список недоступных: иначе
 * отсутствие метрики выглядит в отчёте как отличный результат.
 */
public final class Scoring {

  public static final int MAX_SCORE = 100;

  /**
   * Недобор фактического RPM относительно целевого.
   *
   * <p>Штрафуется сильнее всех прочих метрик: если частота не набралась, все остальные величины
   * измерены не на той нагрузке, которую собирались измерить, и сравнивать их не с чем.
   */
  private static final Tier[] RPM_SHORTFALL = {
      new Tier(0.25, 50),   // набрано меньше 75 % целевого
      new Tier(0.10, 30),
      new Tier(0.05, 15),
      new Tier(0.01, 5),
  };

  /** Доля ошибочных запросов, 0..1. */
  private static final Tier[] ERROR_RATE = {
      new Tier(0.05, 30),
      new Tier(0.01, 15),
      new Tier(0.0, 5),
  };

  /** Превышение e2e p95 над порогом SLA, в долях порога. */
  private static final Tier[] E2E_OVER_SLA = {
      new Tier(0.5, 25),    // p95 больше полутора порогов
      new Tier(0.0, 15),
  };

  /**
   * Максимум очереди, запросов.
   *
   * <p>Величина ограничена интервалом скрейпа: всплеск короче интервала в данных физически не
   * существует. Именно из-за веса этого штрафа интервал скрейпа на время бенчмарка снижен до 5 с.
   */
  private static final Tier[] QUEUE_MAX = {
      new Tier(50, 15),
      new Tier(10, 8),
      new Tier(0, 3),
  };

  /** Максимальная утилизация KV-кэша, проценты. */
  private static final Tier[] KV_CACHE_MAX_PCT = {
      new Tier(99, 15),
      new Tier(95, 10),
      new Tier(90, 5),
  };

  /** Среднее ряда p95 TTFT, секунды. Пороги модельнозависимы и почти наверняка потребуют правки. */
  private static final Tier[] TTFT_P95_AVG_SEC = {
      new Tier(60, 15),
      new Tier(30, 10),
      new Tier(10, 5),
  };

  /** Среднее ряда p95 TPOT, секунды. Пороги модельнозависимы и почти наверняка потребуют правки. */
  private static final Tier[] TPOT_P95_AVG_SEC = {
      new Tier(2.0, 15),
      new Tier(1.0, 10),
      new Tier(0.5, 5),
  };

  private Scoring() {
  }

  /**
   * Оценка ступени.
   *
   * @param window   окно удержания — из него берётся целевой RPM
   * @param client   клиентская часть отчёта
   * @param server   серверная часть отчёта
   * @param e2eSlaSec порог штрафа за время отклика, {@code scoring.e2eSlaSec}
   */
  public static ScoreResult evaluate(HoldWindow window, JtlStats.Result client,
      VllmMetrics.ServerMetrics server, double e2eSlaSec) {
    List<String> penalties = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    int score = MAX_SCORE;

    if (client.isEmpty()) {
      return new ScoreResult(0,
          List.of("-100 За окно удержания не пришло ни одного запроса"),
          List.of());
    }

    // --- фактический RPM ---
    double shortfall = 1.0 - client.actualRpm() / window.targetRpm();
    score -= apply(penalties, RPM_SHORTFALL, shortfall,
        () -> String.format(Locale.ROOT, "Фактический RPM %.1f против целевых %d (недобор %.1f %%)",
            client.actualRpm(), window.targetRpm(), shortfall * 100));

    // --- ошибки ---
    score -= apply(penalties, ERROR_RATE, client.errorRate(),
        () -> String.format(Locale.ROOT, "Ошибок %d из %d (%.2f %%)",
            client.errors(), client.total(), client.errorRate() * 100));

    // --- время отклика ---
    double overSla = client.e2eP95Sec() / e2eSlaSec - 1.0;
    score -= apply(penalties, E2E_OVER_SLA, overSla,
        () -> String.format(Locale.ROOT, "E2E p95 %.1f с при пороге %.1f с",
            client.e2eP95Sec(), e2eSlaSec));

    // --- очередь ---
    score -= applyOptional(penalties, unavailable, QUEUE_MAX, server.queueMax(), "Queue Max",
        v -> String.format(Locale.ROOT, "Очередь доходила до %.0f запросов", v));

    // --- KV cache ---
    score -= applyOptional(penalties, unavailable, KV_CACHE_MAX_PCT, server.kvCacheMaxPct(),
        "KV Cache Max", v -> String.format(Locale.ROOT, "Утилизация KV-кэша доходила до %.1f %%", v));

    // --- TTFT ---
    score -= applyOptional(penalties, unavailable, TTFT_P95_AVG_SEC, server.ttftP95AvgSec(),
        "TTFT p95 (avg)", v -> String.format(Locale.ROOT, "TTFT p95 (avg) %.2f с", v));

    // --- TPOT ---
    score -= applyOptional(penalties, unavailable, TPOT_P95_AVG_SEC, server.tpotP95AvgSec(),
        "TPOT p95 (avg)", v -> String.format(Locale.ROOT, "TPOT p95 (avg) %.3f с", v));

    // Метрики, не участвующие в оценке, но которых может не быть в отчёте.
    noteUnavailable(unavailable, server.kvCacheAvgPct(), "KV Cache Avg");
    noteUnavailable(unavailable, server.ttftP95MaxSec(), "TTFT p95 (max)");
    noteUnavailable(unavailable, server.tpotP95MaxSec(), "TPOT p95 (max)");
    noteUnavailable(unavailable, server.promptTpsAvg(), "Prompt Throughput Avg");
    noteUnavailable(unavailable, server.promptTpsMax(), "Prompt Throughput Max");
    noteUnavailable(unavailable, server.generationTpsAvg(), "Generation Throughput Avg");
    noteUnavailable(unavailable, server.generationTpsMax(), "Generation Throughput Max");
    if (!server.itlAvailable()) {
      unavailable.add("ITL p95 (avg/max) — в этой версии vLLM отдельной гистограммы ITL нет");
    } else {
      noteUnavailable(unavailable, server.itlP95AvgSec(), "ITL p95 (avg)");
      noteUnavailable(unavailable, server.itlP95MaxSec(), "ITL p95 (max)");
    }

    return new ScoreResult(Math.max(0, score), List.copyOf(penalties), List.copyOf(unavailable));
  }

  private static int apply(List<String> penalties, Tier[] tiers, double value,
      java.util.function.Supplier<String> describe) {
    for (Tier tier : tiers) {
      if (value > tier.threshold()) {
        penalties.add("-" + tier.penalty() + "  " + describe.get());
        return tier.penalty();
      }
    }
    return 0;
  }

  private static int applyOptional(List<String> penalties, List<String> unavailable, Tier[] tiers,
      Optional<Double> value, String label, java.util.function.Function<Double, String> describe) {
    if (value.isEmpty()) {
      unavailable.add(label);
      return 0;
    }
    double actual = value.get();
    return apply(penalties, tiers, actual, () -> describe.apply(actual));
  }

  private static void noteUnavailable(List<String> unavailable, Optional<Double> value,
      String label) {
    if (value.isEmpty() && !unavailable.contains(label)) {
      unavailable.add(label);
    }
  }

  /**
   * Порог и штраф за его превышение.
   *
   * <p>Ступени перебираются сверху вниз, применяется первая сработавшая — штрафы не суммируются
   * внутри одного правила.
   */
  private record Tier(double threshold, int penalty) {
  }

  /**
   * Итог оценки ступени.
   *
   * @param score       0..100
   * @param penalties   строки вида {@code -10  Очередь доходила до 132 запросов}
   * @param unavailable метрики, данных по которым нет
   */
  public record ScoreResult(int score, List<String> penalties, List<String> unavailable) {
  }
}

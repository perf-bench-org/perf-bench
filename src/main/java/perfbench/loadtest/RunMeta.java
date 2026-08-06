package perfbench.loadtest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Содержимое {@code run.meta.properties} — всё, что нужно, чтобы пересчитать отчёт по уже
 * собранным данным, не подавая нагрузку заново.
 *
 * @param runId       идентификатор прогона
 * @param mode        режим, в котором прогон выполнялся
 * @param threads     рассчитанное число потоков генератора
 * @param startedAt   момент запуска движка
 * @param loadStart   момент фактического старта нагрузки — от него отсчитаны окна ступеней
 * @param endedAt     момент завершения прогона со всеми оставшимися в полёте запросами
 * @param windows     окна удержания всех ступеней
 * @param datasetSha  SHA-256 файла датасета
 * @param datasetSize число промптов в датасете
 * @param model       имя модели, как его отдал vLLM
 * @param vllmVersion версия vLLM
 * @param metricNames разрешённые имена метрик vLLM: логическое имя → фактическое
 */
public record RunMeta(
    String runId,
    LoadMode mode,
    int threads,
    Instant startedAt,
    Instant loadStart,
    Instant endedAt,
    List<HoldWindow> windows,
    String datasetSha,
    int datasetSize,
    String model,
    String vllmVersion,
    Map<String, String> metricNames) {

  public RunMeta {
    windows = windows == null ? List.of() : List.copyOf(windows);
    metricNames = metricNames == null ? Map.of() : Map.copyOf(metricNames);
  }
}

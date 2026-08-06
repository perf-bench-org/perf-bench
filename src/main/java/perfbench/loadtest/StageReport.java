package perfbench.loadtest;

/**
 * Готовые данные одной ступени: окно, клиентская часть, серверная часть, оценка.
 */
public record StageReport(
    HoldWindow window,
    JtlStats.Result client,
    VllmMetrics.ServerMetrics server,
    Scoring.ScoreResult score) {
}

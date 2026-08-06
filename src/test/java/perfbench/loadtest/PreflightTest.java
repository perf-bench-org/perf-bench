package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PreflightTest {

  /** Фрагмент реального экспорта vLLM: TYPE-строки счётчика, гистограммы и gauge. */
  private static final String METRICS = String.join("\n",
      "# HELP vllm:num_requests_waiting Number of requests waiting to be processed.",
      "# TYPE vllm:num_requests_waiting gauge",
      "vllm:num_requests_waiting{engine=\"0\",model_name=\"m\"} 0.0",
      "# HELP vllm:prompt_tokens_total Number of prefill tokens processed.",
      "# TYPE vllm:prompt_tokens_total counter",
      "vllm:prompt_tokens_total{engine=\"0\",model_name=\"m\"} 1234.0",
      "# HELP vllm:time_to_first_token_seconds Histogram of time to first token in seconds.",
      "# TYPE vllm:time_to_first_token_seconds histogram",
      "vllm:time_to_first_token_seconds_bucket{engine=\"0\",le=\"0.001\",model_name=\"m\"} 0.0",
      "vllm:time_to_first_token_seconds_sum{engine=\"0\",model_name=\"m\"} 12.5",
      "vllm:time_to_first_token_seconds_count{engine=\"0\",model_name=\"m\"} 10.0",
      "");

  @Test
  @DisplayName("имена берутся из TYPE-строк: счётчик с _total, гистограмма без _bucket")
  void parsesTypeLines() {
    Set<String> names = Preflight.parseMetricNames(METRICS);

    assertTrue(names.contains("vllm:num_requests_waiting"));
    assertTrue(names.contains("vllm:prompt_tokens_total"));
    assertTrue(names.contains("vllm:time_to_first_token_seconds"));
  }

  @Test
  @DisplayName("производные серии гистограммы отдельными метриками не считаются")
  void ignoresHistogramSuffixes() {
    Set<String> names = Preflight.parseMetricNames(METRICS);

    assertFalse(names.contains("vllm:time_to_first_token_seconds_bucket"));
    assertFalse(names.contains("vllm:time_to_first_token_seconds_sum"));
    assertFalse(names.contains("vllm:time_to_first_token_seconds_count"));
  }

  @Test
  @DisplayName("экспорт с CRLF разбирается так же")
  void handlesCrlf() {
    Set<String> names = Preflight.parseMetricNames(METRICS.replace("\n", "\r\n"));

    assertTrue(names.contains("vllm:prompt_tokens_total"), names.toString());
  }

  @Test
  @DisplayName("пустой ответ даёт пустой набор, а не падение")
  void handlesEmptyBody() {
    assertTrue(Preflight.parseMetricNames("").isEmpty());
  }
}

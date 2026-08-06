package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VllmMetricsTest {

  private static final Set<String> V1_NAMES = Set.of(
      "vllm:kv_cache_usage_perc",
      "vllm:num_requests_waiting",
      "vllm:time_to_first_token_seconds",
      "vllm:inter_token_latency_seconds",
      "vllm:request_time_per_output_token_seconds",
      "vllm:prompt_tokens_total",
      "vllm:generation_tokens_total");

  private static final Set<String> V0_NAMES = Set.of(
      "vllm:gpu_cache_usage_perc",
      "vllm:num_requests_waiting",
      "vllm:time_to_first_token_seconds",
      "vllm:time_per_output_token_seconds",
      "vllm:prompt_tokens_total",
      "vllm:generation_tokens_total");

  @AfterEach
  void tearDown() {
    VllmMetrics.reset();
  }

  @Test
  @DisplayName("актуальные имена метрик разрешаются полностью, включая ITL")
  void resolvesCurrentNames() {
    VllmMetrics.Resolution resolution = VllmMetrics.resolve(V1_NAMES);

    assertTrue(resolution.isComplete(), resolution.missingRequired().toString());
    assertEquals("vllm:kv_cache_usage_perc", resolution.resolved().get(VllmMetrics.M_KV_CACHE));
    assertEquals("vllm:request_time_per_output_token_seconds",
        resolution.resolved().get(VllmMetrics.M_TPOT));
    assertEquals("vllm:inter_token_latency_seconds", resolution.resolved().get(VllmMetrics.M_ITL));
    assertTrue(resolution.missingOptional().isEmpty());
  }

  @Test
  @DisplayName("исторические имена разрешаются, отсутствие ITL не делает набор неполным")
  void resolvesLegacyNames() {
    VllmMetrics.Resolution resolution = VllmMetrics.resolve(V0_NAMES);

    assertTrue(resolution.isComplete(), resolution.missingRequired().toString());
    assertEquals("vllm:gpu_cache_usage_perc", resolution.resolved().get(VllmMetrics.M_KV_CACHE));
    assertEquals("vllm:time_per_output_token_seconds",
        resolution.resolved().get(VllmMetrics.M_TPOT));
    assertFalse(resolution.resolved().containsKey(VllmMetrics.M_ITL));
    assertEquals(1, resolution.missingOptional().size());
  }

  @Test
  @DisplayName("отсутствие обязательной метрики называет её и список кандидатов")
  void reportsMissingRequired() {
    Set<String> partial = Set.of("vllm:num_requests_waiting", "vllm:prompt_tokens_total");

    VllmMetrics.Resolution resolution = VllmMetrics.resolve(partial);

    assertFalse(resolution.isComplete());
    assertTrue(resolution.missingRequired().stream()
            .anyMatch(m -> m.contains(VllmMetrics.M_KV_CACHE)
                && m.contains("vllm:kv_cache_usage_perc")),
        resolution.missingRequired().toString());
  }

  @Test
  @DisplayName("гистограммы помечены как гистограммы — от этого зависит суффикс _bucket")
  void knowsHistograms() {
    assertTrue(VllmMetrics.isHistogram(VllmMetrics.M_TTFT));
    assertTrue(VllmMetrics.isHistogram(VllmMetrics.M_TPOT));
    assertTrue(VllmMetrics.isHistogram(VllmMetrics.M_ITL));
    assertFalse(VllmMetrics.isHistogram(VllmMetrics.M_QUEUE));
    assertFalse(VllmMetrics.isHistogram(VllmMetrics.M_KV_CACHE));
    assertFalse(VllmMetrics.isHistogram(VllmMetrics.M_PROMPT_TOKENS));
  }

  @Test
  @DisplayName("запрос присутствия гистограммы адресует серию _bucket")
  void presenceQueryUsesBucket() {
    VllmMetrics.adopt(VllmMetrics.resolve(V1_NAMES).resolved());

    assertEquals("vllm:time_to_first_token_seconds_bucket",
        VllmMetrics.presenceQuery(VllmMetrics.M_TTFT, true));
    assertEquals("vllm:num_requests_waiting",
        VllmMetrics.presenceQuery(VllmMetrics.M_QUEUE, false));
  }

  @Test
  @DisplayName("наличие ITL определяется разрешёнными именами")
  void itlAvailability() {
    VllmMetrics.adopt(VllmMetrics.resolve(V1_NAMES).resolved());
    assertTrue(VllmMetrics.itlAvailable());

    VllmMetrics.adopt(VllmMetrics.resolve(V0_NAMES).resolved());
    assertFalse(VllmMetrics.itlAvailable());
  }
}

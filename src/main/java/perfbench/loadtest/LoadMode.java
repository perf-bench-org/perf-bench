package perfbench.loadtest;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Режим работы стенда.
 *
 * <p>{@link #STEP} и {@link #FLAT} подают нагрузку и формируют отчёт, {@link #REPORT} только
 * пересчитывает отчёт по уже собранным данным существующего прогона.
 */
public enum LoadMode {

  STEP,
  FLAT,
  REPORT;

  public static LoadMode parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("режим не задан");
    }
    String normalized = value.trim().toUpperCase();
    for (LoadMode mode : values()) {
      if (mode.name().equals(normalized)) {
        return mode;
      }
    }
    throw new IllegalArgumentException(
        "неизвестный режим '" + value + "', допустимы: " + names());
  }

  public static String names() {
    return Arrays.stream(values())
        .map(m -> m.name().toLowerCase())
        .collect(Collectors.joining(" | "));
  }

  /** Подаёт ли режим нагрузку. */
  public boolean isLoadGenerating() {
    return this != REPORT;
  }

  public String lower() {
    return name().toLowerCase();
  }
}

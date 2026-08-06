package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetTest {

  @TempDir
  Path temp;

  @AfterEach
  void tearDown() {
    Dataset.reset();
  }

  private Path csv(String content) throws IOException {
    Path file = temp.resolve("prompts.csv");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  @DisplayName("значение с запятой внутри кавычек не разваливается")
  void keepsQuotedCommas() throws IOException {
    Path file = csv("prompt\n\"Первый, с запятой\"\nВторой\n");

    Dataset.load(file, ',', 1);

    assertEquals(Set.of("Первый, с запятой", "Второй"), collectAll(2));
  }

  @Test
  @DisplayName("экранированные кавычки внутри значения сохраняются")
  void keepsEscapedQuotes() throws IOException {
    Path file = csv("prompt\n\"Он сказал \"\"да\"\" и ушёл\"\n");

    Dataset.load(file, ',', 1);

    assertEquals("Он сказал \"да\" и ушёл", Dataset.next());
  }

  @Test
  @DisplayName("лишние колонки игнорируются, маппинг по заголовку")
  void ignoresExtraColumns() throws IOException {
    Path file = csv("id,prompt,created_at\n7,Текст,2026-01-01\n");

    Dataset.load(file, ',', 1);

    assertEquals(1, Dataset.size());
    assertEquals("Текст", Dataset.next());
  }

  @Test
  @DisplayName("пустые промпты отбрасываются")
  void dropsBlankPrompts() throws IOException {
    Path file = csv("prompt\nПервый\n\"\"\n   \nВторой\n");

    Dataset.load(file, ',', 1);

    assertEquals(2, Dataset.size());
  }

  @Test
  @DisplayName("порядок детерминирован при одинаковом seed и различается при разном")
  void shuffleIsDeterministic() throws IOException {
    Path file = csv("prompt\n" + String.join("\n",
        List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")) + "\n");

    Dataset.load(file, ',', 42);
    List<String> first = collect(10);
    Dataset.load(file, ',', 42);
    List<String> second = collect(10);
    Dataset.load(file, ',', 4242);
    List<String> other = collect(10);

    assertEquals(first, second);
    assertNotEquals(first, other);
  }

  @Test
  @DisplayName("обход циклический")
  void wrapsAround() throws IOException {
    Path file = csv("prompt\nодин\n");

    Dataset.load(file, ',', 1);

    assertEquals("один", Dataset.next());
    assertEquals("один", Dataset.next());
  }

  @Test
  @DisplayName("BOM в начале файла не ломает распознавание колонки")
  void stripsBom() throws IOException {
    Path file = csv("\uFEFFprompt\nТекст\n");

    Dataset.load(file, ',', 1);

    assertEquals("Текст", Dataset.next());
  }

  @Test
  @DisplayName("отсутствие колонки prompt — падение на старте")
  void failsWithoutPromptColumn() throws IOException {
    Path file = csv("text\nчто-то\n");

    Dataset.DatasetException e = assertThrows(Dataset.DatasetException.class,
        () -> Dataset.load(file, ',', 1));
    assertTrue(e.getMessage().contains("prompt"), e.getMessage());
  }

  @Test
  @DisplayName("файл без единого непустого промпта — падение на старте")
  void failsOnEmptyDataset() throws IOException {
    Path file = csv("prompt\n");

    assertThrows(Dataset.DatasetException.class, () -> Dataset.load(file, ',', 1));
  }

  @Test
  @DisplayName("отсутствующий файл — падение на старте")
  void failsOnMissingFile() {
    assertThrows(Dataset.DatasetException.class,
        () -> Dataset.load(temp.resolve("нет-такого.csv"), ',', 1));
  }

  @Test
  @DisplayName("хеш файла фиксируется")
  void computesSha256() throws IOException {
    Path file = csv("prompt\nТекст\n");

    Dataset.load(file, ',', 1);

    assertEquals(64, Dataset.sha256().length());
    assertTrue(Dataset.sha256().matches("[0-9a-f]{64}"));
  }

  private List<String> collect(int count) {
    List<String> values = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      values.add(Dataset.next());
    }
    return values;
  }

  private Set<String> collectAll(int count) {
    return new HashSet<>(collect(count));
  }
}

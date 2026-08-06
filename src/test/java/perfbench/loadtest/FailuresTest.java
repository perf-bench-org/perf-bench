package perfbench.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FailuresTest {

  @Test
  @DisplayName("исключение без сообщения не превращается в 'null'")
  void describesMessagelessException() {
    // Ровно так приходит ConnectException из java.net.http при неразрешимом имени.
    String described = Failures.describe(new ConnectException());

    assertEquals("ConnectException", described);
    assertTrue(!described.contains("null"), described);
  }

  @Test
  @DisplayName("сообщение берётся из первой причины, у которой оно есть")
  void walksCauseChain() {
    // Ровно то, что прилетает из java.net.http при неразрешимом имени хоста: у внешнего
    // исключения сообщения нет, имя хоста лежит на два уровня глубже.
    Throwable error = new ConnectException();
    error.initCause(new UnknownHostException("no-such-vllm"));

    String described = Failures.describe(error);

    assertEquals("ConnectException <- UnknownHostException: no-such-vllm", described);
  }

  @Test
  @DisplayName("собственное сообщение важнее сообщений причин")
  void prefersOwnMessage() {
    Throwable error = new IOException("не читается", new ConnectException("глубже"));

    assertEquals("IOException: не читается", Failures.describe(error));
  }
}

package perfbench.loadtest;

/**
 * Приведение исключений к строке, пригодной для сообщения оператору.
 *
 * <p>Нужно потому, что у сетевых исключений JDK сообщение сплошь и рядом пустое:
 * {@code UnknownHostException} несёт только имя хоста, {@code ConnectException} из
 * {@code HttpClient} — вообще {@code null}. Печатать «недоступен: null» человеку, который
 * разбирается в закрытом контуре без отладчика, бессмысленно.
 */
public final class Failures {

  private Failures() {
  }

  /** Тип исключения плюс первое непустое сообщение по цепочке причин. */
  public static String describe(Throwable error) {
    StringBuilder out = new StringBuilder(error.getClass().getSimpleName());
    for (Throwable current = error; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message != null && !message.isBlank()) {
        out.append(": ").append(message);
        break;
      }
      if (current.getCause() != null) {
        out.append(" <- ").append(current.getCause().getClass().getSimpleName());
      }
    }
    return out.toString();
  }
}

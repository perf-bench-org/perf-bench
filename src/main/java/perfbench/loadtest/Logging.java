package perfbench.loadtest;

import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;

/**
 * Направляет лог движка в каталог прогона.
 *
 * <p>Путь к файлу известен только после разбора конфигурации, поэтому log4j2 конфигурируется по
 * системному свойству и переинициализируется, как только каталог прогона создан. До этого момента
 * стенд пишет в {@code System.out} и логгер не трогает.
 */
public final class Logging {

  /** Читается из {@code log4j2.xml}. */
  public static final String LOG_FILE_PROPERTY = "perfbench.logfile";

  public static final String LOG_FILE_NAME = "jmeter.log";

  private Logging() {
  }

  /** Переводит запись лога в {@code <каталог прогона>/jmeter.log}. */
  public static void redirectTo(Path runDir) {
    System.setProperty(LOG_FILE_PROPERTY, runDir.resolve(LOG_FILE_NAME).toString());
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    context.reconfigure();
  }

  public static Logger log() {
    return LogManager.getLogger("perfbench");
  }
}

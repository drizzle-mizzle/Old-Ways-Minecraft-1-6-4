package oldways.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Журнал лаунчера: одновременно в файл и в окно.
 *
 * Файл важнее окна: когда игрок пишет «не запускается», разбираться придётся
 * по нему, а окно к тому моменту уже закрыто.
 */
final class Log {

    interface Sink {
        void line(String text);
    }

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss");
    private static PrintWriter file;
    private static Sink sink;

    private Log() {}

    static synchronized void toFile(File path) {
        try {
            Util.mkdirs(path.getParentFile());
            // UTF-8 явно: иначе на русской Windows журнал ляжет в cp1251
            // и его не прочитает никто, кроме этой же машины
            file = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(path, false), "UTF-8"), true);
            file.println("--- запуск лаунчера " + new Date() + " ---");
        } catch (Exception e) {
            file = null;   // без журнала жить можно, без лаунчера — нет
        }
    }

    static synchronized void listen(Sink target) {
        sink = target;
    }

    static synchronized void info(String message) {
        String line = STAMP.format(new Date()) + "  " + message;
        System.out.println(line);
        if (file != null) file.println(line);
        if (sink != null) sink.line(message);
    }

    static void info(String format, Object... args) {
        info(String.format(format, args));
    }

    static synchronized void error(String message, Throwable cause) {
        info(message + (cause == null ? "" : ": " + describe(cause)));
        if (file != null && cause != null) {
            StringWriter trace = new StringWriter();
            cause.printStackTrace(new PrintWriter(trace));
            file.println(trace);
        }
    }

    /** Короткое описание для игрока: тип исключения без пакета плюс сообщение. */
    static String describe(Throwable cause) {
        String name = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? name : message;
    }
}

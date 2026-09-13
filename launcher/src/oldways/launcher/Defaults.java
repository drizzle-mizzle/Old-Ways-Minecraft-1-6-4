package oldways.launcher;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Что лаунчер знает о сервере до первого запуска.
 *
 * Адрес не вшит в код: он лежит в файле `launcher/ADDRESS` и попадает в jar
 * ресурсом при сборке — как и версия. Так один и тот же исходник собирается
 * и для своего сервера, и для чужого, а игроку не приходится вводить адрес
 * руками (и ошибаться в нём).
 *
 * Это именно значение по умолчанию: как только игрок что-то сохранит
 * в настройках, лаунчер берёт из `launcher.properties` его.
 */
final class Defaults {

    /** Запуск из каталога классов или сборка без файла — играем локально. */
    private static final String LOCAL = "localhost";

    public static final String ADDRESS = read();

    private Defaults() {
    }

    private static String read() {
        InputStream stream = Defaults.class.getResourceAsStream("ADDRESS");
        if (stream == null) return LOCAL;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            try {
                String line = reader.readLine();
                return line == null || line.trim().isEmpty() ? LOCAL : line.trim();
            } finally {
                reader.close();
            }
        } catch (Exception error) {
            return LOCAL;
        }
    }
}

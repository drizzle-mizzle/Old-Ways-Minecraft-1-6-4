package oldways.launcher;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Версия лаунчера.
 *
 * Живёт она в одном месте — файле launcher/VERSION, — а сборка разносит её
 * в три: сюда ресурсом, в MANIFEST.MF рядом с точкой входа и в VERSIONINFO
 * внутри exe. Так её одинаково видят и сам лаунчер, и Windows в свойствах
 * файла, и сервис обновлений, который читает версию прямо из бинарника.
 *
 * Формат — SemVer: старшая цифра для несовместимых перемен, средняя для
 * заметных, младшая поднимается сама при сборке на отгрузку.
 */
public final class Version {

    /** Запасное значение: запуск из каталога классов, а не из собранного jar. */
    private static final String UNKNOWN = "0.0.0-dev";

    public static final String CURRENT = read();

    private Version() {
    }

    private static String read() {
        InputStream stream = Version.class.getResourceAsStream("VERSION");
        if (stream == null) return UNKNOWN;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            try {
                String line = reader.readLine();
                return line == null || line.trim().isEmpty() ? UNKNOWN : line.trim();
            } finally {
                reader.close();
            }
        } catch (Exception error) {
            return UNKNOWN;
        }
    }

    /**
     * Сравнение по номерам, а не по буквам: «0.2.10» новее «0.2.9», хотя
     * строкой выходит наоборот. Хвост после дефиса («0.3.0-rc1») отбрасываем —
     * для сравнения он не нужен, а разбираться в его правилах незачем.
     */
    public static int compare(String left, String right) {
        int[] a = numbers(left);
        int[] b = numbers(right);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
        }
        return 0;
    }

    /** Новее ли то, что лежит на сервере, чем то, с чем мы запустились. */
    public static boolean newer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private static int[] numbers(String value) {
        int[] parts = new int[3];
        if (value == null) return parts;
        int cut = value.indexOf('-');
        String clean = cut < 0 ? value : value.substring(0, cut);
        String[] pieces = clean.trim().split("\\.");
        for (int i = 0; i < 3 && i < pieces.length; i++) {
            try {
                parts[i] = Integer.parseInt(pieces[i].trim());
            } catch (NumberFormatException error) {
                parts[i] = 0;
            }
        }
        return parts;
    }
}

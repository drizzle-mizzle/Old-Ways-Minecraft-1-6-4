package oldways.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Проверки перед тем, как трогать файлы сборки.
 *
 * Обе беды здесь одинаковые по сути: файл на месте, а записать в него нельзя.
 * Разница в том, что игроку об этом надо сказать по-человечески и до начала
 * работы, а не оборванной докачкой на середине.
 */
final class Guard {

    private Guard() {}

    /** Что мешает обновлять сборку, или null, если ничего. */
    static String blocker(Config cfg) {
        if (gameLocked(cfg)) {
            return "Игра запущена — закройте её, чтобы обновить сборку";
        }
        File root = cfg.root();
        if (!writable(root)) {
            return "Нет прав на запись в " + root
                    + " — запустите лаунчер от имени администратора";
        }
        return null;
    }

    /**
     * Объяснение к уже случившейся ошибке.
     *
     * Windows на занятый файл отвечает «отказано в доступе», и по такому тексту
     * игрок ничего не поймёт. Проверяем причину задним числом — это дешевле,
     * чем гонять проверки перед каждым файлом.
     */
    static String hint(Config cfg, String message) {
        String blocker = blocker(cfg);
        return blocker == null ? message : message + ". " + blocker;
    }

    /**
     * Держит ли кто-то файлы игры.
     *
     * Запущенная игра открывает библиотеки из natives без права на запись для
     * других — и открыть такой файл на запись мы уже не сможем. Способ грубый,
     * зато не требует ни перебора процессов, ни разбора их командных строк:
     * и то и другое на Windows стоит секунды и ломается от версии к версии.
     */
    static boolean gameLocked(Config cfg) {
        File[] files = cfg.natives().listFiles();
        if (files == null) return false;          // ещё не распаковано
        for (File file : files) {
            String name = file.getName().toLowerCase();
            // Только библиотеки: остальное игра открывает и закрывает,
            // и поймать её на этом — вопрос везения.
            if (!name.endsWith(".dll") && !name.endsWith(".so") && !name.endsWith(".dylib")) {
                continue;
            }
            if (!file.canWrite()) continue;       // файл помечен только для чтения — не наш случай
            RandomAccessFile probe = null;
            try {
                probe = new RandomAccessFile(file, "rw");
            } catch (IOException busy) {
                Log.info("файл занят, похоже игра запущена: %s", file.getName());
                return true;
            } finally {
                if (probe != null) try { probe.close(); } catch (IOException ignored) {}
            }
        }
        return false;
    }

    /** Пробная запись: в Program Files или на диске только для чтения её не будет. */
    static boolean writable(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File probe = new File(dir, ".ow-write-test");
        try {
            new FileOutputStream(probe).close();
            return true;
        } catch (IOException denied) {
            return false;
        } finally {
            probe.delete();
        }
    }
}

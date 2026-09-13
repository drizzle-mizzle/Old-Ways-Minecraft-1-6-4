package oldways.launcher;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Самообновление лаунчера.
 *
 * Лаунчер не заменяет себя сам: файл, которым запущена работающая Java,
 * на Windows занят и переименованию не поддаётся. Он лишь кладёт рядом
 * oldways-launcher.new.jar, а подменяет запускатель (exe) при следующем
 * старте — до того, как поднимется Java, когда файл свободен.
 *
 * Отсюда и поведение: обновление не требует ни перезапуска по кнопке, ни
 * вопросов. Скачали — сказали об этом — применится само в следующий раз.
 */
final class Updater {

    static final String FRESH = "oldways-launcher.new.jar";
    static final String BACKUP = "oldways-launcher.bak.jar";
    static final String EXE_FRESH = "Old Ways.new.exe";
    static final String EXE_OLD = "Old Ways.old.exe";

    /** Сколько ждать, прежде чем убирать прежний jar (см. forgetBackup). */
    private static final long BACKUP_KEEP_MS = 20000;

    /** Обновление, которое сам лаунчер поставить не может: нужны руки игрока. */
    static final class NeedsHands extends IOException {
        private static final long serialVersionUID = 1L;

        NeedsHands(String message) {
            super(message);
        }
    }

    private Updater() {}

    /**
     * Файл, которым мы запущены, или null, если это каталог классов.
     * Во втором случае обновлять нечего — так лаунчер работает при разработке.
     */
    static File ownJar() {
        try {
            java.security.CodeSource source =
                    Updater.class.getProtectionDomain().getCodeSource();
            if (source == null) return null;
            File file = new File(source.getLocation().toURI());
            return file.isFile() && file.getName().endsWith(".jar") ? file : null;
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * Проверить и, если надо, скачать обновление.
     *
     * @return версия, которая применится при следующем запуске, или null,
     *         если обновляться нечем или незачем.
     */
    /** Что сейчас выложено. Спрашиваем один раз на запуск, ответ крошечный. */
    static Map<String, Object> ask(String base) throws IOException {
        return Json.map(Json.parse(Http.get(base + "/api/launcher")));
    }

    static String prepare(String base, Map<String, Object> root) throws IOException {
        File jar = ownJar();
        if (jar == null) return null;

        Map<String, Object> part = Json.map(root.get("jar"));
        String version = Json.str(part, "version");
        String sum = Json.str(part, "sha256");
        String url = Json.str(part, "url");
        if (version.isEmpty() || !Version.newer(version, Version.CURRENT)) return null;

        File fresh = new File(jar.getParentFile(), FRESH);
        // Скачанное прошлым запуском переживает выход из лаунчера: если оно
        // уже то самое, второй раз тянуть незачем.
        if (fresh.isFile() && Util.sha256(fresh).equalsIgnoreCase(sum)) {
            Log.info("обновление %s уже скачано", version);
            return version;
        }

        Log.info("есть обновление: %s, у нас %s", version, Version.CURRENT);
        Http.download(base + url, fresh, null);
        String got = Util.sha256(fresh);
        if (!got.equalsIgnoreCase(sum)) {
            fresh.delete();
            throw new IOException("сумма скачанного не сошлась: " + got + " вместо " + sum);
        }
        Log.info("обновление %s готово, применится при следующем запуске", version);
        return version;
    }

    /**
     * Заменить сам exe.
     *
     * Нужно редко: exe — это запускатель и вложенная Java, они меняются куда
     * реже кода лаунчера. Зато здесь проще, чем кажется: к этому времени
     * запускатель уже вышел, свой файл он не держит, и достаточно обычного
     * переименования. Прежний остаётся рядом и убирается при следующем
     * запуске — если он всё-таки занят, удалить его сейчас не выйдет.
     *
     * @return новая версия или null, если менять нечего
     */
    static String prepareExe(String base, Map<String, Object> root, File exe)
            throws IOException {
        if (exe == null || !exe.isFile()) return null;
        Map<String, Object> part = Json.map(root.get("exe"));
        String version = Json.str(part, "version");
        String sum = Json.str(part, "sha256");
        String url = Json.str(part, "url");
        String local = Pe.version(exe);
        if (version.isEmpty() || local == null || !Version.newer(version, local)) return null;

        // Права проверяем до загрузки: тянуть двадцать три мегабайта, чтобы
        // упереться в отказ на последнем шаге, — плохой способ узнать о правах.
        File dir = exe.getParentFile();
        if (!Guard.writable(dir)) {
            throw new NeedsHands("Нет прав обновить запускатель в " + dir
                    + " — скачайте Old Ways.exe заново");
        }

        Log.info("запускатель обновляется: %s, у нас %s", version, local);
        File fresh = new File(dir, EXE_FRESH);
        Http.download(base + url, fresh, null);
        String got = Util.sha256(fresh);
        if (!got.equalsIgnoreCase(sum)) {
            fresh.delete();
            throw new IOException("сумма скачанного не сошлась: " + got + " вместо " + sum);
        }

        File old = new File(dir, EXE_OLD);
        old.delete();
        if (!exe.renameTo(old)) {
            // Пробная запись прошла, а переименование нет: файл кто-то держит.
            throw new NeedsHands("Не удалось заменить " + exe.getName()
                    + " — новый лежит рядом как " + EXE_FRESH);
        }
        if (!fresh.renameTo(exe)) {
            old.renameTo(exe);
            throw new IOException("не удалось поставить новый " + exe.getName());
        }
        old.delete();
        Log.info("запускатель заменён на %s", version);
        return version;
    }

    /** Прежний exe, оставшийся от замены: занят он был только до выхода. */
    static void forgetOldExe(File exe) {
        if (exe == null) return;
        File old = new File(exe.getParentFile(), EXE_OLD);
        if (old.isFile() && old.delete()) Log.info("прежний запускатель убран");
    }

    /**
     * Убрать прежний jar, оставленный запускателем.
     *
     * Не сразу: запускатель первые пятнадцать секунд следит, поднялась ли новая
     * версия, и при неудаче возвращает прежнюю. Уберём раньше — возвращать
     * будет нечего, и сломанное обновление останется сломанным навсегда.
     */
    static void forgetBackup() {
        final File jar = ownJar();
        if (jar == null) return;
        final File backup = new File(jar.getParentFile(), BACKUP);
        if (!backup.isFile()) return;
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(BACKUP_KEEP_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (backup.delete()) Log.info("прежний jar убран: обновление прижилось");
            }
        }, "update-backup");
        thread.setDaemon(true);
        thread.start();
    }
}

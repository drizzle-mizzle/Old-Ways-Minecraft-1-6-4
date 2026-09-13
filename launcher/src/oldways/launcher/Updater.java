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

    /** Сколько ждать, прежде чем убирать прежний jar (см. forgetBackup). */
    private static final long BACKUP_KEEP_MS = 20000;

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
    static String prepare(String base) throws IOException {
        File jar = ownJar();
        if (jar == null) return null;

        Map<String, Object> root = Json.map(Json.parse(Http.get(base + "/api/launcher")));
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

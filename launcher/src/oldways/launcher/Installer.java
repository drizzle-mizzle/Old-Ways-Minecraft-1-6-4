package oldways.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Приведение сборки на машине игрока в соответствие с манифестом.
 *
 * Четыре шага: докачать недостающее, распаковать natives, разложить ассеты
 * в вид, который понимает 1.6.4, и вписать в клиент адрес сервера. Каждый шаг
 * идемпотентен — второй запуск подряд не делает ничего.
 */
final class Installer {

    interface Watcher {
        void stage(String text);
        void bytes(long done, long total);
        boolean cancelled();
    }

    private final Config cfg;
    private final Manifest manifest;
    private final String base;
    private final Watcher watcher;
    private final Verified verified;

    Installer(Config cfg, Manifest manifest, String base, Watcher watcher) {
        this.cfg = cfg;
        this.manifest = manifest;
        this.base = base;
        this.watcher = watcher;
        this.verified = new Verified(new File(cfg.root(), "verified.properties"));
    }

    File run() throws IOException {
        download();
        natives();
        virtualAssets();
        return client();
    }

    private void checkCancelled() throws IOException {
        if (watcher.cancelled()) throw new IOException("отменено");
    }

    // ------------------------------------------------------------- загрузка

    private void download() throws IOException {
        watcher.stage("Проверяю сборку…");
        List<Manifest.FileEntry> wanted = new ArrayList<Manifest.FileEntry>();
        long need = 0;
        for (Manifest.FileEntry entry : manifest.files) {
            checkCancelled();
            if (!intact(entry)) {
                wanted.add(entry);
                need += entry.size;
            }
        }
        verified.save();

        if (wanted.isEmpty()) {
            Log.info("сборка на месте: %d файлов, %s",
                    manifest.files.size(), Util.size(manifest.totalBytes));
            return;
        }

        Log.info("качаю %d файлов, %s", wanted.size(), Util.size(need));
        watcher.stage("Качаю " + Util.size(need) + "…");
        final long[] done = { 0 };
        final long total = need;
        for (Manifest.FileEntry entry : wanted) {
            checkCancelled();
            File target = cfg.local(entry.path);
            Http.download(manifest.fileUrl(base, entry.path), target, new Http.Progress() {
                public boolean advanced(long chunk) {
                    done[0] += chunk;
                    watcher.bytes(done[0], total);
                    return !watcher.cancelled();
                }
            });
            String got = Util.sha1(target);
            if (!got.equalsIgnoreCase(entry.sha1)) {
                target.delete();
                throw new IOException("скачанный файл не сошёлся по хешу: " + entry.path);
            }
            verified.remember(entry.path, target);
        }
        verified.save();
        Log.info("докачано %s", Util.size(need));
    }

    /**
     * Цел ли файл. Хеш считается только для тех, чья пара размер+время
     * изменилась с прошлой проверки: пересчитывать 118 МБ на каждом запуске
     * незачем, а подмену файла это всё равно заметит.
     */
    private boolean intact(Manifest.FileEntry entry) throws IOException {
        File file = cfg.local(entry.path);
        if (!file.isFile() || file.length() != entry.size) return false;
        if (verified.matches(entry.path, file)) return true;
        if (!Util.sha1(file).equalsIgnoreCase(entry.sha1)) {
            Log.info("файл испорчен, перекачаю: %s", entry.path);
            return false;
        }
        verified.remember(entry.path, file);
        return true;
    }

    // -------------------------------------------------------------- natives

    private void natives() throws IOException {
        File dir = cfg.natives();
        // Метка хранит список natives-архивов: сменились они — распаковываем заново.
        StringBuilder stamp = new StringBuilder();
        for (String path : manifest.natives) stamp.append(path).append('\n');
        File marker = new File(dir, ".stamp");
        if (marker.isFile() && stamp.toString().equals(Util.readText(marker))) return;

        watcher.stage("Распаковываю библиотеки системы…");
        int count = 0;
        for (String path : manifest.natives) {
            checkCancelled();
            count += JarTool.extractNatives(cfg.local(path), dir);
        }
        Util.writeText(marker, stamp.toString());
        Log.info("natives: распаковано %d файлов в %s", count, dir);
    }

    // --------------------------------------------------------------- ассеты

    /**
     * Раскладка ассетов для 1.6.4.
     *
     * Индекс legacy устроен как «имя -> хеш», а игра этой версии ещё ждёт файлы
     * по именам, поэтому из content-addressed хранилища собирается обычное
     * дерево assets/virtual/legacy. 1120 имён на 596 объектов — дубликаты
     * кладём жёсткими ссылками, чтобы не платить за них диском; где ссылки
     * не поддерживаются (чужая файловая система), копируем.
     */
    private void virtualAssets() throws IOException {
        File index = cfg.local("assets/indexes/" + manifest.assets + ".json");
        File root = new File(cfg.assets(), "virtual" + File.separator + manifest.assets);
        Map<String, Object> objects = Json.map(
                Json.map(Json.parse(Util.readText(index))).get("objects"));

        int created = 0;
        boolean linked = true;
        for (Map.Entry<String, Object> item : objects.entrySet()) {
            checkCancelled();
            Map<String, Object> info = Json.map(item.getValue());
            String hash = Json.str(info, "hash");
            long size = Json.num(info, "size");
            File target = new File(root, item.getKey().replace('/', File.separatorChar));
            if (target.isFile() && target.length() == size) continue;
            File source = cfg.local("assets/objects/" + hash.substring(0, 2) + "/" + hash);
            Util.mkdirs(target.getParentFile());
            if (target.exists() && !target.delete()) {
                throw new IOException("не удалось заменить ассет " + target);
            }
            if (created == 0) watcher.stage("Раскладываю ресурсы игры…");
            if (linked) {
                try {
                    Files.createLink(target.toPath(), source.toPath());
                    created++;
                    continue;
                } catch (Exception e) {
                    // например, раздача и данные на разных разделах
                    Log.info("жёсткие ссылки недоступны, копирую ассеты: %s", Log.describe(e));
                    linked = false;
                }
            }
            copy(source, target);
            created++;
        }
        if (created > 0) Log.info("ассеты: разложено %d файлов в %s", created, root);
    }

    // --------------------------------------------------------------- клиент

    /**
     * Возвращает рабочий клиент, пропатченный на текущий адрес.
     *
     * Базовый jar из раздачи не трогается: он одинаков у всех и им же
     * проверяется целостность. Рабочий пересобирается, когда сменился адрес
     * или обновилась сама сборка.
     */
    private File client() throws IOException {
        File source = cfg.local(manifest.client);
        if (!manifest.patchClient) {
            // адреса правит coremod при загрузке классов — jar остаётся ванильным
            return source;
        }
        File target = new File(source.getParentFile(), manifest.id + "-run.jar");
        String sourceHash = Util.sha1(source);

        boolean fresh = target.isFile()
                && base.equals(cfg.get("patched.address", ""))
                && sourceHash.equals(cfg.get("patched.base", ""));
        if (fresh) return target;

        watcher.stage("Вписываю адрес сервера в клиент…");
        Log.info("патчу клиент на %s", base);
        int patched = JarTool.patchJar(source, target, base, base);
        if (patched == 0) {
            throw new IOException("в клиенте не нашлось адресов авторизации — чужой jar?");
        }
        cfg.set("patched.address", base);
        cfg.set("patched.base", sourceHash);
        cfg.save();
        Log.info("клиент готов: %d классов, %s", patched, Util.size(target.length()));
        for (String url : JarTool.inspect(target)) Log.info("  теперь: %s", url);
        return target;
    }

    // ------------------------------------------------------------ мелочёвка

    private static void copy(File source, File target) throws IOException {
        InputStream in = new FileInputStream(source);
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } finally {
            out.close();
            in.close();
        }
    }

}

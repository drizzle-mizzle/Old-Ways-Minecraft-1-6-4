package oldways.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Память о том, какие файлы уже сверены по хешу.
 *
 * Хранится пара «размер:время правки». Совпала — файл считается тем же и хеш
 * не пересчитывается; не совпала — считается заново. Без этого каждый запуск
 * читал бы и хешировал всю сборку целиком.
 */
final class Verified {

    private final File file;
    private final Properties values = new Properties();
    private boolean dirty;

    Verified(File file) {
        this.file = file;
        if (!file.isFile()) return;
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                values.load(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            Log.error("не смог прочитать список проверенных файлов", e);
        }
    }

    private static String stamp(File target) {
        return target.length() + ":" + target.lastModified();
    }

    boolean matches(String path, File target) {
        return stamp(target).equals(values.getProperty(path));
    }

    void remember(String path, File target) {
        values.setProperty(path, stamp(target));
        dirty = true;
    }

    void save() {
        if (!dirty) return;
        try {
            Util.mkdirs(file.getParentFile());
            FileOutputStream out = new FileOutputStream(file);
            try {
                values.store(out, "проверенные файлы сборки");
            } finally {
                out.close();
            }
            dirty = false;
        } catch (IOException e) {
            Log.error("не смог сохранить список проверенных файлов", e);
        }
    }
}

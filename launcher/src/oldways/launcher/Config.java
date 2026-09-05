package oldways.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Настройки и раскладка файлов на машине игрока.
 *
 * Всё нажитое лежит в одном каталоге, чтобы удаление сборки было понятным
 * действием, а не поиском по системе:
 *
 *   launcher.properties            настройки
 *   launcher.log                   журнал
 *   versions/1.6.4/1.6.4.jar       базовый клиент из раздачи, не трогается
 *   versions/1.6.4/1.6.4-run.jar   рабочий: тот же клиент с нашим адресом
 *   libraries/                     библиотеки из раздачи
 *   natives/                       распакованные natives под эту ОС
 *   assets/objects, assets/virtual готовые ассеты
 *   minecraft/                     игровой каталог: миры, настройки, скриншоты
 */
final class Config {

    private final File root;
    private final File file;
    private final Properties values = new Properties();

    Config(File root) {
        this.root = root;
        this.file = new File(root, "launcher.properties");
        load();
    }

    /** Каталог данных: OW_HOME, иначе %APPDATA%\.old-ways или ~/.old-ways. */
    static File defaultRoot() {
        String override = System.getenv("OW_HOME");
        if (override != null && !override.isEmpty()) return new File(override);
        if ("windows".equals(Util.OS)) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null && !appdata.isEmpty()) return new File(appdata, ".old-ways");
        }
        return new File(System.getProperty("user.home"), ".old-ways");
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                values.load(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            Log.error("не смог прочитать настройки, беру значения по умолчанию", e);
        }
    }

    void save() {
        try {
            Util.mkdirs(root);
            FileOutputStream out = new FileOutputStream(file);
            try {
                values.store(out, "Old Ways launcher");
            } finally {
                out.close();
            }
        } catch (IOException e) {
            Log.error("не смог сохранить настройки", e);
        }
    }

    String get(String key, String fallback) {
        String value = values.getProperty(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    void set(String key, String value) {
        if (value == null) values.remove(key);
        else values.setProperty(key, value);
    }

    int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // -------------------------------------------------------------- пути

    File root()      { return root; }
    File versions()  { return new File(root, "versions"); }
    File libraries() { return new File(root, "libraries"); }
    File assets()    { return new File(root, "assets"); }
    File natives()   { return new File(root, "natives"); }
    File gameDir()   { return new File(root, "minecraft"); }
    File logFile()   { return new File(root, "launcher.log"); }

    /** Файл из раздачи по его пути в манифесте. */
    File local(String path) {
        return new File(root, path.replace('/', File.separatorChar));
    }
}

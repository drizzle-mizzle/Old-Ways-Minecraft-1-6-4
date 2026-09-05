package wfactory.launcher;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Точка входа.
 *
 * Кроме окна есть консольный режим (--cli): им удобно проверять всю цепочку
 * без графики и разбираться, если у игрока лаунчер не открывается вовсе.
 */
public final class Main {

    static final String VERSION = "0.2";

    public static void main(String[] args) {
        Map<String, String> options = parse(args);
        File root = options.containsKey("home")
                ? new File(options.get("home")) : Config.defaultRoot();
        Config cfg = new Config(root);
        Log.toFile(cfg.logFile());
        Log.info("W-Factory лаунчер %s, каталог %s", VERSION, root);
        Log.info("система %s, java %s", Util.OS, System.getProperty("java.version"));

        if (options.containsKey("cli")) {
            System.exit(console(cfg, options));
        }
        MainWindow.open(cfg);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "");
            }
        }
        return options;
    }

    /** Тот же путь, что и в окне, только текстом: вход, сверка, патч, запуск. */
    private static int console(Config cfg, Map<String, String> options) {
        try {
            String raw = options.containsKey("address")
                    ? options.get("address") : cfg.get("address", "localhost");
            Address address = Address.parse(raw);
            String base = address.base();
            Log.info("адрес: %s (игра %s:%d)", base, address.host,
                    cfg.getInt("game.port", Address.DEFAULT_GAME_PORT));

            String user = options.containsKey("user")
                    ? options.get("user") : cfg.get("username", "");
            String password = options.get("password");
            if (user.isEmpty() || password == null) {
                Log.info("нужны --user и --password");
                return 2;
            }

            Auth.Session session = Auth.login(base, user, password);
            Log.info("вход выполнен: %s%s", session.username, session.admin ? " (админ)" : "");
            if (session.mustChangePassword) {
                Log.info("ВНИМАНИЕ: пароль совпадает с ником, смените его");
            }

            Manifest manifest = Manifest.fetch(base);
            Log.info("сборка %s %s, файлов %d, %s", manifest.name, manifest.id,
                    manifest.files.size(), Util.size(manifest.totalBytes));

            Installer.Watcher watcher = new Installer.Watcher() {
                private int percent = -1;

                public void stage(String text) {
                    Log.info(text);
                }

                public void bytes(long done, long total) {
                    int now = total > 0 ? (int) (done * 100 / total) : 0;
                    if (now / 10 != percent / 10) {
                        percent = now;
                        Log.info("  %d%% (%s из %s)", now, Util.size(done), Util.size(total));
                    }
                }

                public boolean cancelled() {
                    return false;
                }
            };

            File client = new Installer(cfg, manifest, base, watcher).run();
            cfg.set("address", raw);
            cfg.set("username", session.username);
            cfg.save();

            if (options.containsKey("no-launch")) {
                Log.info("готово, клиент: %s (запуск не просили)", client);
                return 0;
            }

            List<String> command = GameRunner.command(cfg, manifest, client, session, address);
            Process game = GameRunner.start(cfg, command);
            int code = game.waitFor();
            Log.info("игра завершилась с кодом %d", code);
            return code;
        } catch (Exception e) {
            Log.error("не вышло", e);
            return 1;
        }
    }
}

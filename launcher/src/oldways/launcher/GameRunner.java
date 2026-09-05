package oldways.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Запуск игры: сборка командной строки и присмотр за процессом. */
final class GameRunner {

    private GameRunner() {}

    /**
     * Java для игры — та же, на которой работает лаунчер.
     *
     * В раздаче лежит своя JRE 8, лаунчер запускается на ней, значит и игра
     * получит именно её. Системная Java может быть какой угодно версии или
     * не быть вовсе — на неё мы не полагаемся.
     */
    static File javaBinary() {
        File home = new File(System.getProperty("java.home"));
        File exe = new File(home, "bin" + File.separator
                + ("windows".equals(Util.OS) ? "java.exe" : "java"));
        return exe.isFile() ? exe : new File("java");
    }

    static List<String> command(Config cfg, Manifest manifest, File clientJar,
                                Auth.Session session, Address address) {
        return command(cfg, manifest, clientJar, session, address, null);
    }

    /**
     * @param authBase адрес нашего сервиса для coremod; null — не передавать
     */
    static List<String> command(Config cfg, Manifest manifest, File clientJar,
                                Auth.Session session, Address address, String authBase) {
        StringBuilder classpath = new StringBuilder();
        for (String path : manifest.classpath) {
            classpath.append(cfg.local(path).getPath()).append(File.pathSeparatorChar);
        }
        classpath.append(clientJar.getPath());   // клиент идёт последним

        File gameDir = cfg.gameDir();
        File assetsDir = new File(cfg.assets(), "virtual" + File.separator + manifest.assets);

        List<String> command = new ArrayList<String>();
        command.add(javaBinary().getPath());
        command.add("-Xmx" + cfg.getInt("memory", 1024) + "M");
        command.add("-Djava.library.path=" + cfg.natives().getPath());
        // 1.6.4 родом из времён, когда заголовок окна брали из системы;
        // на Linux без этого окно называется просто java
        command.add("-Dorg.lwjgl.opengl.Window.undecorated=false");
        if (!manifest.patchClient) {
            if (authBase != null) {
                // по нему coremod правит адреса авторизации, скинов и плащей
                command.add("-Doldways.auth=" + authBase);
            }
            // Клиент 1.6.4 подписан ключом 2013 года, а Java 8 такие подписи
            // давно не проверяет: сертификатов у классов нет, и FML считает
            // подлинный jar подделанным. Файл при этом сходится по хешу с
            // эталоном Mojang — сверку делает сам лаунчер, до запуска.
            command.add("-Dfml.ignoreInvalidMinecraftCertificates=true");
        }
        command.add("-cp");
        command.add(classpath.toString());
        command.add(manifest.mainClass);

        for (String argument : manifest.arguments.split(" ")) {
            command.add(argument
                    .replace("${auth_player_name}", session.username)
                    .replace("${auth_session}", session.token)
                    .replace("${version_name}", manifest.id)
                    .replace("${game_directory}", gameDir.getPath())
                    .replace("${game_assets}", assetsDir.getPath())
                    .replace("${assets_root}", cfg.assets().getPath())
                    .replace("${assets_index_name}", manifest.assets));
        }

        // Игра про DPI тоже не знает: окно 854x480 на экране 4К выходит
        // с ладонь. Берём тот же масштаб, что и для лаунчера, и не вылезаем
        // за рабочую область.
        Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        double dpi = Toolkit.getDefaultToolkit().getScreenResolution() / 96.0;
        int width = Math.min(cfg.getInt("game.width", (int) Math.round(854 * dpi)),
                work.width - 40);
        int height = Math.min(cfg.getInt("game.height", (int) Math.round(480 * dpi)),
                work.height - 60);
        if ("true".equals(cfg.get("game.fullscreen", "false"))) {
            command.add("--fullscreen");
        } else {
            command.add("--width");
            command.add(String.valueOf(width));
            command.add("--height");
            command.add(String.valueOf(height));
        }

        if (address != null) {   // сразу подключиться к серверу, без списка миров
            command.add("--server");
            command.add(address.host);
            command.add("--port");
            command.add(String.valueOf(cfg.getInt("game.port", Address.DEFAULT_GAME_PORT)));
        }
        return command;
    }

    /** Запускает игру и уводит её вывод в журнал лаунчера. */
    static Process start(Config cfg, List<String> command) throws IOException {
        Util.mkdirs(cfg.gameDir());
        Log.info("запускаю игру: %s", hide(command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cfg.gameDir());
        builder.redirectErrorStream(true);
        final Process process = builder.start();

        Thread pipe = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) Log.info("[игра] %s", line);
                } catch (IOException e) {
                    Log.error("вывод игры оборвался", e);
                }
            }
        }, "game-output");
        pipe.setDaemon(true);
        pipe.start();
        return process;
    }

    /** Та же команда, но без токена сессии: журнал читают посторонние. */
    private static String hide(List<String> command) {
        StringBuilder sb = new StringBuilder();
        boolean secret = false;
        for (String part : command) {
            sb.append(secret ? "<сессия>" : part).append(' ');
            secret = "--session".equals(part);
        }
        return sb.toString().trim();
    }
}

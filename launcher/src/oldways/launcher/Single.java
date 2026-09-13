package oldways.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Один лаунчер на машину.
 *
 * Два окна на одну папку — это две докачки в одни и те же файлы и сборка,
 * собранная наполовину одним и наполовину другим. Ловится это не сразу и
 * чинится удалением папки, так что дешевле не допускать.
 *
 * Замок и почтовый ящик здесь — одно и то же: гнездо на петле. Занять его
 * может только один; второй пускай достучится и попросит поднять окно,
 * вместо того чтобы молча закрыться и оставить игрока гадать.
 */
final class Single {

    private static final String PORT_FILE = "launcher.port";
    private static final String ASK = "raise";
    private static final int TIMEOUT = 700;

    private static volatile Runnable onAsk;

    private Single() {}

    /** Заняли место — true. Уже занято — false, и работать дальше незачем. */
    static boolean claim(Config cfg) {
        File file = new File(cfg.root(), PORT_FILE);
        int port = readPort(file);
        if (port > 0 && ask(port)) return false;

        try {
            final ServerSocket server = new ServerSocket(
                    0, 4, InetAddress.getByName("127.0.0.1"));
            Util.writeText(file, String.valueOf(server.getLocalPort()));
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    listen(server);
                }
            }, "single-instance");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException error) {
            // Не смогли занять гнездо — не повод не пускать игрока в игру.
            Log.info("сторож одного окна не поднялся: %s", error);
        }
        return true;
    }

    /** Что делать, когда второй запуск просит показать окно. */
    static void whenAsked(Runnable action) {
        onAsk = action;
    }

    private static void listen(ServerSocket server) {
        while (!server.isClosed()) {
            Socket client = null;
            try {
                client = server.accept();
                client.setSoTimeout(TIMEOUT);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), "UTF-8"));
                String line = in.readLine();
                if (ASK.equals(line)) {
                    OutputStream out = client.getOutputStream();
                    out.write("ok\n".getBytes("UTF-8"));
                    out.flush();
                    Runnable action = onAsk;
                    if (action != null) action.run();
                }
            } catch (IOException ignored) {
                // оборванное соединение — не наша забота
            } finally {
                if (client != null) try { client.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** Достучаться до первого окна. true — оно откликнулось и живо. */
    private static boolean ask(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                    TIMEOUT);
            socket.setSoTimeout(TIMEOUT);
            OutputStream out = socket.getOutputStream();
            out.write((ASK + "\n").getBytes("UTF-8"));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            return "ok".equals(in.readLine());
        } catch (IOException dead) {
            // Записка осталась от прошлого запуска — место свободно.
            return false;
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static int readPort(File file) {
        try {
            String text = Util.readText(file);
            return text == null ? 0 : Integer.parseInt(text.trim());
        } catch (Exception missing) {
            return 0;
        }
    }
}

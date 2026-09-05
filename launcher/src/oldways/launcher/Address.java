package oldways.launcher;

/**
 * Адрес сервера — единственное, что игрок вводит руками.
 *
 * Сервер, сервис авторизации и скины живут на одной машине, поэтому из одного
 * поля выводится всё: игра идёт на <host>:25565, авторизация и скины — на
 * http://<host>:8080. Порт в поле означает порт сервиса авторизации: именно он
 * может отличаться, игровой порт стандартный.
 *
 * Принимается что угодно похожее: "localhost", "localhost:8080",
 * "http://old-ways.ru", "old-ways.ru/" — лишнее отбрасывается.
 */
final class Address {

    static final int DEFAULT_AUTH_PORT = 8080;
    static final int DEFAULT_GAME_PORT = 25565;

    final String host;
    final int authPort;
    final boolean secure;

    private Address(String host, int authPort, boolean secure) {
        this.host = host;
        this.authPort = authPort;
        this.secure = secure;
    }

    static Address parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        boolean secure = false;
        if (text.regionMatches(true, 0, "https://", 0, 8)) {
            secure = true;
            text = text.substring(8);
        } else if (text.regionMatches(true, 0, "http://", 0, 7)) {
            text = text.substring(7);
        }
        int slash = text.indexOf('/');
        if (slash >= 0) text = text.substring(0, slash);
        if (text.isEmpty()) throw new IllegalArgumentException("адрес не задан");

        int port = secure ? 443 : DEFAULT_AUTH_PORT;
        int colon = text.lastIndexOf(':');
        if (colon >= 0 && text.indexOf(':') == colon) {   // одно двоеточие: host:port
            String tail = text.substring(colon + 1);
            text = text.substring(0, colon);
            try {
                port = Integer.parseInt(tail);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("после двоеточия ждали номер порта: " + tail);
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("порт вне диапазона: " + port);
            }
        }
        if (text.isEmpty()) throw new IllegalArgumentException("адрес не задан");
        return new Address(text, port, secure);
    }

    /** База для joinserver/checkserver, скинов и раздачи сборки. */
    String base() {
        String scheme = secure ? "https://" : "http://";
        boolean standard = secure ? authPort == 443 : authPort == 80;
        return scheme + host + (standard ? "" : ":" + authPort);
    }

    @Override
    public String toString() {
        return host + ":" + authPort;
    }
}

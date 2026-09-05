package oldways.launcher;

import java.io.IOException;
import java.util.Map;

/**
 * Разговор с сервисом авторизации.
 *
 * Токен, который выдаёт /api/login, уходит игре как --session: тот же токен
 * сервис ждёт в joinserver.jsp, когда клиент здоровается с игровым сервером.
 * Никаких паролей за пределами этого класса не ходит.
 */
final class Auth {

    static final class Session {
        final String username;
        final String token;
        final boolean admin;
        final boolean mustChangePassword;
        final long expiresAt;

        Session(String username, String token, boolean admin,
                boolean mustChangePassword, long expiresAt) {
            this.username = username;
            this.token = token;
            this.admin = admin;
            this.mustChangePassword = mustChangePassword;
            this.expiresAt = expiresAt;
        }
    }

    private Auth() {}

    static Session login(String base, String username, String password) throws IOException {
        String body = Json.write("username", username, "password", password);
        String answer;
        try {
            answer = Http.post(base + "/api/login", body.getBytes("UTF-8"),
                    "application/json", null);
        } catch (Http.HttpError e) {
            if (e.code == 401) throw new IOException("неверный ник или пароль");
            if (e.code == 422) throw new IOException("ник до 16 знаков, пароль не пустой");
            throw new IOException("сервис авторизации ответил " + e.detail());
        }
        Map<String, Object> data = Json.map(Json.parse(answer));
        return new Session(
                Json.str(data, "username"),
                Json.str(data, "session"),
                Json.bool(data, "is_admin"),
                Json.bool(data, "must_change_password"),
                Json.num(data, "expires_at"));
    }

    static void changePassword(String base, String session, String oldPassword,
                               String newPassword) throws IOException {
        String body = Json.write("old_password", oldPassword, "new_password", newPassword);
        try {
            Http.post(base + "/api/password", body.getBytes("UTF-8"),
                    "application/json", session);
        } catch (Http.HttpError e) {
            if (e.code == 403) throw new IOException("старый пароль не подходит");
            if (e.code == 422) throw new IOException("новый пароль короче шести знаков");
            throw new IOException("сменить пароль не вышло: " + e.detail());
        }
    }

    static void uploadSkin(String base, String session, byte[] png) throws IOException {
        try {
            Http.post(base + "/api/skin", png, "image/png", session);
        } catch (Http.HttpError e) {
            throw new IOException("скин не принят: " + e.detail());
        }
    }
}

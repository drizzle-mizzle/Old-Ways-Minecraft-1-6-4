package oldways.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** HTTP поверх HttpURLConnection: этого хватает и нет лишних зависимостей. */
final class Http {

    /** Ошибка с кодом ответа: по нему решаем, что показать игроку. */
    static final class HttpError extends IOException {
        private static final long serialVersionUID = 1L;

        final int code;
        final String body;

        HttpError(int code, String body) {
            super("HTTP " + code + (body.isEmpty() ? "" : ": " + body));
            this.code = code;
            this.body = body;
        }

        /** Текст из поля detail — сервис пишет туда объяснение по-русски. */
        String detail() {
            try {
                Object parsed = Json.parse(body);
                if (parsed instanceof java.util.Map) {
                    Object detail = Json.map(parsed).get("detail");
                    if (detail != null) return String.valueOf(detail);
                }
            } catch (RuntimeException ignored) {
                // тело не JSON — покажем как есть
            }
            return body.isEmpty() ? ("HTTP " + code) : body;
        }
    }

    interface Progress {
        /** Вернуть false, чтобы прервать загрузку. */
        boolean advanced(long bytes);
    }

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;

    private Http() {}

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "Old-Ways-Launcher/" + Version.CURRENT);
        return conn;
    }

    private static byte[] finish(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        byte[] data = stream == null ? new byte[0] : Util.readAll(stream);
        if (stream != null) stream.close();
        if (code >= 400) throw new HttpError(code, new String(data, "UTF-8"));
        return data;
    }

    static String get(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            return new String(finish(conn), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Скачивает файл целиком и попутно отдаёт значение одного заголовка:
     * по нему видно, свой у игрока скин или общий скин сервера.
     */
    static byte[] fetch(String url, String header, String[] value) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            byte[] data = finish(conn);
            if (value != null && value.length > 0) value[0] = conn.getHeaderField(header);
            return data;
        } finally {
            conn.disconnect();
        }
    }

    static String post(String url, byte[] body, String contentType, String session)
            throws IOException {
        HttpURLConnection conn = open(url);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", contentType);
            if (session != null) conn.setRequestProperty("X-Session", session);
            OutputStream out = conn.getOutputStream();
            out.write(body);
            out.close();
            return new String(finish(conn), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /** GET с пропуском в заголовке: им спрашивают, жив ли сеанс. */
    static String get(String url, String session) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            if (session != null) conn.setRequestProperty("X-Session", session);
            return new String(finish(conn), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    static String delete(String url, String session) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            conn.setRequestMethod("DELETE");
            if (session != null) conn.setRequestProperty("X-Session", session);
            return new String(finish(conn), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Качает файл во временный и переименовывает по готовности.
     *
     * Так оборванная загрузка не оставляет обрубок на месте настоящего файла:
     * иначе следующий запуск счёл бы его целым по имени и упал бы уже в игре.
     */
    static void download(String url, File target, Progress progress) throws IOException {
        Util.mkdirs(target.getParentFile());
        File temp = new File(target.getPath() + ".part");
        HttpURLConnection conn = open(url);
        InputStream in = null;
        OutputStream out = null;
        try {
            int code = conn.getResponseCode();
            if (code >= 400) {
                InputStream err = conn.getErrorStream();
                byte[] data = err == null ? new byte[0] : Util.readAll(err);
                throw new HttpError(code, new String(data, "UTF-8"));
            }
            in = conn.getInputStream();
            out = new FileOutputStream(temp);
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (progress != null && !progress.advanced(read)) {
                    throw new IOException("загрузка отменена");
                }
            }
            out.close();
            out = null;
            if (target.exists() && !target.delete()) {
                throw new IOException("не удалось заменить файл: " + target);
            }
            if (!temp.renameTo(target)) {
                throw new IOException("не удалось переименовать: " + temp);
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            conn.disconnect();
            if (temp.exists()) temp.delete();
        }
    }
}

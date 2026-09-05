package oldways.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Мелочь, нужная всем: хеши, чтение потоков, работа с файлами. */
final class Util {

    private Util() {}

    static final String OS = detectOs();

    private static String detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("win")) return "windows";
        if (name.contains("mac") || name.contains("darwin")) return "osx";
        return "linux";
    }

    static String sha1(File file) throws IOException {
        MessageDigest md = digest();
        byte[] buffer = new byte[1 << 16];
        InputStream in = new FileInputStream(file);
        try {
            int read;
            while ((read = in.read(buffer)) > 0) md.update(buffer, 0, read);
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    static String sha1(byte[] data) {
        return hex(digest().digest(data));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("в этой JVM нет SHA-1", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("не удалось создать каталог: " + dir);
        }
    }

    /** Человеческий размер: лаунчер сообщает объём загрузки игроку, а не машине. */
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return String.format("%.0f КБ", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f МБ", bytes / (1024.0 * 1024));
        return String.format("%.2f ГБ", bytes / (1024.0 * 1024 * 1024));
    }
}

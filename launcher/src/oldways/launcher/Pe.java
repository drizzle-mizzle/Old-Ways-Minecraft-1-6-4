package oldways.launcher;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Версия exe — из ресурса, который показывает и сама Windows в свойствах файла.
 *
 * Обходить дерево ресурсов целиком не нужно: достаточно найти секцию .rsrc
 * и поискать в ней подпись VS_FIXEDFILEINFO. По всему файлу искать нельзя —
 * за запускателем лежит сжатая сборка на двадцать три мегабайта, и четыре
 * байта подписи могут встретиться там просто так.
 *
 * Тот же разбор есть на стороне сервиса (auth/binver.py): версию читают из
 * файла обе стороны, каждая своими средствами.
 */
final class Pe {

    // Именно long: как int эта подпись отрицательна, и сравнение с беззнаковым
    // значением из файла не сошлось бы никогда.
    private static final long FIXED_FILE_INFO = 0xFEEF04BDL;

    private Pe() {}

    /** Версия вида «0.2.3» или null, если это не наш exe. */
    static String version(File file) {
        RandomAccessFile data = null;
        try {
            data = new RandomAccessFile(file, "r");
            if (data.length() < 0x40 || read16(data, 0) != 0x5A4D) return null;   // MZ
            long pe = read32(data, 0x3C);
            if (pe + 24 > data.length() || read32(data, pe) != 0x00004550) return null;  // PE\0\0

            int sections = read16(data, pe + 6);
            int optional = read16(data, pe + 20);
            long table = pe + 24 + optional;
            for (int index = 0; index < sections; index++) {
                long entry = table + index * 40L;
                if (entry + 40 > data.length()) return null;
                byte[] name = new byte[8];
                data.seek(entry);
                data.readFully(name);
                if (!".rsrc".equals(trim(name))) continue;

                int size = (int) read32(data, entry + 16);
                long offset = read32(data, entry + 20);
                return search(data, offset, size);
            }
            return null;
        } catch (IOException | RuntimeException error) {
            return null;
        } finally {
            if (data != null) try { data.close(); } catch (IOException ignored) {}
        }
    }

    private static String search(RandomAccessFile data, long offset, int size)
            throws IOException {
        if (size <= 0 || offset + size > data.length()) return null;
        byte[] blob = new byte[size];
        data.seek(offset);
        data.readFully(blob);
        for (int at = 0; at + 16 <= blob.length; at += 4) {
            if (le32(blob, at) != FIXED_FILE_INFO) continue;
            // За подписью: версия структуры, потом версия файла двумя
            // половинами — старшая даёт X и Y, младшая Z и номер сборки.
            long high = le32(blob, at + 8);
            long low = le32(blob, at + 12);
            return (high >> 16) + "." + (high & 0xFFFF) + "." + (low >> 16);
        }
        return null;
    }

    private static String trim(byte[] name) {
        int end = 0;
        while (end < name.length && name[end] != 0) end++;
        return new String(name, 0, end, java.nio.charset.Charset.forName("US-ASCII"));
    }

    private static long le32(byte[] data, int at) {
        return (data[at] & 0xFFL) | ((data[at + 1] & 0xFFL) << 8)
                | ((data[at + 2] & 0xFFL) << 16) | ((data[at + 3] & 0xFFL) << 24);
    }

    private static long read32(RandomAccessFile data, long at) throws IOException {
        byte[] four = new byte[4];
        data.seek(at);
        data.readFully(four);
        return le32(four, 0);
    }

    private static int read16(RandomAccessFile data, long at) throws IOException {
        byte[] two = new byte[2];
        data.seek(at);
        data.readFully(two);
        return (two[0] & 0xFF) | ((two[1] & 0xFF) << 8);
    }
}

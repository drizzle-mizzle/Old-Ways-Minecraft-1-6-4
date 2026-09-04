package wfactory.launcher;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * Правка адресов авторизации и скинов в class-файле. Порт tools/patch_jars.py.
 *
 * В 1.6.4 нет authlib: клиент ходит за сессией и текстурами по обычному HTTP на
 * адреса, лежащие в constant pool простыми строками. Константы адресуются по
 * индексу, а не по смещению в байтах, поэтому строку можно заменить на другую
 * длины — достаточно переписать пул целиком, остальное остаётся как есть.
 *
 * Константа опознаётся по хвосту пути, а не по адресу Mojang. Иначе патч
 * сработал бы ровно один раз, и смена адреса требовала бы новой раздачи —
 * а лаунчер как раз должен перепатчивать клиент при смене адреса.
 */
final class ClassPatcher {

    static final class ClassFormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ClassFormatException(String message) {
            super(message);
        }
    }

    /** Хвост пути -> нужна ли база авторизации (иначе база скинов). */
    private static final String[] SUFFIXES = {
            "/game/joinserver.jsp?user=",
            "/game/checkserver.jsp?user=",
            "/MinecraftSkins/%s.png",
            "/MinecraftCloaks/%s.png",
    };
    private static final boolean[] IS_AUTH = { true, true, false, false };

    private static final int MAGIC = 0xcafebabe;

    /** Размер полезной нагрузки записи пула по тегу; 0 — тег неизвестен. */
    private static final int[] FIXED = new int[21];
    static {
        FIXED[3] = 4;  FIXED[4] = 4;  FIXED[5] = 8;  FIXED[6] = 8;
        FIXED[7] = 2;  FIXED[8] = 2;  FIXED[9] = 4;  FIXED[10] = 4;
        FIXED[11] = 4; FIXED[12] = 4; FIXED[15] = 3; FIXED[16] = 2;
        FIXED[17] = 4; FIXED[18] = 4; FIXED[19] = 2; FIXED[20] = 2;
    }

    private static final class Entry {
        final int tag;
        byte[] payload;

        Entry(int tag, byte[] payload) {
            this.tag = tag;
            this.payload = payload;
        }
    }

    private final byte[] data;
    private int pos;
    private int poolEnd;

    private ClassPatcher(byte[] data) {
        this.data = data;
    }

    // --------------------------------------------------------------- разбор

    private int u1() { return data[pos++] & 0xff; }

    private int u2() {
        int v = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
        pos += 2;
        return v;
    }

    private long u4() {
        long v = ((long) (data[pos] & 0xff) << 24) | ((data[pos + 1] & 0xff) << 16)
                | ((data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xff);
        pos += 4;
        return v;
    }

    private List<Entry> readPool() {
        if (data.length < 10) throw new ClassFormatException("файл короче заголовка класса");
        int magic = ((data[0] & 0xff) << 24) | ((data[1] & 0xff) << 16)
                | ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        if (magic != MAGIC) {
            throw new ClassFormatException("не class-файл: нет сигнатуры 0xCAFEBABE");
        }
        pos = 8;
        int count = u2();
        List<Entry> entries = new ArrayList<Entry>(count);
        int index = 1;
        while (index < count) {
            int tag = u1();
            byte[] payload;
            if (tag == 1) {                       // CONSTANT_Utf8
                int length = u2();
                payload = new byte[length];
                System.arraycopy(data, pos, payload, 0, length);
                pos += length;
            } else if (tag < FIXED.length && FIXED[tag] != 0) {
                int size = FIXED[tag];
                payload = new byte[size];
                System.arraycopy(data, pos, payload, 0, size);
                pos += size;
            } else {
                throw new ClassFormatException(
                        "неизвестный тег constant pool " + tag + " на смещении " + (pos - 1));
            }
            entries.add(new Entry(tag, payload));
            index += (tag == 5 || tag == 6) ? 2 : 1;   // long и double занимают две позиции
        }
        poolEnd = pos;
        return entries;
    }

    private void attributes() {
        int count = u2();
        for (int i = 0; i < count; i++) {
            u2();                        // имя атрибута
            // Длина читается отдельной строкой намеренно. В «pos += (int) u4()»
            // левая часть берётся до вызова, и сдвиг на четыре байта, который
            // делает сам u4(), потерялся бы — разбор уехал бы на этих четырёх.
            long length = u4();
            pos += (int) length;         // тело атрибута пропускаем
        }
    }

    /** Проходит файл до конца: ловит порчу структуры, а не только пула. */
    private List<Entry> validate() {
        List<Entry> entries = readPool();
        u2(); u2(); u2();                        // флаги, свой класс, родитель
        int interfaces = u2();
        pos += interfaces * 2;
        for (int kind = 0; kind < 2; kind++) {   // поля, затем методы
            int count = u2();
            for (int i = 0; i < count; i++) {
                u2(); u2(); u2();
                attributes();
            }
        }
        attributes();                            // атрибуты класса
        if (pos != data.length) {
            throw new ClassFormatException(
                    "разбор закончился на " + pos + ", а файл длиной " + data.length);
        }
        return entries;
    }

    // ---------------------------------------------------------------- правка

    /** Новый адрес для константы или null, если она нас не касается. */
    private static String rewrite(String text, String auth, String skin) {
        if (!(text.startsWith("http://") || text.startsWith("https://"))) return null;
        for (int i = 0; i < SUFFIXES.length; i++) {
            if (text.endsWith(SUFFIXES[i])) {
                String base = IS_AUTH[i] ? auth : skin;
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                String replacement = base + SUFFIXES[i];
                return replacement.equals(text) ? null : replacement;
            }
        }
        return null;
    }

    private static String utf8(byte[] bytes) {
        try {
            return new String(bytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Переписывает адреса. Возвращает новый class-файл или null, если менять
     * нечего — тогда вызывающий кладёт исходные байты и не тратит время.
     */
    static byte[] patch(byte[] data, String authBase, String skinBase) {
        ClassPatcher reader = new ClassPatcher(data);
        List<Entry> entries = reader.validate();
        int poolEnd = reader.poolEnd;

        boolean changed = false;
        for (Entry entry : entries) {
            if (entry.tag != 1) continue;
            String text = utf8(entry.payload);
            String replacement = rewrite(text, authBase, skinBase);
            if (replacement != null) {
                Log.info("    %s -> %s", text, replacement);
                entry.payload = bytes(replacement);
                changed = true;
            }
        }
        if (!changed) return null;

        int poolSize = 0;
        for (Entry entry : entries) {
            poolSize += 1 + entry.payload.length + (entry.tag == 1 ? 2 : 0);
        }
        byte[] result = new byte[10 + poolSize + (data.length - poolEnd)];
        System.arraycopy(data, 0, result, 0, 10);          // сигнатура, версия, размер пула
        int at = 10;
        for (Entry entry : entries) {
            result[at++] = (byte) entry.tag;
            if (entry.tag == 1) {
                result[at++] = (byte) (entry.payload.length >> 8);
                result[at++] = (byte) entry.payload.length;
            }
            System.arraycopy(entry.payload, 0, result, at, entry.payload.length);
            at += entry.payload.length;
        }
        System.arraycopy(data, poolEnd, result, at, data.length - poolEnd);

        new ClassPatcher(result).validate();   // круговая проверка: файл всё ещё цел
        return result;
    }

    /** Адреса, на которые class-файл смотрит сейчас — для журнала и сверки. */
    static List<String> findUrls(byte[] data) {
        List<String> found = new ArrayList<String>();
        for (Entry entry : new ClassPatcher(data).readPool()) {
            if (entry.tag != 1) continue;
            String text = utf8(entry.payload);
            if (!(text.startsWith("http://") || text.startsWith("https://"))) continue;
            for (String suffix : SUFFIXES) {
                if (text.endsWith(suffix)) {
                    found.add(text);
                    break;
                }
            }
        }
        return found;
    }
}

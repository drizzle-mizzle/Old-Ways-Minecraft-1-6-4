package oldways.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import oldways.launcher.ClassPatcher;

/**
 * Правит адреса авторизации, скинов и плащей в каждом загружаемом классе.
 *
 * Адрес приходит от лаунчера системным свойством oldways.auth. Без него
 * трансформер молча пропускает всё — игра, запущенная чужим лаунчером,
 * останется с адресами, которые в ней записаны.
 *
 * Разбирать constant pool у каждого класса игры дорого, поэтому сначала
 * идёт грубый поиск подстроки по байтам: адреса встречаются в считанных
 * классах из нескольких тысяч.
 */
public class AuthTransformer implements IClassTransformer {

    /** По этим кускам ищем кандидатов; сравнение идёт по байтам, без разбора. */
    private static final String[] MARKERS = {
            "/MinecraftSkins/", "/MinecraftCloaks/",
            "/game/joinserver.jsp", "s.optifine.net/capes/",
    };

    private final String base = System.getProperty("oldways.auth", "").trim();

    public byte[] transform(String name, String transformedName, byte[] data) {
        if (data == null || base.length() == 0) return data;
        if (!interesting(data)) return data;

        try {
            byte[] patched = ClassPatcher.patch(data, base, base, new ClassPatcher.Note() {
                public void changed(String from, String to) {
                    System.out.println("[Old Ways] " + from + " -> " + to);
                }
            });
            return patched == null ? data : patched;
        } catch (RuntimeException e) {
            // класс не разобрался — отдаём как есть: игра важнее нашей правки
            System.out.println("[Old Ways] не смог поправить " + name + ": " + e);
            return data;
        }
    }

    private static boolean interesting(byte[] data) {
        for (int i = 0; i < MARKERS.length; i++) {
            if (indexOf(data, MARKERS[i]) >= 0) return true;
        }
        return false;
    }

    /** Поиск подстроки в байтах класса: строки пула лежат в UTF-8 как есть. */
    private static int indexOf(byte[] data, String text) {
        int length = text.length();
        outer:
        for (int start = 0; start + length <= data.length; start++) {
            for (int i = 0; i < length; i++) {
                if (data[start + i] != (byte) text.charAt(i)) continue outer;
            }
            return start;
        }
        return -1;
    }
}

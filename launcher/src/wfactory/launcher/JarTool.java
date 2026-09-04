package wfactory.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Пересборка клиентского jar: правка адресов и снятие подписи.
 *
 * Клиент подписан Mojang (META-INF/MOJANGCS.*). После правки классов подпись
 * становится недействительной, и JVM откажется грузить классы, поэтому файлы
 * подписи выбрасываются, а из MANIFEST.MF убираются секции с хешами. Ровно то
 * же делают при установке модов на 1.6.4.
 */
final class JarTool {

    private static final String[] SIGNATURE_SUFFIXES = { ".SF", ".DSA", ".RSA", ".EC" };

    private JarTool() {}

    private static boolean isSignature(String name) {
        String upper = name.toUpperCase();
        if (!upper.startsWith("META-INF/")) return false;
        for (String suffix : SIGNATURE_SUFFIXES) {
            if (upper.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * Убирает из MANIFEST.MF секции с хешами файлов, оставляя главную секцию.
     * Секции разделены пустой строкой; главная несёт Main-Class и должна уцелеть.
     */
    private static byte[] stripManifestDigests(byte[] manifest) {
        int end = manifest.length;
        for (int i = 0; i + 1 < manifest.length; i++) {
            if (manifest[i] == '\n' && manifest[i + 1] == '\n') { end = i; break; }
            if (i + 3 < manifest.length && manifest[i] == '\r' && manifest[i + 1] == '\n'
                    && manifest[i + 2] == '\r' && manifest[i + 3] == '\n') { end = i; break; }
        }
        while (end > 0 && (manifest[end - 1] == '\n' || manifest[end - 1] == '\r')) end--;
        byte[] tail = { '\r', '\n', '\r', '\n' };
        byte[] result = new byte[end + tail.length];
        System.arraycopy(manifest, 0, result, 0, end);
        System.arraycopy(tail, 0, result, end, tail.length);
        return result;
    }

    /**
     * Пишет в dst копию src с адресами authBase/skinBase. Возвращает число
     * классов, в которых что-то поменялось.
     */
    static int patchJar(File src, File dst, String authBase, String skinBase) throws IOException {
        Util.mkdirs(dst.getParentFile());
        File temp = new File(dst.getPath() + ".part");
        ZipFile in = new ZipFile(src);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp));
        int patched = 0;
        int dropped = 0;
        try {
            boolean signed = false;
            Enumeration<? extends ZipEntry> names = in.entries();
            List<ZipEntry> entries = new ArrayList<ZipEntry>();
            while (names.hasMoreElements()) {
                ZipEntry entry = names.nextElement();
                if (isSignature(entry.getName())) signed = true;
                entries.add(entry);
            }

            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (isSignature(name)) {
                    dropped++;
                    continue;
                }
                if (entry.isDirectory()) {
                    out.putNextEntry(new ZipEntry(name));
                    out.closeEntry();
                    continue;
                }

                InputStream stream = in.getInputStream(entry);
                byte[] data;
                try {
                    data = Util.readAll(stream);
                } finally {
                    stream.close();
                }

                if (name.endsWith(".class")) {
                    byte[] replacement;
                    try {
                        replacement = ClassPatcher.patch(data, authBase, skinBase);
                    } catch (RuntimeException e) {
                        // без имени класса такую поломку не найти
                        throw new IOException(name + ": " + Log.describe(e), e);
                    }
                    if (replacement != null) {
                        data = replacement;
                        patched++;
                    }
                } else if (signed && name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    data = stripManifestDigests(data);
                }

                ZipEntry copy = new ZipEntry(name);
                copy.setTime(entry.getTime());
                if (entry.getMethod() == ZipEntry.STORED) {
                    // без сжатия так и остаётся: для STORED zip требует размер и CRC заранее
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(data.length);
                    copy.setCompressedSize(data.length);
                    copy.setCrc(crc.getValue());
                }
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        } finally {
            try { out.close(); } catch (IOException ignored) {}
            in.close();
        }

        if (dst.exists() && !dst.delete()) throw new IOException("не удалось заменить " + dst);
        if (!temp.renameTo(dst)) throw new IOException("не удалось переименовать " + temp);
        if (dropped > 0) Log.info("  подпись снята (%d файлов)", dropped);
        return patched;
    }

    /** Адреса, на которые смотрит готовый jar — для журнала и проверок. */
    static List<String> inspect(File jar) throws IOException {
        List<String> found = new ArrayList<String>();
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> names = zip.entries();
            while (names.hasMoreElements()) {
                ZipEntry entry = names.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                InputStream stream = zip.getInputStream(entry);
                byte[] data;
                try {
                    data = Util.readAll(stream);
                } finally {
                    stream.close();
                }
                try {
                    found.addAll(ClassPatcher.findUrls(data));
                } catch (ClassPatcher.ClassFormatException ignored) {
                    // чужой класс непонятной формы — не наше дело
                }
            }
        } finally {
            zip.close();
        }
        return found;
    }

    /** Распаковывает natives в каталог, пропуская META-INF и подкаталоги. */
    static int extractNatives(File jar, File target) throws IOException {
        Util.mkdirs(target);
        int written = 0;
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> names = zip.entries();
            while (names.hasMoreElements()) {
                ZipEntry entry = names.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.toUpperCase().startsWith("META-INF/")) continue;
                // библиотеки лежат в корне архива; вложенные пути игре не нужны
                String flat = name.substring(name.lastIndexOf('/') + 1);
                File file = new File(target, flat);
                if (file.isFile() && file.length() == entry.getSize()) continue;
                InputStream stream = zip.getInputStream(entry);
                FileOutputStream out = new FileOutputStream(file);
                try {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = stream.read(buffer)) > 0) out.write(buffer, 0, read);
                } finally {
                    out.close();
                    stream.close();
                }
                written++;
            }
        } finally {
            zip.close();
        }
        return written;
    }
}

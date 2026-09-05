package oldways.launcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Манифест раздачи: что качать, чем это проверять и как потом запускать.
 *
 * Правила Mojang (rules, natives, классификаторы) раскрыты на сервере
 * (tools/build_dist.py), поэтому здесь остаются готовые списки под текущую ОС.
 */
final class Manifest {

    static final class FileEntry {
        final String path;
        final String sha1;
        final long size;

        FileEntry(String path, String sha1, long size) {
            this.path = path;
            this.sha1 = sha1;
            this.size = size;
        }
    }

    final String name;
    final String id;
    final String built;
    final String mainClass;
    final String arguments;
    final String assets;
    final String client;
    final List<String> classpath;
    final List<String> natives;
    final List<FileEntry> files;
    final long totalBytes;

    private Manifest(Map<String, Object> root) {
        long format = Json.num(root, "format");
        if (format != 1) {
            throw new IllegalArgumentException(
                    "манифест версии " + format + ", а лаунчер понимает 1 — обновите лаунчер");
        }
        name = Json.str(root, "name", "Old Ways");
        id = Json.str(root, "id");
        built = Json.str(root, "built", "");
        mainClass = Json.str(root, "mainClass");
        arguments = Json.str(root, "arguments");
        assets = Json.str(root, "assets", "legacy");
        client = Json.str(root, "client");
        classpath = strings(Json.map(root.get("classpath")).get(Util.OS));
        natives = strings(Json.map(root.get("natives")).get(Util.OS));

        files = new ArrayList<FileEntry>();
        long bytes = 0;
        for (Object item : Json.list(root.get("files"))) {
            Map<String, Object> entry = Json.map(item);
            if (!forThisOs(entry.get("os"))) continue;
            long size = Json.num(entry, "size");
            files.add(new FileEntry(Json.str(entry, "path"), Json.str(entry, "sha1"), size));
            bytes += size;
        }
        totalBytes = bytes;

        if (classpath.isEmpty()) {
            throw new IllegalArgumentException("в раздаче нет сборки под " + Util.OS);
        }
    }

    /** Файл без пометки нужен всем; с пометкой — только названным системам. */
    private static boolean forThisOs(Object marker) {
        if (marker == null) return true;
        for (Object os : Json.list(marker)) {
            if (Util.OS.equals(String.valueOf(os))) return true;
        }
        return false;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<String>();
        for (Object item : Json.list(value)) result.add(String.valueOf(item));
        return result;
    }

    static Manifest parse(String text) {
        return new Manifest(Json.map(Json.parse(text)));
    }

    static Manifest fetch(String base) throws IOException {
        String text;
        try {
            text = Http.get(base + "/dist/manifest.json");
        } catch (Http.HttpError e) {
            if (e.code == 503) throw new IOException("на сервере ещё не собрана раздача");
            throw new IOException("манифест не отдан: " + e.detail());
        }
        try {
            return parse(text);
        } catch (RuntimeException e) {
            throw new IOException("манифест не разобрался: " + Log.describe(e));
        }
    }

    String fileUrl(String base, String path) {
        return base + "/dist/files/" + path;
    }
}

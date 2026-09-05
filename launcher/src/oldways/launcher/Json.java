package oldways.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный JSON: разбор манифеста и ответов сервиса, сборка тел запросов.
 *
 * Своя реализация вместо библиотеки — чтобы лаунчер оставался одним jar-ником
 * без зависимостей. Чем меньше в раздаче движущихся частей, тем меньше поводов
 * ей сломаться на чужой машине.
 *
 * Значения отображаются так: объект -> Map, массив -> List, строка -> String,
 * число -> Double, true/false -> Boolean, null -> null.
 */
final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object value = p.value();
        p.ws();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("лишний текст после JSON на позиции " + p.pos);
        }
        return value;
    }

    // ------------------------------------------------------------- разбор

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default:  return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        expect('{');
        ws();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            map.put(key, value());
            ws();
            char c = next();
            if (c == '}') return map;
            if (c != ',') throw err("ждали ',' или '}'");
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<Object>();
        expect('[');
        ws();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw err("ждали ',' или ']'");
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char esc = next();
            switch (esc) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw err("неизвестная escape-последовательность \\" + esc);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) pos++;
        if (start == pos) throw err("не похоже на значение");
        return Double.valueOf(src.substring(start, pos));
    }

    private void literal(String word) {
        if (!src.startsWith(word, pos)) throw err("ждали " + word);
        pos += word.length();
    }

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private char peek() {
        if (pos >= src.length()) throw err("текст оборвался");
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw err("ждали '" + c + "'");
    }

    private IllegalArgumentException err(String message) {
        return new IllegalArgumentException(message + " (позиция " + pos + ")");
    }

    // ------------------------------------------------------------- запись

    /** Собирает объект из пар ключ-значение: write("username", user, "password", pass). */
    static String write(Object... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append(',');
            quote(sb, String.valueOf(pairs[i]));
            sb.append(':');
            Object v = pairs[i + 1];
            if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else if (v == null) sb.append("null");
            else quote(sb, String.valueOf(v));
        }
        return sb.append('}').toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------- доступ к полям

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("ждали объект JSON");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (value == null) return new ArrayList<Object>();
        if (!(value instanceof List)) throw new IllegalArgumentException("ждали массив JSON");
        return (List<Object>) value;
    }

    static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) throw new IllegalArgumentException("нет поля " + key);
        return String.valueOf(v);
    }

    static String str(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    static long num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof Number)) throw new IllegalArgumentException("нет числа в поле " + key);
        return ((Number) v).longValue();
    }

    static boolean bool(Map<String, Object> map, String key) {
        return Boolean.TRUE.equals(map.get(key));
    }
}

package io.onury.dtrexp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON reader — just enough for vectors.json (objects,
 * arrays, strings with escapes, numbers, booleans, null). No dependencies.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != text.length()) {
            throw new IllegalArgumentException("trailing JSON content at " + j.i);
        }
        return v;
    }

    private Object value() {
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // '{'
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at " + (i - 1));
            }
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++; // '['
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at " + (i - 1));
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s, i, i + 4, 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("bad escape '\\" + e + "' at " + (i - 1));
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String tok = s.substring(start, i);
        if (tok.indexOf('.') >= 0 || tok.indexOf('e') >= 0 || tok.indexOf('E') >= 0) {
            return Double.parseDouble(tok);
        }
        return Long.parseLong(tok);
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, i)) {
            throw new IllegalArgumentException("bad literal at " + i);
        }
        i += word.length();
        return value;
    }

    private void expect(char c) {
        if (s.charAt(i) != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + i);
        }
        i++;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}

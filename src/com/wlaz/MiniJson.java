package com.wlaz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 极简 JSON：解析成 Map/List/String/Double/Boolean/null，并提供字符串转义 */
public class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
        this.i = 0;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        return v;
    }

    /** 取嵌套字段，path 形如 "ret.code"；不存在返回 null */
    public static Object get(Object root, String path) {
        Object cur = root;
        for (String k : path.split("\\.")) {
            if (cur instanceof Map) {
                cur = ((Map<?, ?>) cur).get(k);
            } else if (cur instanceof List && k.matches("\\d+")) {
                List<?> l = (List<?>) cur;
                int idx = Integer.parseInt(k);
                cur = idx < l.size() ? l.get(idx) : null;
            } else {
                return null;
            }
            if (cur == null) return null;
        }
        return cur;
    }

    public static String str(Object root, String path) {
        Object v = get(root, path);
        return v == null ? "" : String.valueOf(v);
    }

    public static int integer(Object root, String path, int def) {
        Object v = get(root, path);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return def;
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    public static List<Object> list(Object root, String path) {
        Object v = get(root, path);
        ArrayList<Object> out = new ArrayList<Object>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) out.add(o);
        }
        return out;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"') return string();
        if (c == 't') { i += 4; return Boolean.TRUE; }
        if (c == 'f') { i += 5; return Boolean.FALSE; }
        if (c == 'n') { i += 4; return null; }
        return number();
    }

    private Map<String, Object> obj() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        i++; // {
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (i < s.length()) {
            ws();
            String k = string();
            ws();
            if (i < s.length() && s.charAt(i) == ':') i++;
            ws();
            Object v = value();
            m.put(k, v);
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == '}') { i++; break; }
            break;
        }
        return m;
    }

    private List<Object> arr() {
        ArrayList<Object> l = new ArrayList<Object>();
        i++; // [
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (i < s.length()) {
            ws();
            l.add(value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == ']') { i++; break; }
            break;
        }
        return l;
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        if (i < s.length() && s.charAt(i) == '"') i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private Object number() {
        int st = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
            else break;
        }
        String t = s.substring(st, i);
        try {
            return Double.valueOf(t);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 输出 ----------------

    public static String quote(String v) {
        if (v == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** 序列化 Map/List/String/Number/Boolean/null */
    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        writeTo(b, o);
        return b.toString();
    }

    private static void writeTo(StringBuilder b, Object o) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(quote(String.valueOf(e.getKey()))).append(':');
                writeTo(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object v : (List<?>) o) {
                if (!first) b.append(',');
                first = false;
                writeTo(b, v);
            }
            b.append(']');
        } else if (o instanceof Boolean) {
            b.append(o.toString());
        } else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) b.append((long) d);
            else b.append(d);
        } else {
            b.append(quote(String.valueOf(o)));
        }
    }
}

package dev.chronotone.core.log;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic JSON writer: object keys are sorted, whitespace is absent and numbers have a single
 * canonical spelling. Two structurally equal values always produce identical bytes, which is what
 * lets a Chronotone render be content-addressed.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeTo(sb, value);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b.booleanValue());
            case Byte b -> sb.append(b.longValue());
            case Short s -> sb.append(s.longValue());
            case Integer i -> sb.append(i.longValue());
            case Long l -> sb.append(l.longValue());
            case BigInteger bi -> sb.append(bi);
            case Float f -> writeDouble(sb, f.doubleValue());
            case Double d -> writeDouble(sb, d.doubleValue());
            case BigDecimal bd -> sb.append(bd.stripTrailingZeros().toPlainString());
            case Map<?, ?> map -> writeObject(sb, map);
            case List<?> list -> writeArray(sb, list);
            case Iterable<?> it -> writeIterable(sb, it);
            default -> writeString(sb, value.toString());
        }
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("Canonical JSON cannot encode " + d);
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(entry.getKey() == null ? "null" : String.valueOf(entry.getKey()), entry.getValue());
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, entry.getKey());
            sb.append(':');
            writeTo(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeTo(sb, element);
        }
        sb.append(']');
    }

    private static void writeIterable(StringBuilder sb, Iterable<?> iterable) {
        sb.append('[');
        boolean first = true;
        for (Object element : iterable) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeTo(sb, element);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u").append(Character.forDigit((c >> 12) & 0xF, 16))
                                .append(Character.forDigit((c >> 8) & 0xF, 16))
                                .append(Character.forDigit((c >> 4) & 0xF, 16))
                                .append(Character.forDigit(c & 0xF, 16));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}

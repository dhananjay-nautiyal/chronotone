package dev.chronotone.core.log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader. Round-trips exactly with {@link CanonicalJson}: integers
 * decode to {@link Long}, decimals to {@link Double}, objects to insertion-ordered maps and arrays
 * to lists. The log format is ours, so a full JSON library would be dead weight.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Trailing content at offset " + parser.offset());
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a JSON object at the top level");
        }
        return (Map<String, Object>) map;
    }

    private static final class Parser {

        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        int offset() {
            return index;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw fail("Unexpected end of input");
            }
            return switch (text.charAt(index)) {
                case '{' -> parseObjectValue();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObjectValue() {
            Map<String, Object> out = new LinkedHashMap<>();
            index++; // '{'
            skipWhitespace();
            if (!atEnd() && text.charAt(index) == '}') {
                index++;
                return out;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                out.put(key, parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw fail("Unterminated object");
                }
                char c = text.charAt(index++);
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return out;
                }
                throw fail("Expected ',' or '}' but found '" + c + "'");
            }
        }

        private List<Object> parseArray() {
            List<Object> out = new ArrayList<>();
            index++; // '['
            skipWhitespace();
            if (!atEnd() && text.charAt(index) == ']') {
                index++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw fail("Unterminated array");
                }
                char c = text.charAt(index++);
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    return out;
                }
                throw fail("Expected ',' or ']' but found '" + c + "'");
            }
        }

        private String parseString() {
            skipWhitespace();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw fail("Unterminated string");
                }
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw fail("Unterminated escape sequence");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw fail("Truncated unicode escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw fail("Invalid escape '\\" + escaped + "'");
                }
            }
        }

        private Object parseNumber() {
            int start = index;
            if (!atEnd() && (text.charAt(index) == '-' || text.charAt(index) == '+')) {
                index++;
            }
            boolean decimal = false;
            while (!atEnd()) {
                char c = text.charAt(index);
                if (c >= '0' && c <= '9') {
                    index++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    decimal |= (c == '.' || c == 'e' || c == 'E');
                    index++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, index);
            if (raw.isEmpty()) {
                throw fail("Expected a number");
            }
            try {
                return decimal ? (Object) Double.valueOf(raw) : (Object) Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw fail("Invalid number '" + raw + "'");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw fail("Expected '" + literal + "'");
            }
            index += literal.length();
            return value;
        }

        private void expect(char c) {
            if (atEnd() || text.charAt(index) != c) {
                throw fail("Expected '" + c + "'");
            }
            index++;
        }

        private IllegalArgumentException fail(String message) {
            return new IllegalArgumentException(message + " at offset " + index);
        }
    }
}

package dev.chronotone.player

/**
 * A small JSON reader.
 *
 * The player reads exactly one document — the station manifest the Java engine writes — so this is
 * a hundred lines instead of a dependency. Numbers keep their literal text: a 64-bit seed or a
 * sample offset must never round-trip through a `Double`.
 */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val literal: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

class JsonException(message: String, offset: Int) : IllegalArgumentException("$message (offset $offset)")

object Json {

    fun parse(text: String): JsonValue = Reader(text).document()

    private class Reader(private val text: String) {
        private var at = 0

        fun document(): JsonValue {
            val value = value()
            whitespace()
            if (at != text.length) fail("trailing input")
            return value
        }

        private fun value(): JsonValue {
            whitespace()
            if (at >= text.length) fail("unexpected end of input")
            return when (val c = text[at]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> JsonValue.Str(stringValue())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) numberValue() else fail("unexpected '$c'")
            }
        }

        private fun objectValue(): JsonValue.Obj {
            at++ // '{'
            val fields = LinkedHashMap<String, JsonValue>()
            whitespace()
            if (peek() == '}') { at++; return JsonValue.Obj(fields) }
            while (true) {
                whitespace()
                if (peek() != '"') fail("expected a field name")
                val name = stringValue()
                whitespace()
                if (peek() != ':') fail("expected ':' after field name")
                at++
                fields[name] = value()
                whitespace()
                when (peek()) {
                    ',' -> at++
                    '}' -> { at++; return JsonValue.Obj(fields) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arrayValue(): JsonValue.Arr {
            at++ // '['
            val items = ArrayList<JsonValue>()
            whitespace()
            if (peek() == ']') { at++; return JsonValue.Arr(items) }
            while (true) {
                items.add(value())
                whitespace()
                when (peek()) {
                    ',' -> at++
                    ']' -> { at++; return JsonValue.Arr(items) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun stringValue(): String {
            at++ // '"'
            val out = StringBuilder()
            while (true) {
                if (at >= text.length) fail("unterminated string")
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (at >= text.length) fail("unterminated escape")
                        when (val e = text[at++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) fail("truncated \\u escape")
                                val code = text.substring(at, at + 4).toIntOrNull(16)
                                    ?: fail("bad \\u escape")
                                at += 4
                                out.append(code.toChar())
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> {
                        if (c < ' ') fail("unescaped control character")
                        out.append(c)
                    }
                }
            }
        }

        private fun numberValue(): JsonValue.Num {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && text[at].isDigit()) at++
            if (at < text.length && text[at] == '.') {
                at++
                while (at < text.length && text[at].isDigit()) at++
            }
            if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                at++
                if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
                while (at < text.length && text[at].isDigit()) at++
            }
            if (at == start) fail("expected a number")
            return JsonValue.Num(text.substring(start, at))
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, at)) fail("expected '$word'")
            at += word.length
            return value
        }

        private fun whitespace() {
            while (at < text.length && (text[at] == ' ' || text[at] == '\n' || text[at] == '\r' || text[at] == '\t')) at++
        }

        private fun peek(): Char = if (at < text.length) text[at] else fail("unexpected end of input")

        private fun fail(message: String): Nothing = throw JsonException(message, at)
    }
}

fun JsonValue.asObject(): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw JsonException("expected an object, got ${describe()}", 0)

fun JsonValue.asArray(): JsonValue.Arr =
    this as? JsonValue.Arr ?: throw JsonException("expected an array, got ${describe()}", 0)

fun JsonValue.asString(): String =
    (this as? JsonValue.Str)?.value ?: throw JsonException("expected a string, got ${describe()}", 0)

fun JsonValue.asLong(): Long = when (this) {
    is JsonValue.Num -> literal.toLongOrNull()
        ?: throw JsonException("'$literal' is not a 64-bit integer", 0)
    else -> throw JsonException("expected a number, got ${describe()}", 0)
}

fun JsonValue.asInt(): Int {
    val value = asLong()
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw JsonException("$value does not fit in an int", 0)
    return value.toInt()
}

fun JsonValue.asDouble(): Double = when (this) {
    is JsonValue.Num -> literal.toDoubleOrNull() ?: throw JsonException("'$literal' is not a number", 0)
    else -> throw JsonException("expected a number, got ${describe()}", 0)
}

fun JsonValue.asBoolean(): Boolean =
    (this as? JsonValue.Bool)?.value ?: throw JsonException("expected a boolean, got ${describe()}", 0)

fun JsonValue.Obj.field(name: String): JsonValue =
    fields[name] ?: throw JsonException("missing field '$name'", 0)

fun JsonValue.Obj.string(name: String): String = field(name).asString()

fun JsonValue.Obj.long(name: String): Long = field(name).asLong()

fun JsonValue.Obj.int(name: String): Int = field(name).asInt()

fun JsonValue.Obj.double(name: String): Double = field(name).asDouble()

private fun JsonValue.describe(): String = when (this) {
    is JsonValue.Obj -> "an object"
    is JsonValue.Arr -> "an array"
    is JsonValue.Str -> "a string"
    is JsonValue.Num -> "a number"
    is JsonValue.Bool -> "a boolean"
    JsonValue.Null -> "null"
}

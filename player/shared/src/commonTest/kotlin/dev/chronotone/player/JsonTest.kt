package dev.chronotone.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {

    @Test
    fun readsTheShapeOfAStationManifest() {
        val value = Json.parse(
            """{"slots":30,"slot_samples":[0,132300],"nested":{"a":[true,false,null]},"text":"hi"}"""
        ).asObject()

        assertEquals(30, value.int("slots"))
        assertEquals(listOf(0, 132300), value.field("slot_samples").asArray().items.map { it.asInt() })

        val nested = value.field("nested").asObject().field("a").asArray()
        assertEquals(3, nested.items.size)
        assertEquals(true, nested.items[0].asBoolean())
        assertEquals(JsonValue.Null, nested.items[2])
        assertEquals("hi", value.string("text"))
    }

    @Test
    fun keepsEveryDigitOfALong() {
        // A seed or a sample offset must not go through a double on its way here.
        val value = Json.parse("""{"n":9223372036854775807}""").asObject()
        assertEquals(Long.MAX_VALUE, value.long("n"))
    }

    @Test
    fun understandsEscapes() {
        val value = Json.parse("""{"s":"a\"b\\c\nd\u0041"}""").asObject()
        assertEquals("a\"b\\c\ndA", value.string("s"))
    }

    @Test
    fun rejectsTrailingInput() {
        assertFailsWith<JsonException> { Json.parse("""{"a":1} {"b":2}""") }
    }

    @Test
    fun rejectsUnterminatedStrings() {
        assertFailsWith<JsonException> { Json.parse("""{"a":"unterminated}""") }
    }

    @Test
    fun namesTheMissingField() {
        val value = Json.parse("""{"a":1}""").asObject()
        val failure = assertFailsWith<JsonException> { value.string("wav_sha256") }
        assertEquals(true, failure.message!!.contains("wav_sha256"))
    }
}

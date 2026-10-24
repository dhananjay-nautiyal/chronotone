package dev.chronotone.core.log;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void decodesIntegerWithinTheLongRange() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(-7L, Json.parse("-7"));
    }

    @Test
    void decodesIntegerBeyondTheLongRange() {
        // CanonicalJson writes BigInteger and large-double payloads as plain digits; the reader
        // must decode them or a single such event bricks the log it was written to.
        Object value = Json.parse("99999999999999999999");
        assertEquals(new BigInteger("99999999999999999999"), value);
        assertEquals(new BigInteger("-99999999999999999999"), Json.parse("-99999999999999999999"));
    }

    @Test
    void recanonicalisesEveryNumberTheWriterCanEmit() {
        // The property verify() depends on: parse(write(v)) must re-serialise to identical bytes.
        var values = Map.<String, Object>ofEntries(
                Map.entry("long", 42L),
                Map.entry("bigInteger", new BigInteger("99999999999999999999")),
                Map.entry("hugeDouble", 1.0e20),     // written as 100000000000000000000
                Map.entry("fraction", 0.1),
                Map.entry("negative", -2.5));
        String json = CanonicalJson.write(values);
        assertEquals(json, CanonicalJson.write(Json.parse(json)), json);
    }

    @Test
    void parsesBigNumbersInsidePayloads() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("big", new BigInteger("123456789012345678901234567890"));
        String json = CanonicalJson.write(payload);
        Object parsed = Json.parse(json);
        assertEquals(json, CanonicalJson.write(parsed));
        assertTrue(parsed instanceof Map<?, ?> map && map.get("big") instanceof BigInteger);
    }

    @Test
    void stillRejectsNumbersThatAreNotNumbers() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1.2.3]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[--5]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1e]"));
    }
}

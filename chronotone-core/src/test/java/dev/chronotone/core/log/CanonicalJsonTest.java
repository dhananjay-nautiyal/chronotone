package dev.chronotone.core.log;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalJsonTest {

    @Test
    void sortsKeysRecursively() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", Map.of("y", 5, "x", true));
        value.put("a", 1);
        assertEquals("{\"a\":1,\"b\":{\"x\":true,\"y\":5}}", CanonicalJson.write(value));
    }

    @Test
    void writesNumbersWithOneSpelling() {
        assertEquals("0.1", CanonicalJson.write(0.1));
        assertEquals("1", CanonicalJson.write(1.0));
        assertEquals("-2.5", CanonicalJson.write(-2.5));
        assertEquals("10000000000", CanonicalJson.write(1.0e10));
        assertEquals("42", CanonicalJson.write(42L));
    }

    @Test
    void writesNullAndEscapesStrings() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("nothing", null);
        value.put("text", "a\"b\\c\nd\te");
        assertEquals("{\"nothing\":null,\"text\":\"a\\\"b\\\\c\\nd\\te\"}", CanonicalJson.write(value));
    }

    @Test
    void roundTripsThroughTheJsonReader() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", "git");
        value.put("count", 7L);
        value.put("ratio", 0.25);
        value.put("flag", true);
        value.put("list", List.of(1L, 2L, 3L));
        value.put("nested", Map.of("k", "v"));

        String json = CanonicalJson.write(value);
        assertEquals(json, CanonicalJson.write(Json.parse(json)));
    }

    @Test
    void refusesNonFiniteNumbers() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Double.POSITIVE_INFINITY));
    }

    @Test
    void jsonReaderRejectsTrailingGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} trailing"));
    }
}

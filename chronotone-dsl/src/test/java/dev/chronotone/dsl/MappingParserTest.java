package dev.chronotone.dsl;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingParserTest {

    @Test
    void parsesTheDefaultMapping() {
        MappingSpec spec = new MappingParser().parse(DefaultMapping.YAML);

        assertEquals("v1.0", spec.version());
        assertEquals(Duration.ofMinutes(1), spec.grid());
        assertEquals("chronotone", spec.seedSalt());
        assertEquals(Set.of("tempo", "energy", "brightness", "hatDensity", "reverbWet"), spec.paramNames());

        ParamSpec tempo = spec.params().get("tempo");
        assertEquals(Duration.ofMinutes(10), tempo.window());
        assertEquals(Aggregate.Kind.COUNT, tempo.aggregate().kind());
        assertEquals(72.0, tempo.outMin());
        assertEquals(132.0, tempo.outMax());

        ParamSpec energy = spec.params().get("energy");
        assertEquals(Aggregate.Kind.SUM, energy.aggregate().kind());
        assertEquals("lines_changed", energy.aggregate().field());
    }

    @Test
    void rejectsUnknownParamsBecauseASilentTypoIsTheWorstFailure() {
        MappingException error = assertThrows(MappingException.class, () -> new MappingParser().parse("""
                mapping: v1.0
                params:
                  loudness:
                    events: { source: git }
                    window: 5m
                    aggregate: count
                    in: [0, 1]
                    out: [0, 1]
                """));
        assertTrue(error.getMessage().contains("loudness"));
    }

    @Test
    void rejectsADescendingInputRange() {
        assertThrows(MappingException.class, () -> new MappingParser().parse("""
                mapping: v1.0
                params:
                  tempo:
                    events: { source: git }
                    window: 5m
                    aggregate: { kind: count }
                    in: [8, 0]
                    out: [72, 132]
                """));
    }

    @Test
    void rejectsAnAggregateWithoutItsField() {
        assertThrows(MappingException.class, () -> new MappingParser().parse("""
                mapping: v1.0
                params:
                  energy:
                    events: { source: git }
                    window: 5m
                    aggregate: { kind: sum }
                    in: [0, 1]
                    out: [0, 1]
                """));
    }

    @Test
    void defaultsGridAndSalt() {
        MappingSpec spec = new MappingParser().parse("""
                params:
                  tempo:
                    events: { source: git }
                    aggregate: count
                    window: 5m
                    in: [0, 4]
                    out: [80, 120]
                """);
        assertEquals("v0", spec.version());
        assertEquals(Duration.ofMinutes(1), spec.grid());
        assertEquals("chronotone", spec.seedSalt());
    }

    @Test
    void parsesCompactDurations() {
        assertEquals(Duration.ofSeconds(30), MappingParser.duration("30s"));
        assertEquals(Duration.ofMinutes(15), MappingParser.duration("15m"));
        assertEquals(Duration.ofHours(2), MappingParser.duration("2h"));
        assertEquals(Duration.ofDays(1), MappingParser.duration("1d"));
        assertThrows(MappingException.class, () -> MappingParser.duration("5 fortnights"));
    }

    @Test
    void rejectsBrokenYaml() {
        assertThrows(MappingException.class, () -> new MappingParser().parse("params: [1, 2, 3]"));
    }
}

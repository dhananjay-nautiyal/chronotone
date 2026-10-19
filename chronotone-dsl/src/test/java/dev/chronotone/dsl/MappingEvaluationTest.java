package dev.chronotone.dsl;

import dev.chronotone.core.log.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingEvaluationTest {

    private static final Instant T0 = Instant.parse("2026-10-19T21:00:00Z");

    private static final List<Event> COMMITS = List.of(
            Event.of("git", "commit", T0, Map.of("lines_changed", 10)),
            Event.of("git", "commit", T0.plusSeconds(60), Map.of("lines_changed", 30)),
            Event.of("git", "commit", T0.plusSeconds(120), Map.of("lines_changed", 20)));

    @Test
    void aggregatesRawValues() {
        assertEquals(3.0, Aggregate.count().apply(COMMITS));
        assertEquals(60.0, new Aggregate(Aggregate.Kind.SUM, "lines_changed").apply(COMMITS));
        assertEquals(20.0, new Aggregate(Aggregate.Kind.MEAN, "lines_changed").apply(COMMITS));
        assertEquals(30.0, new Aggregate(Aggregate.Kind.MAX, "lines_changed").apply(COMMITS));
        assertEquals(10.0, new Aggregate(Aggregate.Kind.MIN, "lines_changed").apply(COMMITS));
        assertEquals(20.0, new Aggregate(Aggregate.Kind.LAST, "lines_changed").apply(COMMITS));
    }

    @Test
    void anEmptyWindowIsNaNNotZero() {
        assertTrue(Double.isNaN(new Aggregate(Aggregate.Kind.MEAN, "lines_changed").apply(List.of())));
        assertEquals(0.0, Aggregate.count().apply(List.of()));
    }

    @Test
    void mapsAggregatesIntoTheDeclaredOutputRange() {
        MappingSpec spec = new MappingParser().parse(DefaultMapping.YAML);

        // Three commits inside a ten minute window -> count 3 through in [0,8] into out [72,132].
        Map<String, Double> values = spec.evaluate(T0.plusSeconds(120), COMMITS);
        assertEquals(94.5, values.get("tempo"), 1e-9);

        // 60 lines changed through in [0,600] into out [0.15,1.0].
        assertEquals(0.235, values.get("energy"), 1e-9);
    }

    @Test
    void fallsBackToWhenEmptyForMissingSources() {
        MappingSpec spec = new MappingParser().parse(DefaultMapping.YAML);
        Map<String, Double> values = spec.evaluate(T0, COMMITS);

        // No weather events: whenEmpty 0.6 of out [1.0, 0.25].
        assertEquals(0.55, values.get("brightness"), 1e-9);
        // No steps: whenEmpty 0.2 of out [0.1, 0.6].
        assertEquals(0.2, values.get("reverbWet"), 1e-9);
    }

    @Test
    void onlyCountsEventsFromItsOwnSource() {
        MappingSpec spec = new MappingParser().parse(DefaultMapping.YAML);
        List<Event> mixed = List.of(
                Event.of("git", "commit", T0, Map.of("lines_changed", 10)),
                Event.of("music", "scrobble", T0, Map.of("track", "Awake")),
                Event.of("music", "scrobble", T0, Map.of("track", "Xtal")),
                Event.of("music", "now_playing", T0, Map.of("track", "ignored")),
                Event.of("weather", "sample", T0, Map.of("cloud_cover", 0.2)));

        Map<String, Double> values = spec.evaluate(T0.plusSeconds(120), mixed);

        // One commit through in [0,8] into out [72,132].
        assertEquals(79.5, values.get("tempo"), 1e-9);
        // Two scrobbles (the now_playing event has the wrong type), in [0,4] into out [0,0.9].
        assertEquals(0.45, values.get("hatDensity"), 1e-9);
        // One weather sample, mean cloud cover 0.2 through the inverted out [1.0, 0.25].
        assertEquals(0.85, values.get("brightness"), 1e-9);
    }

    @Test
    void ignoresEventsOutsideTheWindow() {
        MappingSpec spec = new MappingParser().parse(DefaultMapping.YAML);
        List<Event> spread = List.of(
                Event.of("git", "commit", T0.minusSeconds(3600), Map.of("lines_changed", 999)),
                Event.of("git", "commit", T0, Map.of("lines_changed", 10)));

        assertEquals(72.0 + (1.0 / 8.0) * 60.0, spec.evaluate(T0, spread).get("tempo"), 1e-9);
    }

    @Test
    void countReportsZeroForAnEmptyWindowAndClampsIntoOut() {
        MappingSpec spec = new MappingParser().parse(DefaultMapping.YAML);
        Map<String, Double> values = spec.evaluate(T0, List.of());

        // Counting params treat an empty window as the measurement "zero", so they clamp to outMin.
        assertEquals(72.0, values.get("tempo"), 1e-9);
        assertEquals(0.0, values.get("hatDensity"), 1e-9);
        // Field-reading params have genuinely missing data, so whenEmpty applies instead.
        assertEquals(0.55, values.get("brightness"), 1e-9);
        assertEquals(0.2, values.get("reverbWet"), 1e-9);
        assertEquals(0.235, values.get("energy"), 1e-9);
    }

    @Test
    void busierWindowsNeverLowerTheTempo() {
        MappingSpec spec = new MappingParser().parse(DefaultMapping.YAML);
        double quiet = spec.evaluate(T0, List.of()).get("tempo");
        double busy = spec.evaluate(T0.plusSeconds(120), COMMITS).get("tempo");
        assertTrue(busy > quiet, "expected " + busy + " > " + quiet);
    }
}

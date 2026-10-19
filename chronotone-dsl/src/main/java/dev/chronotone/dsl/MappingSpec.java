package dev.chronotone.dsl;

import dev.chronotone.core.log.Event;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A parsed, validated mapping document: life events in, musical parameters out.
 *
 * <p>Mapping evaluation requires a time-sorted event list (ascending); the log always produces one.
 */
public record MappingSpec(String version, Duration grid, String seedSalt, Map<String, ParamSpec> params) {

    public MappingSpec {
        version = Objects.requireNonNull(version, "version");
        grid = Objects.requireNonNull(grid, "grid");
        seedSalt = Objects.requireNonNull(seedSalt, "seedSalt");
        params = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(params, "params")));
        if (grid.isZero() || grid.isNegative()) {
            throw new MappingException("grid must be positive");
        }
        if (params.isEmpty()) {
            throw new MappingException("mapping declares no params");
        }
    }

    public Set<String> paramNames() {
        return params.keySet();
    }

    /** Evaluates every param for one slot. One slot in the timeline is one bar of music. */
    public Map<String, Double> evaluate(Instant slot, List<Event> sortedEvents) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (ParamSpec param : params.values()) {
            values.put(param.name(), param.value(window(param, slot, sortedEvents)));
        }
        return values;
    }

    /** Events in {@code (slot - window, slot]} that match the param's selector. */
    static List<Event> window(ParamSpec param, Instant slot, List<Event> sortedEvents) {
        int from = lowerBound(sortedEvents, slot.minus(param.window()));
        int to = lowerBound(sortedEvents, slot.plusMillis(1));
        List<Event> matched = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            Event event = sortedEvents.get(i);
            if (param.selector().matches(event)) {
                matched.add(event);
            }
        }
        return matched;
    }

    static int lowerBound(List<Event> sortedEvents, Instant timestamp) {
        int low = 0;
        int high = sortedEvents.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sortedEvents.get(mid).timestamp().isBefore(timestamp)) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}

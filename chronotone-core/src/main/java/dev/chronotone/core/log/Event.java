package dev.chronotone.core.log;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * One immutable, canonically-serialisable moment of a life: a source, a type, a timestamp and a
 * small payload. Timestamps are truncated to milliseconds so that two events that "happened at the
 * same time" really do serialise identically.
 */
public record Event(String source, String type, Instant timestamp, Map<String, Object> payload) {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public Event {
        source = requireNonBlank(source, "source");
        type = requireNonBlank(type, "type");
        timestamp = Objects.requireNonNull(timestamp, "timestamp").truncatedTo(ChronoUnit.MILLIS);
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(payload, "payload")));
    }

    public static Event of(String source, String type, Instant timestamp) {
        return new Event(source, type, timestamp, Map.of());
    }

    public static Event of(String source, String type, Instant timestamp, Map<String, Object> payload) {
        return new Event(source, type, timestamp, payload);
    }

    /** Rebuilds an event from the canonical map produced by {@link #canonicalMap()}. */
    public static Event fromMap(Map<String, Object> map) {
        Object timestamp = map.get("ts");
        if (!(timestamp instanceof String ts)) {
            throw new IllegalArgumentException("event is missing a string 'ts' field");
        }
        Object payload = map.get("payload");
        Map<String, Object> payloadMap = payload instanceof Map<?, ?> m ? stringifyKeys(m) : Map.of();
        return new Event((String) map.get("source"), (String) map.get("type"), Instant.parse(ts), payloadMap);
    }

    public static String formatTimestamp(Instant instant) {
        return TS.format(instant);
    }

    /** The exact structure that gets hashed: sorted keys, millisecond timestamps, no decimals drift. */
    public Map<String, Object> canonicalMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("payload", payload);
        root.put("source", source);
        root.put("ts", TS.format(timestamp));
        root.put("type", type);
        return root;
    }

    public String canonical() {
        return CanonicalJson.write(canonicalMap());
    }

    public long epochMillis() {
        return timestamp.toEpochMilli();
    }

    /** Numeric payload field, or empty when absent or not numeric. Never throws on bad input. */
    public OptionalDouble number(String field) {
        Object value = payload.get(field);
        if (value instanceof Number number) {
            return OptionalDouble.of(number.doubleValue());
        }
        if (value instanceof String text) {
            try {
                return OptionalDouble.of(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Map<String, Object> stringifyKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}

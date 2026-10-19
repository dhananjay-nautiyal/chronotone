package dev.chronotone.dsl;

import dev.chronotone.core.log.Event;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a window of events collapses into one number.
 *
 * <p>{@code count} needs no field; every other kind reads a numeric payload field and ignores
 * events that do not carry it.
 *
 * <p>Missing data is not the same as zero activity. A field-reading kind yields {@code NaN} when no
 * event in the window carries that field, and the mapping's {@code whenEmpty} decides what "no data"
 * sounds like. {@code count} never yields {@code NaN}: an empty window is a real measurement of zero
 * events, so it counts 0 and the {@code out} range decides what zero sounds like. See
 * {@link #readsPayloadField()}.
 */
public record Aggregate(Kind kind, String field) {

    public enum Kind {COUNT, SUM, MEAN, MAX, MIN, LAST}

    public Aggregate {
        if (kind == null) {
            throw new MappingException("aggregate.kind is required");
        }
        if (kind != Kind.COUNT && (field == null || field.isBlank())) {
            throw new MappingException("aggregate '" + kind.name().toLowerCase(Locale.ROOT) + "' needs a field");
        }
    }

    public static Aggregate count() {
        return new Aggregate(Kind.COUNT, null);
    }

    /**
     * Whether this aggregate can report missing data by returning {@code NaN}. Only {@code count}
     * cannot: it always has something to say, because "no events" is itself the answer (zero).
     * A declared {@code whenEmpty} on such an aggregate would never apply, so the parser rejects it
     * instead of ignoring it.
     */
    public boolean readsPayloadField() {
        return kind != Kind.COUNT;
    }

    /** Accepts either {@code count} or {@code { kind: sum, field: lines_changed }}. */
    public static Aggregate parse(Object node) {
        if (node instanceof String shorthand) {
            return of(shorthand, null);
        }
        if (node instanceof Map<?, ?> map) {
            Object kind = map.get("kind");
            if (kind == null) {
                throw new MappingException("aggregate is missing 'kind'");
            }
            Object field = map.get("field");
            return of(String.valueOf(kind), field == null ? null : String.valueOf(field));
        }
        throw new MappingException("aggregate must be a kind name or a { kind, field } map");
    }

    private static Aggregate of(String kind, String field) {
        try {
            return new Aggregate(Kind.valueOf(kind.trim().toUpperCase(Locale.ROOT)), field);
        } catch (IllegalArgumentException e) {
            throw new MappingException("unknown aggregate kind '" + kind
                    + "'; expected one of count, sum, mean, max, min, last");
        }
    }

    /** The aggregated value, or {@code NaN} when the window contributes nothing. */
    public double apply(List<Event> events) {
        if (kind == Kind.COUNT) {
            return events.size();
        }
        double sum = 0.0;
        long seen = 0;
        double best = Double.NaN;
        double latestValue = Double.NaN;
        Event latest = null;

        for (Event event : events) {
            var value = event.number(field);
            if (value.isEmpty()) {
                continue;
            }
            double raw = value.getAsDouble();
            seen++;
            sum += raw;
            best = switch (kind) {
                case MAX -> Double.isNaN(best) ? raw : Math.max(best, raw);
                case MIN -> Double.isNaN(best) ? raw : Math.min(best, raw);
                default -> best;
            };
            if (kind == Kind.LAST && (latest == null || event.timestamp().isAfter(latest.timestamp()))) {
                latest = event;
                latestValue = raw;
            }
        }

        return switch (kind) {
            case SUM -> seen == 0 ? Double.NaN : sum;
            case MEAN -> seen == 0 ? Double.NaN : sum / seen;
            case MAX, MIN -> best;
            case LAST -> latestValue;
            case COUNT -> events.size();
        };
    }
}

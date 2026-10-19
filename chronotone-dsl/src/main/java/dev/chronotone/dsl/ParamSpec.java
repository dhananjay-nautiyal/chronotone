package dev.chronotone.dsl;

import dev.chronotone.core.log.Event;

import java.time.Duration;
import java.util.List;

/**
 * One musical dimension, described declaratively: look back {@code window} from the slot, aggregate
 * the matching events, squeeze the raw number through {@code in} into 0..1, then stretch it across
 * {@code out}. {@code whenEmpty} is the normalised value to use when the window has no data.
 */
public record ParamSpec(String name, EventSelector selector, Duration window, Aggregate aggregate,
                        double inMin, double inMax, double outMin, double outMax, double whenEmpty) {

    public ParamSpec {
        if (name == null || name.isBlank()) {
            throw new MappingException("param needs a name");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new MappingException("param '" + name + "': window must be positive");
        }
        if (inMax <= inMin) {
            throw new MappingException("param '" + name + "': 'in' range must be ascending (got "
                    + inMin + ".." + inMax + ")");
        }
        if (whenEmpty < 0.0 || whenEmpty > 1.0) {
            throw new MappingException("param '" + name + "': whenEmpty must be within 0..1");
        }
    }

    /** The value of this param for one slot, given the events already filtered to its window. */
    public double value(List<Event> windowEvents) {
        double raw = aggregate.apply(windowEvents);
        if (Double.isNaN(raw)) {
            return outMin + whenEmpty * (outMax - outMin);
        }
        double normalised = Math.clamp((raw - inMin) / (inMax - inMin), 0.0, 1.0);
        return outMin + normalised * (outMax - outMin);
    }

    public String describe() {
        return "%s: %s %s over %s -> [%s, %s]".formatted(
                name, aggregate.kind().name().toLowerCase(), aggregate.field() == null ? "" : aggregate.field(),
                window, outMin, outMax);
    }
}

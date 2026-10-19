package dev.chronotone.dsl;

import dev.chronotone.core.log.Event;

/** Which events a param listens to. A blank or absent {@code type} matches every type of a source. */
public record EventSelector(String source, String type) {

    public EventSelector {
        if (source == null || source.isBlank()) {
            throw new MappingException("events.source is required");
        }
    }

    public boolean matches(Event event) {
        return source.equals(event.source())
                && (type == null || type.isBlank() || type.equals(event.type()));
    }

    public String describe() {
        return type == null || type.isBlank() ? source : source + "/" + type;
    }
}

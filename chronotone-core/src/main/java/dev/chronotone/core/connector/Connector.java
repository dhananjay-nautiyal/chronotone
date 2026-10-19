package dev.chronotone.core.connector;

import dev.chronotone.core.log.Event;

import java.time.Instant;
import java.util.List;

/**
 * A source of life events. Connectors are pull-based and side-effect free: given a window they
 * return events, and the log decides what to keep. Implementations must be deterministic for a
 * fixed window so that a re-fetch cannot silently change history.
 *
 * <p>Private connectors (your git repos, your Last.fm account) live outside this repository: only
 * the SPI and the synthetic connector ship here.
 */
public interface Connector extends AutoCloseable {

    /** Stable identifier, e.g. {@code git}, {@code music}, {@code weather}. */
    String id();

    /** Events that happened in {@code [from, to)}. May be empty, never null. */
    List<Event> fetch(Instant from, Instant to);

    default String describe() {
        return id();
    }

    @Override
    default void close() {
    }
}

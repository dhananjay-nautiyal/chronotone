package dev.chronotone.core.log;

/** One verified entry of the log: the event plus the hashes that bind it to its predecessor. */
public record LoggedEvent(long index, String previousHash, String hash, Event event) {

    @Override
    public String toString() {
        return "%6d  %s  %s/%s  %s".formatted(
                index, hash.substring(0, 12), event.source(), event.type(),
                Event.formatTimestamp(event.timestamp()));
    }
}

package dev.chronotone.connectors;

import java.time.Instant;

/**
 * What one connector contributed to a sync. A connector that failed carries the reason and
 * contributes nothing; its watermark is not advanced, so the next sync retries exactly it.
 */
public record ConnectorReport(String id, String type, Instant from, Instant to,
                              int fetched, int appended, int duplicates, String failure) {

    public ConnectorReport(String id, String type, Instant from, Instant to,
                           int fetched, int appended, int duplicates) {
        this(id, type, from, to, fetched, appended, duplicates, null);
    }

    public boolean failed() {
        return failure != null;
    }

    public String summary() {
        String line = "%-12s %-11s fetched %4d | appended %4d | duplicates %4d".formatted(id, type, fetched, appended, duplicates);
        return failed() ? line + " | FAILED: " + failure : line;
    }
}

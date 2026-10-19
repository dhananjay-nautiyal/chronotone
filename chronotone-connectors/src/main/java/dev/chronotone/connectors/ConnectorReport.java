package dev.chronotone.connectors;

import java.time.Instant;

/** What one connector contributed to a sync. */
public record ConnectorReport(String id, String type, Instant from, Instant to,
                              int fetched, int appended, int duplicates) {

    public String summary() {
        return "%-12s %-11s fetched %4d | appended %4d | duplicates %4d".formatted(id, type, fetched, appended, duplicates);
    }
}

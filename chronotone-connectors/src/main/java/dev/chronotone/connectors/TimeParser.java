package dev.chronotone.connectors;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Turns an exported timestamp column into an {@link Instant}. */
final class TimeParser {

    private TimeParser() {
    }

    /**
     * Supported {@code time_format} values: {@code iso} (default), {@code epoch_s}, {@code epoch_ms},
     * or any {@link DateTimeFormatter} pattern, interpreted as UTC.
     */
    static Instant parse(String raw, String format) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new ConnectorConfigException("row has an empty timestamp");
        }
        try {
            return switch (format.toLowerCase(Locale.ROOT)) {
                case "iso", "iso8601" -> Instant.parse(value);
                case "epoch_s" -> Instant.ofEpochSecond(Long.parseLong(value));
                case "epoch_ms" -> Instant.ofEpochMilli(Long.parseLong(value));
                default -> LocalDateTime.parse(value, DateTimeFormatter.ofPattern(format)).toInstant(ZoneOffset.UTC);
            };
        } catch (RuntimeException e) {
            throw new ConnectorConfigException("cannot parse timestamp '" + value
                    + "' with format '" + format + "'", e);
        }
    }

    /** Half-open window: {@code from} inclusive, {@code to} exclusive, so syncs never double-count. */
    static boolean inWindow(Instant timestamp, Instant from, Instant to) {
        return !timestamp.isBefore(from) && timestamp.isBefore(to);
    }
}

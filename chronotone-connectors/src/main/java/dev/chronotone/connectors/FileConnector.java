package dev.chronotone.connectors;

import dev.chronotone.core.connector.Connector;
import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * Reads a data export you already own: a CSV or JSONL file where each row is one event.
 *
 * <p>This is the privacy-preserving workhorse. Health Connect exports, Subsonic/Last.fm CSV dumps,
 * a personal habit sheet — anything with a timestamp column becomes a source, and the file never
 * leaves your machine.
 *
 * <pre>{@code
 * - id: steps
 *   type: file
 *   path: ~/exports/steps.csv
 *   format: csv                 # csv | jsonl
 *   time_column: start_time
 *   time_format: iso            # iso | epoch_s | epoch_ms | <pattern>
 *   event_source: health
 *   event_type: steps
 *   payload: steps,distance_m   # omit to take every other column
 * }</pre>
 */
public final class FileConnector implements Connector {

    private final String id;
    private final Path path;
    private final String format;
    private final String timeColumn;
    private final String timeFormat;
    private final String eventSource;
    private final String eventType;
    private final String sourceColumn;
    private final String typeColumn;
    private final List<String> payloadColumns;

    public FileConnector(ConnectorConfig config) {
        this.id = config.id();
        this.path = config.requirePath("path");
        this.format = config.option("format", detectFormat(path)).toLowerCase(java.util.Locale.ROOT);
        this.timeColumn = config.require("time_column");
        this.timeFormat = config.option("time_format", "iso");
        this.eventSource = config.option("event_source", id);
        this.eventType = config.option("event_type", "sample");
        this.sourceColumn = config.option("source_column");
        this.typeColumn = config.option("type_column");
        this.payloadColumns = config.list("payload");
    }

    private static String detectFormat(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jsonl") || name.endsWith(".ndjson") ? "jsonl" : "csv";
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String describe() {
        return id + " (" + format + " file " + path + ")";
    }

    @Override
    public List<Event> fetch(Instant from, Instant to) {
        if (!Files.exists(path)) {
            throw new ConnectorException("connector '" + id + "': no such file " + path);
        }
        List<Map<String, String>> rows;
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) {
                // A BOM (normal in Windows-written exports) would become part of the first
                // header name, so time_column would never match and every row would be skipped.
                content = content.substring(1);
            }
            rows = "jsonl".equals(format) ? jsonl(content) : csv(content);
        } catch (IOException e) {
            throw new ConnectorException("connector '" + id + "': cannot read " + path, e);
        }

        List<Event> events = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String raw = row.get(timeColumn);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Instant timestamp = TimeParser.parse(raw, timeFormat);
            if (!TimeParser.inWindow(timestamp, from, to)) {
                continue;
            }
            events.add(Event.of(
                    sourceColumn == null ? eventSource : row.getOrDefault(sourceColumn, eventSource),
                    typeColumn == null ? eventType : row.getOrDefault(typeColumn, eventType),
                    timestamp,
                    payload(row)));
        }
        events.sort(Comparator.comparing(Event::timestamp));
        return events;
    }

    private Map<String, Object> payload(Map<String, String> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            String column = entry.getKey();
            if (column.equals(timeColumn) || column.equals(sourceColumn) || column.equals(typeColumn)) {
                continue;
            }
            if (!payloadColumns.isEmpty() && !payloadColumns.contains(column)) {
                continue;
            }
            payload.put(column, coerce(entry.getValue()));
        }
        return payload;
    }

    /** Numbers stay numbers so that {@code sum}/{@code mean} aggregates can read them. */
    private static Object coerce(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // not an integer, maybe a decimal
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static List<Map<String, String>> csv(String content) {
        String[] lines = content.split("\r?\n");
        List<Map<String, String>> rows = new ArrayList<>();
        if (lines.length == 0) {
            return rows;
        }
        List<String> header = CsvReader.parseLine(lines[0]).stream().map(String::trim).toList();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> fields = CsvReader.parseLine(lines[i]);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), c < fields.size() ? fields.get(c).trim() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, String>> jsonl(String content) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : content.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> parsed = Json.parseObject(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                row.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
            rows.add(row);
        }
        return rows;
    }
}

package dev.chronotone.connectors;

import dev.chronotone.core.log.CanonicalJson;
import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * How far each connector has already been read, stored next to the log. This is what makes
 * {@code sync} incremental: the next run starts a millisecond after the last one stopped.
 *
 * <p>Watermarks are a convenience, not the correctness mechanism — duplicate events are still
 * rejected by content identity in {@link SyncService}.
 */
public final class Watermarks {

    private final Path path;
    private final Map<String, Instant> marks = new LinkedHashMap<>();

    private Watermarks(Path path) {
        this.path = path;
    }

    public static Watermarks load(Path path) throws IOException {
        Watermarks watermarks = new Watermarks(path);
        if (Files.exists(path)) {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (!content.isBlank()) {
                for (Map.Entry<String, Object> entry : Json.parseObject(content).entrySet()) {
                    watermarks.marks.put(entry.getKey(), Instant.parse(String.valueOf(entry.getValue())));
                }
            }
        }
        return watermarks;
    }

    public Optional<Instant> get(String connectorId) {
        return Optional.ofNullable(marks.get(connectorId));
    }

    public void put(String connectorId, Instant mark) {
        if (mark != null) {
            marks.put(connectorId, mark);
        }
    }

    public void putAll(Map<String, Instant> other) {
        other.forEach(this::put);
    }

    public Map<String, Instant> asMap() {
        return Map.copyOf(marks);
    }

    public void save() throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        marks.forEach((id, mark) -> out.put(id, Event.formatTimestamp(mark)));
        Files.writeString(path, CanonicalJson.write(out) + "\n", StandardCharsets.UTF_8);
    }

    public Path path() {
        return path;
    }
}

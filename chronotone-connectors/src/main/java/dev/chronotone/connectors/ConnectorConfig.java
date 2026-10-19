package dev.chronotone.connectors;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One connector's configuration. Options are plain strings on purpose: the same shape serves every
 * connector and the whole config file stays readable as YAML.
 *
 * <p>Connectors read from your machine or from keyless public APIs, and the events they produce go
 * into a log that never leaves your disk. Nothing here uploads anything.
 */
public record ConnectorConfig(String id, String type, Map<String, String> options) {

    public ConnectorConfig {
        if (id == null || id.isBlank()) {
            throw new ConnectorConfigException("a connector needs a non-blank 'id'");
        }
        if (type == null || type.isBlank()) {
            throw new ConnectorConfigException("connector '" + id + "' needs a 'type'");
        }
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options == null ? Map.of() : options));
    }

    public String option(String key) {
        return options.get(key);
    }

    public String option(String key, String fallback) {
        String value = options.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public String require(String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new ConnectorConfigException("connector '" + id + "' (" + type + ") requires option '" + key + "'");
        }
        return value;
    }

    public Path requirePath(String key) {
        return expand(require(key));
    }

    public Path path(String key, Path fallback) {
        String value = options.get(key);
        return value == null || value.isBlank() ? fallback : expand(value);
    }

    public double decimal(String key, double fallback) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new ConnectorConfigException("connector '" + id + "': option '" + key + "' must be a number, got '" + value + "'");
        }
    }

    public int integer(String key, int fallback) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ConnectorConfigException("connector '" + id + "': option '" + key + "' must be an integer, got '" + value + "'");
        }
    }

    public long seed() {
        return (long) decimal("seed", 42.0);
    }

    public List<String> list(String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** Expands a leading {@code ~} so configs can be written the way a human thinks about paths. */
    public static Path expand(String raw) {
        String value = raw.trim();
        if (value.startsWith("~")) {
            String home = System.getProperty("user.home", "~");
            return Path.of(home + value.substring(1));
        }
        return Path.of(value);
    }

    @Override
    public String toString() {
        return id + " (" + type + ")";
    }
}

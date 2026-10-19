package dev.chronotone.connectors;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code connectors.yaml}:
 *
 * <pre>{@code
 * connectors:
 *   - id: git
 *     type: git
 *     repo: ~/projects/chronotone
 *   - id: steps
 *     type: file
 *     path: ~/exports/steps.csv
 *     format: csv
 *     time_column: start_time
 *     event_source: health
 *     event_type: steps
 * }</pre>
 */
public final class ConnectorConfigFile {

    private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

    public static List<ConnectorConfig> read(Path path) throws IOException {
        return new ConnectorConfigFile().parse(Files.readString(path));
    }

    public List<ConnectorConfig> parse(String yamlText) {
        Object loaded;
        try {
            loaded = yaml.load(yamlText);
        } catch (RuntimeException e) {
            throw new ConnectorConfigException("connectors config is not valid YAML: " + e.getMessage(), e);
        }
        Map<String, Object> root = asMap(loaded, "connectors config");
        Object listNode = root.getOrDefault("connectors", List.of());
        if (!(listNode instanceof List<?> list)) {
            throw new ConnectorConfigException("'connectors' must be a list");
        }

        List<ConnectorConfig> configs = new ArrayList<>(list.size());
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> node = asMap(list.get(i), "connectors[" + i + "]");
            Map<String, String> options = new LinkedHashMap<>();
            String id = null;
            String type = null;
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
                switch (key) {
                    case "id" -> id = value;
                    case "type" -> type = value;
                    default -> options.put(key, value);
                }
            }
            ConnectorConfig config = new ConnectorConfig(id, type, options);
            if (seen.contains(config.id())) {
                throw new ConnectorConfigException("duplicate connector id '" + config.id()
                        + "'; ids key the watermark file and must be unique");
            }
            seen.add(config.id());
            configs.add(config);
        }
        return configs;
    }

    private static Map<String, Object> asMap(Object node, String what) {
        if (node == null) {
            throw new ConnectorConfigException(what + " is empty");
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new ConnectorConfigException(what + " must be a map");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}

package dev.chronotone.dsl;

import dev.chronotone.core.music.MusicalParams;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses mapping YAML. Unknown parameter names are rejected rather than ignored: a silent typo in a
 * mapping means silent music, which is the worst possible failure mode for this project.
 */
public final class MappingParser {

    private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

    public MappingSpec parse(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) {
            throw new MappingException("mapping document is empty");
        }
        Object loaded;
        try {
            loaded = yaml.load(yamlText);
        } catch (RuntimeException e) {
            throw new MappingException("mapping is not valid YAML: " + e.getMessage(), e);
        }
        Map<String, Object> root = asMap(loaded, "mapping document");

        String version = text(root.getOrDefault("mapping", "v0"));
        Duration grid = duration(root.getOrDefault("grid", "1m"));
        String salt = text(root.getOrDefault("seed_salt", "chronotone"));
        Map<String, Object> paramsNode = asMap(root.get("params"), "'params'");

        Map<String, ParamSpec> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : paramsNode.entrySet()) {
            String name = entry.getKey();
            if (!MusicalParams.PARAM_NAMES.contains(name)) {
                throw new MappingException("unknown param '" + name + "'; known params are "
                        + MusicalParams.PARAM_NAMES);
            }
            params.put(name, paramSpec(name, asMap(entry.getValue(), "'params." + name + "'")));
        }
        return new MappingSpec(version, grid, salt, params);
    }

    private ParamSpec paramSpec(String name, Map<String, Object> node) {
        EventSelector selector = selector(name, node.get("events"));
        Duration window = duration(node.getOrDefault("window", "10m"));
        Aggregate aggregate = Aggregate.parse(node.get("aggregate"));
        double[] in = range(node.get("in"), "params." + name + ".in");
        double[] out = range(node.get("out"), "params." + name + ".out");
        double whenEmpty = 0.0;
        if (node.containsKey("whenEmpty")) {
            if (!aggregate.readsPayloadField()) {
                throw new MappingException("params." + name + ": 'whenEmpty' can never apply to a 'count'"
                        + " aggregate — an empty window counts 0, which is a measurement rather than missing"
                        + " data, so it clamps into 'out' like any other value. Set 'out' to control what zero"
                        + " sounds like, or use sum/mean over a field if you need a distinct no-data value.");
            }
            whenEmpty = number(node.get("whenEmpty"), "params." + name + ".whenEmpty");
        }
        return new ParamSpec(name, selector, window, aggregate, in[0], in[1], out[0], out[1], whenEmpty);
    }

    private EventSelector selector(String name, Object node) {
        Map<String, Object> map = asMap(node, "params." + name + ".events");
        Object type = map.get("type");
        return new EventSelector(text(map.get("source")), type == null ? null : text(type));
    }

    static Map<String, Object> asMap(Object node, String what) {
        if (node == null) {
            throw new MappingException(what + " is required");
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new MappingException(what + " must be a map but was " + node.getClass().getSimpleName());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    static double[] range(Object node, String what) {
        if (!(node instanceof List<?> list) || list.size() != 2) {
            throw new MappingException(what + " must be a two-element list, e.g. [0, 8]");
        }
        return new double[]{number(list.get(0), what), number(list.get(1), what)};
    }

    static double number(Object node, String what) {
        if (node instanceof Number n) {
            return n.doubleValue();
        }
        if (node instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the error below
            }
        }
        throw new MappingException(what + " must be a number but was " + node);
    }

    static String text(Object node) {
        if (node == null) {
            throw new MappingException("expected a value");
        }
        return String.valueOf(node).trim();
    }

    /** Accepts compact durations: {@code 30s}, {@code 5m}, {@code 2h}, {@code 1d}. */
    static Duration duration(Object node) {
        if (node instanceof Duration duration) {
            return duration;
        }
        String raw = text(node);
        char unit = raw.charAt(raw.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
        } catch (NumberFormatException e) {
            throw new MappingException("cannot parse duration '" + raw + "' (use 30s, 5m, 2h, 1d)");
        }
        return switch (Character.toLowerCase(unit)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new MappingException("cannot parse duration '" + raw + "' (use 30s, 5m, 2h, 1d)");
        };
    }
}

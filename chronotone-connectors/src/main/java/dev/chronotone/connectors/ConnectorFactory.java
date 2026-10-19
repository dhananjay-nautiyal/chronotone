package dev.chronotone.connectors;

import dev.chronotone.core.connector.Connector;
import dev.chronotone.core.connector.SyntheticConnector;

import java.util.List;

/** Maps a config's {@code type} to a {@link Connector}. Adding a source is a one-line change here. */
public final class ConnectorFactory {

    public static final List<String> KNOWN_TYPES = List.of("git", "file", "open-meteo", "synthetic");

    private ConnectorFactory() {
    }

    public static Connector create(ConnectorConfig config) {
        return switch (config.type()) {
            case "git" -> new GitConnector(config);
            case "file" -> new FileConnector(config);
            case "open-meteo" -> new OpenMeteoConnector(config);
            case "synthetic" -> new SyntheticConnector(config.seed());
            default -> throw new ConnectorConfigException("connector '" + config.id() + "' has unknown type '"
                    + config.type() + "'; known types are " + KNOWN_TYPES);
        };
    }
}

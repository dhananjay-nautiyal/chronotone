package dev.chronotone.connectors;

/** Thrown when a connector config is malformed or a connector cannot run. */
public class ConnectorConfigException extends RuntimeException {

    public ConnectorConfigException(String message) {
        super(message);
    }

    public ConnectorConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}

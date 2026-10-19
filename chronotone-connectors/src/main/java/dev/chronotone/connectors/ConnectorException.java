package dev.chronotone.connectors;

/** Thrown when a connector fails at runtime — git missing, file unreadable, API unreachable. */
public class ConnectorException extends RuntimeException {

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}

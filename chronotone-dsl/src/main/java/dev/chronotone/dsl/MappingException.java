package dev.chronotone.dsl;

/** Thrown when a mapping document is malformed. Mapping errors are user errors, not bugs. */
public class MappingException extends RuntimeException {

    public MappingException(String message) {
        super(message);
    }

    public MappingException(String message, Throwable cause) {
        super(message, cause);
    }
}

package dev.chronotone.core.log;

/** Outcome of {@link EventLog#verify(java.nio.file.Path)}. */
public record VerifyResult(boolean ok, long entries, String head, long badIndex, String message) {

    public static VerifyResult ok(long entries, String head) {
        return new VerifyResult(true, entries, head, -1, "chain intact");
    }

    public static VerifyResult failed(long entries, long badIndex, String message) {
        return new VerifyResult(false, entries, null, badIndex, message);
    }

    @Override
    public String toString() {
        return ok
                ? "OK  %d entries  head=%s".formatted(entries, head)
                : "FAILED after %d entries (entry #%d): %s".formatted(entries, badIndex, message);
    }
}

package dev.chronotone.core.log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content addressing primitives. Everything Chronotone publishes is one of these hashes. */
public final class Hashes {

    /** The {@code prev} value of the first entry in a log. */
    public static final String GENESIS = "0".repeat(64);

    private Hashes() {
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] data) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required in every JDK", e);
        }
        return HexFormat.of().formatHex(digest.digest(data));
    }

    /**
     * One link of the log chain: {@code sha256(previousHash + "\n" + canonicalEvent)}.
     * The separator keeps the chain unambiguous (no concatenation collisions).
     */
    public static String chain(String previousHash, String canonicalEvent) {
        return sha256Hex(previousHash + "\n" + canonicalEvent);
    }
}

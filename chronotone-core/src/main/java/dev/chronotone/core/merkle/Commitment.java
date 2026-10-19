package dev.chronotone.core.merkle;

import dev.chronotone.core.log.CanonicalJson;
import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.Hashes;
import dev.chronotone.core.log.Json;
import dev.chronotone.core.log.LoggedEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The thing you publish: a Merkle root over a slice of the log, plus enough scope to say what the
 * root covers.
 *
 * <p>It is deliberately dull and self-describing. No wall clock, no machine name, no event payloads —
 * just a root and a description of the range it commits to. Two people committing the same slice of
 * the same log get the same document, byte for byte.
 *
 * <p>{@code priorHead} and {@code chainHead} are the chain links immediately before and at the end of
 * the slice. When the slice is contiguous — which it is for a day of a chronologically appended log —
 * they pin the commitment to an exact position in the chain, so the same root cannot be recycled to
 * claim a different place in history.
 */
public record Commitment(String format, int entries, String merkleRoot, String chainHead,
                         String priorHead, String firstEvent, String lastEvent) {

    public static final String FORMAT = "chronotone-merkle-commitment/1";

    /** Published alongside every commitment so the artifact explains its own hashing. */
    public static final String RULE = "leaf=sha256(\"leaf:\"+entryHash);"
            + " node=sha256(\"node:\"+left+right); odd node promoted unchanged";

    public Commitment {
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("unsupported commitment format '" + format + "'");
        }
        if (entries < 0) {
            throw new IllegalArgumentException("entries must not be negative");
        }
        Objects.requireNonNull(merkleRoot, "merkleRoot");
        Objects.requireNonNull(chainHead, "chainHead");
        Objects.requireNonNull(priorHead, "priorHead");
    }

    /** Commits to the given entries, in log order. Empty logs commit to {@link MerkleTree#EMPTY_ROOT}. */
    public static Commitment of(List<LoggedEvent> slice) {
        List<String> hashes = new ArrayList<>(slice.size());
        for (LoggedEvent entry : slice) {
            hashes.add(entry.hash());
        }
        MerkleTree tree = MerkleTree.of(hashes);
        String prior = slice.isEmpty() ? Hashes.GENESIS : slice.getFirst().previousHash();
        String head = slice.isEmpty() ? Hashes.GENESIS : slice.getLast().hash();
        String first = slice.isEmpty() ? null : Event.formatTimestamp(slice.getFirst().event().timestamp());
        String last = slice.isEmpty() ? null : Event.formatTimestamp(slice.getLast().event().timestamp());
        return new Commitment(FORMAT, slice.size(), tree.root(), head, prior, first, last);
    }

    /** Builds the tree for this commitment's scope, so a proof can be generated against it. */
    public MerkleTree treeOver(List<LoggedEvent> slice) {
        if (!covers(slice)) {
            throw new IllegalArgumentException("these entries are not the slice this commitment describes");
        }
        List<String> hashes = new ArrayList<>(slice.size());
        for (LoggedEvent entry : slice) {
            hashes.add(entry.hash());
        }
        return MerkleTree.of(hashes);
    }

    /** True when this commitment describes exactly the given entries, root included. */
    public boolean covers(List<LoggedEvent> candidate) {
        Commitment other = of(candidate);
        return entries == other.entries
                && merkleRoot.equals(other.merkleRoot)
                && chainHead.equals(other.chainHead)
                && priorHead.equals(other.priorHead);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", format);
        out.put("rule", RULE);
        out.put("entries", entries);
        out.put("merkleRoot", merkleRoot);
        out.put("chainHead", chainHead);
        out.put("priorHead", priorHead);
        out.put("firstEvent", firstEvent);
        out.put("lastEvent", lastEvent);
        return out;
    }

    public String toJson() {
        return CanonicalJson.write(toMap()) + "\n";
    }

    public static Commitment fromJson(String json) {
        return fromMap(Json.parseObject(json));
    }

    public static Commitment fromMap(Map<String, Object> map) {
        return new Commitment(
                string(map.get("format"), "format"),
                integer(map.get("entries"), "entries"),
                string(map.get("merkleRoot"), "merkleRoot"),
                string(map.get("chainHead"), "chainHead"),
                string(map.get("priorHead"), "priorHead"),
                nullableString(map.get("firstEvent")),
                nullableString(map.get("lastEvent")));
    }

    public String describe() {
        String window = (firstEvent == null || lastEvent == null)
                ? "empty"
                : firstEvent + " .. " + lastEvent;
        return "%d entries | %s | root %s".formatted(entries, window, shortHash(merkleRoot));
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12);
    }

    private static String string(Object node, String what) {
        if (node instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("a commitment needs a string '" + what + "'");
    }

    private static String nullableString(Object node) {
        return node instanceof String text && !text.isBlank() ? text : null;
    }

    private static int integer(Object node, String what) {
        if (node instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("a commitment needs a numeric '" + what + "'");
    }
}

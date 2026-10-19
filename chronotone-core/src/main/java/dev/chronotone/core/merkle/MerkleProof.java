package dev.chronotone.core.merkle;

import dev.chronotone.core.log.CanonicalJson;
import dev.chronotone.core.log.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An inclusion proof: the short list of sibling hashes needed to walk one entry up to the root.
 *
 * <p>Everything in here is public. Verifying it needs the entry's own hash and the published root —
 * not the log, not the other entries, and not any event payload. That is the whole point: you can
 * prove a specific event was committed to without revealing the rest of the day.
 *
 * <p>Each step records the level it belongs to, and {@link #verify()} insists that the levels ascend
 * and that the left/right choices imply exactly the {@code index} the proof claims. Without that, a
 * proof for one position could simply be relabelled as another — and since positions map to
 * timestamps, a verifier would be told the wrong moment was committed to.
 */
public record MerkleProof(String root, int index, int size, String entryHash, List<Step> path) {

    public static final String FORMAT = "chronotone-merkle-proof/1";

    /** One sibling on the way up: its hash, which side of the pair it sits on, and its tree level. */
    public record Step(String sibling, boolean siblingIsLeft, int level) {

        public Step {
            if (level < 0) {
                throw new IllegalArgumentException("a proof step level must not be negative");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("level", level);
            out.put("side", siblingIsLeft ? "left" : "right");
            out.put("sibling", sibling);
            return out;
        }

        static Step fromMap(Map<String, Object> map) {
            Object sibling = map.get("sibling");
            Object side = map.get("side");
            Object level = map.get("level");
            if (!(sibling instanceof String hash) || !(side instanceof String position)) {
                throw new IllegalArgumentException("a proof step needs a string 'sibling' and 'side'");
            }
            if (!"left".equals(position) && !"right".equals(position)) {
                throw new IllegalArgumentException("a proof step 'side' must be 'left' or 'right', got '"
                        + position + "'");
            }
            if (!(level instanceof Number number)) {
                throw new IllegalArgumentException("a proof step needs a numeric 'level'");
            }
            return new Step(hash, "left".equals(position), number.intValue());
        }
    }

    public MerkleProof {
        path = List.copyOf(path);
        if (index < 0) {
            throw new IllegalArgumentException("proof index must not be negative");
        }
        if (size < 0) {
            throw new IllegalArgumentException("proof size must not be negative");
        }
        if (entryHash == null || entryHash.isBlank()) {
            throw new IllegalArgumentException("proof needs the entry hash it is proving");
        }
    }

    public int pathLength() {
        return path.size();
    }

    /** Walks the path back up and returns the root it implies. */
    public String recomputeRoot() {
        String node = MerkleTree.leaf(entryHash);
        for (Step step : path) {
            node = step.siblingIsLeft()
                    ? MerkleTree.node(step.sibling(), node)
                    : MerkleTree.node(node, step.sibling());
        }
        return node;
    }

    /**
     * The entry position this path actually proves: each level where the node was the right child
     * sets a bit. Levels the path skips are levels where the node was an odd one out and was
     * promoted, so their bit is zero.
     *
     * @return the implied index, or {@code -1} if the path is too deep to describe an int index
     */
    public int impliedIndex() {
        int implied = 0;
        for (Step step : path) {
            if (step.level() > 30) {
                return -1;
            }
            if (step.siblingIsLeft()) {
                implied |= 1 << step.level();
            }
        }
        return implied;
    }

    /**
     * Why this proof does not verify, or {@code null} when it does. Callers deserve a precise reason:
     * a broken path, a relabelled position and a reordered path are different accusations.
     */
    public String failureReason() {
        if (root == null || !root.equals(recomputeRoot())) {
            return "the audit path does not reproduce the root";
        }
        int previousLevel = -1;
        for (Step step : path) {
            if (step.level() <= previousLevel) {
                return "the path steps are out of order (level " + step.level()
                        + " follows level " + previousLevel + ")";
            }
            previousLevel = step.level();
        }
        int implied = impliedIndex();
        if (implied != index) {
            return "the path proves position #" + implied + " but the proof claims #" + index;
        }
        return null;
    }

    /**
     * True only when the path reproduces the claimed root, the levels ascend, and the path proves the
     * position the proof claims. A one-character change anywhere fails.
     */
    public boolean verify() {
        return failureReason() == null;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        out.put("root", root);
        out.put("index", index);
        out.put("size", size);
        out.put("entryHash", entryHash);
        List<Map<String, Object>> steps = new ArrayList<>(path.size());
        for (Step step : path) {
            steps.add(step.toMap());
        }
        out.put("path", steps);
        return out;
    }

    public String toJson() {
        return CanonicalJson.write(toMap()) + "\n";
    }

    public static MerkleProof fromJson(String json) {
        return fromMap(Json.parseObject(json));
    }

    public static MerkleProof fromMap(Map<String, Object> map) {
        Object root = map.get("root");
        Object entryHash = map.get("entryHash");
        Object pathNode = map.get("path");
        if (!(root instanceof String rootHash) || !(entryHash instanceof String leaf)) {
            throw new IllegalArgumentException("a proof needs a 'root' and an 'entryHash'");
        }
        if (!(pathNode instanceof List<?> steps)) {
            throw new IllegalArgumentException("a proof needs a 'path' array");
        }
        List<Step> path = new ArrayList<>(steps.size());
        for (Object step : steps) {
            if (!(step instanceof Map<?, ?> stepMap)) {
                throw new IllegalArgumentException("every proof path element must be an object");
            }
            path.add(Step.fromMap(stringifyKeys(stepMap)));
        }
        return new MerkleProof(rootHash, integer(map.get("index"), "index"), integer(map.get("size"), "size"),
                leaf, path);
    }

    public String describe() {
        return "entry #%d of %d | path %d steps | root %s".formatted(index, size, path.size(), shortHash(root));
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12);
    }

    private static int integer(Object node, String what) {
        if (node instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("a proof needs a numeric '" + what + "'");
    }

    private static Map<String, Object> stringifyKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}

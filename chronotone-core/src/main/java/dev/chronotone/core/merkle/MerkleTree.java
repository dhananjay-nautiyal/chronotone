package dev.chronotone.core.merkle;

import dev.chronotone.core.log.Hashes;

import java.util.ArrayList;
import java.util.List;

/**
 * A Merkle tree over a slice of a log's entry hashes.
 *
 * <p>The chain and the tree answer different questions. The chain proves "nothing in this log was
 * edited" but to check it you need every byte. A Merkle tree proves "this one entry is inside the
 * thing I published" while the other entries stay secret — which is what lets you publish a daily
 * commitment without publishing your day.
 *
 * <p>Two details keep the hashing unambiguous:
 *
 * <ul>
 *   <li>Leaves and internal nodes carry different domain prefixes, so no internal node can be
 *       replayed as a leaf (and vice versa).
 *   <li>An odd node is <em>promoted</em> to the next level unchanged rather than duplicated. Duplicating
 *       the last node is what makes Bitcoin-style trees vulnerable to the duplicated-leaf
 *       ambiguity, where two different logs share a root.
 * </ul>
 */
public final class MerkleTree {

    /** Domain separator for leaves: a leaf can never be mistaken for an internal node. */
    static final String LEAF_PREFIX = "leaf:";

    /** Domain separator for internal nodes. */
    static final String NODE_PREFIX = "node:";

    /** The root of the empty tree: a fixed value, so callers never need a special case. */
    public static final String EMPTY_ROOT = Hashes.sha256Hex("chronotone/merkle/empty");

    private final List<String> entryHashes;
    private final List<List<String>> levels;

    private MerkleTree(List<String> entryHashes, List<List<String>> levels) {
        this.entryHashes = List.copyOf(entryHashes);
        this.levels = levels;
    }

    /** Builds the tree over entry hashes in log order. */
    public static MerkleTree of(List<String> entryHashes) {
        List<String> leaves = new ArrayList<>(entryHashes.size());
        for (String entryHash : entryHashes) {
            leaves.add(leaf(entryHash));
        }
        List<List<String>> levels = new ArrayList<>();
        levels.add(List.copyOf(leaves));
        List<String> current = leaves;
        while (current.size() > 1) {
            current = parents(current);
            levels.add(List.copyOf(current));
        }
        return new MerkleTree(entryHashes, levels);
    }

    static String leaf(String entryHash) {
        return Hashes.sha256Hex(LEAF_PREFIX + entryHash);
    }

    static String node(String left, String right) {
        return Hashes.sha256Hex(NODE_PREFIX + left + right);
    }

    private static List<String> parents(List<String> nodes) {
        List<String> parents = new ArrayList<>((nodes.size() + 1) / 2);
        for (int i = 0; i < nodes.size(); i += 2) {
            if (i + 1 < nodes.size()) {
                parents.add(node(nodes.get(i), nodes.get(i + 1)));
            } else {
                parents.add(nodes.get(i)); // odd one out: promoted unchanged
            }
        }
        return parents;
    }

    public String root() {
        List<String> top = levels.getLast();
        return top.isEmpty() ? EMPTY_ROOT : top.getLast();
    }

    public int size() {
        return entryHashes.size();
    }

    public List<String> entryHashes() {
        return entryHashes;
    }

    public int depth() {
        return levels.size() - 1;
    }

    /**
     * The audit path for one entry: just enough hashes to recompute the root, and nothing about any
     * other entry's contents.
     */
    public MerkleProof proof(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("no entry #" + index + " in a tree of " + size() + " entries");
        }
        List<MerkleProof.Step> path = new ArrayList<>();
        int position = index;
        for (int level = 0; level < levels.size() - 1; level++) {
            List<String> nodes = levels.get(level);
            boolean siblingIsLeft = position % 2 == 1;
            int sibling = siblingIsLeft ? position - 1 : position + 1;
            if (sibling < nodes.size()) {
                // An absent sibling means this node was promoted, so it contributes no step.
                path.add(new MerkleProof.Step(nodes.get(sibling), siblingIsLeft, level));
            }
            position /= 2;
        }
        return new MerkleProof(root(), index, size(), entryHashes.get(index), List.copyOf(path));
    }
}

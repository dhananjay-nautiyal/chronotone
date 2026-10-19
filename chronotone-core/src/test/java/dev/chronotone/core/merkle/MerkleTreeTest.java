package dev.chronotone.core.merkle;

import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.Hashes;
import dev.chronotone.core.log.LoggedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerkleTreeTest {

    private static final Instant T0 = Instant.parse("2026-10-19T00:00:00Z");

    private static List<String> hashes(int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(Hashes.sha256Hex("entry-" + i));
        }
        return out;
    }

    /** A real, contiguous chain slice, so commitments here behave like commitments of a real log. */
    private static List<LoggedEvent> slice(int count) {
        List<LoggedEvent> out = new ArrayList<>(count);
        String previous = Hashes.GENESIS;
        for (int i = 0; i < count; i++) {
            Event event = Event.of("git", "commit", T0.plusSeconds(i * 60L), Map.of("lines_changed", i));
            String hash = Hashes.chain(previous, event.canonical());
            out.add(new LoggedEvent(i, previous, hash, event));
            previous = hash;
        }
        return out;
    }

    @Test
    void theEmptyTreeHasAFixedRootRatherThanASpecialCase() {
        MerkleTree tree = MerkleTree.of(List.of());
        assertEquals(0, tree.size());
        assertEquals(MerkleTree.EMPTY_ROOT, tree.root());
    }

    @Test
    void aSingleEntryTreeIsItsOwnLeaf() {
        String entry = hashes(1).getFirst();
        assertEquals(MerkleTree.leaf(entry), MerkleTree.of(List.of(entry)).root());
    }

    @Test
    void oddNodesArePromotedRatherThanDuplicated() {
        List<String> entries = hashes(3);
        String expected = MerkleTree.node(
                MerkleTree.node(MerkleTree.leaf(entries.get(0)), MerkleTree.leaf(entries.get(1))),
                MerkleTree.leaf(entries.get(2))); // third leaf rides up unchanged
        assertEquals(expected, MerkleTree.of(entries).root());

        // Duplicating the odd node instead (the Bitcoin rule) must give a different root, which is
        // what stops two different logs from sharing a root.
        String ifDuplicated = MerkleTree.node(
                MerkleTree.node(MerkleTree.leaf(entries.get(0)), MerkleTree.leaf(entries.get(1))),
                MerkleTree.node(MerkleTree.leaf(entries.get(2)), MerkleTree.leaf(entries.get(2))));
        assertNotEquals(ifDuplicated, MerkleTree.of(entries).root());
    }

    @Test
    void everyEntryOfEveryTreeSizeHasAVerifiableProof() {
        for (int size = 1; size <= 17; size++) {
            MerkleTree tree = MerkleTree.of(hashes(size));
            for (int index = 0; index < size; index++) {
                MerkleProof proof = tree.proof(index);
                assertTrue(proof.verify(), "size " + size + " index " + index + " should verify");
                assertEquals(tree.root(), proof.root());
                assertEquals(index, proof.index());
                assertEquals(size, proof.size());
                assertEquals(tree.entryHashes().get(index), proof.entryHash());
                // The left/right choices must imply the position the proof claims.
                assertEquals(index, proof.impliedIndex(), "size " + size + " index " + index);
            }
        }
    }

    @Test
    void theFailureReasonNamesWhatIsActuallyWrong() {
        MerkleProof proof = MerkleTree.of(hashes(24)).proof(4);
        assertNull(proof.failureReason());

        MerkleProof relabelled = new MerkleProof(proof.root(), 5, proof.size(), proof.entryHash(), proof.path());
        String reason = relabelled.failureReason();
        assertTrue(reason.contains("#5") && reason.contains("#4"),
                "expected an accusation about positions, got: " + reason);

        MerkleProof brokenLeaf = new MerkleProof(proof.root(), proof.index(), proof.size(),
                Hashes.sha256Hex("a different event"), proof.path());
        assertTrue(brokenLeaf.failureReason().contains("root"));
    }

    @Test
    void aProofCannotBeRelabelledAsADifferentPosition() {
        MerkleProof proof = MerkleTree.of(hashes(24)).proof(4);
        assertEquals(4, proof.impliedIndex());

        // The path for #4 cannot be passed off as #5, or as any other position.
        assertFalse(new MerkleProof(proof.root(), 5, proof.size(), proof.entryHash(), proof.path()).verify());
        assertFalse(new MerkleProof(proof.root(), 0, proof.size(), proof.entryHash(), proof.path()).verify());
    }

    @Test
    void aPathWithReorderedOrDuplicatedLevelsIsRejected() {
        MerkleTree tree = MerkleTree.of(hashes(8));
        MerkleProof proof = tree.proof(3);

        List<MerkleProof.Step> reversed = new ArrayList<>(proof.path());
        java.util.Collections.reverse(reversed);
        assertFalse(new MerkleProof(proof.root(), proof.index(), proof.size(), proof.entryHash(), reversed)
                .verify());

        List<MerkleProof.Step> doubled = new ArrayList<>(proof.path());
        MerkleProof.Step first = doubled.getFirst();
        doubled.add(0, new MerkleProof.Step(first.sibling(), first.siblingIsLeft(), first.level()));
        assertFalse(new MerkleProof(proof.root(), proof.index(), proof.size(), proof.entryHash(), doubled)
                .verify());
    }

    @Test
    void proofLengthIsLogarithmicSoItLeaksLittle() {
        MerkleTree tree = MerkleTree.of(hashes(1024));
        MerkleProof proof = tree.proof(700);
        assertEquals(10, proof.pathLength());
        assertEquals(10, tree.depth());
    }

    @Test
    void aProofRevealsNoOtherEntryHash() {
        List<LoggedEvent> entries = slice(8);
        MerkleTree tree = MerkleTree.of(entries.stream().map(LoggedEvent::hash).toList());
        MerkleProof proof = tree.proof(3);
        String json = proof.toJson();

        // The published proof carries exactly one entry hash: the one it is proving.
        for (LoggedEvent entry : entries) {
            if (!entry.hash().equals(proof.entryHash())) {
                assertFalse(json.contains(entry.hash()),
                        "the proof for #3 leaked entry " + entry.index());
            }
        }
        assertTrue(json.contains(proof.entryHash()));
    }

    @Test
    void changingAnySingleEntryChangesTheRoot() {
        List<String> entries = new ArrayList<>(hashes(9));
        String original = MerkleTree.of(entries).root();
        for (int i = 0; i < entries.size(); i++) {
            List<String> tampered = new ArrayList<>(entries);
            tampered.set(i, Hashes.sha256Hex("tampered-" + i));
            assertNotEquals(original, MerkleTree.of(tampered).root(), "entry " + i + " did not affect the root");
        }
    }

    @Test
    void aTamperedEntryOrSiblingFailsVerification() {
        MerkleTree tree = MerkleTree.of(hashes(6));
        MerkleProof proof = tree.proof(4);

        MerkleProof forgedEntry = new MerkleProof(proof.root(), proof.index(), proof.size(),
                Hashes.sha256Hex("a different event"), proof.path());
        assertFalse(forgedEntry.verify());

        List<MerkleProof.Step> steps = new ArrayList<>(proof.path());
        MerkleProof.Step step = steps.getFirst();
        steps.set(0, new MerkleProof.Step(Hashes.sha256Hex("a different sibling"),
                step.siblingIsLeft(), step.level()));
        assertFalse(new MerkleProof(proof.root(), proof.index(), proof.size(), proof.entryHash(), steps).verify());

        // Flipping which side a sibling sits on must also fail, not accidentally pass.
        steps.set(0, new MerkleProof.Step(step.sibling(), !step.siblingIsLeft(), step.level()));
        assertFalse(new MerkleProof(proof.root(), proof.index(), proof.size(), proof.entryHash(), steps).verify());
    }

    @Test
    void aProofDoesNotVerifyAgainstADifferentTreesRoot() {
        MerkleProof proof = MerkleTree.of(hashes(5)).proof(2);
        String foreign = MerkleTree.of(hashes(6)).root();
        assertFalse(new MerkleProof(foreign, proof.index(), proof.size(), proof.entryHash(), proof.path())
                .verify());
    }

    @Test
    void proofsSurviveAJsonRoundTrip() {
        MerkleTree tree = MerkleTree.of(hashes(13));
        for (int index : new int[]{0, 5, 12}) {
            MerkleProof parsed = MerkleProof.fromJson(tree.proof(index).toJson());
            assertEquals(tree.proof(index), parsed);
            assertTrue(parsed.verify());
        }
    }

    @Test
    void anOutOfRangeIndexIsRejected() {
        MerkleTree tree = MerkleTree.of(hashes(4));
        assertThrows(IndexOutOfBoundsException.class, () -> tree.proof(4));
        assertThrows(IndexOutOfBoundsException.class, () -> tree.proof(-1));
    }

    @Test
    void aCommitmentPinsTheSliceAndItsChainPosition() {
        List<LoggedEvent> entries = slice(5);
        Commitment commitment = Commitment.of(entries);

        assertEquals(Commitment.FORMAT, commitment.format());
        assertEquals(5, commitment.entries());
        assertEquals(entries.getLast().hash(), commitment.chainHead());
        assertEquals(Hashes.GENESIS, commitment.priorHead());
        assertEquals("2026-10-19T00:00:00.000Z", commitment.firstEvent());
        assertEquals("2026-10-19T00:04:00.000Z", commitment.lastEvent());
        assertTrue(commitment.covers(entries));
        assertEquals(MerkleTree.of(entries.stream().map(LoggedEvent::hash).toList()).root(), commitment.merkleRoot());
    }

    @Test
    void aCommitmentOfASuffixRecordsTheLinkItStartsFrom() {
        List<LoggedEvent> entries = slice(5);
        List<LoggedEvent> suffix = entries.subList(2, 5);
        Commitment commitment = Commitment.of(suffix);
        assertEquals(entries.get(1).hash(), commitment.priorHead());
        assertEquals(entries.get(4).hash(), commitment.chainHead());
    }

    @Test
    void aCommitmentRejectsADifferentSlice() {
        List<LoggedEvent> entries = slice(5);
        Commitment commitment = Commitment.of(entries);
        assertFalse(commitment.covers(slice(4)));
        assertFalse(commitment.covers(slice(6)));
        assertFalse(commitment.covers(entries.subList(1, 5)));
        assertThrows(IllegalArgumentException.class, () -> commitment.treeOver(slice(4)));
    }

    @Test
    void aCommitmentRoundTripsThroughJson() {
        Commitment commitment = Commitment.of(slice(7));
        Commitment parsed = Commitment.fromJson(commitment.toJson());
        assertEquals(commitment, parsed);
        assertTrue(parsed.covers(slice(7)));
        String json = parsed.toJson();
        assertTrue(json.contains("\"rule\""), "the artifact must carry a rule field");
        // The rule's own quotes are JSON-escaped, so match a quote-free fragment of it.
        assertTrue(json.contains("odd node promoted unchanged"), "the artifact must explain its own hashing");
    }

    @Test
    void anEmptyCommitmentIsWellDefined() {
        Commitment commitment = Commitment.of(List.of());
        assertEquals(0, commitment.entries());
        assertEquals(MerkleTree.EMPTY_ROOT, commitment.merkleRoot());
        assertEquals(Hashes.GENESIS, commitment.chainHead());
        assertTrue(commitment.covers(List.of()));
        assertTrue(commitment.describe().contains("empty"));
    }
}

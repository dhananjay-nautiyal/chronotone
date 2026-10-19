package dev.chronotone.render;

import dev.chronotone.core.connector.SyntheticConnector;
import dev.chronotone.core.log.Event;
import dev.chronotone.dsl.DefaultMapping;
import dev.chronotone.dsl.MappingParser;
import dev.chronotone.dsl.MappingSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The load-bearing claim of phase 4: the incremental synth and the whole-buffer one are the same
 * instrument. If these fail, "live" and "archival" are different songs.
 */
class StreamingSynthTest {

    private static final MappingSpec MAPPING = new MappingParser().parse(DefaultMapping.YAML);
    private static final Instant DAY = Instant.parse("2026-10-19T18:00:00Z");
    private static final int MINUTES = 30;

    /** A realistic slice of a day: tempo, energy, hats and reverb all move, and every slot has notes. */
    private static List<Event> slice() {
        return new SyntheticConnector(42L).fetch(DAY, DAY.plus(24, ChronoUnit.HOURS));
    }

    private static Timeline timeline() {
        return Timeline.of(MAPPING, slice(), DAY, DAY.plus(MINUTES, ChronoUnit.MINUTES));
    }

    @Test
    void theStreamingMixIsSampleExactWithTheBatchMix() {
        // Different seeds exercise different lead motifs, and different slots exercise different tempos,
        // so voices start at different offsets inside a block and the bar length itself changes.
        for (long seed : new long[]{1L, 7L, 42L}) {
            Timeline timeline = timeline();
            List<NoteEvent> notes = new ScoreBuilder(timeline, seed).build();

            double[] batch = WavRenderer.mix(timeline, notes);
            double[] streamed = new StreamingSynth(timeline, notes).mix();

            assertBitIdentical(batch, streamed, "seed " + seed);
        }
    }

    @Test
    void aVaryingTempoKeepsStreamingAndBatchInStep() {
        // Busy slots are faster bars than quiet ones, so block lengths genuinely differ from each
        // other — which is the interesting case for a renderer that has to cut the signal up. The
        // synthetic day peaks in the late morning and late evening, so scan a few windows for one
        // where the tempo actually moves rather than assuming it.
        Timeline timeline = null;
        List<Integer> tempos = null;
        for (long hours : new long[]{16, 17, 4, 3, 5, 0}) {
            Instant start = DAY.plus(hours, ChronoUnit.HOURS);
            Timeline candidate = Timeline.of(MAPPING, slice(), start, start.plus(MINUTES, ChronoUnit.MINUTES));
            List<Integer> candidateTempos = new ScoreBuilder(candidate, 42L).tempos();
            if (candidateTempos.stream().distinct().count() > 1) {
                timeline = candidate;
                tempos = candidateTempos;
                break;
            }
        }
        assertNotNull(timeline, "no fixture window had a moving tempo, so the test proves nothing");
        assertTrue(tempos.stream().distinct().count() > 1, "fixture should have a moving tempo: " + tempos);

        List<NoteEvent> notes = new ScoreBuilder(timeline, 42L).build();
        assertBitIdentical(WavRenderer.mix(timeline, notes), new StreamingSynth(timeline, notes).mix(), "tempo");
    }

    @Test
    void blocksTileTheSignalAndEachOneMatchesItsSlice() {
        Timeline timeline = timeline();
        List<NoteEvent> notes = new ScoreBuilder(timeline, 42L).build();
        double[] batch = WavRenderer.mix(timeline, notes);

        StreamingSynth synth = new StreamingSynth(timeline, notes);
        assertEquals(timeline.size() + 1, synth.blocks(), "one block per slot, plus the release tail");

        int at = 0;
        while (synth.hasNext()) {
            int start = synth.nextBlockStart();
            assertEquals(at, start, "blocks must tile the signal with no gap and no overlap");

            double[] block = synth.nextBlock();
            assertTrue(block.length > 0, "a block must not be empty");
            assertBitIdentical(Arrays.copyOfRange(batch, start, start + block.length), block,
                    "block starting at sample " + start);

            at += block.length;
        }
        assertEquals(batch.length, at, "the blocks must account for every sample, tail included");
        assertEquals(synth.totalSamples(), at);
    }

    @Test
    void theFinalBlockIsTheTwoSecondReleaseTail() {
        Timeline timeline = timeline();
        List<NoteEvent> notes = new ScoreBuilder(timeline, 42L).build();
        WavRenderer.Geometry geometry = WavRenderer.geometry(timeline);

        int tail = geometry.totalSamples() - geometry.bounds()[timeline.size()];
        assertTrue(Math.abs(tail - 2 * WavRenderer.SAMPLE_RATE) <= 1,
                "expected a ~2s tail, got " + tail + " samples");
    }

    @Test
    void theReverbDelayLinesAreCarriedFromOneBlockToTheNext() {
        Timeline timeline = timeline();
        assertTrue(WavRenderer.meanReverb(timeline) > 0.001,
                "this fixture is meant to have reverb, otherwise the carried state proves nothing");

        List<NoteEvent> notes = new ScoreBuilder(timeline, 42L).build();
        double[] batch = WavRenderer.mix(timeline, notes);
        StreamingSynth synth = new StreamingSynth(timeline, notes);

        // Walk block by block: the last sample of a block feeds the first samples of the next through
        // the combs, so a per-block reset of the state would show up here immediately.
        double[] streamed = new double[batch.length];
        int at = 0;
        while (synth.hasNext()) {
            double[] block = synth.nextBlock();
            System.arraycopy(block, 0, streamed, at, block.length);
            at += block.length;
        }
        assertBitIdentical(batch, streamed, "carried reverb");
    }

    @Test
    void theTailBlockCarriesTheReleaseOfTheLastBar() {
        Timeline timeline = timeline();
        List<NoteEvent> notes = new ScoreBuilder(timeline, 42L).build();
        WavRenderer.Geometry geometry = WavRenderer.geometry(timeline);

        StreamingSynth synth = new StreamingSynth(timeline, notes);
        double[] tail = null;
        while (synth.hasNext()) {
            tail = synth.nextBlock();
        }
        assertTrue(synth.blocks() == timeline.size() + 1);
        assertEquals(geometry.totalSamples() - geometry.bounds()[timeline.size()], tail.length);

        // The pad of the final bar is still sounding after its slot ends, so the tail must not be silent.
        double loudest = 0.0;
        for (double sample : tail) {
            loudest = Math.max(loudest, Math.abs(sample));
        }
        assertTrue(loudest > 0.0, "the release tail was silent — the last bar is being cut off");
    }

    @Test
    void anExhaustedSynthRefusesToHandOutMoreBlocks() {
        Timeline timeline = timeline();
        List<NoteEvent> notes = new ScoreBuilder(timeline, 42L).build();
        StreamingSynth synth = new StreamingSynth(timeline, notes);

        int emitted = 0;
        while (synth.hasNext()) {
            synth.nextBlock();
            emitted++;
        }
        assertEquals(synth.blocks(), emitted);
        assertFalse(synth.hasNext());
        assertThrows(NoSuchElementException.class, synth::nextBlock);
    }

    @Test
    void aSingleSlotWindowIsOneBlockPlusTheTail() {
        // The smallest window a station can ask for: one slot of music, then the release.
        Timeline timeline = Timeline.of(MAPPING, List.of(), DAY, DAY.plus(1, ChronoUnit.MINUTES));
        List<NoteEvent> notes = new ScoreBuilder(timeline, 42L).build();
        StreamingSynth synth = new StreamingSynth(timeline, notes);

        assertEquals(1, timeline.size());
        assertEquals(2, synth.blocks());

        double[] first = synth.nextBlock();
        double[] tail = synth.nextBlock();
        assertTrue(first.length > 0 && tail.length > 0);
        assertFalse(synth.hasNext());
        assertBitIdentical(WavRenderer.mix(timeline, notes), concat(first, tail), "single slot");
    }

    private static double[] concat(double[] first, double[] second) {
        double[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    /** Bitwise, not approximate: the contract is about bytes, so -0.0 vs 0.0 and 1ulp both matter. */
    private static void assertBitIdentical(double[] expected, double[] actual, String message) {
        assertEquals(expected.length, actual.length, message + ": length");
        for (int i = 0; i < expected.length; i++) {
            if (Double.doubleToLongBits(expected[i]) != Double.doubleToLongBits(actual[i])) {
                fail(message + ": first difference at sample " + i
                        + " (" + expected[i] + " vs " + actual[i] + ")");
            }
        }
    }
}

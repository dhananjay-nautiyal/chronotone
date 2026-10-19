package dev.chronotone.render;

import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.Hashes;
import dev.chronotone.dsl.MappingSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Live station mode: the day as it happens, instead of the day once it is over.
 *
 * <p>A station frame is the same music as {@link Chronotone#render} over the same window, rendered
 * through {@link StreamingSynth} instead of in one pass. The window always ends on a grid boundary,
 * so slots land on the wall clock a listener would expect (a minute, a quarter hour — whatever the
 * mapping's grid says), and the frames of two processes at the same instant are byte-identical.
 *
 * <p>Two properties are worth stating plainly, because tests pin both of them:
 *
 * <ul>
 *   <li><b>Same bytes as a batch render.</b> {@code frame(...).wav()} equals
 *       {@code Chronotone.render(...).wav()} for the same window: going live costs nothing in
 *       fidelity, because the incremental synth is sample-exact.
 *   <li><b>Only the window is buffered.</b> Memory is bounded by the window, not by the length of the
 *       session, so a station can run all night.
 * </ul>
 *
 * <p>What a station does <em>not</em> promise is a single normalisation across the whole session.
 * {@link WavRenderer#normalize} needs the peak of the signal being mastered, and the peak of tomorrow
 * is not knowable today, so gain is derived per window. Each frame is an honest master of its own
 * window; the archival master of a whole day is still a {@code render}.
 */
public final class Station {

    public static final String FORMAT = "chronotone.station/1";
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(30);

    private Station() {
    }

    /**
     * One rendered window: the audio, the hashes, and the slot geometry a caller needs to slice it.
     *
     * @param bounds sample offsets into {@link #wav}; slot {@code k} spans {@code bounds[k]} to
     *               {@code bounds[k + 1]}, so {@code bounds} has {@code slots + 2} entries (the last
     *               block is the release tail)
     */
    public record Frame(String timelineFingerprint, String wavHash, Instant from, Instant to, int slots,
                        int notes, double seconds, int[] bounds, byte[] wav, Timeline timeline) {

        public long wavBytes() {
            return wav.length;
        }

        /** The archival 16-bit PCM of one block — a slot, or the tail block at index {@code slots}. */
        public byte[] pcmOfBlock(int block) {
            int bytesPerSample = WavRenderer.BITS_PER_SAMPLE / 8;
            int start = WavRenderer.HEADER_BYTES + bounds[block] * bytesPerSample;
            int end = WavRenderer.HEADER_BYTES + bounds[block + 1] * bytesPerSample;
            return Arrays.copyOfRange(wav, start, end);
        }

        public String summary() {
            return "%d slots | %d notes | %.1f s | window %s -> %s | timeline %s".formatted(
                    slots, notes, seconds, Event.formatTimestamp(from), Event.formatTimestamp(to),
                    timelineFingerprint.substring(0, 12));
        }
    }

    /** Truncates an instant down to a grid boundary, so slots move with the wall clock. */
    public static Instant align(Instant at, Duration grid) {
        long step = grid.toMillis();
        if (step <= 0) {
            throw new IllegalArgumentException("grid must be positive");
        }
        return Instant.ofEpochMilli(StrictMath.floorDiv(at.toEpochMilli(), step) * step);
    }

    /**
     * The cursor a fresh listener starts from: everything older than the slot that has just closed is
     * history, so the first frame streams one slot rather than a whole window of backlog.
     */
    public static Instant listenFrom(Frame frame) {
        return frame.to().minus(frame.timeline().mapping().grid().multipliedBy(2));
    }

    /**
     * The blocks of a frame that closed after {@code through}, in order. These are the blocks a live
     * station has not played yet; the tail block is deliberately excluded, since it belongs to the
     * final release rather than to a clock slot.
     */
    public static List<Integer> blocksAfter(Frame frame, Instant through) {
        List<Integer> blocks = new ArrayList<>();
        for (int slot = 0; slot < frame.slots(); slot++) {
            if (frame.timeline().slot(slot).isAfter(through)) {
                blocks.add(slot);
            }
        }
        return blocks;
    }

    /**
     * Renders the window of {@code window} length that ends at the grid boundary at or before
     * {@code now}. A pure function of its inputs apart from {@code now}: pin {@code now} and the same
     * inputs give the same {@link Frame#wavHash()}.
     */
    public static Frame frame(MappingSpec mapping, List<Event> events, Instant now, Duration window, long seed) {
        if (window.compareTo(mapping.grid()) < 0) {
            throw new IllegalArgumentException("the window (" + window + ") must be at least one grid step ("
                    + mapping.grid() + ")");
        }
        Instant to = align(now, mapping.grid());
        Instant from = to.minus(window);

        Timeline timeline = Timeline.of(mapping, events, from, to);
        List<NoteEvent> notes = new ScoreBuilder(timeline, seed).build();
        WavRenderer.Geometry geometry = WavRenderer.geometry(timeline);
        byte[] wav = WavRenderer.encode(WavRenderer.normalize(new StreamingSynth(timeline, notes).mix(),
                WavRenderer.PEAK));
        return new Frame(timeline.fingerprint(), Hashes.sha256Hex(wav), from, to, timeline.size(),
                notes.size(), WavRenderer.secondsOf(wav), geometry.bounds(), wav, timeline);
    }
}

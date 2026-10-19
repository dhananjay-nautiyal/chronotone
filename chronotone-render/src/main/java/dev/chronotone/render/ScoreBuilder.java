package dev.chronotone.render;

import dev.chronotone.core.music.MusicalParams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Turns a timeline into notes. One slot is one bar in 4/4; every decision inside a bar is drawn from
 * a {@link Random} seeded by a mixing function of {@code (seed, slot)}, so a bar is reproducible but
 * never identical to its neighbours.
 *
 * <p>Only integer ticks and a specified PRNG algorithm are used: no floating-point accumulation,
 * no iteration over unordered collections, no wall-clock time.
 */
public final class ScoreBuilder {

    public static final int PPQ = 480;
    public static final int BEATS_PER_BAR = 4;
    public static final long BAR_TICKS = (long) BEATS_PER_BAR * PPQ;

    /** i - vi - iv - v in scale degrees: a progression that works in every mode. */
    private static final int[] PROGRESSION = {0, 5, 3, 4};

    private static final int PAD_CHANNEL = 0;
    private static final int BASS_CHANNEL = 1;
    private static final int LEAD_CHANNEL = 2;
    private static final int DRUM_CHANNEL = 9;
    private static final int KICK = 36;
    private static final int CLOSED_HAT = 42;

    private final Timeline timeline;
    private final long seed;

    public ScoreBuilder(Timeline timeline, long seed) {
        this.timeline = timeline;
        this.seed = seed;
    }

    public long totalTicks() {
        return BAR_TICKS * timeline.size();
    }

    public List<Integer> tempos() {
        List<Integer> tempos = new ArrayList<>(timeline.size());
        for (int i = 0; i < timeline.size(); i++) {
            tempos.add(timeline.params(i).tempoBpm());
        }
        return tempos;
    }

    public List<NoteEvent> build() {
        List<NoteEvent> notes = new ArrayList<>();
        for (int slot = 0; slot < timeline.size(); slot++) {
            MusicalParams p = timeline.params(slot);
            long bar = (long) slot * BAR_TICKS;
            Random rng = new Random(mix(seed, slot));
            int degree = PROGRESSION[slot % PROGRESSION.length];

            // Pad: a sustained triad, brighter when the sky is clear.
            int padVelocity = clamp((int) Math.round(24 + p.brightness() * 46));
            for (int interval : new int[]{0, 2, 4}) {
                notes.add(new NoteEvent(bar, p.midiNote(degree + interval, 4), padVelocity, BAR_TICKS, PAD_CHANNEL));
            }

            // Bass: downbeat, plus an offbeat push on high-energy bars.
            int bassVelocity = clamp((int) Math.round(44 + p.energy() * 60));
            notes.add(new NoteEvent(bar, p.midiNote(degree, 2), bassVelocity, PPQ * 2L, BASS_CHANNEL));
            if (p.energy() > 0.45) {
                notes.add(new NoteEvent(bar + PPQ * 2L, p.midiNote(degree, 2),
                        clamp(bassVelocity - 10), PPQ * 2L, BASS_CHANNEL));
            }

            // Drums: kick on 1 and 3, hats driven by how much music you had on.
            notes.add(new NoteEvent(bar, KICK, clamp((int) Math.round(58 + p.energy() * 50)), 60, DRUM_CHANNEL));
            notes.add(new NoteEvent(bar + PPQ * 2L, KICK, clamp((int) Math.round(48 + p.energy() * 42)), 60, DRUM_CHANNEL));
            int hats = (int) Math.round(p.hatDensity() * BEATS_PER_BAR * 2);
            for (int i = 0; i < hats; i++) {
                notes.add(new NoteEvent(bar + (long) i * (PPQ / 2L), CLOSED_HAT,
                        clamp(46 + rng.nextInt(36)), 40, DRUM_CHANNEL));
            }

            // Lead: a short motif whose length grows with energy.
            int leadNotes = 1 + (int) Math.round(p.energy() * 4);
            for (int i = 0; i < leadNotes; i++) {
                int offset = rng.nextInt(7);
                int octave = 5 + (rng.nextInt(3) == 0 ? 1 : 0);
                long start = bar + (long) rng.nextInt(BEATS_PER_BAR) * PPQ;
                long duration = PPQ / 2L + rng.nextInt(PPQ);
                notes.add(new NoteEvent(start, p.midiNote(degree + offset, octave),
                        clamp(50 + rng.nextInt(46)), duration, LEAD_CHANNEL));
            }
        }
        notes.sort(Comparator.comparingLong(NoteEvent::tick)
                .thenComparingInt(NoteEvent::channel)
                .thenComparingInt(NoteEvent::midiNote));
        return List.copyOf(notes);
    }

    /** Stable per-bar seed. Mixing avoids the correlated first draws of adjacent java.util.Random seeds. */
    static long mix(long seed, int slot) {
        long h = seed * 0x9E3779B97F4A7C15L + slot * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 29);
        return h;
    }

    private static int clamp(int value) {
        return Math.clamp(value, 1, 127);
    }
}

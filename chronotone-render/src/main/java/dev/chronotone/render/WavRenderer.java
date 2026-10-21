package dev.chronotone.render;

import dev.chronotone.core.music.MusicalParams;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny additive synth and 16-bit PCM WAV writer, so that hearing a day requires no soundfont, no
 * audio device and no third-party library.
 *
 * <p>Everything uses {@link StrictMath} and integer-indexed noise, so the same score yields the same
 * bytes on every machine — that is why the WAV can be hashed and published like any other artifact.
 *
 * <p>The pipeline is split into three stages on purpose: {@link #mix} builds the raw signal,
 * {@link #normalize} sets its peak, and {@link #encode} packs it. Only the first two stages need the
 * whole signal, and only the second needs a global peak — which is exactly why a live station can
 * stream the mix ({@link StreamingSynth}) but must buffer a window before mastering it.
 */
public final class WavRenderer {

    public static final int SAMPLE_RATE = 44_100;
    public static final int CHANNELS = 1;
    public static final int BITS_PER_SAMPLE = 16;
    public static final int HEADER_BYTES = 44;

    /** Every render is normalised to this peak, so loud days and quiet ones stay comparable. */
    public static final double PEAK = 0.89;

    static final double TAIL_SECONDS = 2.0;

    private WavRenderer() {
    }

    public static byte[] toWav(Timeline timeline, List<NoteEvent> notes) {
        return encode(normalize(mix(timeline, notes), PEAK));
    }

    /** The raw, pre-normalisation signal. {@link StreamingSynth} must reproduce this exactly. */
    public static double[] mix(Timeline timeline, List<NoteEvent> notes) {
        Geometry geometry = geometry(timeline);
        List<Placement> placements = placements(timeline, notes, geometry);
        double[] buffer = new double[geometry.totalSamples()];
        for (Placement placement : placements) {
            addVoice(buffer, 0, timeline.params(placement.slot()), placement.note(),
                    placement.startSeconds(), placement.durationSeconds());
        }
        applySpace(buffer, meanReverb(timeline));
        return buffer;
    }

    public static double[] normalize(double[] buffer, double peak) {
        double loudest = 0.0;
        for (double sample : buffer) {
            loudest = Math.max(loudest, Math.abs(sample));
        }
        if (loudest < 1e-9) {
            return buffer;
        }
        double scale = peak / loudest;
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] *= scale;
        }
        return buffer;
    }

    public static byte[] encode(double[] buffer) {
        int dataBytes = buffer.length * (BITS_PER_SAMPLE / 8);
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.putInt(36 + dataBytes);
        out.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        out.putInt(16);
        out.putShort((short) 1);
        out.putShort((short) CHANNELS);
        out.putInt(SAMPLE_RATE);
        out.putInt(SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8));
        out.putShort((short) (CHANNELS * (BITS_PER_SAMPLE / 8)));
        out.putShort((short) BITS_PER_SAMPLE);
        out.put("data".getBytes(StandardCharsets.US_ASCII));
        out.putInt(dataBytes);
        for (double sample : buffer) {
            int value = (int) StrictMath.round(Math.clamp(sample, -1.0, 1.0) * 32_767.0);
            out.putShort((short) value);
        }
        return out.array();
    }

    /** The WAV header alone, so a station can start writing before it knows the final length. */
    public static byte[] header(int dataBytes) {
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.putInt(36 + dataBytes);
        out.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        out.putInt(16);
        out.putShort((short) 1);
        out.putShort((short) CHANNELS);
        out.putInt(SAMPLE_RATE);
        out.putInt(SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8));
        out.putShort((short) (CHANNELS * (BITS_PER_SAMPLE / 8)));
        out.putShort((short) BITS_PER_SAMPLE);
        out.put("data".getBytes(StandardCharsets.US_ASCII));
        out.putInt(dataBytes);
        return out.array();
    }

    public static double secondsOf(byte[] wav) {
        int dataBytes = Math.max(0, wav.length - HEADER_BYTES);
        return dataBytes / (double) (SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8));
    }

    public static short toPcm(double sample) {
        return (short) (int) StrictMath.round(Math.clamp(sample, -1.0, 1.0) * 32_767.0);
    }

    /** One slot is one bar, so tempo decides how many real seconds a slot occupies. */
    static double secondsPerBar(MusicalParams params) {
        return ScoreBuilder.BEATS_PER_BAR * 60.0 / params.tempoBpm();
    }

    /**
     * Sample geometry of a timeline: where each slot begins in samples, the length of the tail, and
     * the total sample count. Shared so that a whole-buffer render and a block-by-block one cannot
     * disagree about where a slot starts.
     */
    record Geometry(double[] slotStart, double[] secondsPerTick, int[] bounds, int totalSamples) {

        int blocks() {
            return bounds.length - 1;
        }

        /** The block that owns an absolute sample index. */
        int blockOf(int sample) {
            int low = 0;
            int high = bounds.length - 2;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (bounds[mid] <= sample) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return low;
        }
    }

    static Geometry geometry(Timeline timeline) {
        int slots = timeline.size();
        double[] slotStart = new double[slots + 1];
        double[] secondsPerTick = new double[slots];
        int[] bounds = new int[slots + 2];
        for (int i = 0; i < slots; i++) {
            double secondsPerBar = secondsPerBar(timeline.params(i));
            slotStart[i + 1] = slotStart[i] + secondsPerBar;
            secondsPerTick[i] = secondsPerBar / ScoreBuilder.BAR_TICKS;
            bounds[i] = sampleAt(slotStart[i]);
        }
        bounds[slots] = sampleAt(slotStart[slots]);
        int totalSamples = (int) StrictMath.ceil((slotStart[slots] + TAIL_SECONDS) * SAMPLE_RATE);
        bounds[slots + 1] = totalSamples;
        return new Geometry(slotStart, secondsPerTick, bounds, totalSamples);
    }

    /** Where one note sits in real time, and in which slot. */
    record Placement(int slot, NoteEvent note, double startSeconds, double durationSeconds) {
    }

    static List<Placement> placements(Timeline timeline, List<NoteEvent> notes, Geometry geometry) {
        int slots = timeline.size();
        List<Placement> placements = new ArrayList<>(notes.size());
        for (NoteEvent note : notes) {
            int slot = (int) Math.min(slots - 1L, note.tick() / ScoreBuilder.BAR_TICKS);
            double start = geometry.slotStart()[slot]
                    + (note.tick() - (long) slot * ScoreBuilder.BAR_TICKS) * geometry.secondsPerTick()[slot];
            double duration = note.durationTicks() * geometry.secondsPerTick()[slot];
            placements.add(new Placement(slot, note, start, duration));
        }
        return placements;
    }

    static int sampleAt(double seconds) {
        return (int) StrictMath.round(seconds * SAMPLE_RATE);
    }

    /** Samples a voice occupies, release included. */
    static int voiceSamples(NoteEvent note, double durationSeconds) {
        return (int) StrictMath.round((durationSeconds + release(timbreOf(note))) * SAMPLE_RATE);
    }

    enum Timbre {PAD, BASS, LEAD, KICK, HAT}

    static Timbre timbreOf(NoteEvent note) {
        if (note.channel() == 9) {
            return note.midiNote() == 36 ? Timbre.KICK : Timbre.HAT;
        }
        return switch (note.channel()) {
            case 0 -> Timbre.PAD;
            case 1 -> Timbre.BASS;
            default -> Timbre.LEAD;
        };
    }

    /**
     * Adds one voice into {@code buffer}, where {@code buffer[0]} is absolute sample
     * {@code bufferStartSample}. Voices are placed by absolute sample index and the hat's noise is
     * keyed on the absolute index too, so rendering into a whole buffer or into blocks lands on
     * identical samples.
     */
    static void addVoice(double[] buffer, int bufferStartSample, MusicalParams params, NoteEvent note,
                         double startSeconds, double durationSeconds) {
        Timbre timbre = timbreOf(note);
        double amplitude = StrictMath.pow(note.velocity() / 127.0, 1.6) * gain(timbre);
        double release = release(timbre);
        double frequency = frequency(note.midiNote());

        int startSample = sampleAt(startSeconds);
        int voiceSamples = (int) StrictMath.round((durationSeconds + release) * SAMPLE_RATE);

        int first = Math.max(0, startSample - bufferStartSample);
        int last = Math.min(buffer.length, startSample + voiceSamples - bufferStartSample);
        for (int j = first; j < last; j++) {
            int absolute = bufferStartSample + j;
            double elapsed = (double) (absolute - startSample) / SAMPLE_RATE;
            double envelope = envelope(timbre, elapsed, durationSeconds);
            if (envelope <= 0.0) {
                continue;
            }
            buffer[j] += amplitude * envelope * waveform(timbre, frequency, elapsed, params, absolute);
        }
    }

    private static double envelope(Timbre timbre, double t, double duration) {
        double attack = switch (timbre) {
            case PAD -> 0.45;
            case BASS -> 0.008;
            case LEAD -> 0.012;
            case KICK, HAT -> 0.0;
        };
        if (t < attack) {
            return t / attack;
        }
        double into = t - duration;
        if (into <= 0.0) {
            return 1.0;
        }
        return switch (timbre) {
            case PAD -> Math.max(0.0, 1.0 - into / 1.1);
            case BASS -> Math.max(0.0, 1.0 - into / 0.15);
            case LEAD -> Math.max(0.0, 1.0 - into / 0.20);
            case KICK -> StrictMath.exp(-t * 20.0);
            case HAT -> StrictMath.exp(-t * 55.0);
        };
    }

    private static double waveform(Timbre timbre, double frequency, double t, MusicalParams params, int sampleIndex) {
        double phase = 2.0 * StrictMath.PI * frequency * t;
        return switch (timbre) {
            case PAD -> StrictMath.sin(phase)
                    + 0.32 * StrictMath.sin(2.0 * phase)
                    + 0.14 * StrictMath.sin(3.0 * phase)
                    + (0.05 + 0.18 * params.brightness()) * StrictMath.sin(5.0 * phase);
            case BASS -> StrictMath.sin(phase)
                    + 0.30 * StrictMath.sin(2.0 * phase)
                    + 0.10 * StrictMath.sin(3.0 * phase);
            case LEAD -> StrictMath.sin(phase)
                    + 0.40 * StrictMath.sin(2.0 * phase)
                    + 0.22 * StrictMath.sin(3.0 * phase)
                    + 0.12 * StrictMath.sin(5.0 * phase);
            case KICK -> {
                double sweep = 48.0 + 150.0 * StrictMath.exp(-t * 18.0);
                yield StrictMath.sin(2.0 * StrictMath.PI * sweep * t);
            }
            case HAT -> noise(sampleIndex);
        };
    }

    /** Deterministic white noise: a hash of the sample index, so it survives being rendered anywhere. */
    private static double noise(int index) {
        int x = index * 0x9E3779B1;
        x ^= x >>> 15;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        return Math.clamp(x / (double) Integer.MAX_VALUE, -1.0, 1.0);
    }

    private static double gain(Timbre timbre) {
        return switch (timbre) {
            case PAD -> 0.15;
            case BASS -> 0.20;
            case LEAD -> 0.11;
            case KICK -> 0.34;
            case HAT -> 0.08;
        };
    }

    private static double release(Timbre timbre) {
        return switch (timbre) {
            case PAD -> 1.1;
            case BASS -> 0.15;
            case LEAD -> 0.20;
            case KICK -> 0.25;
            case HAT -> 0.05;
        };
    }

    private static double frequency(int midiNote) {
        return 440.0 * StrictMath.pow(2.0, (midiNote - 69) / 12.0);
    }

    static double meanReverb(Timeline timeline) {
        if (timeline.size() == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (MusicalParams p : timeline.params()) {
            total += p.reverbWet();
        }
        return total / timeline.size();
    }

    /** Two feedback combs: crude, cheap, fully deterministic, and enough to suggest a room. */
    static void applySpace(double[] buffer, double wet) {
        if (wet <= 0.001) {
            return;
        }
        Comb first = new Comb((int) (0.037 * SAMPLE_RATE), 0.42 * wet);
        Comb second = new Comb((int) (0.061 * SAMPLE_RATE), 0.34 * wet);
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = second.process(first.process(buffer[i]));
        }
    }

    /**
     * A delay line with feedback. Causal, so it can run across block boundaries as long as the state
     * is carried — which is what lets the live synth sound exactly like the batch one.
     */
    static final class Comb {

        private final double[] line;
        private final double feedback;
        private int cursor;

        Comb(int delay, double feedback) {
            this.line = new double[delay];
            this.feedback = feedback;
        }

        double process(double sample) {
            double out = sample + feedback * line[cursor];
            line[cursor] = out;
            cursor++;
            if (cursor == line.length) {
                cursor = 0;
            }
            return out;
        }
    }
}

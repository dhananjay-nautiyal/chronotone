package dev.chronotone.render;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The same synth as {@link WavRenderer}, but it hands out one slot at a time.
 *
 * <p>A live station cannot wait for the end of a day before it starts playing, and it should not have
 * to re-synthesize the whole morning every time another minute closes. This class walks the timeline
 * slot by slot, keeping only the reverb's delay lines as state, and produces exactly the samples the
 * whole-buffer renderer would have produced at the same indices.
 *
 * <p>That equality is the point of the class. {@link #mix()} must be sample-for-sample identical to
 * {@link WavRenderer#mix}, and a test asserts it: if the two ever disagreed, "live" and "archival"
 * would be two different songs, which is exactly what the determinism contract exists to prevent.
 *
 * <p>Only the mix is streamable. {@link WavRenderer#normalize} needs the peak of the whole signal, so
 * a station buffers one window before it masters it — hence the split of the pipeline into stages.
 */
public final class StreamingSynth {

    private final Timeline timeline;
    private final WavRenderer.Geometry geometry;
    private final List<WavRenderer.Placement> placements;
    private final List<List<Integer>> voicesByBlock;
    private final boolean spacious;
    private final WavRenderer.Comb first;
    private final WavRenderer.Comb second;

    private int cursor;

    public StreamingSynth(Timeline timeline, List<NoteEvent> notes) {
        this.timeline = timeline;
        this.geometry = WavRenderer.geometry(timeline);
        this.placements = WavRenderer.placements(timeline, notes, geometry);
        this.voicesByBlock = new ArrayList<>(geometry.blocks());
        for (int i = 0; i < geometry.blocks(); i++) {
            voicesByBlock.add(new ArrayList<>());
        }
        double wet = WavRenderer.meanReverb(timeline);
        this.spacious = wet > 0.001;
        this.first = spacious ? new WavRenderer.Comb((int) (0.037 * WavRenderer.SAMPLE_RATE), 0.42 * wet) : null;
        this.second = spacious ? new WavRenderer.Comb((int) (0.061 * WavRenderer.SAMPLE_RATE), 0.34 * wet) : null;
        indexVoices();
    }

    /**
     * Files every voice under each block its samples reach. Placing them by absolute sample index,
     * and visiting voices in the order the score produced them, keeps summation order identical to
     * the whole-buffer render — floating-point addition is not associative, so order is part of the
     * contract, not an implementation detail.
     */
    private void indexVoices() {
        int total = geometry.totalSamples();
        for (int voice = 0; voice < placements.size(); voice++) {
            WavRenderer.Placement placement = placements.get(voice);
            int start = WavRenderer.sampleAt(placement.startSeconds());
            int end = Math.min(total, start + WavRenderer.voiceSamples(placement.note(), placement.durationSeconds()));
            if (end <= 0 || start >= total) {
                continue;
            }
            int from = geometry.blockOf(Math.max(start, 0));
            int to = geometry.blockOf(end - 1);
            for (int block = from; block <= to; block++) {
                voicesByBlock.get(block).add(voice);
            }
        }
    }

    /** One block per slot, plus a final block for the release tail. */
    public int blocks() {
        return geometry.blocks();
    }

    public int totalSamples() {
        return geometry.totalSamples();
    }

    public boolean hasNext() {
        return cursor < geometry.blocks();
    }

    /** Absolute sample index where the next block begins. */
    public int nextBlockStart() {
        return geometry.bounds()[cursor];
    }

    /**
     * The next slot's samples, with reverb applied across the block boundary. The block is a view of
     * the same signal a whole-buffer render would have produced over the same indices.
     */
    public double[] nextBlock() {
        if (!hasNext()) {
            throw new NoSuchElementException("all " + geometry.blocks() + " blocks have been rendered");
        }
        int from = geometry.bounds()[cursor];
        int to = geometry.bounds()[cursor + 1];
        double[] samples = new double[to - from];
        for (int voice : voicesByBlock.get(cursor)) {
            WavRenderer.Placement placement = placements.get(voice);
            WavRenderer.addVoice(samples, from, timeline.params(placement.slot()), placement.note(),
                    placement.startSeconds(), placement.durationSeconds());
        }
        if (spacious) {
            for (int i = 0; i < samples.length; i++) {
                samples[i] = second.process(first.process(samples[i]));
            }
        }
        cursor++;
        return samples;
    }

    /** Everything the synth has left, concatenated. Equal to {@link WavRenderer#mix} exactly. */
    public double[] mix() {
        double[] all = new double[geometry.totalSamples()];
        int at = 0;
        while (hasNext()) {
            double[] samples = nextBlock();
            System.arraycopy(samples, 0, all, at, samples.length);
            at += samples.length;
        }
        return all;
    }
}

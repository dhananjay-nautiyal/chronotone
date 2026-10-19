package dev.chronotone.render;

/**
 * A single note in the render. Ticks are counted at {@link ScoreBuilder#PPQ} resolution from the
 * start of the piece; channels follow General MIDI conventions (0 pad, 1 bass, 2 lead, 9 drums).
 */
public record NoteEvent(long tick, int midiNote, int velocity, long durationTicks, int channel) {

    public long endTick() {
        return tick + durationTicks;
    }
}

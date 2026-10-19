package dev.chronotone.render;

import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.Hashes;
import dev.chronotone.dsl.MappingSpec;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * The whole engine behind one call: events + mapping + window + seed in, MIDI and audio out.
 *
 * <pre>{@code
 * RenderResult result = Chronotone.render(mapping, events, from, to, 42L);
 * Files.write(Path.of("day.wav"), result.wav());
 * }</pre>
 *
 * The determinism contract lives here: this method is a pure function, so
 * {@code result.wavHash()} identifies the audio and nothing else has to be trusted.
 */
public final class Chronotone {

    private Chronotone() {
    }

    public static RenderResult render(MappingSpec mapping, List<Event> events, Instant from, Instant to, long seed)
            throws IOException {
        return render(Timeline.of(mapping, events, from, to), seed);
    }

    public static RenderResult render(Timeline timeline, long seed) throws IOException {
        List<NoteEvent> notes = new ScoreBuilder(timeline, seed).build();
        byte[] midi = MidiRenderer.toSmf(timeline, notes);
        byte[] wav = WavRenderer.toWav(timeline, notes);
        return new RenderResult(
                timeline.fingerprint(),
                Hashes.sha256Hex(midi),
                Hashes.sha256Hex(wav),
                timeline.size(),
                notes.size(),
                WavRenderer.secondsOf(wav),
                timeline.describe(),
                midi,
                wav);
    }
}

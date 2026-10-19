package dev.chronotone.render;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes a Standard MIDI File. Tempo changes follow the timeline slot by slot, so a busy hour is
 * literally faster in the exported MIDI that any DAW can open.
 */
public final class MidiRenderer {

    private static final int[] PROGRAMS = {89, 38, 81}; // pad 2 (warm), synth bass 1, lead 2 (sawtooth)

    private MidiRenderer() {
    }

    public static byte[] toSmf(Timeline timeline, List<NoteEvent> notes) throws IOException {
        try {
            Sequence sequence = new Sequence(Sequence.PPQ, ScoreBuilder.PPQ);
            Track track = sequence.createTrack();

            byte[] name = "Chronotone".getBytes(StandardCharsets.US_ASCII);
            track.add(new MidiEvent(new MetaMessage(0x03, name, name.length), 0));
            for (int channel = 0; channel < PROGRAMS.length; channel++) {
                track.add(new MidiEvent(
                        new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, PROGRAMS[channel], 0), 0));
            }

            for (int slot = 0; slot < timeline.size(); slot++) {
                int microsPerQuarter = (int) Math.round(60_000_000.0 / timeline.params(slot).tempoBpm());
                byte[] data = {
                        (byte) (microsPerQuarter >> 16),
                        (byte) (microsPerQuarter >> 8),
                        (byte) microsPerQuarter
                };
                track.add(new MidiEvent(new MetaMessage(0x51, data, 3), (long) slot * ScoreBuilder.BAR_TICKS));
            }

            long endTick = 0;
            for (NoteEvent note : notes) {
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, note.channel(), note.midiNote(),
                        note.velocity()), note.tick()));
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, note.channel(), note.midiNote(), 0),
                        note.endTick()));
                endTick = Math.max(endTick, note.endTick());
            }
            track.add(new MidiEvent(new MetaMessage(0x2F, new byte[0], 0), endTick + ScoreBuilder.PPQ));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MidiSystem.write(sequence, 1, out);
            return out.toByteArray();
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("failed to build the MIDI sequence", e);
        }
    }

    /** Helper for tests and tooling: the tick of the last event of a file. */
    static long lastTick(Sequence sequence) {
        long last = 0;
        for (Track track : sequence.getTracks()) {
            int count = track.size();
            if (count > 0) {
                MidiEvent event = track.get(count - 1);
                last = Math.max(last, event.getTick());
            }
        }
        return last;
    }
}

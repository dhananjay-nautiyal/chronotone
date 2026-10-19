package dev.chronotone.render;

/**
 * The artifacts of one render, plus the hashes that make them verifiable.
 *
 * @param timelineFingerprint the musical timeline's content address — publishable without events
 * @param midiHash            {@code sha256} of the Standard MIDI File
 * @param wavHash             {@code sha256} of the PCM audio
 * @param description         human-readable summary of how the day moved
 */
public record RenderResult(String timelineFingerprint, String midiHash, String wavHash, int slots,
                           int notes, double seconds, String description, byte[] midi, byte[] wav) {

    public long midiBytes() {
        return midi.length;
    }

    public long wavBytes() {
        return wav.length;
    }

    public String summary() {
        return "%d slots | %d notes | %.1f s | timeline %s".formatted(
                slots, notes, seconds, timelineFingerprint.substring(0, 12));
    }
}

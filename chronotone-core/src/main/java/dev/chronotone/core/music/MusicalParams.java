package dev.chronotone.core.music;

import java.util.Map;
import java.util.Set;

/**
 * The musical state of one slot in the timeline. This is the vocabulary the mapping DSL speaks:
 * a mapping document can only drive the parameters named in {@link #PARAM_NAMES}.
 *
 * @param tempoBpm       beats per minute, 40..240
 * @param energy         0..1, how hard the arrangement hits (velocity, bass drive)
 * @param brightness     0..1, pad overtone content
 * @param hatDensity     0..1, fraction of eighth notes that carry a hat
 * @param reverbWet      0..1, amount of space
 * @param rootPitchClass 0..11 (C..B), derived per day from the mapping salt
 * @param mode           scale, derived per day from the mapping salt
 */
public record MusicalParams(int tempoBpm, double energy, double brightness, double hatDensity,
                            double reverbWet, int rootPitchClass, Mode mode) {

    public static final Set<String> PARAM_NAMES =
            Set.of("tempo", "energy", "brightness", "hatDensity", "reverbWet");

    public MusicalParams {
        tempoBpm = clampInt(tempoBpm, 40, 240);
        energy = clamp01(energy);
        brightness = clamp01(brightness);
        hatDensity = clamp01(hatDensity);
        reverbWet = clamp01(reverbWet);
        rootPitchClass = Math.floorMod(rootPitchClass, 12);
        mode = mode == null ? Mode.MINOR : mode;
    }

    public static MusicalParams defaults() {
        return new MusicalParams(96, 0.35, 0.5, 0.25, 0.25, 9, Mode.MINOR);
    }

    /** Builds params from DSL output, falling back to {@code fallback} for anything the mapping omits. */
    public static MusicalParams from(Map<String, Double> values, MusicalParams fallback) {
        return new MusicalParams(
                (int) Math.round(values.getOrDefault("tempo", (double) fallback.tempoBpm())),
                values.getOrDefault("energy", fallback.energy()),
                values.getOrDefault("brightness", fallback.brightness()),
                values.getOrDefault("hatDensity", fallback.hatDensity()),
                values.getOrDefault("reverbWet", fallback.reverbWet()),
                fallback.rootPitchClass(),
                fallback.mode());
    }

    public MusicalParams withKey(int rootPitchClass, Mode mode) {
        return new MusicalParams(tempoBpm, energy, brightness, hatDensity, reverbWet, rootPitchClass, mode);
    }

    /** MIDI note for a scale degree in a given octave (octave 4 starts at middle C, note 60). */
    public int midiNote(int degree, int octave) {
        int pitchClass = Math.floorMod(rootPitchClass + mode.degree(degree), 12);
        return clampInt(12 * (octave + 1) + pitchClass, 0, 127);
    }

    private static double clamp01(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.clamp(value, min, max);
    }
}

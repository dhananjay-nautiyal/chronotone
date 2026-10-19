package dev.chronotone.core.music;

/** The scales a day can be rendered in. Intervals are semitone offsets from the root. */
public enum Mode {

    MINOR(new int[]{0, 2, 3, 5, 7, 8, 10}),
    DORIAN(new int[]{0, 2, 3, 5, 7, 9, 10}),
    MAJOR(new int[]{0, 2, 4, 5, 7, 9, 11}),
    PHRYGIAN(new int[]{0, 1, 3, 5, 7, 8, 10});

    private final int[] degrees;

    Mode(int[] degrees) {
        this.degrees = degrees;
    }

    public int size() {
        return degrees.length;
    }

    /** Semitone offset of a scale degree; degrees wrap in both directions. */
    public int degree(int index) {
        return degrees[Math.floorMod(index, degrees.length)];
    }
}

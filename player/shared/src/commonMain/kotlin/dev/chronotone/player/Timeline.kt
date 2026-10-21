package dev.chronotone.player

/**
 * Navigation over a rendered station frame.
 *
 * Three coordinate systems meet here:
 *
 *  - **wall clock** — the instants the engine laid out on a grid;
 *  - **slots** — the bars, one per grid step; they are what the mapping actually produced;
 *  - **samples** — where a slot begins inside the WAV.
 *
 * The only thing this class does is convert between them, and it takes the slot-to-sample table from
 * the manifest rather than computing it. That is the point of the whole design: the player can seek
 * to an instant exactly because the engine published where that instant starts.
 */
class Timeline(val manifest: StationManifest) {

    val slotCount: Int get() = manifest.slots
    val audioEndSamples: Int get() = manifest.audioEndSamples

    /** One past the audio of the last slot — where the release tail begins. */
    val endOfSlotsSamples: Int get() = manifest.slotSamples[slotCount]

    fun startOfSlot(slot: Int): Int {
        requireSlot(slot)
        return manifest.slotSamples[slot]
    }

    fun endOfSlot(slot: Int): Int {
        requireSlot(slot)
        return manifest.slotSamples[slot + 1]
    }

    fun sampleCountOfSlot(slot: Int): Int = endOfSlot(slot) - startOfSlot(slot)

    fun instantOfSlotStart(slot: Int): Long {
        requireSlot(slot)
        return manifest.fromMillis + slot * manifest.gridMillis
    }

    /**
     * The slot containing an instant, clamped to the window. This is the inverse of
     * [instantOfSlotStart] in the sense that `slotAtInstant(instantOfSlotStart(i)) == i`.
     */
    fun slotAtInstant(epochMillis: Long): Int {
        val offset = epochMillis - manifest.fromMillis
        val slot = floorDiv(offset, manifest.gridMillis)
        return slot.coerceIn(0L, (slotCount - 1).toLong()).toInt()
    }

    /**
     * The scrubbing promise: an instant maps to the sample where the engine started the bar that
     * contains it. Play from here and you hear the moment you asked for.
     */
    fun startOfInstant(epochMillis: Long): Int = startOfSlot(slotAtInstant(epochMillis))

    /** The slot containing a sample. Samples in the release tail belong to the last slot. */
    fun slotAtSample(sample: Long): Int {
        if (sample <= 0L) return 0
        if (sample >= endOfSlotsSamples.toLong()) return slotCount - 1
        var low = 0
        var high = slotCount - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (startOfSlot(mid) <= sample) low = mid else high = mid - 1
        }
        return low
    }

    /** Position across the audible frame, 0 at the first sample and 1 at the end of the tail. */
    fun progressOfSample(sample: Long): Double =
        sample.coerceIn(0L, audioEndSamples.toLong()).toDouble() / audioEndSamples

    fun sampleAtProgress(fraction: Double): Int =
        (fraction.coerceIn(0.0, 1.0) * audioEndSamples).toInt().coerceIn(0, audioEndSamples)

    /** `HH:mm` (UTC) at which a slot begins — what a scrubber labels itself with. */
    fun clockOfSlot(slot: Int): String = Iso8601.clock(instantOfSlotStart(slot))

    fun clockAtSample(sample: Long): String = clockOfSlot(slotAtSample(sample.toLong()))

    private fun requireSlot(slot: Int) {
        require(slot in 0 until slotCount) { "slot $slot is outside 0..${slotCount - 1}" }
    }
}

private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    return if (value % divisor != 0L && (value < 0L) != (divisor < 0L)) quotient - 1 else quotient
}

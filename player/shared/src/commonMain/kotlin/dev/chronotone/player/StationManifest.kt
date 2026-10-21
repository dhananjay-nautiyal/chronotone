package dev.chronotone.player

/**
 * The station manifest, as written by `chronotone station` (format `chronotone.station/1`).
 *
 * This class is the whole interoperability contract between the Java engine and this player. It is
 * deliberately strict: a manifest that does not describe a coherent timeline is refused rather than
 * half-played, because "the same moment always sounds the same" is only true if the reader agrees
 * with the writer about where every moment begins.
 *
 * The important field is [slotSamples]. It has `slots + 2` entries: the start of every slot, then
 * the end of the last slot, then the end of the release tail. Those are the engine's own sample
 * offsets — the player never re-derives a bar length from a tempo.
 */
class StationManifest private constructor(
    val seed: Long,
    val gridMillis: Long,
    val windowMillis: Long,
    val fromMillis: Long,
    val toMillis: Long,
    val slots: Int,
    val slotSamples: IntArray,
    val timelineSha256: String,
    val wavSha256: String,
    val notes: Int,
    val eventsInLog: Int,
    val timeline: String,
) {

    /** One past the last audible sample, including the release tail. */
    val audioEndSamples: Int get() = slotSamples[slots + 1]

    companion object {
        const val FORMAT = "chronotone.station/1"

        fun parse(text: String): StationManifest {
            val root = Json.parse(text).asObject()

            val format = root.string("format")
            require(format == FORMAT) {
                "not a Chronotone station manifest: format is '$format', expected '$FORMAT'"
            }

            val slots = root.int("slots")
            require(slots > 0) { "manifest has $slots slots" }

            val bounds = root.field("slot_samples").asArray().items.map { it.asInt() }.toIntArray()
            require(bounds.size == slots + 2) {
                "slot_samples has ${bounds.size} entries; a manifest with $slots slots needs ${slots + 2} " +
                    "(one per slot boundary, plus the end of the last slot and of the release tail)"
            }
            require(bounds[0] == 0) { "slot_samples must start at 0, not ${bounds[0]}" }
            for (i in 1 until bounds.size) {
                require(bounds[i] > bounds[i - 1]) {
                    "slot_samples is not strictly increasing at index $i: ${bounds[i - 1]} then ${bounds[i]}"
                }
            }

            val gridMillis = Iso8601.durationMillis(root.string("grid"))
            val windowMillis = Iso8601.durationMillis(root.string("window"))
            val fromMillis = Iso8601.instantMillis(root.string("from"))
            val toMillis = Iso8601.instantMillis(root.string("to"))

            require(toMillis > fromMillis) { "the window ends before it starts" }
            require(toMillis - fromMillis == slots * gridMillis) {
                "the window spans ${toMillis - fromMillis} ms but $slots slots of ${gridMillis} ms " +
                    "would span ${slots * gridMillis} ms"
            }
            require(windowMillis == toMillis - fromMillis) {
                "window is ${windowMillis} ms but from..to spans ${toMillis - fromMillis} ms"
            }

            val timelineSha = root.string("timeline_sha256")
            val wavSha = root.string("wav_sha256")
            require(timelineSha.isNotBlank()) { "manifest has no timeline_sha256" }
            require(wavSha.isNotBlank()) { "manifest has no wav_sha256" }

            return StationManifest(
                seed = root.long("seed"),
                gridMillis = gridMillis,
                windowMillis = windowMillis,
                fromMillis = fromMillis,
                toMillis = toMillis,
                slots = slots,
                slotSamples = bounds,
                timelineSha256 = timelineSha,
                wavSha256 = wavSha,
                notes = root.int("notes"),
                eventsInLog = root.int("events_in_log"),
                timeline = root.string("timeline"),
            )
        }
    }
}

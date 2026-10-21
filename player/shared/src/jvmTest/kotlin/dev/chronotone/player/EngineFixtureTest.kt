package dev.chronotone.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cross-language check.
 *
 * `station.json` in `src/jvmTest/resources` is written by the Java engine, not by hand. Every
 * assertion below is about the player agreeing with the engine's own numbers rather than
 * re-deriving them — if the seek math ever drifts from the renderer's geometry, this fails.
 *
 * CI regenerates the same frame with the CLI and diffs the manifest, so the fixture cannot quietly
 * fall behind the engine either.
 */
class EngineFixtureTest {

    private val manifest: StationManifest by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/station.json")) { "fixture is missing" }
            .bufferedReader()
            .readText()
        StationManifest.parse(text)
    }

    @Test
    fun parsesTheEnginesOwnManifest() {
        assertEquals(30, manifest.slots)
        assertEquals(42, manifest.seed)
        assertEquals(60_000L, manifest.gridMillis)
        assertEquals(1_800_000L, manifest.windowMillis)
        assertEquals(32, manifest.slotSamples.size)
        assertEquals(0, manifest.slotSamples.first())
        assertEquals(64, manifest.timelineSha256.length)
        assertEquals(64, manifest.wavSha256.length)
    }

    @Test
    fun usesEveryPublishedBoundAsGiven() {
        val timeline = Timeline(manifest)
        for (slot in 0 until manifest.slots) {
            assertEquals(manifest.slotSamples[slot], timeline.startOfSlot(slot))
            assertEquals(manifest.slotSamples[slot + 1], timeline.endOfSlot(slot))
            assertTrue(timeline.sampleCountOfSlot(slot) > 0, "slot $slot has no audio")
        }
        assertEquals(manifest.slotSamples.last(), timeline.audioEndSamples)
    }

    @Test
    fun theGridLinesUpWithTheClock() {
        val timeline = Timeline(manifest)
        for (slot in 0 until manifest.slots) {
            assertEquals(manifest.fromMillis + slot * 60_000L, timeline.instantOfSlotStart(slot))
            assertEquals(slot, timeline.slotAtInstant(timeline.instantOfSlotStart(slot)))
        }
        assertEquals(manifest.toMillis - manifest.gridMillis, timeline.instantOfSlotStart(manifest.slots - 1))
    }

    @Test
    fun scrubbingToAnInstantLandsInsideThatBarsAudio() {
        val timeline = Timeline(manifest)
        for (slot in 0 until manifest.slots) {
            val sample = timeline.startOfInstant(timeline.instantOfSlotStart(slot))
            assertEquals(manifest.slotSamples[slot], sample)
            assertEquals(slot, timeline.slotAtSample(sample.toLong()))
        }
    }

    @Test
    fun theFrameIsThirtyBarsAndATwoSecondTail() {
        assertEquals(30, manifest.slots)
        assertEquals(1_800_000L, manifest.toMillis - manifest.fromMillis)

        val timeline = Timeline(manifest)
        val tail = timeline.audioEndSamples - timeline.endOfSlotsSamples
        assertTrue(tail > 0, "the frame has no release tail")
        assertEquals(2.0 * 44_100, tail.toDouble(), 0.5)
    }

    @Test
    fun aLaterBarStartsLaterInTheFileAndLaterOnTheClock() {
        val timeline = Timeline(manifest)
        for (slot in 1 until manifest.slots) {
            assertTrue(timeline.startOfSlot(slot) > timeline.startOfSlot(slot - 1))
            assertTrue(timeline.instantOfSlotStart(slot) > timeline.instantOfSlotStart(slot - 1))
        }
    }
}

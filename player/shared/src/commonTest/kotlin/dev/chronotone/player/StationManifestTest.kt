package dev.chronotone.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StationManifestTest {

    @Test
    fun readsEveryField() {
        val manifest = StationManifest.parse(manifestJson())

        assertEquals(42, manifest.seed)
        assertEquals(60_000L, manifest.gridMillis)
        assertEquals(180_000L, manifest.windowMillis)
        assertEquals(3, manifest.slots)
        assertEquals(listOf(0, 100, 250, 399, 500), manifest.slotSamples.toList())
        assertEquals(500, manifest.audioEndSamples)
        assertEquals(true, manifest.timeline.contains("3 slots"))
    }

    @Test
    fun refusesAnotherFormat() {
        val failure = assertFailsWith<IllegalArgumentException> {
            StationManifest.parse(manifestJson(format = "chronotone.station/2"))
        }
        assertTrue(failure.message!!.contains("chronotone.station/1"))
    }

    @Test
    fun refusesTheWrongNumberOfBounds() {
        // Three slots need five bounds: three starts, the last slot's end, and the tail's end.
        val failure = assertFailsWith<IllegalArgumentException> {
            StationManifest.parse(manifestJson(bounds = listOf(0, 100, 250)))
        }
        assertTrue(failure.message!!.contains("slot_samples"))
        assertTrue(failure.message!!.contains("5"))
    }

    @Test
    fun refusesBoundsThatDoNotIncrease() {
        val failure = assertFailsWith<IllegalArgumentException> {
            StationManifest.parse(manifestJson(bounds = listOf(0, 100, 100, 399, 500)))
        }
        assertTrue(failure.message!!.contains("strictly increasing"))
    }

    @Test
    fun refusesAGridThatDoesNotFillTheWindow() {
        // Three one-minute slots cannot cover the declared three-minute window if the grid is two.
        val failure = assertFailsWith<IllegalArgumentException> {
            StationManifest.parse(manifestJson(grid = "PT2M"))
        }
        assertTrue(failure.message!!.contains("slot"))
    }

    @Test
    fun refusesAWindowThatDisagreesWithFromAndTo() {
        val failure = assertFailsWith<IllegalArgumentException> {
            StationManifest.parse(manifestJson(window = "PT5M"))
        }
        assertTrue(failure.message!!.contains("window"))
    }

    @Test
    fun refusesAnEmptyWindow() {
        assertFailsWith<IllegalArgumentException> {
            StationManifest.parse(manifestJson(from = "2026-10-19T21:44:00.000Z", to = "2026-10-19T21:44:00.000Z"))
        }
    }

    @Test
    fun refusesAManifestWithoutHashes() {
        val text = manifestJson().replace("\"aa11\"", "\"\"")
        assertFailsWith<IllegalArgumentException> { StationManifest.parse(text) }
    }
}

package dev.chronotone.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimelineTest {

    private val timeline = Timeline(StationManifest.parse(manifestJson()))

    @Test
    fun boundsComeStraightFromTheManifest() {
        assertEquals(0, timeline.startOfSlot(0))
        assertEquals(100, timeline.endOfSlot(0))
        assertEquals(100, timeline.startOfSlot(1))
        assertEquals(250, timeline.endOfSlot(1))
        assertEquals(250, timeline.startOfSlot(2))
        assertEquals(399, timeline.endOfSlot(2))
        assertEquals(399, timeline.endOfSlotsSamples)
        assertEquals(500, timeline.audioEndSamples)
    }

    @Test
    fun aSampleBelongsToTheSlotItFallsIn() {
        assertEquals(0, timeline.slotAtSample(0))
        assertEquals(0, timeline.slotAtSample(99))
        assertEquals(1, timeline.slotAtSample(100))
        assertEquals(1, timeline.slotAtSample(249))
        assertEquals(2, timeline.slotAtSample(250))
        assertEquals(2, timeline.slotAtSample(398))
    }

    @Test
    fun theReleaseTailBelongsToTheLastSlot() {
        assertEquals(2, timeline.slotAtSample(399))
        assertEquals(2, timeline.slotAtSample(499))
        assertEquals(2, timeline.slotAtSample(50_000))
    }

    @Test
    fun everySlotRoundTripsThroughItsOwnSamples() {
        for (slot in 0 until timeline.slotCount) {
            assertEquals(slot, timeline.slotAtSample(timeline.startOfSlot(slot).toLong()))
            assertEquals(slot, timeline.slotAtSample((timeline.endOfSlot(slot) - 1).toLong()))
        }
    }

    @Test
    fun instantsMapToTheBarThatContainsThem() {
        for (slot in 0 until timeline.slotCount) {
            val start = timeline.instantOfSlotStart(slot)
            assertEquals(slot, timeline.slotAtInstant(start))
            assertEquals(timeline.startOfSlot(slot), timeline.startOfInstant(start))
            if (slot > 0) {
                assertEquals(slot - 1, timeline.slotAtInstant(start - 1))
                assertEquals(slot, timeline.slotAtInstant(start + 59_999))
            }
        }
    }

    @Test
    fun clampsToTheWindow() {
        val before = timeline.manifest.fromMillis - 1
        val after = timeline.manifest.toMillis + 86_400_000L
        assertEquals(0, timeline.slotAtInstant(before))
        assertEquals(0, timeline.startOfInstant(before))
        assertEquals(timeline.slotCount - 1, timeline.slotAtInstant(after))
    }

    @Test
    fun progressCoversTheWholeAudioIncludingTheTail() {
        assertEquals(0.0, timeline.progressOfSample(0), 1e-9)
        assertEquals(0.5, timeline.progressOfSample(250), 1e-9)
        assertEquals(1.0, timeline.progressOfSample(500), 1e-9)
        assertEquals(1.0, timeline.progressOfSample(9_999), 1e-9)
        assertEquals(0, timeline.sampleAtProgress(0.0))
        assertEquals(250, timeline.sampleAtProgress(0.5))
        assertEquals(500, timeline.sampleAtProgress(1.0))
    }

    @Test
    fun labelsSlotsInUtc() {
        assertEquals("21:44", timeline.clockOfSlot(0))
        assertEquals("21:45", timeline.clockOfSlot(1))
        assertEquals("21:46", timeline.clockOfSlot(2))
    }

    @Test
    fun refusesSlotsOutsideTheFrame() {
        assertFailsWith<IllegalArgumentException> { timeline.startOfSlot(-1) }
        assertFailsWith<IllegalArgumentException> { timeline.startOfSlot(3) }
        assertFailsWith<IllegalArgumentException> { timeline.instantOfSlotStart(3) }
    }
}

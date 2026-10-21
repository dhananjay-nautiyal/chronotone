package dev.chronotone.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Iso8601Test {

    @Test
    fun readsTheEpoch() {
        assertEquals(0L, Iso8601.instantMillis("1970-01-01T00:00:00.000Z"))
    }

    @Test
    fun readsAManifestInstant() {
        assertEquals(1_792_446_240_000L, Iso8601.instantMillis("2026-10-19T21:44:00.000Z"))
    }

    @Test
    fun roundsTripsInstants() {
        for (text in listOf(
            "1970-01-01T00:00:00.000Z",
            "2024-02-29T23:59:59.999Z",
            "2026-10-19T21:44:00.000Z",
            "2100-12-31T00:00:00.000Z",
        )) {
            assertEquals(text, Iso8601.formatInstant(Iso8601.instantMillis(text)))
        }
    }

    @Test
    fun handlesLeapDaysAndCenturyRules() {
        // 2000 is a leap year, 2100 is not.
        assertEquals(86_400_000L, Iso8601.instantMillis("2000-03-01T00:00:00.000Z") -
            Iso8601.instantMillis("2000-02-29T00:00:00.000Z"))
        assertEquals(86_400_000L, Iso8601.instantMillis("2100-03-01T00:00:00.000Z") -
            Iso8601.instantMillis("2100-02-28T00:00:00.000Z"))
    }

    @Test
    fun readsTheDurationsTheEngineWrites() {
        assertEquals(60_000L, Iso8601.durationMillis("PT1M"))
        assertEquals(1_800_000L, Iso8601.durationMillis("PT30M"))
        assertEquals(90_000L, Iso8601.durationMillis("PT90S"))
        assertEquals(3_600_000L, Iso8601.durationMillis("PT1H"))
        assertEquals(5_400_000L, Iso8601.durationMillis("PT1H30M"))
        assertEquals(86_400_000L, Iso8601.durationMillis("P1D"))
        assertEquals(500L, Iso8601.durationMillis("PT0.5S"))
    }

    @Test
    fun rejectsDurationsWithNoFixedLength() {
        assertFailsWith<IllegalArgumentException> { Iso8601.durationMillis("P1M") }
        assertFailsWith<IllegalArgumentException> { Iso8601.durationMillis("P1Y") }
        assertFailsWith<IllegalArgumentException> { Iso8601.durationMillis("PT") }
        assertFailsWith<IllegalArgumentException> { Iso8601.durationMillis("30m") }
    }

    @Test
    fun rejectsMalformedInstants() {
        assertFailsWith<IllegalArgumentException> { Iso8601.instantMillis("2026-10-19 21:44:00") }
        assertFailsWith<IllegalArgumentException> { Iso8601.instantMillis("2026-13-01T00:00:00Z") }
    }

    @Test
    fun labelsSlotsInUtc() {
        assertEquals("22:14", Iso8601.clock(Iso8601.instantMillis("2026-10-19T22:14:00.000Z")))
        assertEquals("00:00", Iso8601.clock(Iso8601.instantMillis("2026-10-19T00:00:00.000Z")))
    }
}

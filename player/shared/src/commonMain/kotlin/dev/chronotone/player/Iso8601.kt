package dev.chronotone.player

/**
 * The two ISO-8601 shapes the manifest actually uses: instants (`2026-10-19T21:44:00.000Z`) and
 * durations (`PT30M`, `PT1M`, `PT90S`).
 *
 * `java.time` would do this on the JVM, but the core is meant to compile for Android and, later,
 * iOS, where it does not exist. The arithmetic here is integer-only, so a timestamp means the same
 * thing on every platform.
 */
object Iso8601 {

    private val INSTANT = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z""")
    private val DURATION = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?""")

    fun instantMillis(text: String): Long {
        val m = INSTANT.matchEntire(text)
            ?: throw IllegalArgumentException("not an ISO-8601 UTC instant: '$text'")
        val (year, month, day) = m.destructured.let { Triple(it.component1(), it.component2(), it.component3()) }
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        val second = m.groupValues[6].toInt()
        val fraction = m.groupValues[7]
        // Nanosecond precision is truncated to milliseconds: the engine emits exactly three digits,
        // and anything finer is below the resolution of the manifest's own fields.
        val millis = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').take(3).toInt()

        require(month.toInt() in 1..12) { "bad month in '$text'" }
        require(day.toInt() in 1..31) { "bad day in '$text'" }
        require(hour in 0..23) { "bad hour in '$text'" }
        require(minute in 0..59) { "bad minute in '$text'" }
        require(second in 0..60) { "bad second in '$text'" }

        return daysFromCivil(year.toInt(), month.toInt(), day.toInt()) * 86_400_000L +
            hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
    }

    fun formatInstant(epochMillis: Long): String {
        var days = floorDiv(epochMillis, 86_400_000L)
        var rest = epochMillis - days * 86_400_000L
        if (rest < 0) { rest += 86_400_000L; days -= 1 }

        val (year, month, day) = civilFromDays(days)
        val hour = (rest / 3_600_000L).toInt()
        val minute = ((rest % 3_600_000L) / 60_000L).toInt()
        val second = ((rest % 60_000L) / 1_000L).toInt()
        val millis = (rest % 1_000L).toInt()

        return buildString {
            append(year.toString().padStart(4, '0')); append('-')
            append(month.toString().padStart(2, '0')); append('-')
            append(day.toString().padStart(2, '0')); append('T')
            append(hour.toString().padStart(2, '0')); append(':')
            append(minute.toString().padStart(2, '0')); append(':')
            append(second.toString().padStart(2, '0')); append('.')
            append(millis.toString().padStart(3, '0')); append('Z')
        }
    }

    /** `HH:mm` in UTC, for labels. */
    fun clock(epochMillis: Long): String {
        val rest = floorMod(epochMillis, 86_400_000L)
        val hour = (rest / 3_600_000L).toInt()
        val minute = ((rest % 3_600_000L) / 60_000L).toInt()
        return hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
    }

    /**
     * Parses the ISO-8601 durations the engine writes (`PT1M`, `PT30M`, `PT90S`, `P1D`, `PT0.5S`).
     * Years and months are rejected: they have no fixed length, and a grid is never one of them.
     */
    fun durationMillis(text: String): Long {
        val m = DURATION.matchEntire(text)
            ?: throw IllegalArgumentException("not an ISO-8601 duration: '$text'")
        if (m.value == "P" || m.value == "PT") throw IllegalArgumentException("empty ISO-8601 duration: '$text'")

        val days = m.groupValues[1].ifEmpty { "0" }.toLong()
        val hours = m.groupValues[2].ifEmpty { "0" }.toLong()
        val minutes = m.groupValues[3].ifEmpty { "0" }.toLong()
        val seconds = if (m.groupValues[4].isEmpty()) 0.0 else m.groupValues[4].toDouble()

        val millis = days * 86_400_000L + hours * 3_600_000L + minutes * 60_000L + (seconds * 1000.0).toLong()
        require(millis > 0) { "duration must be positive: '$text'" }
        return millis
    }

    /** Howard Hinnant's days-from-civil; exact for the whole proleptic Gregorian range. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = floorDiv(y.toLong(), 400L)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365L + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val shifted = days + 719_468L
        val era = floorDiv(shifted, 146_097L)
        val dayOfEra = shifted - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val year = yearOfEra + era * 400
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (month <= 2) year + 1 else year).toInt(), month.toInt(), day.toInt())
    }

    private fun floorDiv(value: Long, divisor: Long): Long {
        val quotient = value / divisor
        return if (value % divisor != 0L && (value < 0L) != (divisor < 0L)) quotient - 1 else quotient
    }

    private fun floorMod(value: Long, divisor: Long): Long {
        val remainder = value % divisor
        return if (remainder != 0L && (remainder < 0L) != (divisor < 0L)) remainder + divisor else remainder
    }
}

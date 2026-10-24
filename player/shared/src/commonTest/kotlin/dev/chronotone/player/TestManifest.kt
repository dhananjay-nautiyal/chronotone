package dev.chronotone.player

/** A small, coherent manifest for tests that do not need the real engine output. */
internal fun manifestJson(
    format: String = StationManifest.FORMAT,
    slots: Int = 3,
    grid: String = "PT1M",
    window: String = "PT3M",
    from: String = "2026-10-19T21:44:00.000Z",
    to: String = "2026-10-19T21:47:00.000Z",
    bounds: List<Int> = listOf(0, 100, 250, 399, 500),
    timelineSha256: String = "aa11",
    wavSha256: String = "bb22",
): String = """
    {
      "format": "$format",
      "mapping": "v1.0",
      "grid": "$grid",
      "window": "$window",
      "from": "$from",
      "to": "$to",
      "seed": 42,
      "events_in_log": 405,
      "slots": $slots,
      "notes": 10,
      "seconds": 0.011,
      "slot_samples": [${bounds.joinToString(", ")}],
      "timeline_sha256": "$timelineSha256",
      "wav_sha256": "$wavSha256",
      "timeline": "$slots slots"
    }
""".trimIndent()

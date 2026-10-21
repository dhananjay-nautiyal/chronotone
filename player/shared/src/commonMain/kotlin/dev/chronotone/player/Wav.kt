package dev.chronotone.player

/**
 * Just enough WAV to find the samples.
 *
 * The engine writes a canonical 44-byte header around mono 16-bit PCM, but this walks the chunk list
 * properly so a re-encoded file is either handled or rejected with a reason, never mis-read.
 */
class WavFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val blockAlign: Int get() = channels * bitsPerSample / 8
    val frames: Long get() = if (blockAlign == 0) 0 else dataBytes / blockAlign
    val durationMillis: Long get() = if (sampleRate == 0) 0 else frames * 1000 / sampleRate
}

object Wav {

    /** The first few kilobytes are enough to reach the `data` chunk of any sane file. */
    const val PROBE_BYTES = 4096

    fun parse(probe: ByteArray): WavFormat {
        require(probe.size >= 12) { "not a WAV file: only ${probe.size} bytes" }
        require(tag(probe, 0) == "RIFF") { "not a RIFF file" }
        require(tag(probe, 8) == "WAVE") { "a RIFF file, but not WAVE" }

        var at = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0

        while (at + 8 <= probe.size) {
            val id = tag(probe, at)
            val size = (intLe(probe, at + 4).toLong() and 0xFFFF_FFFFL)
            val body = at + 8

            when (id) {
                "fmt " -> {
                    require(size >= 16) { "the fmt chunk is $size bytes, need at least 16" }
                    require(body + 16 <= probe.size) { "the fmt chunk runs past the header probe" }
                    val encoding = shortLe(probe, body)
                    require(encoding == 1) {
                        "unsupported WAV encoding $encoding; Chronotone writes integer PCM (1)"
                    }
                    channels = shortLe(probe, body + 2)
                    sampleRate = intLe(probe, body + 4)
                    bits = shortLe(probe, body + 14)
                    require(channels > 0) { "the fmt chunk says $channels channels" }
                    require(sampleRate > 0) { "the fmt chunk says ${sampleRate}Hz" }
                }

                "data" -> {
                    require(sampleRate > 0) { "the data chunk appears before the fmt chunk" }
                    return WavFormat(sampleRate, channels, bits, body.toLong(), size)
                }
            }

            // Chunks are padded to an even length.
            at = body + (size + (size and 1L)).toInt()
        }

        throw IllegalArgumentException("no data chunk in the first ${probe.size} bytes")
    }

    /** Chronotone's own output: mono 16-bit PCM at 44.1kHz. A player may support more, but must not guess. */
    fun requireEngineFormat(format: WavFormat) {
        require(format.channels == 1) { "the engine writes mono; this file has ${format.channels} channels" }
        require(format.bitsPerSample == 16) { "the engine writes 16-bit samples; this file has ${format.bitsPerSample}" }
        require(format.sampleRate == 44_100) { "the engine writes 44100Hz; this file is ${format.sampleRate}Hz" }
    }

    private fun tag(bytes: ByteArray, at: Int): String = buildString(4) {
        for (i in 0 until 4) append((bytes[at + i].toInt() and 0xFF).toChar())
    }

    private fun shortLe(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun intLe(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}

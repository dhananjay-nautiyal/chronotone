package dev.chronotone.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavTest {

    @Test
    fun readsTheEnginesHeader() {
        val format = Wav.parse(header())

        assertEquals(44_100, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(16, format.bitsPerSample)
        assertEquals(44L, format.dataOffset)
        assertEquals(4_410L, format.dataBytes)
        assertEquals(2_205L, format.frames) // 4410 bytes, two per mono sample
        assertEquals(50L, format.durationMillis)
    }

    @Test
    fun skipsChunksItDoesNotNeed() {
        val format = Wav.parse(header(extraChunk = true))
        assertEquals(44L + 8L + 10L, format.dataOffset) // the LIST chunk, padded, sits in front of data
    }

    @Test
    fun rejectsSomethingThatIsNotRiff() {
        val bytes = header()
        "RIFX".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
        assertFailsWith<IllegalArgumentException> { Wav.parse(bytes) }
    }

    @Test
    fun rejectsNonPcmEncoding() {
        val bytes = header()
        bytes[20] = 3 // IEEE float
        val failure = assertFailsWith<IllegalArgumentException> { Wav.parse(bytes) }
        assertEquals(true, failure.message!!.contains("PCM"))
    }

    @Test
    fun rejectsAFileWithNoDataChunk() {
        assertFailsWith<IllegalArgumentException> { Wav.parse(header(includeData = false)) }
    }

    @Test
    fun knowsWhatTheEngineProduces() {
        Wav.requireEngineFormat(Wav.parse(header()))

        val stereo = WavFormat(sampleRate = 44_100, channels = 2, bitsPerSample = 16, dataOffset = 44, dataBytes = 4_410)
        assertFailsWith<IllegalArgumentException> { Wav.requireEngineFormat(stereo) }
    }

    /**
     * A canonical 44-byte header, optionally with a padded `LIST` chunk in front of `data` (to prove
     * the chunk walk), and optionally without `data` at all.
     */
    private fun header(
        extraChunk: Boolean = false,
        includeData: Boolean = true,
        dataBytes: Int = 4_410,
    ): ByteArray {
        val extra = if (extraChunk) 8 + 10 else 0
        val out = ByteArray(44 + extra)
        fun ascii(at: Int, text: String) { text.forEachIndexed { i, c -> out[at + i] = c.code.toByte() } }
        fun i32(at: Int, value: Int) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value ushr 8) and 0xFF).toByte()
            out[at + 2] = ((value ushr 16) and 0xFF).toByte()
            out[at + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        fun i16(at: Int, value: Int) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        ascii(0, "RIFF")
        i32(4, out.size - 8)
        ascii(8, "WAVE")

        ascii(12, "fmt ")
        i32(16, 16)
        i16(20, 1)                    // PCM
        i16(22, 1)                    // mono
        i32(24, 44_100)
        i32(28, 88_200)               // byte rate
        i16(32, 2)                    // block align
        i16(34, 16)

        var at = 36
        if (extraChunk) {
            ascii(at, "LIST")
            i32(at + 4, 10)
            at += 8 + 10
        }
        if (includeData) {
            ascii(at, "data")
            i32(at + 4, dataBytes)
        } else {
            ascii(at, "JUNK")
            i32(at + 4, 0)
        }
        return out
    }
}

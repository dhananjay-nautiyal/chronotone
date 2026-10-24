package dev.chronotone.player

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `StationFrame.open` is the player's gate: it must refuse frames whose audio is not the audio
 * the manifest describes. Frame counts and slot bounds are functions of window and grid only, so
 * the hash is the only thing that can catch a WAV from another render of the same window.
 */
class StationFrameTest {

    private val dir: Path = Files.createTempDirectory("chronotone-station-frame")

    @Test
    fun opensAFrameWhoseWavMatchesTheManifestHash() {
        val wav = engineWav(frames = 500)
        write("chronotone.wav", wav)
        write("station.json", manifestJson(wavSha256 = sha256Hex(wav)))

        StationFrame.open(dir).use { frame ->
            assertEquals(500L, frame.wav.samples)
            assertEquals(500, frame.manifest.audioEndSamples)
        }
    }

    @Test
    fun refusesAWavWhoseHashDoesNotMatch() {
        // Same geometry (window + grid decide both), different audio — exactly what two renders
        // of the same window with a different seed or log look like.
        write("chronotone.wav", engineWav(frames = 500))
        write("station.json", manifestJson(wavSha256 = "0".repeat(64)))

        val failure = assertFailsWith<IllegalArgumentException> { StationFrame.open(dir) }
        assertContains(failure.message!!, "different renders")
        assertContains(failure.message!!, "chronotone.wav has sha256")
    }

    @Test
    fun stillRefusesAWavWhoseFrameCountDisagrees() {
        write("chronotone.wav", engineWav(frames = 499))
        write("station.json", manifestJson(wavSha256 = "0".repeat(64)))

        val failure = assertFailsWith<IllegalArgumentException> { StationFrame.open(dir) }
        assertContains(failure.message!!, "499")
    }

    private fun write(name: String, bytes: ByteArray) = Files.write(dir.resolve(name), bytes)

    private fun write(name: String, text: String) = Files.write(dir.resolve(name), text.toByteArray())

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    /** A minimal engine-format WAV: RIFF/WAVE, PCM, mono, 44100 Hz, 16-bit, [frames] silent frames. */
    private fun engineWav(frames: Int): ByteArray {
        val dataBytes = frames * 2
        val out = ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun intLe(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun shortLe(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        ascii("RIFF"); intLe(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); intLe(16); shortLe(1); shortLe(1)
        intLe(44_100); intLe(44_100 * 2); shortLe(2); shortLe(16)
        ascii("data"); intLe(dataBytes)
        repeat(frames) { shortLe(0) }
        return out.toByteArray()
    }
}

package dev.chronotone.player.desktop

import dev.chronotone.player.StationFrame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlayerPanelTest {

    @Test
    fun paintsARealFrameOffscreen() {
        fixture().use { frame ->
            val panel = PlayerPanel(frame, audio = null, startSlot = 1)
            val image = Screenshot.render(panel, 960, 540)

            assertEquals(960, image.width)
            assertEquals(540, image.height)
            assertTrue(distinctColours(image) > 8, "the panel painted almost nothing")
        }
    }

    @Test
    fun theFrameAgreesWithItsManifest() {
        fixture().use { frame ->
            assertEquals(500L, frame.wav.samples)
            assertEquals(500, frame.timeline.audioEndSamples)
            assertEquals(0, frame.timeline.startOfSlot(0))
            assertEquals(100, frame.timeline.startOfSlot(1))
            assertEquals(250, frame.timeline.startOfSlot(2))
            assertEquals(399, frame.timeline.endOfSlotsSamples)
        }
    }

    @Test
    fun refusesAManifestThatDoesNotMatchTheAudio() {
        val directory = Files.createTempDirectory("chronotone-mismatch")
        writeWav(directory.resolve("chronotone.wav"), frames = 400)
        Files.writeString(directory.resolve("station.json"), manifest(samples = 500))

        val failure = assertFailsWith<IllegalArgumentException> { StationFrame.open(directory) }
        assertTrue(failure.message!!.contains("different renders"), failure.message)
    }

    /** A three-bar frame with a decaying tone, so the waveform has a shape to draw. */
    private fun fixture(): StationFrame {
        val directory = Files.createTempDirectory("chronotone-player")
        writeWav(directory.resolve("chronotone.wav"), frames = 500)
        Files.writeString(directory.resolve("station.json"), manifest(samples = 500))
        return StationFrame.open(directory)
    }

    private fun manifest(samples: Int): String = """
        {
          "format": "chronotone.station/1",
          "mapping": "v1.0",
          "grid": "PT1M",
          "window": "PT3M",
          "from": "2026-10-19T21:44:00.000Z",
          "to": "2026-10-19T21:47:00.000Z",
          "seed": 42,
          "events_in_log": 405,
          "slots": 3,
          "notes": 10,
          "seconds": 0.011,
          "slot_samples": [0, 100, 250, 399, $samples],
          "timeline_sha256": "aa11",
          "wav_sha256": "bb22",
          "timeline": "3 slots"
        }
    """.trimIndent()

    private fun writeWav(path: Path, frames: Int) {
        val out = ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun i32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        fun i16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        val dataBytes = frames * 2
        ascii("RIFF"); i32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); i32(16); i16(1); i16(1); i32(44_100); i32(88_200); i16(2); i16(16)
        ascii("data"); i32(dataBytes)
        for (i in 0 until frames) {
            val fade = 1.0 - i.toDouble() / frames
            i16((sin(i * 0.07) * 12_000 * fade).toInt())
        }
        Files.write(path, out.toByteArray())
    }

    private fun distinctColours(image: BufferedImage): Int {
        val seen = HashSet<Int>()
        for (y in 0 until image.height step 4) {
            for (x in 0 until image.width step 4) seen.add(image.getRGB(x, y))
        }
        return seen.size
    }
}

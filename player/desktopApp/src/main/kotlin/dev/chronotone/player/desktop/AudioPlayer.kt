package dev.chronotone.player.desktop

import dev.chronotone.player.WavFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * Plays the frame from an exact sample offset.
 *
 * Seeking re-opens the line rather than buffering ahead, because the point of the player is that a
 * seek is a seek: the next byte you hear is the byte the engine wrote at that sample.
 */
class AudioPlayer(private val wav: WavFile) : AutoCloseable {

    private val format = AudioFormat(wav.format.sampleRate.toFloat(), 16, 1, true, false)

    @Volatile
    private var line: SourceDataLine? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var positionSamples: Long = 0
        private set

    val isPlaying: Boolean get() = running

    fun play(fromSample: Long) {
        stop()
        positionSamples = fromSample
        running = true

        worker = thread(isDaemon = true, name = "chronotone-audio") {
            val opened = AudioSystem.getSourceDataLine(format)
            try {
                opened.open(format)
                opened.start()
                line = opened
                var at = fromSample
                while (running && at < wav.samples) {
                    val count = minOf(4096L, wav.samples - at).toInt()
                    val bytes = wav.read(at, count)
                    opened.write(bytes, 0, bytes.size)
                    at += count
                    positionSamples = at
                }
            } finally {
                runCatching { opened.stop() }
                runCatching { opened.close() }
                running = false
            }
        }
    }

    fun stop() {
        running = false
        runCatching { line?.stop() }
        runCatching { line?.flush() }
        runCatching { line?.close() }
        line = null
        worker?.join(250)
        worker = null
    }

    override fun close() = stop()
}

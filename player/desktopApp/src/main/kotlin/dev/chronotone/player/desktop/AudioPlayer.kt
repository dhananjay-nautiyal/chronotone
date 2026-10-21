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

    /**
     * One play-through.
     *
     * The stop flag lives on the playback, not on the player: pressing play twice quickly must not
     * let a thread that is slow to unwind cancel the one that replaced it.
     */
    private class Playback {
        @Volatile var stopRequested = false
        @Volatile var finished = false
        @Volatile var line: SourceDataLine? = null
        var thread: Thread? = null
    }

    private var current: Playback? = null

    @Volatile
    var positionSamples: Long = 0
        private set

    val isPlaying: Boolean get() = current?.let { !it.finished } == true

    fun play(fromSample: Long) {
        stop()

        val playback = Playback().also { current = it }
        positionSamples = fromSample

        playback.thread = thread(isDaemon = true, name = "chronotone-audio") {
            val opened = AudioSystem.getSourceDataLine(format)
            playback.line = opened
            try {
                opened.open(format)
                opened.start()
                var at = fromSample
                while (!playback.stopRequested && at < wav.samples) {
                    val count = minOf(4096L, wav.samples - at).toInt()
                    val bytes = wav.read(at, count)
                    opened.write(bytes, 0, bytes.size)
                    at += count
                    positionSamples = at
                }
            } finally {
                runCatching { opened.stop() }
                runCatching { opened.close() }
                playback.line = null
                playback.finished = true
            }
        }
    }

    fun stop() {
        val playback = current ?: return
        current = null
        playback.stopRequested = true
        runCatching { playback.line?.stop() }
        runCatching { playback.line?.flush() }
        runCatching { playback.line?.close() }
        playback.thread?.join(250)
    }

    override fun close() = stop()
}

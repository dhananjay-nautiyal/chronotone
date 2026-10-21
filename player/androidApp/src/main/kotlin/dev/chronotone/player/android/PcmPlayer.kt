package dev.chronotone.player.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.chronotone.player.StationFrame
import java.io.Closeable
import kotlin.concurrent.thread

/**
 * Streams the frame through `AudioTrack`, starting at an exact sample.
 *
 * A seeking player could use `MediaPlayer` and `seekTo(millis)`, but that would round the seek to a
 * time and then to whatever frame the decoder lands on. The whole point here is that the next sample
 * written is `slot_samples[bar]`, so the PCM is fed by hand.
 */
class PcmPlayer(private val frame: StationFrame) : Closeable {

    /** One play-through; see `AudioPlayer` on the desktop for why the flag is not on the player. */
    private class Playback {
        @Volatile var stopRequested = false
        @Volatile var finished = false
        @Volatile var track: AudioTrack? = null
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
            val minimum = AudioTrack.getMinBufferSize(
                frame.wav.format.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )

            val opened = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(frame.wav.format.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum * 2, 16_384))
                .build()

            playback.track = opened
            try {
                opened.play()
                var at = fromSample
                while (!playback.stopRequested && at < frame.wav.samples) {
                    val count = minOf(4096L, frame.wav.samples - at).toInt()
                    val bytes = frame.wav.read(at, count)
                    val written = opened.write(bytes, 0, bytes.size)
                    if (written <= 0) break
                    at += written / 2
                    positionSamples = at
                }
            } finally {
                runCatching { opened.stop() }
                runCatching { opened.release() }
                playback.track = null
                playback.finished = true
            }
        }
    }

    fun stop() {
        val playback = current ?: return
        current = null
        playback.stopRequested = true
        runCatching { playback.track?.pause() }
        runCatching { playback.track?.flush() }
        runCatching { playback.track?.release() }
        playback.thread?.join(250)
    }

    override fun close() = stop()
}

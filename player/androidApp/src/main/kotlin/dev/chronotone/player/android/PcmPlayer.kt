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

    @Volatile
    private var track: AudioTrack? = null

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

            try {
                opened.play()
                track = opened
                var at = fromSample
                while (running && at < frame.wav.samples) {
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
                running = false
            }
        }
    }

    fun stop() {
        running = false
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        worker?.join(250)
        worker = null
    }

    override fun close() = stop()
}

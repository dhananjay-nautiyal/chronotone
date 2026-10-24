package dev.chronotone.player

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/** A rendered frame on disk: the manifest that describes it and the WAV that is it. */
class StationFrame(
    val manifest: StationManifest,
    val timeline: Timeline,
    val wav: WavFile,
) : AutoCloseable {
    override fun close() = wav.close()

    companion object {
        fun open(directory: Path): StationFrame {
            val manifestPath = directory.resolve("station.json")
            val wavPath = directory.resolve("chronotone.wav")
            require(Files.isRegularFile(manifestPath)) { "no station.json in $directory" }
            require(Files.isRegularFile(wavPath)) { "no chronotone.wav in $directory" }

            // `Files.readString` only reaches Android at API 33; `readAllBytes` has been there since 26.
            val manifest = StationManifest.parse(Files.readAllBytes(manifestPath).decodeToString())
            val wav = WavFile.open(wavPath)

            // The manifest says how long the audio is; so does the file. If they disagree, one of
            // them is from a different render and every seek below would be a lie.
            val frames = wav.format.frames
            require(frames == manifest.audioEndSamples.toLong()) {
                "station.json describes ${manifest.audioEndSamples} samples but the WAV has $frames — " +
                    "these files are from different renders"
            }

            // Frame counts and slot bounds are functions of window and grid only, so two renders
            // of the same window with a different seed or a different log have identical geometry
            // and would pass the check above. The engine publishes wav_sha256 so a wrong, stale
            // or partially copied WAV can be caught before a note of it plays.
            val actual = sha256Hex(wavPath)
            require(actual == manifest.wavSha256) {
                "chronotone.wav has sha256 $actual but station.json describes ${manifest.wavSha256} — " +
                    "these files are from different renders"
            }

            return StationFrame(manifest, Timeline(manifest), wav)
        }

        private fun sha256Hex(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        }
    }
}

/** Random-access 16-bit PCM, so seeking never loads a whole day into memory. */
class WavFile private constructor(
    private val file: RandomAccessFile,
    val format: WavFormat,
) : AutoCloseable {

    val samples: Long get() = format.frames

    /** Peak amplitude per bucket, normalised — the shape drawn in the waveform view. */
    fun envelope(buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        val perBucket = maxOf(1L, format.frames / buckets)
        val buffer = ByteArray(1 shl 20)

        file.seek(format.dataOffset)
        var bucket = 0
        var peak = 0
        var inBucket = 0L

        while (bucket < buckets) {
            val read = file.read(buffer)
            if (read <= 0) break
            var i = 0
            while (i + 1 < read && bucket < buckets) {
                val sample = ((buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
                val magnitude = abs(sample)
                if (magnitude > peak) peak = magnitude
                i += 2
                inBucket++
                if (inBucket >= perBucket) {
                    out[bucket++] = peak / 32768f
                    peak = 0
                    inBucket = 0
                }
            }
        }
        while (bucket < buckets) out[bucket++] = peak / 32768f
        return out
    }

    /** Reads [count] frames starting at [startSample], as little-endian 16-bit PCM. */
    fun read(startSample: Long, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        file.seek(format.dataOffset + startSample * 2)
        file.readFully(bytes)
        return bytes
    }

    override fun close() = file.close()

    companion object {
        fun open(path: Path): WavFile {
            val file = RandomAccessFile(path.toFile(), "r")
            try {
                val probe = ByteArray(Wav.PROBE_BYTES)
                val read = file.read(probe)
                val format = Wav.parse(if (read == probe.size) probe else probe.copyOf(read))
                Wav.requireEngineFormat(format)
                return WavFile(file, format)
            } catch (failure: Throwable) {
                file.close()
                throw failure
            }
        }
    }
}

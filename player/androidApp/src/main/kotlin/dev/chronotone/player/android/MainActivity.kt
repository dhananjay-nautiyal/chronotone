package dev.chronotone.player.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chronotone.player.Iso8601
import dev.chronotone.player.StationFrame
import dev.chronotone.player.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val external = getExternalFilesDir(null) ?: filesDir
        setContent {
            ChronotoneTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    PlayerRoute(external)
                }
            }
        }
    }
}

@Composable
private fun PlayerRoute(externalFiles: File) {
    var frame by remember { mutableStateOf<StationFrame?>(null) }
    var envelope by remember { mutableStateOf(FloatArray(0)) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(externalFiles) {
        runCatching {
            withContext(Dispatchers.IO) {
                val loaded = FrameStore.load(externalFiles)
                loaded to loaded.wav.envelope(900)
            }
        }.onSuccess { (loaded, shape) ->
            frame = loaded
            envelope = shape
        }.onFailure {
            failure = it.message ?: it.toString()
        }
    }

    val loaded = frame
    when {
        failure != null -> Notice("cannot open the frame", "$failure\n\n${FrameStore.hint()}")
        loaded == null -> Notice("loading", "reading station.json and chronotone.wav")
        else -> {
            val player = remember(loaded) { PcmPlayer(loaded) }
            DisposableEffect(player) {
                onDispose {
                    player.close()
                    loaded.close()
                }
            }
            PlayerScreen(loaded, envelope, player)
        }
    }
}

@Composable
private fun PlayerScreen(frame: StationFrame, envelope: FloatArray, player: PcmPlayer) {
    val timeline = frame.timeline
    var position by remember { mutableLongStateOf(timeline.startOfSlot(0).toLong()) }
    var playing by remember { mutableStateOf(false) }

    // The player thread owns the real position; the UI only samples it.
    LaunchedEffect(playing) {
        while (playing) {
            position = player.positionSamples
            if (!player.isPlaying) playing = false
            delay(40)
        }
    }

    fun seek(sample: Long) {
        position = sample
        if (player.isPlaying) player.play(sample)
    }

    fun seekToFraction(fraction: Float) {
        // Snap to the bar the engine published, not to the nearest pixel.
        val sample = timeline.sampleAtProgress(fraction.toDouble()).toLong()
        seek(timeline.startOfSlot(timeline.slotAtSample(sample)).toLong())
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Header(frame)
        Spacer(Modifier.height(18.dp))

        Waveform(
            envelope = envelope,
            timeline = timeline,
            position = position,
            modifier = Modifier.fillMaxWidth().height(190.dp).background(Panel),
        )

        Spacer(Modifier.height(10.dp))
        Slider(
            value = timeline.progressOfSample(position).toFloat(),
            onValueChange = ::seekToFraction,
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Grid,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Mono(timeline.clockOfSlot(0), Dim)
            Mono(Iso8601.clock(frame.manifest.toMillis), Dim)
        }

        Spacer(Modifier.height(22.dp))
        Readout(frame, timeline, position)

        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                if (player.isPlaying) {
                    player.stop()
                    playing = false
                } else {
                    player.play(position)
                    playing = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (playing) "pause" else "play")
        }
        Spacer(Modifier.height(6.dp))
        Mono("seeks land on the engine's slot_samples boundaries", Dim)
    }
}

@Composable
private fun Header(frame: StationFrame) {
    val manifest = frame.manifest
    Column {
        Text("chronotone", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Mono(
            "${Iso8601.formatInstant(manifest.fromMillis).substring(0, 10)}   " +
                "${Iso8601.clock(manifest.fromMillis)} → ${Iso8601.clock(manifest.toMillis)} UTC",
            Muted,
        )
    }
}

@Composable
private fun Waveform(envelope: FloatArray, timeline: Timeline, position: Long, modifier: Modifier) {
    Canvas(modifier) {
        val middle = size.height / 2f
        val limit = size.height / 2f - 9f
        val played = timeline.progressOfSample(position).toFloat()

        for (slot in 1 until timeline.slotCount) {
            val x = size.width * timeline.startOfSlot(slot) / timeline.audioEndSamples
            drawLine(Grid, Offset(x, 6f), Offset(x, size.height - 6f), strokeWidth = 1f)
        }

        if (envelope.isNotEmpty()) {
            val step = size.width / (envelope.size - 1).coerceAtLeast(1)
            for (index in envelope.indices) {
                val height = envelope[index] * limit
                if (height <= 0f) continue
                val x = index * step
                val colour = if (index.toFloat() / (envelope.size - 1) <= played) Amber else WaveIdle
                drawLine(colour, Offset(x, middle - height), Offset(x, middle + height), strokeWidth = 1.5f)
            }
        }

        val head = size.width * played
        drawLine(Head, Offset(head, 6f), Offset(head, size.height - 6f), strokeWidth = 2f)
    }
}

@Composable
private fun Readout(frame: StationFrame, timeline: Timeline, position: Long) {
    val slot = timeline.slotAtSample(position)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(
                timeline.clockOfSlot(slot),
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Mono("bar ${slot + 1} / ${timeline.slotCount}", Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Mono("slot_samples[$slot]", Amber)
            Mono("sample ${grouped(position)}", Muted)
            Mono("wav ${frame.manifest.wavSha256.take(12)}", Dim)
            Mono("timeline ${frame.manifest.timelineSha256.take(12)}", Dim)
        }
    }
}

@Composable
private fun Notice(title: String, body: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(body, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun Mono(text: String, colour: Color) {
    Text(text, color = colour, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}

@Composable
private fun ChronotoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            background = Bg,
            surface = Panel,
            onBackground = Ink,
            onSurface = Ink,
        ),
        content = content,
    )
}

private fun grouped(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(" ").reversed()

private val Bg = Color(0xFF0B0E14)
private val Panel = Color(0xFF0F131B)
private val Grid = Color(0xFF1A2029)
private val Ink = Color(0xFFE6EDF3)
private val Muted = Color(0xFF8B949E)
private val Dim = Color(0xFF59636E)
private val Amber = Color(0xFFFFB454)
private val Head = Color(0xFFF2F6FA)
private val WaveIdle = Color(0xFF6B5A3A)

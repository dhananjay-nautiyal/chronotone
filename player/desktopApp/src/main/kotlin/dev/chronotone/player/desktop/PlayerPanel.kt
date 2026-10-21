package dev.chronotone.player.desktop

import dev.chronotone.player.Iso8601
import dev.chronotone.player.StationFrame
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Timer

/**
 * The whole player, painted by hand.
 *
 * One component rather than a tree of widgets, for two reasons: it renders identically whether it is
 * on screen or off-screen (so the documentation screenshot is the real UI, not a mock-up), and the
 * scrubber can snap to the engine's own bar boundaries instead of a pixel grid.
 */
class PlayerPanel(
    private val frame: StationFrame,
    private val audio: AudioPlayer?,
    startSlot: Int = 0,
) : JPanel() {

    private val timeline = frame.timeline
    private val envelope = frame.wav.envelope(BUCKETS)

    private var position: Long =
        timeline.startOfSlot(startSlot.coerceIn(0, timeline.slotCount - 1)).toLong()

    private val clock = Timer(40) {
        // Only the playing thread may move the playhead; a seek while paused must stick.
        val sound = audio
        if (sound != null && sound.isPlaying) {
            position = sound.positionSamples
            repaint()
        }
    }

    private var playButton = java.awt.Rectangle()

    init {
        preferredSize = Dimension(WIDTH, HEIGHT)
        background = BG
        isOpaque = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = onClick(event.x, event.y)
        })

        inputMap.put(KeyStroke.getKeyStroke("SPACE"), "toggle")
        actionMap.put("toggle", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = toggle()
        })
    }

    fun start() {
        clock.start()
        requestFocusInWindow()
    }

    fun stop() {
        clock.stop()
        audio?.stop()
    }

    private fun toggle() {
        val sound = audio ?: return
        if (sound.isPlaying) {
            sound.stop()
        } else {
            sound.play(position)
        }
        repaint()
    }

    private fun onClick(x: Int, y: Int) {
        if (playButton.contains(x, y)) {
            toggle()
            return
        }
        val plot = plot()
        val inWaveform = y >= plot.y && y <= plot.y + plot.height
        val inScrubber = y >= SCRUBBER_Y - 10 && y <= SCRUBBER_Y + 18
        if (!inWaveform && !inScrubber) return

        val fraction = ((x - PAD).toDouble() / (WIDTH - 2 * PAD)).coerceIn(0.0, 1.0)
        val sample = timeline.sampleAtProgress(fraction).toLong()
        val slot = timeline.slotAtSample(sample)
        seekTo(timeline.startOfSlot(slot).toLong())
    }

    private fun seekTo(sample: Long) {
        position = sample
        audio?.let { sound ->
            if (sound.isPlaying) sound.play(sample)
        }
        repaint()
    }

    override fun paintComponent(raw: Graphics) {
        super.paintComponent(raw)
        val g = raw as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = BG
        g.fillRect(0, 0, width, height)

        paintHeader(g)
        val plot = plot()
        paintWaveform(g, plot)
        paintScrubber(g)
        paintReadout(g)
        paintFooter(g)
    }

    private fun paintHeader(g: Graphics2D) {
        g.font = SANS_BOLD_22
        g.color = INK
        g.drawString("chronotone", PAD, 46)

        g.font = MONO_12
        g.color = DIM
        g.drawString("player", PAD + 150, 45)

        val window = frame.manifest
        g.font = MONO_12
        g.color = MUTED
        val label = "${Iso8601.formatInstant(window.fromMillis).substring(0, 10)}   " +
            "${Iso8601.clock(window.fromMillis)} → ${Iso8601.clock(window.toMillis)} UTC"
        g.drawString(label, WIDTH - PAD - g.fontMetrics.stringWidth(label), 45)

        g.color = BORDER
        g.drawLine(PAD, 62, WIDTH - PAD, 62)
    }

    private fun paintWaveform(g: Graphics2D, plot: java.awt.Rectangle) {
        g.color = PANEL
        g.fillRoundRect(plot.x, plot.y, plot.width, plot.height, 10, 10)
        g.color = BORDER
        g.drawRoundRect(plot.x, plot.y, plot.width, plot.height, 10, 10)

        val middle = plot.y + plot.height / 2
        val limit = plot.height / 2 - 10
        val played = timeline.progressOfSample(position)

        // The bar grid the engine laid out: every slot boundary, at its real sample offset.
        g.color = GRID
        for (slot in 1 until timeline.slotCount) {
            val x = plot.x + (plot.width * timeline.startOfSlot(slot).toDouble() / timeline.audioEndSamples).toInt()
            g.drawLine(x, plot.y + 6, x, plot.y + plot.height - 6)
        }

        for (index in 0 until BUCKETS) {
            val x = plot.x + (plot.width * index / (BUCKETS - 1))
            val height = (envelope[index] * limit).toInt()
            if (height <= 0) continue
            val fraction = index.toDouble() / (BUCKETS - 1)
            g.color = if (fraction <= played) AMBER else WAVE_IDLE
            g.drawLine(x, middle - height, x, middle + height)
        }

        // Playhead.
        val headX = plot.x + (plot.width * played).toInt()
        g.color = HEAD
        g.stroke = BasicStroke(1.5f)
        g.drawLine(headX, plot.y + 6, headX, plot.y + plot.height - 6)
        g.stroke = BasicStroke(1f)
        g.fillOval(headX - 3, plot.y + 3, 6, 6)
    }

    private fun paintScrubber(g: Graphics2D) {
        val x0 = PAD
        val x1 = WIDTH - PAD
        val width = x1 - x0
        val track = 8

        g.color = GRID
        g.fillRoundRect(x0, SCRUBBER_Y, width, track, track, track)

        val played = timeline.progressOfSample(position)
        val head = x0 + (width * played).toInt()
        g.color = AMBER
        g.fillRoundRect(x0, SCRUBBER_Y, (head - x0).coerceAtLeast(0), track, track, track)

        g.color = DIM
        for (slot in 0..timeline.slotCount) {
            val x = x0 + (width * slot / timeline.slotCount)
            g.drawLine(x, SCRUBBER_Y + track, x, SCRUBBER_Y + track + 4)
        }

        g.color = HEAD
        g.fillOval(head - 6, SCRUBBER_Y - 2, 12, 12)
        g.color = BG
        g.fillOval(head - 3, SCRUBBER_Y + 1, 6, 6)

        g.font = MONO_11
        g.color = DIM
        g.drawString(timeline.clockOfSlot(0), x0, SCRUBBER_Y + 26)
        val end = Iso8601.clock(frame.manifest.toMillis)
        g.drawString(end, x1 - g.fontMetrics.stringWidth(end), SCRUBBER_Y + 26)
    }

    private fun paintReadout(g: Graphics2D) {
        val slot = timeline.slotAtSample(position)
        val y = READOUT_Y

        g.font = MONO_BOLD_30
        g.color = INK
        g.drawString(timeline.clockOfSlot(slot), PAD, y + 26)

        g.font = MONO_13
        g.color = MUTED
        g.drawString("bar ${slot + 1} / ${timeline.slotCount}", PAD, y + 52)

        g.drawString("sample ${group(position)}", PAD + 150, y + 52)
        g.color = AMBER
        g.drawString("slot_samples[$slot]", PAD + 150, y + 26)

        g.font = MONO_11
        g.color = DIM
        val right = WIDTH - PAD
        val timelineLine = "timeline ${frame.manifest.timelineSha256.take(12)}"
        val wavLine = "wav ${frame.manifest.wavSha256.take(12)}"
        g.drawString(timelineLine, right - g.fontMetrics.stringWidth(timelineLine), y + 26)
        g.drawString(wavLine, right - g.fontMetrics.stringWidth(wavLine), y + 46)
    }

    private fun paintFooter(g: Graphics2D) {
        g.font = MONO_11
        g.color = DIM
        g.drawString("click to seek to a bar  ·  space to play/pause", PAD, HEIGHT - 22)

        g.font = MONO_12
        val playing = audio?.isPlaying == true
        val label = if (playing) "pause" else "play"
        val width = 104
        playButton = java.awt.Rectangle(WIDTH - PAD - width, HEIGHT - 46, width, 30)

        g.color = if (playing) AMBER else PANEL
        g.fillRoundRect(playButton.x, playButton.y, playButton.width, playButton.height, 15, 15)
        g.color = if (playing) AMBER else BORDER
        g.drawRoundRect(playButton.x, playButton.y, playButton.width, playButton.height, 15, 15)

        g.color = if (playing) BG else INK
        if (playing) {
            g.fillRect(playButton.x + 18, playButton.y + 10, 3, 10)
            g.fillRect(playButton.x + 24, playButton.y + 10, 3, 10)
        } else {
            val triangleX = playButton.x + 18
            val triangleY = playButton.y + 10
            g.fillPolygon(
                intArrayOf(triangleX, triangleX + 9, triangleX),
                intArrayOf(triangleY, triangleY + 5, triangleY + 10), 3,
            )
        }
        g.drawString(label, playButton.x + 40, playButton.y + 20)
    }

    private fun plot() = java.awt.Rectangle(PAD, PLOT_Y, WIDTH - 2 * PAD, PLOT_HEIGHT)

    private fun group(value: Long): String =
        value.toString().reversed().chunked(3).joinToString(" ").reversed()

    private companion object {
        const val WIDTH = 960
        const val HEIGHT = 540
        const val PAD = 30
        const val PLOT_Y = 92
        const val PLOT_HEIGHT = 228
        const val SCRUBBER_Y = 348
        const val READOUT_Y = 400
        const val BUCKETS = 900

        val BG = Color(0x0b0e14)
        val PANEL = Color(0x0f131b)
        val BORDER = Color(0x222832)
        val GRID = Color(0x1a2029)
        val INK = Color(0xe6edf3)
        val MUTED = Color(0x8b949e)
        val DIM = Color(0x59636e)
        val AMBER = Color(0xffb454)
        val HEAD = Color(0xf2f6fa)
        val WAVE_IDLE = Color(0x6b5a3a)

        val SANS_BOLD_22 = Font("SansSerif", Font.BOLD, 22)
        val MONO_11 = Font("Monospaced", Font.PLAIN, 11)
        val MONO_12 = Font("Monospaced", Font.PLAIN, 12)
        val MONO_13 = Font("Monospaced", Font.PLAIN, 13)
        val MONO_BOLD_30 = Font("Monospaced", Font.BOLD, 30)
    }
}

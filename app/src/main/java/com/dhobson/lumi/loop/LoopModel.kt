package com.dhobson.lumi.loop

import com.dhobson.lumi.music.Instruments
import kotlin.math.roundToInt

/**
 * The looper's data model and the pure logic around it. No Android or native types,
 * so all of it is unit-tested.
 *
 * Positions are stored in ticks ([LoopTiming.PPQ] per beat), which makes a recording
 * independent of tempo and sample rate. They become audio frames only when the loop
 * is compiled for the native sequencer.
 */

/** A note-on (0x90) or note-off (0x80) at [tick]. The channel comes from the layer. */
data class LoopEvent(val tick: Int, val status: Int, val note: Int, val velocity: Int)

/** One recorded pass or more with one instrument, on its own MIDI channel. */
data class Layer(
    val id: Int,
    val instrumentId: Int,
    val channel: Int,
    val events: List<LoopEvent> = emptyList(),
    val muted: Boolean = false,
    /** Loudness as a percentage, 0 to 100. Applied to note velocities at playback. */
    val volume: Int = LayerVolume.FULL,
    /** Which colour of the layer palette this layer is drawn in. */
    val colorIndex: Int = 0,
) {
    val noteCount: Int get() = events.count { it.status == 0x90 }
}

/**
 * Per-layer volume. It scales note velocities rather than using a MIDI volume
 * controller, because all drum layers and the metronome click share one channel and a
 * controller there would change them all together.
 */
object LayerVolume {
    const val FULL = 100

    /** The velocity to play for a note recorded at [velocity]; never silent for volume above 0. */
    fun scale(velocity: Int, volume: Int): Int =
        ((velocity * volume.coerceIn(0, FULL) + FULL / 2) / FULL).coerceIn(1, 127)
}

/** Colours for telling layers apart. The palette itself lives in the UI. */
object LayerColors {
    const val COUNT = 8

    /** The first colour no existing layer uses; once all are used, they repeat. */
    fun allocate(layers: List<Layer>): Int {
        val used = layers.map { it.colorIndex }.toSet()
        return (0 until COUNT).firstOrNull { it !in used } ?: (layers.size % COUNT)
    }
}

/** The grid that note starts are pulled onto at playback. Recordings keep their real timing. */
enum class Snap(val label: String, val gridTicks: Int) {
    OFF("Off", 0),
    BEAT("Beat", LoopTiming.PPQ),
    HALF_BEAT("Half beat", LoopTiming.PPQ / 2),
    QUARTER_BEAT("Quarter beat", LoopTiming.PPQ / 4),
}

data class LoopProject(
    val tempoBpm: Int = DEFAULT_TEMPO,
    val bars: Int = DEFAULT_BARS,
    val beatsPerBar: Int = 4,
    val layers: List<Layer> = emptyList(),
    val snap: Snap = Snap.BEAT,
) {
    val beats: Int get() = bars * beatsPerBar
    val lengthTicks: Int get() = beats * LoopTiming.PPQ

    fun withLayer(layer: Layer): LoopProject = copy(layers = layers + layer)

    fun withoutLayer(id: Int): LoopProject = copy(layers = layers.filterNot { it.id == id })

    fun updateLayer(id: Int, change: (Layer) -> Layer): LoopProject =
        copy(layers = layers.map { if (it.id == id) change(it) else it })

    companion object {
        const val DEFAULT_TEMPO = 100
        const val DEFAULT_BARS = 2
        const val MIN_TEMPO = 40
        const val MAX_TEMPO = 240
        val BAR_CHOICES = listOf(1, 2, 4, 8)
    }
}

object LoopTiming {
    /** Ticks per beat. */
    const val PPQ = 480

    fun framesPerBeat(sampleRate: Int, tempoBpm: Int): Double = sampleRate * 60.0 / tempoBpm

    fun lengthFrames(project: LoopProject, sampleRate: Int): Int =
        (project.beats * framesPerBeat(sampleRate, project.tempoBpm)).roundToInt()

    fun tickToFrame(tick: Int, framesPerBeat: Double): Int = (tick * framesPerBeat / PPQ).roundToInt()

    fun frameToTick(frame: Long, framesPerBeat: Double): Int = (frame * PPQ / framesPerBeat).roundToInt()
}

/** MIDI channels for layers. Channel 0 is left for live playing; 9 is General MIDI percussion. */
object LoopChannels {
    val melodic: List<Int> = (1..15).filter { it != Instruments.DRUM_CHANNEL }

    /** A channel for a new layer, or null when every melodic channel is taken. All drum layers share one. */
    fun allocate(layers: List<Layer>, instrumentId: Int): Int? {
        if (instrumentId == Instruments.DRUM_KIT_ID) return Instruments.DRUM_CHANNEL
        val used = layers.map { it.channel }.toSet()
        return melodic.firstOrNull { it !in used }
    }
}

/**
 * Quantising: moves each note's start to the nearest grid line and its end by the
 * same amount, so notes keep their length. Works on a copy; the recording itself is
 * never changed, which is what lets the grid be switched or turned off afterwards.
 */
object LoopQuantizer {
    /**
     * [events] must be in the order they were played. Each note-on is paired with the
     * next note-off of the same key after it in that order.
     */
    fun apply(events: List<LoopEvent>, gridTicks: Int, lengthTicks: Int): List<LoopEvent> {
        if (gridTicks <= 0) return events
        val result = events.toMutableList()
        val release = NotePairing.releaseIndexes(events)
        for (i in events.indices) {
            val start = events[i]
            if (start.status != 0x90) continue
            val shift = nearestGridLine(start.tick, gridTicks) - start.tick
            result[i] = start.copy(tick = Math.floorMod(start.tick + shift, lengthTicks))
            val end = release[i]
            if (end >= 0) {
                result[end] = events[end].copy(tick = Math.floorMod(events[end].tick + shift, lengthTicks))
            }
        }
        return result
    }

    private fun nearestGridLine(tick: Int, gridTicks: Int): Int =
        Math.floorDiv(tick + gridTicks / 2, gridTicks) * gridTicks
}

/** Matches each note start with its release. */
internal object NotePairing {
    /**
     * For each note-on in [events] (which must be in played order), the index of its
     * release: the next note-off of the same key after it that no earlier note has
     * claimed. -1 for other events and for notes with no release yet.
     */
    fun releaseIndexes(events: List<LoopEvent>): IntArray {
        val release = IntArray(events.size) { -1 }
        val claimed = BooleanArray(events.size)
        for (i in events.indices) {
            val start = events[i]
            if (start.status != 0x90) continue
            val end = (i + 1 until events.size).firstOrNull {
                !claimed[it] && events[it].status == 0x80 && events[it].note == start.note
            }
            if (end != null) {
                claimed[end] = true
                release[i] = end
            }
        }
        return release
    }
}

/** One note as drawn on a layer's timeline. A note may run past the loop end and wrap round. */
data class NoteSpan(val startTick: Int, val lengthTicks: Int, val note: Int)

object LoopNotes {
    /** Length shown for a note that has no release yet (a key still held while recording). */
    const val UNFINISHED_LENGTH_TICKS = LoopTiming.PPQ / 4

    /**
     * The notes of a layer as they are heard, that is with the snap grid applied, for
     * drawing. [events] must be in played order.
     */
    fun spans(events: List<LoopEvent>, gridTicks: Int, loopTicks: Int): List<NoteSpan> {
        val heard = LoopQuantizer.apply(events, gridTicks, loopTicks)
        val release = NotePairing.releaseIndexes(heard)
        val spans = ArrayList<NoteSpan>()
        for (i in heard.indices) {
            val start = heard[i]
            if (start.status != 0x90) continue
            val length = if (release[i] >= 0) {
                Math.floorMod(heard[release[i]].tick - start.tick, loopTicks)
            } else {
                UNFINISHED_LENGTH_TICKS
            }
            spans += NoteSpan(start.tick, length.coerceAtLeast(1), start.note)
        }
        return spans
    }
}

/** The loop in the form the native sequencer takes: parallel arrays sorted by frame. */
class CompiledLoop(
    val frames: IntArray,
    val messages: IntArray,
    val lengthFrames: Int,
    val framesPerBeat: Double,
    val beatsPerBar: Int,
)

object LoopCompiler {
    fun compile(project: LoopProject, sampleRate: Int): CompiledLoop {
        val framesPerBeat = LoopTiming.framesPerBeat(sampleRate, project.tempoBpm)
        val lengthFrames = LoopTiming.lengthFrames(project, sampleRate)

        class Scheduled(val frame: Int, val order: Int, val message: Int)

        val scheduled = ArrayList<Scheduled>()
        for (layer in project.layers) {
            if (layer.muted || layer.volume <= 0) continue
            if (layer.instrumentId != Instruments.DRUM_KIT_ID) {
                // Each pass starts by selecting the layer's instrument on its channel.
                scheduled += Scheduled(0, ORDER_PROGRAM, pack(0xC0 or layer.channel, layer.instrumentId, 0))
            }
            for (event in LoopQuantizer.apply(layer.events, project.snap.gridTicks, project.lengthTicks)) {
                val frame = LoopTiming.tickToFrame(event.tick, framesPerBeat).coerceIn(0, lengthFrames - 1)
                val order = if (event.status == 0x80) ORDER_NOTE_OFF else ORDER_NOTE_ON
                val velocity = if (event.status == 0x90) LayerVolume.scale(event.velocity, layer.volume) else event.velocity
                scheduled += Scheduled(frame, order, pack(event.status or layer.channel, event.note, velocity))
            }
        }
        // At the same frame: instrument first, then releases, then new notes, so a note
        // re-struck exactly where it ends is not cut off by its own release.
        scheduled.sortWith(compareBy<Scheduled> { it.frame }.thenBy { it.order })

        return CompiledLoop(
            frames = IntArray(scheduled.size) { scheduled[it].frame },
            messages = IntArray(scheduled.size) { scheduled[it].message },
            lengthFrames = lengthFrames,
            framesPerBeat = framesPerBeat,
            beatsPerBar = project.beatsPerBar,
        )
    }

    fun pack(status: Int, data1: Int, data2: Int): Int =
        (status and 0xFF) or ((data1 and 0x7F) shl 8) or ((data2 and 0x7F) shl 16)

    private const val ORDER_PROGRAM = 0
    private const val ORDER_NOTE_OFF = 1
    private const val ORDER_NOTE_ON = 2
}

/**
 * Turns what the native sequencer captured during one take into [LoopEvent]s.
 *
 * [compensationFrames] is subtracted from every position: the player hears the loop
 * late by the audio output delay and plays in time with what they hear, so their
 * notes arrive that much after the position they were aiming for.
 */
class TakeRecorder(
    private val lengthTicks: Int,
    private val lengthFrames: Int,
    private val framesPerBeat: Double,
    private val compensationFrames: Int,
) {
    private val held = HashSet<Int>()

    /** Converts one captured (frame, packed message) pair; null if it is not a note. */
    fun onCaptured(frame: Long, message: Int): LoopEvent? {
        val status = message and 0xF0
        val note = (message shr 8) and 0x7F
        val velocity = (message shr 16) and 0x7F
        val tick = tickFor(frame)
        return when {
            status == 0x90 && velocity > 0 -> {
                held += note
                LoopEvent(tick, 0x90, note, velocity)
            }
            status == 0x80 || status == 0x90 -> {
                held -= note
                LoopEvent(tick, 0x80, note, 0)
            }
            else -> null
        }
    }

    /** Releases for notes still held when the take ends at [frame], so nothing sticks. */
    fun closeHeld(frame: Long): List<LoopEvent> {
        val tick = tickFor(frame)
        return held.sorted().map { LoopEvent(tick, 0x80, it, 0) }.also { held.clear() }
    }

    private fun tickFor(frame: Long): Int {
        val compensated = Math.floorMod(frame - compensationFrames, lengthFrames.toLong())
        return LoopTiming.frameToTick(compensated, framesPerBeat).coerceIn(0, lengthTicks - 1)
    }
}

/** Where the playhead is, for display. */
data class LoopPosition(
    /** Beats left in the count-in, or 0 once the loop itself is running. */
    val countInBeats: Int,
    val bar: Int,
    val beat: Int,
) {
    companion object {
        fun at(positionFrames: Long, framesPerBeat: Double, beatsPerBar: Int): LoopPosition {
            if (positionFrames < 0) {
                val left = Math.ceil(-positionFrames / framesPerBeat).toInt()
                return LoopPosition(countInBeats = left, bar = 0, beat = 0)
            }
            val beatIndex = (positionFrames / framesPerBeat).toInt()
            return LoopPosition(0, bar = beatIndex / beatsPerBar + 1, beat = beatIndex % beatsPerBar + 1)
        }
    }
}

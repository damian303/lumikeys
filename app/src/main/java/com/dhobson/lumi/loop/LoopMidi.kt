package com.dhobson.lumi.loop

import com.dhobson.lumi.music.Instruments
import java.io.ByteArrayOutputStream

/**
 * Writes a loop as a Standard MIDI File (.mid) that other music programs can open.
 * Pure Kotlin, written from the published file format.
 *
 * The file holds one pass of the loop as it sounds:
 *  - the snap grid is applied, as at playback;
 *  - each layer's volume is applied to its note velocities, as at playback;
 *  - muted layers, silent layers and layers with no notes are left out;
 *  - a note held across the loop's end is cut off at the end, so every track is
 *    exactly the loop's length and the file can be repeated end to end.
 *
 * Layout: format 1; track 1 carries the name, time signature and tempo; then one
 * track per layer, named after its instrument, on the layer's own channel (drums on
 * channel 10) with a program change at the start. Timing is [LoopTiming.PPQ] ticks a
 * beat, the same as the loop itself, so positions are written unchanged.
 */
object LoopMidi {
    /** Used as the file's title when the loop has no name. */
    const val DEFAULT_TITLE = "Lumi loop"

    /** The layers [encode] would write, in order. */
    fun audibleLayers(project: LoopProject): List<Layer> =
        project.layers.filter { !it.muted && it.volume > 0 && it.noteCount > 0 }

    fun encode(project: LoopProject, title: String? = null): ByteArray {
        val layers = audibleLayers(project)
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.int32(6)
        out.int16(1) // format 1: several tracks played together
        out.int16(1 + layers.size)
        out.int16(LoopTiming.PPQ)

        out.chunk(conductorTrack(project, title?.ifBlank { null } ?: DEFAULT_TITLE))
        for (layer in layers) out.chunk(layerTrack(project, layer))
        return out.toByteArray()
    }

    private fun conductorTrack(project: LoopProject, title: String): ByteArray {
        val track = ByteArrayOutputStream()
        track.meta(0, META_TRACK_NAME, text(title))
        // Beats in a bar over a quarter note (2 to the power 2), 24 MIDI clocks a beat,
        // eight 32nd notes to a quarter: the usual values.
        track.meta(0, META_TIME_SIGNATURE, byteArrayOf(project.beatsPerBar.toByte(), 2, 24, 8))
        val microsPerBeat = Math.round(60_000_000.0 / project.tempoBpm).toInt()
        track.meta(
            0, META_TEMPO,
            byteArrayOf((microsPerBeat shr 16).toByte(), (microsPerBeat shr 8).toByte(), microsPerBeat.toByte()),
        )
        track.meta(project.lengthTicks, META_END_OF_TRACK, ByteArray(0))
        return track.toByteArray()
    }

    private class Timed(val tick: Int, val order: Int, val status: Int, val note: Int, val velocity: Int)

    private fun layerTrack(project: LoopProject, layer: Layer): ByteArray {
        val length = project.lengthTicks
        val heard = LoopQuantizer.apply(layer.events, project.snap.gridTicks, length)
        val release = NotePairing.releaseIndexes(heard)
        val timed = ArrayList<Timed>()
        for (i in heard.indices) {
            val start = heard[i]
            if (start.status != 0x90) continue
            val startTick = start.tick.coerceIn(0, length - 1)
            val held = if (release[i] >= 0) {
                Math.floorMod(heard[release[i]].tick - start.tick, length)
            } else {
                LoopNotes.UNFINISHED_LENGTH_TICKS
            }
            val endTick = (startTick + held.coerceAtLeast(1)).coerceAtMost(length)
            timed += Timed(startTick, ORDER_NOTE_ON, 0x90, start.note, LayerVolume.scale(start.velocity, layer.volume))
            timed += Timed(endTick, ORDER_NOTE_OFF, 0x80, start.note, 0)
        }
        // At the same tick releases come first, so a key struck again exactly where it
        // ends is not cut off by its own release. The sort keeps played order otherwise.
        timed.sortWith(compareBy<Timed> { it.tick }.thenBy { it.order })

        val track = ByteArrayOutputStream()
        track.meta(0, META_TRACK_NAME, text(Instruments.byId(layer.instrumentId)?.name ?: "Instrument"))
        val channel = layer.channel and 0x0F
        if (layer.instrumentId != Instruments.DRUM_KIT_ID) {
            track.varLength(0)
            track.write(0xC0 or channel)
            track.write(layer.instrumentId and 0x7F)
        }
        var at = 0
        for (event in timed) {
            track.varLength(event.tick - at)
            at = event.tick
            track.write(event.status or channel)
            track.write(event.note and 0x7F)
            track.write(event.velocity and 0x7F)
        }
        track.meta(length - at, META_END_OF_TRACK, ByteArray(0))
        return track.toByteArray()
    }

    /** Text in a MIDI file is single-byte; anything outside Latin-1 becomes a question mark. */
    private fun text(value: String): ByteArray = value.toByteArray(Charsets.ISO_8859_1)

    private fun ByteArrayOutputStream.chunk(track: ByteArray) {
        write("MTrk".toByteArray(Charsets.US_ASCII))
        int32(track.size)
        write(track)
    }

    private fun ByteArrayOutputStream.meta(delta: Int, type: Int, data: ByteArray) {
        varLength(delta)
        write(0xFF)
        write(type)
        varLength(data.size)
        write(data)
    }

    private fun ByteArrayOutputStream.int16(value: Int) {
        write(value shr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.int32(value: Int) {
        write(value shr 24)
        write(value shr 16)
        write(value shr 8)
        write(value)
    }

    /** A variable-length quantity: seven bits per byte, the top bit set on all but the last. */
    private fun ByteArrayOutputStream.varLength(value: Int) {
        require(value in 0..0x0FFFFFFF) { "cannot be written as a variable-length quantity: $value" }
        var shift = 21
        while (shift > 0 && (value shr shift) == 0) shift -= 7
        while (shift > 0) {
            write(((value shr shift) and 0x7F) or 0x80)
            shift -= 7
        }
        write(value and 0x7F)
    }

    private const val META_TRACK_NAME = 0x03
    private const val META_END_OF_TRACK = 0x2F
    private const val META_TEMPO = 0x51
    private const val META_TIME_SIGNATURE = 0x58
    private const val ORDER_NOTE_OFF = 0
    private const val ORDER_NOTE_ON = 1
}

package com.dhobson.lumi.learn

/**
 * A reader for Standard MIDI Files (.mid), written from the published file format.
 * It keeps what the learn mode needs: notes, the first tempo, track names and
 * instruments. Pure Kotlin.
 */

/** A note as written in the file. Ticks are the file's own, [MidiFile.ticksPerBeat] per beat. */
data class MidiNote(val startTick: Long, val lengthTicks: Long, val pitch: Int, val channel: Int, val velocity: Int = 80)

class MidiTrack(
    val name: String?,
    val notes: List<MidiNote>,
    /** General MIDI program first selected on each channel in this track. */
    val programs: Map<Int, Int>,
)

class MidiFile(
    val format: Int,
    val ticksPerBeat: Int,
    /** Tempo at the start of the piece, in beats per minute; 120 if the file gives none. */
    val bpm: Double,
    val tracks: List<MidiTrack>,
)

object MidiFileReader {
    private const val DEFAULT_MICROS_PER_BEAT = 500_000 // 120 bpm, the format's default

    /** The file's contents, or null if [bytes] is not a MIDI file this reader understands. */
    fun read(bytes: ByteArray): MidiFile? = try {
        parse(bytes)
    } catch (e: Malformed) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null // ran off the end of a truncated file
    }

    private class Malformed : Exception()

    private class Cursor(val data: ByteArray, var at: Int, val end: Int) {
        val remaining: Int get() = end - at
        fun byte(): Int {
            if (at >= end) throw Malformed()
            return data[at++].toInt() and 0xFF
        }
        fun peek(): Int {
            if (at >= end) throw Malformed()
            return data[at].toInt() and 0xFF
        }
        fun int16(): Int = (byte() shl 8) or byte()
        fun int32(): Long = (byte().toLong() shl 24) or (byte().toLong() shl 16) or (byte().toLong() shl 8) or byte().toLong()
        fun text(length: Int): String {
            if (length < 0 || length > remaining) throw Malformed()
            return String(data, at, length, Charsets.ISO_8859_1).also { at += length }
        }
        fun skip(length: Long) {
            if (length < 0 || length > remaining) throw Malformed()
            at += length.toInt()
        }
        /** A variable-length quantity: seven bits per byte, the top bit set on all but the last. */
        fun varLength(): Long {
            var value = 0L
            repeat(4) {
                val b = byte()
                value = (value shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return value
            }
            throw Malformed() // more than four bytes is not allowed
        }
    }

    private fun parse(bytes: ByteArray): MidiFile {
        val file = Cursor(bytes, 0, bytes.size)
        if (file.text(4) != "MThd") throw Malformed()
        val headerLength = file.int32()
        if (headerLength < 6) throw Malformed()
        val format = file.int16()
        file.int16() // number of tracks; the chunks themselves are counted instead
        val division = file.int16()
        file.skip(headerLength - 6)
        // Top bit set means time-code based timing, which has no beats to follow.
        if (format !in 0..1 || division and 0x8000 != 0 || division == 0) throw Malformed()

        val tracks = ArrayList<MidiTrack>()
        var firstTempo: Pair<Long, Int>? = null // (tick, microseconds per beat)
        while (file.remaining >= 8) {
            val type = file.text(4)
            val length = file.int32()
            if (length > file.remaining) throw Malformed()
            val chunkEnd = file.at + length.toInt()
            if (type == "MTrk") {
                val (track, tempo) = parseTrack(Cursor(bytes, file.at, chunkEnd))
                tracks += track
                if (tempo != null && (firstTempo == null || tempo.first < firstTempo.first)) firstTempo = tempo
            }
            file.at = chunkEnd // unknown chunk types are skipped
        }
        if (tracks.isEmpty()) throw Malformed()
        val microsPerBeat = firstTempo?.second ?: DEFAULT_MICROS_PER_BEAT
        return MidiFile(format, division, 60_000_000.0 / microsPerBeat, tracks)
    }

    private fun parseTrack(track: Cursor): Pair<MidiTrack, Pair<Long, Int>?> {
        var tick = 0L
        var runningStatus = 0
        var name: String? = null
        var tempo: Pair<Long, Int>? = null
        val programs = HashMap<Int, Int>()
        val notes = ArrayList<MidiNote>()
        // Notes that have started and not ended, by channel and pitch. The same key can be
        // struck again before it is released, so each holds a queue of start times.
        val sounding = HashMap<Int, ArrayDeque<Pair<Long, Int>>>() // (start tick, velocity)

        fun noteOff(channel: Int, pitch: Int) {
            val (start, velocity) = sounding[channel * 128 + pitch]?.removeFirstOrNull() ?: return
            notes += MidiNote(start, tick - start, pitch, channel, velocity)
        }

        while (track.remaining > 0) {
            tick += track.varLength()
            var status = track.peek()
            if (status < 0x80) {
                // Running status: the status byte is left out when it repeats the last one.
                if (runningStatus == 0) throw Malformed()
                status = runningStatus
            } else {
                track.byte()
            }
            when {
                status == 0xFF -> { // meta event
                    val type = track.byte()
                    val length = track.varLength()
                    when (type) {
                        0x03 -> name = track.text(length.toInt()).trim().ifEmpty { null } ?: name
                        0x51 -> {
                            if (length != 3L) throw Malformed()
                            val micros = (track.byte() shl 16) or (track.byte() shl 8) or track.byte()
                            if (tempo == null && micros > 0) tempo = tick to micros
                        }
                        0x2F -> { // end of track
                            track.skip(length)
                            track.at = track.end
                        }
                        else -> track.skip(length)
                    }
                    runningStatus = 0
                }
                status == 0xF0 || status == 0xF7 -> { // system exclusive
                    track.skip(track.varLength())
                    runningStatus = 0
                }
                status >= 0xF0 -> throw Malformed() // other system messages do not belong in a file
                else -> {
                    runningStatus = status
                    val channel = status and 0x0F
                    val data1 = track.byte()
                    if (data1 > 0x7F) throw Malformed()
                    when (status and 0xF0) {
                        0x90 -> {
                            val velocity = track.byte()
                            if (velocity == 0) {
                                noteOff(channel, data1) // the common shorthand for note-off
                            } else {
                                sounding.getOrPut(channel * 128 + data1) { ArrayDeque() }.addLast(tick to velocity)
                            }
                        }
                        0x80 -> {
                            track.byte()
                            noteOff(channel, data1)
                        }
                        0xC0 -> programs.putIfAbsent(channel, data1)
                        0xD0 -> Unit // channel pressure: one data byte, already read
                        else -> track.byte() // aftertouch, controller, pitch bend: a second data byte
                    }
                }
            }
        }
        // Notes never released end where the track ends.
        for ((key, starts) in sounding) {
            for ((start, velocity) in starts) notes += MidiNote(start, tick - start, key % 128, key / 128, velocity)
        }
        notes.sortWith(compareBy<MidiNote> { it.startTick }.thenBy { it.pitch })
        return MidiTrack(name, notes, programs) to tempo
    }
}

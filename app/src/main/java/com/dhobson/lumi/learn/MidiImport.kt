package com.dhobson.lumi.learn

import com.dhobson.lumi.music.Instruments
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a part of a MIDI file into a [Song] for the learn mode: pick a part, optionally
 * keep only its top line, and fit it onto two octaves. Pure Kotlin.
 */

/** One playable part of a file: the notes of one channel within one track. */
class MidiPart(
    val name: String,
    val isDrums: Boolean,
    val notes: List<MidiNote>,
    /** The MIDI channel (0 to 15) these notes are on in the file. */
    val channel: Int,
    /** The General MIDI instrument the file selects for this part, if it says. */
    val program: Int?,
) {
    val noteCount: Int get() = notes.size
    val lowest: Int get() = notes.minOf { it.pitch }
    val highest: Int get() = notes.maxOf { it.pitch }
    val meanPitch: Double get() = notes.sumOf { it.pitch }.toDouble() / notes.size
}

object MidiImport {
    private const val DRUM_CHANNEL = 9

    /** Notes whose starts are this close (in [Song.PPQ] ticks) count as played together. */
    const val TOGETHER_TICKS = 40

    /** Shortest note kept, so every note can be seen. */
    private const val MIN_LENGTH_TICKS = 60

    /** The parts of [file] that have notes, in file order. */
    fun parts(file: MidiFile): List<MidiPart> {
        val parts = ArrayList<MidiPart>()
        file.tracks.forEachIndexed { index, track ->
            val byChannel = track.notes.groupBy { it.channel }.toSortedMap()
            for ((channel, notes) in byChannel) {
                var name = track.name ?: "Track ${index + 1}"
                if (byChannel.size > 1) name += ", channel ${channel + 1}"
                val instrument = if (channel == DRUM_CHANNEL) {
                    "Drums"
                } else {
                    track.programs[channel]?.let { Instruments.byId(it)?.name }
                }
                if (instrument != null) name += " ($instrument)"
                parts += MidiPart(name, channel == DRUM_CHANNEL, notes, channel, track.programs[channel])
            }
        }
        return parts
    }

    /**
     * A guess at the part that carries the tune: the highest-lying part that is not
     * drums and is not tiny next to the others. Index into [parts], or null if there are none.
     */
    fun likelyMelody(parts: List<MidiPart>): Int? {
        if (parts.isEmpty()) return null
        val pitched = parts.withIndex().filter { !it.value.isDrums }
        if (pitched.isEmpty()) return 0
        val substantial = pitched.maxOf { it.value.noteCount } / 4
        return pitched.filter { it.value.noteCount >= substantial }.maxByOrNull { it.value.meanPitch }?.index
    }

    class Result(
        val song: Song,
        /** Notes in the chosen part before anything was left out. */
        val notesInPart: Int,
        /** Notes moved up or down by octaves to bring them onto the keyboard. */
        val movedByOctave: Int,
        /** How many of the file's other parts play along as accompaniment. */
        val backingParts: Int,
    ) {
        /** Notes of the chosen part that the player does not play (lower notes of chords). */
        val leftOut: Int get() = notesInPart - song.notes.size
    }

    /**
     * The part [partIndex] of [parts] (which must come from [parts] on [file]) as a song
     * for the player, with every other part as accompaniment.
     *
     * With [topNotesOnly], where several notes of the chosen part start together only the
     * highest is kept for the player, which turns chords into a single line; the rest
     * join the accompaniment, so the harmony is still heard. The player's notes that do
     * not fit the two-octave span holding most of the part are moved by octaves into
     * it. The accompaniment is left at its written pitch and on its own instruments.
     */
    fun toSong(
        file: MidiFile,
        parts: List<MidiPart>,
        partIndex: Int,
        topNotesOnly: Boolean,
        id: String,
        title: String,
    ): Result {
        val part = parts[partIndex]
        val scale = Song.PPQ.toDouble() / file.ticksPerBeat
        fun ticks(value: Long): Int = (value * scale).roundToInt()

        var mine = part.notes
            .map {
                SongNote(ticks(it.startTick), ticks(it.lengthTicks).coerceAtLeast(MIN_LENGTH_TICKS), it.pitch) to it
            }
            .sortedWith(compareBy({ it.first.startTick }, { it.first.pitch }))
        val aligned = alignStarts(mine.map { it.first })
        mine = aligned.zip(mine.map { it.second })

        // What the player does not play goes to the accompaniment, on the part's own channel.
        val backing = ArrayList<BackingNote>()
        fun accompany(note: MidiNote, startTick: Int) {
            backing += BackingNote(
                startTick = startTick,
                lengthTicks = ticks(note.lengthTicks).coerceAtLeast(MIN_LENGTH_TICKS),
                pitch = note.pitch,
                velocity = (note.velocity * BACKING_LEVEL).roundToInt().coerceIn(1, 127),
                channel = note.channel,
            )
        }
        var notes = mine.map { it.first }
        if (topNotesOnly) {
            val kept = ArrayList<SongNote>()
            for ((_, together) in mine.groupBy { it.first.startTick }.toSortedMap()) {
                val top = together.maxBy { it.first.pitch }
                kept += top.first
                for (other in together) if (other !== top) accompany(other.second, other.first.startTick)
            }
            notes = kept
        }
        var backingParts = 0
        parts.forEachIndexed { index, other ->
            if (index == partIndex) return@forEachIndexed
            backingParts++
            for (note in other.notes) accompany(note, ticks(note.startTick))
        }

        // Start on the first sound of the piece, keeping any introduction before the player comes in.
        val lead = minOf(notes.firstOrNull()?.startTick ?: Int.MAX_VALUE, backing.minOfOrNull { it.startTick } ?: Int.MAX_VALUE)
        if (lead != 0 && lead != Int.MAX_VALUE) {
            notes = notes.map { it.copy(startTick = it.startTick - lead) }
            for (i in backing.indices) backing[i] = backing[i].copy(startTick = backing[i].startTick - lead)
        }
        backing.sortWith(compareBy({ it.startTick }, { it.channel }, { it.pitch }))

        // Each channel's instrument: the first one any track selects for it.
        val programs = HashMap<Int, Int>()
        for (track in file.tracks) for ((channel, program) in track.programs) programs.putIfAbsent(channel, program)
        val usedChannels = backing.map { it.channel }.toSet()

        val (fitted, moved) = foldOntoTwoOctaves(notes)
        val song = Song(
            id = id,
            title = title,
            credit = "Imported",
            bpm = file.bpm.roundToInt().coerceIn(MIN_BPM, MAX_BPM),
            notes = fitted,
            backing = backing,
            backingPrograms = programs.filterKeys { it in usedChannels && it != DRUM_CHANNEL }.toSortedMap(),
        )
        return Result(song, part.noteCount, moved, backingParts)
    }

    /** How loud the accompaniment is next to the player, as a fraction of its written loudness. */
    private const val BACKING_LEVEL = 0.75

    /** Gives notes that start almost together exactly the same start, so they form one chord. */
    private fun alignStarts(sorted: List<SongNote>): List<SongNote> {
        val aligned = ArrayList<SongNote>(sorted.size)
        var groupStart = Int.MIN_VALUE
        for (note in sorted) {
            if (groupStart == Int.MIN_VALUE || note.startTick - groupStart > TOGETHER_TICKS) groupStart = note.startTick
            aligned += if (note.startTick == groupStart) note else note.copy(startTick = groupStart)
        }
        return aligned
    }

    /**
     * Chooses the two-octave span, starting on a C, that already holds the most notes,
     * and moves every other note into it by whole octaves. Returns the notes and how
     * many were moved. Notes that land on top of each other are merged.
     */
    fun foldOntoTwoOctaves(notes: List<SongNote>): Pair<List<SongNote>, Int> {
        if (notes.isEmpty()) return notes to 0
        val mean = notes.sumOf { it.pitch }.toDouble() / notes.size
        val base = (0..96 step 12).maxWith(
            compareBy<Int> { candidate -> notes.count { it.pitch in candidate until candidate + SongFit.KEYS } }
                .thenBy { candidate -> -abs(candidate + (SongFit.KEYS - 1) / 2.0 - mean) },
        )
        val top = base + SongFit.KEYS - 1
        var moved = 0
        val folded = notes.map { note ->
            val pitch = when {
                note.pitch < base -> note.pitch + 12 * ((base - note.pitch + 11) / 12)
                note.pitch > top -> note.pitch - 12 * ((note.pitch - top + 11) / 12)
                else -> note.pitch
            }
            if (pitch != note.pitch) moved++
            note.copy(pitch = pitch)
        }
        val merged = folded
            .groupBy { it.startTick to it.pitch }
            .map { (_, same) -> same.maxBy { it.lengthTicks } }
            .sortedWith(compareBy<SongNote> { it.startTick }.thenBy { it.pitch })
        return merged to moved
    }

    private const val MIN_BPM = 30
    private const val MAX_BPM = 300
}

/**
 * Reads and writes an imported song as plain text, one item per line:
 *
 *     lumisong 1
 *     title Some Song
 *     credit Imported
 *     bpm 110
 *     n <start tick> <length ticks> <pitch>                          (a note for the player)
 *     p <channel> <program>                                          (an accompaniment instrument)
 *     b <start tick> <length ticks> <pitch> <velocity> <channel>     (an accompaniment note)
 */
object SongCodec {
    private const val HEADER = "lumisong 1"

    fun encode(song: Song): String = buildString {
        appendLine(HEADER)
        appendLine("title ${song.title.replace('\n', ' ')}")
        appendLine("credit ${song.credit.replace('\n', ' ')}")
        appendLine("bpm ${song.bpm}")
        for (note in song.notes) appendLine("n ${note.startTick} ${note.lengthTicks} ${note.pitch}")
        for ((channel, program) in song.backingPrograms) appendLine("p $channel $program")
        for (note in song.backing) {
            appendLine("b ${note.startTick} ${note.lengthTicks} ${note.pitch} ${note.velocity} ${note.channel}")
        }
    }

    /** The song in [text], given the id [id]; null if it is not a song file this version understands. */
    fun decode(text: String, id: String): Song? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != HEADER) return null
        var title: String? = null
        var credit = ""
        var bpm = 100
        val notes = ArrayList<SongNote>()
        val backing = ArrayList<BackingNote>()
        val programs = HashMap<Int, Int>()
        for (line in lines.drop(1)) {
            val word = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            when (word) {
                "title" -> title = rest
                "credit" -> credit = rest
                "bpm" -> bpm = rest.toIntOrNull() ?: return null
                "n" -> {
                    val numbers = rest.split(' ').map { it.toIntOrNull() ?: return null }
                    if (numbers.size != 3 || numbers[0] < 0 || numbers[1] <= 0 || numbers[2] !in 0..127) return null
                    notes += SongNote(numbers[0], numbers[1], numbers[2])
                }
                "p" -> {
                    val numbers = rest.split(' ').map { it.toIntOrNull() ?: return null }
                    if (numbers.size != 2 || numbers[0] !in 0..15 || numbers[1] !in 0..127) return null
                    programs[numbers[0]] = numbers[1]
                }
                "b" -> {
                    val numbers = rest.split(' ').map { it.toIntOrNull() ?: return null }
                    if (numbers.size != 5 || numbers[0] < 0 || numbers[1] <= 0 || numbers[2] !in 0..127 ||
                        numbers[3] !in 1..127 || numbers[4] !in 0..15
                    ) {
                        return null
                    }
                    backing += BackingNote(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4])
                }
                else -> Unit
            }
        }
        if (title.isNullOrBlank() || notes.isEmpty()) return null
        return Song(
            id = id,
            title = title,
            credit = credit,
            bpm = bpm.coerceIn(30, 300),
            notes = notes.sortedWith(compareBy<SongNote> { it.startTick }.thenBy { it.pitch }),
            backing = backing.sortedWith(compareBy({ it.startTick }, { it.channel }, { it.pitch })),
            backingPrograms = programs.toSortedMap(),
        )
    }
}

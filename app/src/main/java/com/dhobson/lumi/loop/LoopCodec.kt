package com.dhobson.lumi.loop

import com.dhobson.lumi.music.Instruments

/**
 * Reads and writes a loop as plain text, one item per line. Pure Kotlin.
 *
 *     lumiloop 1
 *     tempo 100
 *     bars 2
 *     snap BEAT
 *     layer <instrumentId> <muted 0|1> <volume 0-100> <colourIndex>
 *     e <tick> <status 144|128> <note> <velocity>     (the events of the layer above, in played order)
 *
 * MIDI channels are not stored: they are handed out again when a loop is read, so a
 * file can never describe two layers fighting over a channel. Lines with an unknown
 * first word are skipped, which lets later versions add things.
 */
object LoopCodec {
    private const val HEADER = "lumiloop 1"

    fun encode(project: LoopProject): String = buildString {
        appendLine(HEADER)
        appendLine("tempo ${project.tempoBpm}")
        appendLine("bars ${project.bars}")
        appendLine("snap ${project.snap.name}")
        for (layer in project.layers) {
            appendLine("layer ${layer.instrumentId} ${if (layer.muted) 1 else 0} ${layer.volume} ${layer.colorIndex}")
            for (event in layer.events) {
                appendLine("e ${event.tick} ${event.status} ${event.note} ${event.velocity}")
            }
        }
    }

    /**
     * [encode], plus the name the loop was last saved or opened as. Used for the
     * automatically kept current loop; [decode] skips the extra line.
     */
    fun encodeWithName(project: LoopProject, name: String?): String =
        encode(project) + if (name != null) "name $name\n" else ""

    /** The name stored by [encodeWithName], if any. */
    fun nameIn(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("name ") }?.removePrefix("name ")?.trim()?.ifEmpty { null }

    /** The loop in [text], or null if it is not a loop file this version understands. */
    fun decode(text: String): LoopProject? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != HEADER) return null

        var tempo = LoopProject.DEFAULT_TEMPO
        var bars = LoopProject.DEFAULT_BARS
        var snap = Snap.BEAT

        class PendingLayer(val instrumentId: Int, val muted: Boolean, val volume: Int, val colorIndex: Int) {
            val events = ArrayList<LoopEvent>()
        }

        val pending = ArrayList<PendingLayer>()
        for (line in lines.drop(1)) {
            val words = line.split(' ').filter { it.isNotEmpty() }
            val numbers = words.drop(1).map { it.toIntOrNull() }
            when (words[0]) {
                "tempo" -> tempo = numbers.singleOrNull() ?: return null
                "bars" -> bars = numbers.singleOrNull() ?: return null
                "snap" -> snap = Snap.entries.firstOrNull { it.name == words.getOrNull(1) } ?: Snap.BEAT
                "layer" -> {
                    if (numbers.size < 4 || numbers.take(4).any { it == null }) return null
                    val instrumentId = numbers[0]!!
                    if (Instruments.byId(instrumentId) == null) return null
                    pending += PendingLayer(
                        instrumentId = instrumentId,
                        muted = numbers[1] != 0,
                        volume = numbers[2]!!.coerceIn(0, LayerVolume.FULL),
                        colorIndex = numbers[3]!!.coerceIn(0, LayerColors.COUNT - 1),
                    )
                }
                "e" -> {
                    if (numbers.size < 4 || numbers.take(4).any { it == null }) return null
                    val layer = pending.lastOrNull() ?: return null
                    val status = numbers[1]!!
                    val note = numbers[2]!!
                    if ((status != 0x90 && status != 0x80) || note !in 0..127) return null
                    layer.events += LoopEvent(numbers[0]!!, status, note, numbers[3]!!.coerceIn(0, 127))
                }
                else -> Unit
            }
        }
        if (bars !in LoopProject.BAR_CHOICES) return null

        var project = LoopProject(
            tempoBpm = tempo.coerceIn(LoopProject.MIN_TEMPO, LoopProject.MAX_TEMPO),
            bars = bars,
            snap = snap,
        )
        val lengthTicks = project.lengthTicks
        for (layer in pending) {
            // A layer that no channel is left for is dropped rather than made to share one.
            val channel = LoopChannels.allocate(project.layers, layer.instrumentId) ?: continue
            project = project.withLayer(
                Layer(
                    id = project.layers.size + 1,
                    instrumentId = layer.instrumentId,
                    channel = channel,
                    events = layer.events.map { it.copy(tick = it.tick.coerceIn(0, lengthTicks - 1)) },
                    muted = layer.muted,
                    volume = layer.volume,
                    colorIndex = layer.colorIndex,
                ),
            )
        }
        return project
    }
}

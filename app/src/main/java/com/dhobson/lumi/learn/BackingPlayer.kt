package com.dhobson.lumi.learn

/**
 * Plays a song's accompaniment in step with the song's position, rather than with a
 * clock of its own: whenever the position moves on, the notes it has passed are sent.
 * So the accompaniment slows, speeds up and stops exactly as the song does while it
 * waits for the player. Pure logic; [send] receives short MIDI messages
 * (status with channel 0 to 15, data1, data2). Not thread-safe.
 */
class BackingPlayer(private val song: Song, private val send: (status: Int, data1: Int, data2: Int) -> Unit) {
    private class Event(val tick: Int, val on: Boolean, val channel: Int, val pitch: Int, val velocity: Int)

    // Releases come before new notes at the same tick, so a repeated note is not cut by its own release.
    private val events: List<Event> = song.backing
        .flatMap {
            listOf(
                Event(it.startTick, true, it.channel, it.pitch, it.velocity),
                Event(it.startTick + it.lengthTicks, false, it.channel, it.pitch, 0),
            )
        }
        .sortedWith(compareBy({ it.tick }, { it.on }))

    private var next = 0
    private var lastPosition = Double.NEGATIVE_INFINITY

    /** Notes sent and not yet released, as channel * 128 + pitch, with how many times each is held. */
    private val sounding = HashMap<Int, Int>()

    val channels: Set<Int> = song.backing.map { it.channel }.toSortedSet()

    /** Selects each channel's instrument. Call once before the song starts. */
    fun start() {
        for (channel in channels) {
            if (channel == DRUM_CHANNEL) continue
            send(0xC0 or channel, song.backingPrograms[channel] ?: 0, 0)
        }
    }

    /**
     * The song is now at [position] ticks. Sends everything between the last position
     * and this one. If the song has leapt forward (the player pressed a key well before
     * its note arrived), the notes leapt over are not played in a burst: whatever was
     * sounding is released and playing picks up from the new position.
     */
    fun moveTo(position: Double) {
        if (position < lastPosition) return
        val leapt = lastPosition != Double.NEGATIVE_INFINITY && position - lastPosition > LEAP_TICKS
        if (leapt) {
            release()
            while (next < events.size && events[next].tick < position) next++
        }
        while (next < events.size && events[next].tick <= position) {
            val event = events[next++]
            val key = event.channel * 128 + event.pitch
            if (event.on) {
                send(0x90 or event.channel, event.pitch, event.velocity)
                sounding[key] = (sounding[key] ?: 0) + 1
            } else if ((sounding[key] ?: 0) > 0) {
                send(0x80 or event.channel, event.pitch, 0)
                if (sounding[key] == 1) sounding.remove(key) else sounding[key] = sounding[key]!! - 1
            }
        }
        lastPosition = position
    }

    /**
     * Releases every note that is sounding, without moving. Used when the song has been
     * waiting for the player for a moment, so a held chord does not drone on, and when
     * the song stops.
     */
    fun release() {
        for (key in sounding.keys.sorted()) {
            repeat(sounding.getValue(key)) { send(0x80 or (key / 128), key % 128, 0) }
        }
        sounding.clear()
    }

    companion object {
        private const val DRUM_CHANNEL = 9

        /** A forward move of more than this in one step is a leap, not ordinary playing: one beat. */
        const val LEAP_TICKS = Song.PPQ
    }
}

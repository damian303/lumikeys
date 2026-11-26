package com.dhobson.lumi.learn

/**
 * The rules of "wait mode": the song runs at its own speed until the next note is
 * due, then holds there until the player presses the right key (or keys, for notes
 * that start together). Pure logic, driven by [advance] and [keyDown]; not thread-safe.
 *
 * A key press is accepted as soon as its note is the next one, even before the note
 * has reached the keys; the song then jumps forward to that note, so a player who
 * knows the tune can go faster than the display.
 */
class LearnEngine(private val song: Song) : SongRun {
    /** Notes that start on the same tick and must all be pressed before the song moves on. */
    class Group(val tick: Int, val pitches: Set<Int>)

    val groups: List<Group> = song.notes
        .groupBy { it.startTick }
        .toSortedMap()
        .map { (tick, notes) -> Group(tick, notes.map { it.pitch }.toSet()) }

    override var position: Double = -LEAD_IN_TICKS.toDouble()
        private set

    /** Index of the group the player has to play next; equals [groups] size once all are played. */
    var groupIndex = 0
        private set

    private val remaining = HashSet<Int>(groups.firstOrNull()?.pitches.orEmpty())
    private var slippedOnThisGroup = false

    /** Presses of a key that was not wanted. */
    var wrongPresses = 0
        private set

    /** Groups played with no wrong key pressed first. */
    var cleanGroups = 0
        private set

    /** The keys to press now: what is left of the current group. Empty once the song is played. */
    override val targets: Set<Int> get() = remaining

    /** Tick of the group being waited for, or null once every note has been played. */
    override val targetTick: Int? get() = groups.getOrNull(groupIndex)?.tick

    /** True while the song is held at a note, waiting for the player. */
    override val waiting: Boolean get() = targetTick?.let { position >= it } ?: false

    /** True once every note is played and the last one has had time to sound. */
    override val finished: Boolean get() = groupIndex >= groups.size && position >= song.endTick

    /** Moves the song on by [ticks], but never past a note that has not been played. */
    override fun advance(ticks: Double): Boolean {
        if (ticks <= 0) return false
        val limit = (targetTick ?: song.endTick).toDouble()
        if (position < limit) position = minOf(position + ticks, limit)
        return false // only a key press changes what is wanted
    }

    /** Notes before the group being waited for have been played. */
    override fun marks(): List<Mark> {
        val waitingFor = targetTick ?: Int.MAX_VALUE
        return song.notes.map { if (it.startTick < waitingFor) Mark.HIT else Mark.PENDING }
    }

    /** Counted in groups: "perfect" is right first time, "good" is right after a slip. */
    override fun tally() = Tally(
        groups.size, groupIndex, cleanGroups, groupIndex - cleanGroups, 0, wrongPresses,
        Scoring.wait(groups.size, cleanGroups),
    )

    /** A key was pressed. Returns true if it was one of the wanted keys. */
    override fun keyDown(pitch: Int): Boolean {
        val group = groups.getOrNull(groupIndex) ?: return false
        if (pitch !in remaining) {
            wrongPresses++
            slippedOnThisGroup = true
            return false
        }
        remaining -= pitch
        if (remaining.isEmpty()) {
            if (!slippedOnThisGroup) cleanGroups++
            slippedOnThisGroup = false
            groupIndex++
            groups.getOrNull(groupIndex)?.let { remaining += it.pitches }
            // Played before the note arrived: catch the display up.
            if (position < group.tick) position = group.tick.toDouble()
        }
        return true
    }

    companion object {
        /** How far before the first note the song starts: two beats. */
        const val LEAD_IN_TICKS = 2 * Song.PPQ
    }
}

/**
 * The "3, 2, 1" before a song starts. Counts real time, whatever the song's speed.
 * Pure logic, driven by [advance].
 */
class Countdown(seconds: Int) {
    private var nanosLeft = seconds.coerceAtLeast(0) * NANOS_PER_SECOND

    /** The number to show: 3 for the first second, then 2, then 1; 0 once it is over. */
    val secondsLeft: Int get() = ((nanosLeft + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND).toInt()

    val running: Boolean get() = nanosLeft > 0

    /**
     * Uses up [nanos] of time. Returns whatever is left over once the countdown ends,
     * so the song can have the rest of that moment and starts exactly on time.
     */
    fun advance(nanos: Long): Long {
        if (nanos <= 0) return 0
        val used = minOf(nanos, nanosLeft)
        nanosLeft -= used
        return nanos - used
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

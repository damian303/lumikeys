package com.dhobson.lumi.learn

import kotlin.math.abs
import kotlin.math.roundToInt

/** What has become of a note of the player's part. */
enum class Mark { PENDING, HIT, MISSED }

/** How the last key press was judged, in "in time" mode. */
enum class Judgement { PERFECT, GOOD, WRONG }

/** The running totals of one go at a song. */
data class Tally(
    /** Things to play: notes in "in time" mode, groups of notes that start together in wait mode. */
    val total: Int,
    val done: Int,
    /** In time: within the tight window. Wait mode: played without pressing a wrong key first. */
    val perfect: Int,
    /** In time: within the wide window. Wait mode: played after a slip. */
    val good: Int,
    val missed: Int,
    /** Key presses that matched nothing. */
    val wrong: Int,
    /** 0 to 100; how it is worked out depends on the mode. */
    val scorePercent: Int,
) {
    /** 0 to 3. */
    val stars: Int
        get() = when {
            scorePercent >= 90 -> 3
            scorePercent >= 70 -> 2
            scorePercent >= 40 -> 1
            else -> 0
        }
}

object Scoring {
    /**
     * "In time" score, 0 to 100. A perfect note is worth a full point and a good one
     * 60%; each wrong key press costs a quarter of a point, so pressing everything
     * does not pay.
     */
    fun timed(total: Int, perfect: Int, good: Int, wrong: Int): Int =
        if (total == 0) 0
        else ((perfect * 100 + good * 60 - wrong * 25).coerceAtLeast(0) / total.toDouble()).roundToInt().coerceIn(0, 100)

    /** Wait-mode score, 0 to 100: the share played right first time. */
    fun wait(total: Int, firstTime: Int): Int =
        if (total == 0) 0 else (firstTime * 100.0 / total).roundToInt().coerceIn(0, 100)
}

/** One go at a song under one set of rules. Driven by [advance] and [keyDown]; not thread-safe. */
interface SongRun {
    /** Where the song is, in ticks. Starts before zero so the first notes can be seen coming. */
    val position: Double

    /** The keys to press next. Empty once there is nothing left to play. */
    val targets: Set<Int>

    /** Start tick of the notes in [targets], or null once there is nothing left to play. */
    val targetTick: Int?

    /** True while the song is held, waiting for the player. */
    val waiting: Boolean

    val finished: Boolean

    /** Moves the song on. Returns true if that changed [targets] or any [marks] (a note was missed). */
    fun advance(ticks: Double): Boolean

    /** A key was pressed. Returns true if it counted as a note of the song. */
    fun keyDown(pitch: Int): Boolean

    /** One per note of the song's player part, in the same order. */
    fun marks(): List<Mark>

    fun tally(): Tally
}

/**
 * The rules of "in time" mode: the song does not wait. A key press counts if a note of
 * that key is due within the wide window of the moment it was pressed, and is perfect
 * if within the tight window. A note nobody played by the end of its window is missed.
 *
 * [ticksPerSecond] is the song's speed as it will be played, so the windows stay the
 * same length in real time whatever the tempo or speed setting. [compensationTicks]
 * is how far behind the song the player is hearing it (audio output delay): presses
 * are judged against where the song was that long ago.
 */
class TimedEngine(
    private val song: Song,
    ticksPerSecond: Double,
    private val compensationTicks: Double = 0.0,
) : SongRun {
    private val perfectTicks = PERFECT_WINDOW_MS / 1000.0 * ticksPerSecond
    private val goodTicks = GOOD_WINDOW_MS / 1000.0 * ticksPerSecond

    private val notes = song.notes
    private val marks = Array(notes.size) { Mark.PENDING }

    /** Index of the earliest note still pending; everything before it has been judged. */
    private var firstPending = 0

    private var perfect = 0
    private var good = 0
    private var missed = 0
    private var wrong = 0

    override var position: Double = -LearnEngine.LEAD_IN_TICKS.toDouble()
        private set

    /** How the most recent key press was judged; null before the first. */
    var lastJudgement: Judgement? = null
        private set

    override val targetTick: Int? get() = notes.getOrNull(firstPending)?.startTick

    override val targets: Set<Int>
        get() {
            val tick = targetTick ?: return emptySet()
            val pitches = HashSet<Int>()
            var i = firstPending
            while (i < notes.size && notes[i].startTick == tick) {
                if (marks[i] == Mark.PENDING) pitches += notes[i].pitch
                i++
            }
            return pitches
        }

    override val waiting: Boolean get() = false

    override val finished: Boolean get() = firstPending >= notes.size && position >= song.endTick

    override fun advance(ticks: Double): Boolean {
        if (ticks <= 0) return false
        position += ticks
        // Notes whose window has closed without a press.
        val heard = position - compensationTicks
        var changed = false
        var i = firstPending
        while (i < notes.size && notes[i].startTick + goodTicks < heard) {
            if (marks[i] == Mark.PENDING) {
                marks[i] = Mark.MISSED
                missed++
                changed = true
            }
            i++
        }
        return skipJudged() || changed
    }

    override fun keyDown(pitch: Int): Boolean {
        val heard = position - compensationTicks
        var best = -1
        var bestDistance = Double.MAX_VALUE
        var i = firstPending
        while (i < notes.size && notes[i].startTick - heard <= goodTicks) {
            val distance = abs(notes[i].startTick - heard)
            if (marks[i] == Mark.PENDING && notes[i].pitch == pitch && distance <= goodTicks && distance < bestDistance) {
                best = i
                bestDistance = distance
            }
            i++
        }
        if (best < 0) {
            wrong++
            lastJudgement = Judgement.WRONG
            return false
        }
        marks[best] = Mark.HIT
        if (bestDistance <= perfectTicks) {
            perfect++
            lastJudgement = Judgement.PERFECT
        } else {
            good++
            lastJudgement = Judgement.GOOD
        }
        skipJudged()
        return true
    }

    /** Moves [firstPending] past notes that have been judged. Returns true if it moved. */
    private fun skipJudged(): Boolean {
        val before = firstPending
        while (firstPending < notes.size && marks[firstPending] != Mark.PENDING) firstPending++
        return firstPending != before
    }

    override fun marks(): List<Mark> = marks.toList()

    override fun tally() =
        Tally(notes.size, perfect + good + missed, perfect, good, missed, wrong, Scoring.timed(notes.size, perfect, good, wrong))

    companion object {
        /** A press this close to its note, either side, is perfect. */
        const val PERFECT_WINDOW_MS = 110

        /** A press this close counts; further away it is a wrong key and the note is missed. */
        const val GOOD_WINDOW_MS = 260
    }
}

package com.dhobson.lumi.learn

import com.dhobson.lumi.util.TextFolder

/**
 * Who is playing, so that each child has their own best scores in the learn mode.
 * Pure Kotlin; where it is stored is the session's business.
 *
 * There is always a shared "Everyone" as well as the named players. It is what the app
 * uses until somebody adds a name, and it owns the best scores that were set before
 * players existed, so nobody is handed scores that may have been someone else's.
 */
data class Player(
    val id: Int,
    val name: String,
    /** Which colour of the palette this player's dot is drawn in. */
    val colorIndex: Int,
)

data class PlayerRoster(
    val players: List<Player> = emptyList(),
    /** Id of the player whose turn it is, or [EVERYONE_ID]. */
    val currentId: Int = EVERYONE_ID,
) {
    /** The named player whose turn it is, or null when it is Everyone. */
    val current: Player? get() = players.firstOrNull { it.id == currentId }

    val currentName: String get() = current?.name ?: EVERYONE_NAME

    val isFull: Boolean get() = players.size >= MAX_PLAYERS

    sealed interface Change {
        /** [player] is the one that was added or renamed. */
        data class Done(val roster: PlayerRoster, val player: Player) : Change

        /** Nothing was changed; [reason] says why, in words for the screen. */
        data class Refused(val reason: String) : Change
    }

    /** Adds a player called [rawName] and makes it their turn. */
    fun add(rawName: String): Change {
        if (isFull) return Change.Refused("There is room for $MAX_PLAYERS players. Remove one first.")
        val name = cleanName(rawName) ?: return Change.Refused(NEEDS_NAME)
        takenBy(name, exceptId = null)?.let { return Change.Refused("There is already a player called $it.") }
        val used = players.map { it.colorIndex }.toSet()
        val player = Player(
            id = (players.maxOfOrNull { it.id } ?: EVERYONE_ID) + 1,
            name = name,
            colorIndex = (0 until COLOR_COUNT).firstOrNull { it !in used } ?: (players.size % COLOR_COUNT),
        )
        return Change.Done(copy(players = players + player, currentId = player.id), player)
    }

    fun rename(id: Int, rawName: String): Change {
        val player = players.firstOrNull { it.id == id } ?: return Change.Refused("That player is not in the list.")
        val name = cleanName(rawName) ?: return Change.Refused(NEEDS_NAME)
        takenBy(name, exceptId = id)?.let { return Change.Refused("There is already a player called $it.") }
        val renamed = player.copy(name = name)
        return Change.Done(copy(players = players.map { if (it.id == id) renamed else it }), renamed)
    }

    /** Takes a player off the list. If it was their turn, it becomes Everyone's. */
    fun remove(id: Int): PlayerRoster =
        copy(players = players.filterNot { it.id == id }, currentId = if (currentId == id) EVERYONE_ID else currentId)

    /** Makes it [id]'s turn; ids that are not in the list are ignored. */
    fun select(id: Int): PlayerRoster =
        if (id == EVERYONE_ID || players.any { it.id == id }) copy(currentId = id) else this

    /** The name, as it is written in the list, that [name] would clash with; capitals do not count. */
    private fun takenBy(name: String, exceptId: Int?): String? =
        if (name.equals(EVERYONE_NAME, ignoreCase = true)) {
            EVERYONE_NAME
        } else {
            players.firstOrNull { it.id != exceptId && it.name.equals(name, ignoreCase = true) }?.name
        }

    companion object {
        const val EVERYONE_ID = 0
        const val EVERYONE_NAME = "Everyone"
        const val MAX_PLAYERS = 8
        const val MAX_NAME_LENGTH = 12

        /** As many colours as there can be players, so each gets their own. */
        const val COLOR_COUNT = MAX_PLAYERS

        private const val NEEDS_NAME = "Type a name made of letters or numbers."

        /** A name fit to show and store: letters, digits, spaces, hyphens; null if nothing is left. */
        fun cleanName(raw: String): String? =
            TextFolder.cleanName(raw)?.take(MAX_NAME_LENGTH)?.trim()?.ifEmpty { null }

        /**
         * The key a best score is stored under. [songKey] says which song and mode.
         * Everyone's scores use the bare key, which is how scores were stored before
         * there were players.
         */
        fun scoreKey(playerId: Int, songKey: String): String =
            if (playerId == EVERYONE_ID) songKey else scorePrefix(playerId) + songKey

        /** What every score key of player [playerId] starts with (not Everyone's). */
        fun scorePrefix(playerId: Int): String = "p$playerId|"
    }
}

/**
 * The roster as plain text, one line each:
 *
 *     lumiplayers 1
 *     current 2
 *     player 1 0 Holly
 *     player 2 1 Sam B
 *
 * A player line is id, colour, then the name (which may contain spaces).
 */
object PlayerCodec {
    private const val HEADER = "lumiplayers 1"

    fun encode(roster: PlayerRoster): String = buildString {
        append(HEADER).append('\n')
        append("current ").append(roster.currentId).append('\n')
        for (player in roster.players) {
            append("player ").append(player.id).append(' ').append(player.colorIndex).append(' ').append(player.name).append('\n')
        }
    }

    /**
     * The roster in [text]. Anything that cannot be understood is left out rather than
     * failing, so a damaged entry costs one player and not the whole list; text that
     * is not a roster at all gives an empty one.
     */
    fun decode(text: String?): PlayerRoster {
        val lines = text.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != HEADER) return PlayerRoster()
        var current = PlayerRoster.EVERYONE_ID
        val players = ArrayList<Player>()
        for (line in lines.drop(1)) {
            val words = line.split(' ', limit = 4)
            when (words[0]) {
                "current" -> current = words.getOrNull(1)?.toIntOrNull() ?: PlayerRoster.EVERYONE_ID
                "player" -> {
                    val id = words.getOrNull(1)?.toIntOrNull() ?: continue
                    val color = words.getOrNull(2)?.toIntOrNull() ?: continue
                    val name = words.getOrNull(3)?.let(PlayerRoster::cleanName) ?: continue
                    val usable = id > PlayerRoster.EVERYONE_ID && players.size < PlayerRoster.MAX_PLAYERS &&
                        players.none { it.id == id || it.name.equals(name, ignoreCase = true) } &&
                        !name.equals(PlayerRoster.EVERYONE_NAME, ignoreCase = true)
                    if (usable) players += Player(id, name, color.coerceIn(0, PlayerRoster.COLOR_COUNT - 1))
                }
                else -> Unit
            }
        }
        return PlayerRoster(players).select(current)
    }
}

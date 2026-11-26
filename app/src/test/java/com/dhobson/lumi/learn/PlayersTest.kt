package com.dhobson.lumi.learn

import com.dhobson.lumi.learn.PlayerRoster.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayersTest {
    private fun PlayerRoster.adding(name: String): PlayerRoster = (add(name) as Change.Done).roster

    private fun refusal(change: Change): String = (change as Change.Refused).reason

    @Test
    fun `to begin with there is only Everyone`() {
        val roster = PlayerRoster()
        assertEquals(PlayerRoster.EVERYONE_ID, roster.currentId)
        assertNull(roster.current)
        assertEquals("Everyone", roster.currentName)
        assertEquals(emptyList<Player>(), roster.players)
    }

    @Test
    fun `a new player gets the next id and colour, and it becomes their turn`() {
        val one = PlayerRoster().add(" Holly ") as Change.Done
        assertEquals(Player(1, "Holly", 0), one.player)
        assertEquals(1, one.roster.currentId)
        val two = one.roster.add("Sam") as Change.Done
        assertEquals(Player(2, "Sam", 1), two.player)
        assertEquals("Sam", two.roster.currentName)
        assertEquals(listOf("Holly", "Sam"), two.roster.players.map { it.name })
    }

    @Test
    fun `names are tidied and kept short`() {
        assertEquals("Sam B", (PlayerRoster().add("  Sam   B!! ") as Change.Done).player.name)
        assertEquals("Maximilianus", (PlayerRoster().add("Maximilianus the Third") as Change.Done).player.name)
        assertEquals(PlayerRoster.MAX_NAME_LENGTH, "Maximilianus".length)
    }

    @Test
    fun `a name with nothing usable in it is refused`() {
        assertTrue(refusal(PlayerRoster().add("   ")).contains("name"))
        assertTrue(refusal(PlayerRoster().add("!!!")).contains("name"))
    }

    @Test
    fun `two players cannot share a name, whatever the capitals, and nobody can be Everyone`() {
        val roster = PlayerRoster().adding("Holly")
        assertEquals("There is already a player called Holly.", refusal(roster.add("holly")))
        assertEquals("There is already a player called Everyone.", refusal(roster.add("EVERYONE")))
        assertEquals(1, roster.players.size)
    }

    @Test
    fun `there is a limit on how many players there can be`() {
        var roster = PlayerRoster()
        repeat(PlayerRoster.MAX_PLAYERS) { roster = roster.adding("Player $it") }
        assertTrue(roster.isFull)
        assertTrue(refusal(roster.add("One more")).contains("Remove"))
        assertEquals((0 until PlayerRoster.MAX_PLAYERS).toList(), roster.players.map { it.colorIndex })
    }

    @Test
    fun `choosing a player, or Everyone again`() {
        val roster = PlayerRoster().adding("Holly").adding("Sam")
        assertEquals("Holly", roster.select(1).currentName)
        assertEquals("Everyone", roster.select(PlayerRoster.EVERYONE_ID).currentName)
        assertEquals("unknown ids change nothing", roster, roster.select(99))
    }

    @Test
    fun `renaming keeps the id, the colour and whose turn it is`() {
        val roster = PlayerRoster().adding("Holy").adding("Sam")
        val renamed = roster.rename(1, "Holly") as Change.Done
        assertEquals(Player(1, "Holly", 0), renamed.player)
        assertEquals(listOf("Holly", "Sam"), renamed.roster.players.map { it.name })
        assertEquals(2, renamed.roster.currentId)
    }

    @Test
    fun `renaming to another player's name is refused, but changing only the capitals is fine`() {
        val roster = PlayerRoster().adding("holly").adding("Sam")
        assertTrue(refusal(roster.rename(1, "sam")).contains("already"))
        assertEquals("Holly", (roster.rename(1, "Holly") as Change.Done).player.name)
        assertTrue(refusal(roster.rename(1, "")).contains("name"))
        assertTrue(refusal(roster.rename(42, "Zed")).contains("not in the list"))
    }

    @Test
    fun `removing the player whose turn it is hands over to Everyone`() {
        val roster = PlayerRoster().adding("Holly").adding("Sam")
        val without = roster.remove(2)
        assertEquals(listOf("Holly"), without.players.map { it.name })
        assertEquals(PlayerRoster.EVERYONE_ID, without.currentId)
        assertEquals("removing someone else leaves the turn alone", 2, roster.remove(1).currentId)
    }

    @Test
    fun `a colour freed by removing a player is used again`() {
        val roster = PlayerRoster().adding("A").adding("B").adding("C").remove(2)
        assertEquals(1, (roster.add("D") as Change.Done).player.colorIndex)
    }

    // ---- score keys ----

    @Test
    fun `Everyone's scores are stored the way scores were before there were players`() {
        assertEquals("hot-cross-buns|WAIT", PlayerRoster.scoreKey(PlayerRoster.EVERYONE_ID, "hot-cross-buns|WAIT"))
    }

    @Test
    fun `each player's scores are stored apart`() {
        assertEquals("p1|hot-cross-buns|WAIT", PlayerRoster.scoreKey(1, "hot-cross-buns|WAIT"))
        assertEquals("p2|import:My Song|TIMED", PlayerRoster.scoreKey(2, "import:My Song|TIMED"))
        // Player 1's keys are not mistaken for player 10's, or the other way round.
        assertFalse(PlayerRoster.scoreKey(10, "x|WAIT").startsWith(PlayerRoster.scorePrefix(1)))
        assertFalse(PlayerRoster.scoreKey(1, "x|WAIT").startsWith(PlayerRoster.scorePrefix(10)))
        assertTrue(PlayerRoster.scoreKey(1, "x|WAIT").startsWith(PlayerRoster.scorePrefix(1)))
    }

    @Test
    fun `no built-in song could be mistaken for a player's scores`() {
        for (song in Songs.all) {
            assertFalse(song.id, Regex("p\\d+\\|.*").matches("${song.id}|WAIT"))
        }
        assertFalse(Regex("p\\d+\\|.*").matches("${Learn.IMPORT_PREFIX}p1|WAIT"))
    }

    // ---- storage ----

    @Test
    fun `the roster survives being written and read back`() {
        val roster = PlayerRoster().adding("Holly").adding("Sam B").adding("Zo\u00EB").select(2)
        assertEquals(roster, PlayerCodec.decode(PlayerCodec.encode(roster)))
        assertEquals(PlayerRoster(), PlayerCodec.decode(PlayerCodec.encode(PlayerRoster())))
    }

    @Test
    fun `the format is the documented plain text`() {
        val roster = PlayerRoster().adding("Holly").adding("Sam B")
        assertEquals(
            listOf("lumiplayers 1", "current 2", "player 1 0 Holly", "player 2 1 Sam B"),
            PlayerCodec.encode(roster).trim().lines(),
        )
    }

    @Test
    fun `nothing stored, or something else stored, gives just Everyone`() {
        assertEquals(PlayerRoster(), PlayerCodec.decode(null))
        assertEquals(PlayerRoster(), PlayerCodec.decode(""))
        assertEquals(PlayerRoster(), PlayerCodec.decode("player 1 0 Holly"))
    }

    @Test
    fun `a damaged line costs that player only`() {
        val read = PlayerCodec.decode("lumiplayers 1\ncurrent 3\nplayer x 0 Bad\nplayer 2\nplayer 3 99 Sam\nplayer 3 1 Twin\nplayer 4 2 sam\nplayer 0 2 Zero\nnonsense\n")
        assertEquals(listOf(Player(3, "Sam", PlayerRoster.COLOR_COUNT - 1)), read.players)
        assertEquals(3, read.currentId)
    }

    @Test
    fun `a stored turn for a player who is not there falls back to Everyone`() {
        val read = PlayerCodec.decode("lumiplayers 1\ncurrent 7\nplayer 1 0 Holly\n")
        assertEquals(PlayerRoster.EVERYONE_ID, read.currentId)
        assertEquals(listOf("Holly"), read.players.map { it.name })
    }
}

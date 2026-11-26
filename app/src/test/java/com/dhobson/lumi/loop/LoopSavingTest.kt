package com.dhobson.lumi.loop

import com.dhobson.lumi.music.Instruments
import com.dhobson.lumi.music.NoteColors
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopSavingTest {
    private fun on(tick: Int, note: Int, velocity: Int = 100) = LoopEvent(tick, 0x90, note, velocity)
    private fun off(tick: Int, note: Int) = LoopEvent(tick, 0x80, note, 0)

    private val project = LoopProject(tempoBpm = 115, bars = 4, snap = Snap.HALF_BEAT)
        .withLayer(Layer(1, instrumentId = 0, channel = 1, events = listOf(on(70, 60, 90), off(300, 60)), volume = 60, colorIndex = 0))
        .withLayer(Layer(2, Instruments.DRUM_KIT_ID, Instruments.DRUM_CHANNEL, listOf(on(0, 36), off(10, 36)), muted = true, colorIndex = 1))
        .withLayer(Layer(3, instrumentId = 40, channel = 2, colorIndex = 5))

    // ---- file format ----

    @Test
    fun `a loop survives being written and read back`() {
        assertEquals(project, LoopCodec.decode(LoopCodec.encode(project)))
    }

    @Test
    fun `the format is the documented plain text`() {
        val small = LoopProject(tempoBpm = 100, bars = 2)
            .withLayer(Layer(1, instrumentId = 19, channel = 1, events = listOf(on(480, 60, 90), off(700, 60)), volume = 80, colorIndex = 3))
        assertEquals(
            listOf("lumiloop 1", "tempo 100", "bars 2", "snap BEAT", "layer 19 0 80 3", "e 480 144 60 90", "e 700 128 60 0"),
            LoopCodec.encode(small).trim().lines(),
        )
    }

    @Test
    fun `channels are handed out again on reading, whatever order layers were made in`() {
        val odd = LoopProject().withLayer(Layer(7, 5, channel = 12)).withLayer(Layer(9, 6, channel = 3))
        val read = LoopCodec.decode(LoopCodec.encode(odd))!!
        assertEquals(listOf(1, 2), read.layers.map { it.channel })
        assertEquals(listOf(1, 2), read.layers.map { it.id })
        assertEquals(listOf(5, 6), read.layers.map { it.instrumentId })
    }

    @Test
    fun `unknown lines are skipped and missing settings take their defaults`() {
        val read = LoopCodec.decode("lumiloop 1\nswing 60\nlayer 0 0 100 0\ne 0 144 60 100\n")!!
        assertEquals(LoopProject.DEFAULT_TEMPO, read.tempoBpm)
        assertEquals(LoopProject.DEFAULT_BARS, read.bars)
        assertEquals(Snap.BEAT, read.snap)
        assertEquals(1, read.layers.single().noteCount)
    }

    @Test
    fun `out-of-range values are brought into range`() {
        val read = LoopCodec.decode("lumiloop 1\ntempo 999\nbars 1\nlayer 0 0 250 99\ne 5000 144 60 200\n")!!
        assertEquals(LoopProject.MAX_TEMPO, read.tempoBpm)
        val layer = read.layers.single()
        assertEquals(100, layer.volume)
        assertEquals(LayerColors.COUNT - 1, layer.colorIndex)
        assertEquals(LoopEvent(1919, 0x90, 60, 127), layer.events.single()) // inside a 1-bar loop
    }

    @Test
    fun `anything that is not a loop file is refused`() {
        assertNull(LoopCodec.decode(""))
        assertNull(LoopCodec.decode("hello"))
        assertNull(LoopCodec.decode("lumiloop 2\ntempo 100\n"))              // a future version
        assertNull(LoopCodec.decode("lumiloop 1\ntempo fast\n"))             // not a number
        assertNull(LoopCodec.decode("lumiloop 1\nbars 3\n"))                 // not a length the app offers
        assertNull(LoopCodec.decode("lumiloop 1\ne 0 144 60 100\n"))         // a note before any layer
        assertNull(LoopCodec.decode("lumiloop 1\nlayer 500 0 100 0\n"))      // no such instrument
        assertNull(LoopCodec.decode("lumiloop 1\nlayer 0 0 100\n"))          // too few fields
        assertNull(LoopCodec.decode("lumiloop 1\nlayer 0 0 100 0\ne 0 176 60 100\n")) // not a note
        assertNull(LoopCodec.decode("lumiloop 1\nlayer 0 0 100 0\ne 0 144 200 100\n")) // no such key
    }

    @Test
    fun `the automatically kept loop also remembers the name it was saved as`() {
        val text = LoopCodec.encodeWithName(project, "My tune 2")
        assertEquals("My tune 2", LoopCodec.nameIn(text))
        assertEquals(project, LoopCodec.decode(text)) // the name line does not disturb the loop
        assertNull(LoopCodec.nameIn(LoopCodec.encodeWithName(project, null)))
        assertNull(LoopCodec.nameIn(LoopCodec.encode(project)))
    }

    @Test
    fun `an empty loop is a valid file`() {
        assertEquals(LoopProject(), LoopCodec.decode(LoopCodec.encode(LoopProject())))
    }

    // ---- volume ----

    @Test
    fun `volume scales how hard notes are played, and never to nothing`() {
        assertEquals(100, LayerVolume.scale(100, 100))
        assertEquals(50, LayerVolume.scale(100, 50))
        assertEquals(64, LayerVolume.scale(127, 50)) // rounds to nearest
        assertEquals(1, LayerVolume.scale(3, 10))
        assertEquals(127, LayerVolume.scale(127, 300)) // cannot go above full
    }

    @Test
    fun `the compiled loop carries the layer volume, and volume 0 leaves the layer out`() {
        val base = LoopProject(tempoBpm = 120, bars = 1, snap = Snap.OFF)
        val layer = Layer(1, instrumentId = 0, channel = 1, events = listOf(on(0, 60, 100), off(480, 60)), volume = 50)
        val half = LoopCompiler.compile(base.withLayer(layer), 48_000)
        assertEquals(
            listOf(LoopCompiler.pack(0xC1, 0, 0), LoopCompiler.pack(0x91, 60, 50), LoopCompiler.pack(0x81, 60, 0)),
            half.messages.toList(),
        )
        val silent = LoopCompiler.compile(base.withLayer(layer.copy(volume = 0)), 48_000)
        assertEquals(0, silent.messages.size)
    }

    // ---- layer colours ----

    @Test
    fun `each new layer gets the first colour not in use, and colours repeat only when all are used`() {
        var layers = emptyList<Layer>()
        repeat(LayerColors.COUNT) { layers = layers + Layer(it, 0, it, colorIndex = LayerColors.allocate(layers)) }
        assertEquals((0 until LayerColors.COUNT).toList(), layers.map { it.colorIndex })
        assertEquals(0, LayerColors.allocate(layers)) // all used: start again
        assertEquals(2, LayerColors.allocate(layers.filterNot { it.colorIndex == 2 })) // a freed colour is reused
    }

    // ---- note colours for the on-screen keys ----

    @Test
    fun `twelve different note colours, the same in every octave`() {
        assertEquals(12, (60..71).map(NoteColors::argb).toSet().size)
        for (note in 0..115) assertEquals(NoteColors.argb(note), NoteColors.argb(note + 12))
        assertEquals(0xFFE53935.toInt(), NoteColors.argb(60)) // C is red, as on the keyboard
        assertTrue((0..11).all { NoteColors.argb(it) ushr 24 == 0xFF }) // all opaque
    }

    // ---- files on disk ----

    private val folder: File = Files.createTempDirectory("lumi-loops").toFile()

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun `saved loops are listed by name, read back, replaced and deleted`() {
        val store = LoopStore(File(folder, "loops")) // the folder does not exist yet
        assertEquals(emptyList<String>(), store.list())
        assertNull(store.read("Nothing"))

        assertTrue(store.save("My tune", "one"))
        assertTrue(store.save("another", "two"))
        assertEquals(listOf("another", "My tune"), store.list()) // alphabetical, ignoring case
        assertEquals("one", store.read("My tune"))

        assertTrue(store.save("My tune", "changed"))
        assertEquals("changed", store.read("My tune"))
        assertEquals(2, store.list().size)

        assertTrue(store.delete("My tune"))
        assertEquals(listOf("another"), store.list())
        assertFalse(store.delete("My tune"))
    }

    @Test
    fun `the automatically saved loop is kept apart from the named ones`() {
        val store = LoopStore(folder)
        assertNull(store.readAutosave())
        assertTrue(store.writeAutosave("current"))
        assertEquals("current", store.readAutosave())
        assertEquals(emptyList<String>(), store.list())
        assertTrue(store.writeAutosave("newer"))
        assertEquals("newer", store.readAutosave())
        assertEquals(listOf("current.autosave"), folder.list()!!.toList()) // no temporary files left behind
    }

    @Test
    fun `names are made safe for use as file names`() {
        assertEquals("My tune 2", LoopStore.cleanName("  My   tune 2 "))
        assertEquals("etcpasswd", LoopStore.cleanName("../../etc/passwd"))
        assertEquals("a-b_c", LoopStore.cleanName("a-b_c?*"))
        assertNull(LoopStore.cleanName("   "))
        assertNull(LoopStore.cleanName("../.."))
        assertEquals(LoopStore.MAX_NAME_LENGTH, LoopStore.cleanName("x".repeat(100))!!.length)
        assertNotNull(LoopStore.cleanName("Été"))
    }
}

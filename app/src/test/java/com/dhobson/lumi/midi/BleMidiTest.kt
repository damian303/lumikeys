package com.dhobson.lumi.midi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleMidiTest {
    private val midiUuid = "03B80E5A-EDE8-4B33-A751-6CE34EC4C700"
    private val heartRateUuid = "0000180d-0000-1000-8000-00805f9b34fb"

    @Test
    fun `lists anything advertising the MIDI service, whatever its name`() {
        assertTrue(BleMidi.shouldList(listOf(midiUuid), null))
        assertTrue(BleMidi.shouldList(listOf(heartRateUuid, midiUuid.lowercase()), "Some Other Keyboard"))
    }

    @Test
    fun `lists ROLI-looking names even without the MIDI service in the advert`() {
        assertTrue(BleMidi.shouldList(emptyList(), "LUMI Keys Block 2I95"))
        assertTrue(BleMidi.shouldList(emptyList(), "Seaboard Block 3XU9"))
        assertTrue(BleMidi.shouldList(listOf(heartRateUuid), "ROLI Piano M"))
    }

    @Test
    fun `ignores unrelated devices`() {
        assertFalse(BleMidi.shouldList(emptyList(), null))
        assertFalse(BleMidi.shouldList(listOf(heartRateUuid), "Fitness Band"))
        assertFalse(BleMidi.shouldList(emptyList(), "Blockbuster TV"))
        assertFalse(BleMidi.shouldList(emptyList(), "Block"))             // "block" alone is not a product name
        assertFalse(BleMidi.shouldList(emptyList(), "Caroline's iPhone")) // contains "roli"
        assertFalse(BleMidi.shouldList(emptyList(), "Aluminium Speaker")) // contains "lumi"
        assertFalse(BleMidi.shouldList(emptyList(), "Galaxy Buds"))
    }
}

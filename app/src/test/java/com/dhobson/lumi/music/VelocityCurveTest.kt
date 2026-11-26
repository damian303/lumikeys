package com.dhobson.lumi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityCurveTest {
    @Test
    fun `raw passes velocities through unchanged`() {
        for (v in 1..127) assertEquals(v, VelocityCurve.RAW.apply(v))
    }

    @Test
    fun `every curve stays in range, never decreases, and keeps full velocity at full`() {
        for (curve in VelocityCurve.entries) {
            var previous = 0
            for (v in 1..127) {
                val out = curve.apply(v)
                assertTrue("$curve($v)=$out out of range", out in 1..127)
                assertTrue("$curve decreases at $v", out >= previous)
                previous = out
            }
            assertEquals(127, curve.apply(127))
        }
    }

    @Test
    fun `the softest press the keyboard sends becomes audible`() {
        // Velocity 1 is what the LUMI sends for a gentle press.
        assertTrue(VelocityCurve.LIGHT.apply(1) >= 40)
        assertTrue(VelocityCurve.MEDIUM.apply(1) >= 20)
    }

    @Test
    fun `curves only ever raise the velocity, light more than medium`() {
        for (v in 1..127) {
            val light = VelocityCurve.LIGHT.apply(v)
            val medium = VelocityCurve.MEDIUM.apply(v)
            assertTrue(medium >= v)
            assertTrue(light >= medium)
        }
    }

    @Test
    fun `soft and hard presses still sound different on the light curve`() {
        // Typical measured values: soft about 20, normal about 50, hard about 110.
        val soft = VelocityCurve.LIGHT.apply(20)
        val normal = VelocityCurve.LIGHT.apply(50)
        val hard = VelocityCurve.LIGHT.apply(110)
        assertTrue(normal - soft >= 15)
        assertTrue(hard - normal >= 15)
    }

    @Test
    fun `out-of-range input is clamped and unknown names fall back to the default`() {
        assertEquals(VelocityCurve.LIGHT.apply(1), VelocityCurve.LIGHT.apply(0))
        assertEquals(127, VelocityCurve.MEDIUM.apply(200))
        assertEquals(VelocityCurve.DEFAULT, VelocityCurve.fromName(null))
        assertEquals(VelocityCurve.DEFAULT, VelocityCurve.fromName("nonsense"))
        assertEquals(VelocityCurve.RAW, VelocityCurve.fromName("RAW"))
    }
}

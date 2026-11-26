package com.dhobson.lumi.midi

import com.dhobson.lumi.midi.LumiProtocol.DeviceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures marked "captured" were exchanged with a real LUMI Keys (firmware 1.3.9,
 * device index 0x14) on 19 Nov 2026. Others come from benob/LUMI-lights SYSEX.txt,
 * which recorded ROLI Dashboard's traffic.
 */
class LumiProtocolTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    private fun bytes(text: String) = text.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    // ---- building ---------------------------------------------------------------------------

    @Test
    fun `topology request matches the documented frame the keyboard answered`() {
        assertEquals("F0 00 21 10 77 00 01 01 00 5D F7", hex(LumiProtocol.topologyRequest()))
    }

    @Test
    fun `mode writes match the four frames that switched the real keyboard`() {
        assertEquals("F0 00 21 10 77 14 10 40 02 00 00 00 00 00 5E F7", hex(LumiProtocol.setConfig(0x14, 20, 0)))
        assertEquals("F0 00 21 10 77 14 10 40 22 00 00 00 00 00 3E F7", hex(LumiProtocol.setConfig(0x14, 20, 1)))
        assertEquals("F0 00 21 10 77 14 10 40 42 00 00 00 00 00 1E F7", hex(LumiProtocol.setConfig(0x14, 20, 2)))
        assertEquals("F0 00 21 10 77 14 10 40 62 00 00 00 00 00 7E F7", hex(LumiProtocol.setConfig(0x14, 20, 3)))
    }

    @Test
    fun `setting payloads match Dashboard's recorded traffic`() {
        fun payload(item: Int, value: Int) = hex(LumiProtocol.setConfig(0x37, item, value).copyOfRange(6, 14))
        assertEquals("10 40 44 0C 00 00 00 00", payload(36, 50))   // brightness 50%
        assertEquals("10 40 04 19 00 00 00 00", payload(36, 100))  // brightness 100%
        assertEquals("10 40 20 00 00 00 00 00", payload(4, 1))     // octave 1
        assertEquals("10 40 60 7F 7F 7F 7F 03", payload(4, -1))    // octave -1 (negative values)
        assertEquals("10 40 00 7F 7F 7F 7F 03", payload(4, -4))    // octave -4
        assertEquals("10 20 41 1B 00 00 00 00", payload(10, 110))  // strike sensitivity "high"
        assertEquals("10 20 64 3F 00 00 7E 03", payload(34, 0xFF0000FF.toInt())) // key colour blue
    }

    @Test
    fun `config requests match the frames the keyboard answered`() {
        // captured: these exact requests returned the replies decoded below
        assertEquals("F0 00 21 10 77 14 10 01 00 00 00 00 08 00 29 F7", hex(LumiProtocol.requestConfig(0x14, 4)))
        assertEquals("F0 00 21 10 77 14 10 01 00 00 00 00 72 01 68 F7", hex(LumiProtocol.requestConfig(0x14, 121)))
    }

    @Test
    fun `device index is limited to six bits`() {
        assertEquals(0x14, LumiProtocol.setConfig(0x54, 20, 0)[5].toInt())
    }

    // ---- decoding ---------------------------------------------------------------------------

    @Test
    fun `decodes a captured topology reply`() {
        val messages = LumiProtocol.decode(
            bytes(
                "F0 00 21 10 77 54 00 00 00 00 10 10 20 00 00 66 25 61 5A 5A 66 65 19 2C 29 2B 24 59 64 5C " +
                    "1A 4A 5F 01 45 21 4C 6B 4C 2B 0E 60 01 25 00 00 05 F7",
            ),
        )
        val device = messages.filterIsInstance<DeviceMessage.Device>().single()
        assertEquals(0x14, device.index)
        assertTrue(device.serial.startsWith("LKB")) // the LUMI Keys / Piano M serial prefix
        assertEquals(16, device.serial.length)
        assertEquals(31, device.batteryLevel)
        assertEquals(100, device.batteryPercent)
        assertTrue(device.charging)
        assertEquals("1.3.9", messages.filterIsInstance<DeviceMessage.Version>().single().text)
    }

    @Test
    fun `decodes captured config replies, including negative limits`() {
        fun config(text: String) = LumiProtocol.decode(bytes(text)).single() as DeviceMessage.Config
        assertEquals(
            DeviceMessage.Config(item = 4, value = 1, min = -4, max = 5),
            config("F0 00 21 10 77 54 00 00 00 00 00 43 08 04 00 00 00 00 7E 7F 7F 7F 2F 00 00 00 00 0A F7"),
        )
        assertEquals(
            DeviceMessage.Config(item = 20, value = 3, min = 0, max = 3),
            config("F0 00 21 10 77 54 00 00 00 00 00 43 28 0C 00 00 00 00 00 00 00 00 18 00 00 00 00 14 F7"),
        )
        assertEquals(
            DeviceMessage.Config(item = 36, value = 100, min = 0, max = 100),
            config("F0 00 21 10 77 54 00 00 00 00 00 43 48 10 03 00 00 00 00 00 00 00 20 06 00 00 00 7D F7"),
        )
        assertEquals(
            DeviceMessage.Config(item = 10, value = 110, min = 0, max = 127),
            config("F0 00 21 10 77 54 00 00 00 00 00 43 14 38 03 00 00 00 00 00 00 00 78 07 00 00 00 54 F7"),
        )
        assertEquals(
            DeviceMessage.Config(item = 121, value = 3, min = 0, max = 4),
            config("F0 00 21 10 77 54 00 00 00 00 00 43 72 0D 00 00 00 00 00 00 00 00 20 00 00 00 00 69 F7"),
        )
    }

    @Test
    fun `decodes the captured repeating acknowledgement`() {
        assertEquals(
            listOf(DeviceMessage.PacketAck(0)),
            LumiProtocol.decode(bytes("F0 00 21 10 77 54 00 00 00 00 20 00 00 6D F7")),
        )
    }

    @Test
    fun `decodes a captured packet that ends with the button status the keyboard reports on contact`() {
        val messages = LumiProtocol.decode(
            bytes(
                "F0 00 21 10 77 54 00 00 00 00 10 10 20 00 00 66 25 61 5A 5A 66 65 19 2C 29 2B 24 59 64 5C " +
                    "1A 4A 5F 01 45 21 4C 6B 4C 2B 0E 60 01 25 00 20 08 08 00 42 00 01 00 5D F7",
            ),
        )
        assertEquals(1, messages.filterIsInstance<DeviceMessage.Device>().size)
        // Buttons 1 and 2 (octave down / up) both reported as up.
        assertEquals(
            listOf(DeviceMessage.Button(1, down = false), DeviceMessage.Button(2, down = false)),
            messages.filterIsInstance<DeviceMessage.Button>(),
        )
    }

    @Test
    fun `rejects packets that are not valid BLOCKS packets`() {
        assertEquals(emptyList<DeviceMessage>(), LumiProtocol.decode(bytes("F0 7E 7F 06 01 F7"))) // other SysEx
        assertEquals(emptyList<DeviceMessage>(), LumiProtocol.decode(bytes("F0 00 21 10 77 54 F7"))) // too short
        // captured acknowledgement with one payload byte altered: checksum no longer matches
        assertEquals(
            emptyList<DeviceMessage>(),
            LumiProtocol.decode(bytes("F0 00 21 10 77 54 00 00 00 01 20 00 00 6D F7")),
        )
        assertEquals(emptyList<DeviceMessage>(), LumiProtocol.decode(ByteArray(0)))
    }

    @Test
    fun `helpers`() {
        assertEquals(60, LumiProtocol.baseNoteForOctave(1)) // the keyboard's C4-B5 window at octave 1
        assertEquals(48, LumiProtocol.baseNoteForOctave(0))
        assertEquals(listOf(100, 107, 114, 121), (0..3).map(LumiProtocol::colourSchemeItem))
    }
}

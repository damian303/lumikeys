package com.dhobson.lumi.midi

/**
 * The part of ROLI's BLOCKS SysEx protocol needed to read and change a LUMI Keys /
 * Piano M keyboard's settings. Pure Kotlin.
 *
 * The wire format is the one published by ROLI in roli_blocks_basics (ISC licence;
 * see roli_BlocksProtocolDefinitions.h, roli_HostPacketBuilder.h and
 * roli_HostPacketDecoder.h). This is an independent implementation of that format,
 * checked against packets exchanged with a real keyboard (firmware 1.3.9).
 *
 * A packet is `F0 00 21 10 77 <device index> <payload> <checksum> F7`. The payload
 * is a stream of bit fields, least significant bit first, stored 7 bits per byte.
 */
object LumiProtocol {
    // Config items (setting ids) used by the app.
    const val ITEM_OCTAVE = 4
    const val ITEM_STRIKE_SENSITIVITY = 10
    const val ITEM_MODE = 20
    const val ITEM_BRIGHTNESS = 36

    /** Colour scheme of mode slot [mode] (0..3): 0 Pro, 1 User, 2 Piano, 3 Stage, 4 Rainbow. */
    fun colourSchemeItem(mode: Int): Int = 100 + 7 * mode

    val COLOUR_SCHEME_NAMES = listOf("Pro", "User", "Piano", "Stage", "Rainbow")

    /**
     * The scheme in which a key lit from outside shows bright in its own note colour
     * on a dim rainbow (seen on a real keyboard); in the others it shows white.
     */
    const val COLOUR_SCHEME_STAGE = 3

    /** MIDI note of the keyboard's leftmost key for a given octave setting (-4..5). */
    fun baseNoteForOctave(octave: Int): Int = 48 + 12 * octave

    private val header = byteArrayOf(0xF0.toByte(), 0x00, 0x21, 0x10, 0x77)

    // Message types.
    private const val HOST_DEVICE_COMMAND = 0x01
    private const val HOST_CONFIG = 0x10
    private const val DEVICE_TOPOLOGY = 0x01
    private const val DEVICE_PACKET_ACK = 0x02
    private const val DEVICE_VERSION = 0x06
    private const val DEVICE_NAME = 0x07
    private const val DEVICE_CONFIG = 0x18
    private const val DEVICE_BUTTON_DOWN = 0x20
    private const val DEVICE_BUTTON_UP = 0x21

    private const val COMMAND_REQUEST_TOPOLOGY = 0x01
    private const val CONFIG_SET = 0
    private const val CONFIG_REQUEST = 1
    private const val CONFIG_UPDATE = 4

    sealed interface DeviceMessage {
        /** One keyboard in the topology reply. [index] addresses later messages to it. */
        data class Device(val serial: String, val index: Int, val batteryLevel: Int, val charging: Boolean) :
            DeviceMessage {
            /** Battery level is reported on a 0..31 scale. */
            val batteryPercent: Int get() = batteryLevel * 100 / 31
        }

        data class Version(val index: Int, val text: String) : DeviceMessage
        data class Config(val item: Int, val value: Int, val min: Int, val max: Int) : DeviceMessage
        data class PacketAck(val counter: Int) : DeviceMessage
        data class Button(val id: Int, val down: Boolean) : DeviceMessage
    }

    // ---- Host to keyboard ------------------------------------------------------------------

    /** Asks every connected block to describe itself; the reply carries the device index. */
    fun topologyRequest(): ByteArray =
        packet(0, BitWriter().write(HOST_DEVICE_COMMAND, 7).write(COMMAND_REQUEST_TOPOLOGY, 9))

    /** Changes a setting. The keyboard stores it and sends no confirmation. */
    fun setConfig(deviceIndex: Int, item: Int, value: Int): ByteArray =
        packet(deviceIndex, BitWriter().write(HOST_CONFIG, 7).write(CONFIG_SET, 4).write(item, 8).write(value, 32))

    /** Asks for a setting's current value; the reply is a [DeviceMessage.Config]. */
    fun requestConfig(deviceIndex: Int, item: Int): ByteArray =
        // Field order as in the SDK's addRequestMessage: an unused 32-bit zero, then the item.
        packet(deviceIndex, BitWriter().write(HOST_CONFIG, 7).write(CONFIG_REQUEST, 4).write(0, 32).write(item, 8))

    fun checksum(payload: ByteArray): Int {
        var c = payload.size
        for (b in payload) c = (c * 3 + (b.toInt() and 0xFF)) and 0xFF
        return c and 0x7F
    }

    private fun packet(deviceIndex: Int, bits: BitWriter): ByteArray {
        val payload = bits.toBytes()
        return header + byteArrayOf((deviceIndex and 0x3F).toByte()) + payload +
            byteArrayOf(checksum(payload).toByte(), 0xF7.toByte())
    }

    // ---- Keyboard to host ------------------------------------------------------------------

    /**
     * Decodes one SysEx packet from the keyboard. Returns nothing for packets that are
     * not BLOCKS packets or fail the checksum, and stops at the first message type it
     * does not know (the rest of that packet cannot be skipped reliably).
     */
    fun decode(packet: ByteArray): List<DeviceMessage> {
        if (packet.size < header.size + 3 || packet.last() != 0xF7.toByte()) return emptyList()
        if (!header.indices.all { packet[it] == header[it] }) return emptyList()
        val payload = packet.copyOfRange(header.size + 1, packet.size - 2)
        if (checksum(payload) != (packet[packet.size - 2].toInt() and 0xFF)) return emptyList()

        val bits = BitReader(payload)
        if (bits.remaining < 32) return emptyList()
        bits.read(32) // packet timestamp

        val messages = ArrayList<DeviceMessage>()
        while (bits.remaining >= 7) {
            val type = bits.read(7)
            when (type) {
                0 -> break
                DEVICE_TOPOLOGY -> {
                    if (bits.remaining < 23) break
                    bits.read(8) // protocol version
                    val devices = bits.read(7)
                    val connections = bits.read(8)
                    if (bits.remaining < devices * DEVICE_INFO_BITS + connections * CONNECTION_INFO_BITS) break
                    repeat(devices) {
                        val serial = String(CharArray(SERIAL_LENGTH) { bits.read(7).toChar() })
                        messages += DeviceMessage.Device(
                            serial = serial,
                            index = bits.read(7),
                            batteryLevel = bits.read(5),
                            charging = bits.read(1) == 1,
                        )
                    }
                    bits.skip(connections * CONNECTION_INFO_BITS)
                }
                DEVICE_PACKET_ACK -> {
                    if (bits.remaining < 10) break
                    messages += DeviceMessage.PacketAck(bits.read(10))
                }
                DEVICE_VERSION -> {
                    val (index, text) = readText(bits) ?: break
                    messages += DeviceMessage.Version(index, text)
                }
                DEVICE_NAME -> readText(bits) ?: break // not needed; skipped
                DEVICE_CONFIG -> {
                    if (bits.remaining < 4) break
                    when (bits.read(4)) {
                        CONFIG_UPDATE -> {
                            if (bits.remaining < 8 + 3 * 32) break
                            messages += DeviceMessage.Config(bits.read(8), bits.read(32), bits.read(32), bits.read(32))
                        }
                        CONFIG_SET -> {
                            if (bits.remaining < 8 + 32) break
                            val item = bits.read(8)
                            val value = bits.read(32)
                            messages += DeviceMessage.Config(item, value, value, value)
                        }
                        else -> Unit // sync markers: no payload
                    }
                }
                DEVICE_BUTTON_DOWN, DEVICE_BUTTON_UP -> {
                    if (bits.remaining < 17) break
                    bits.skip(5) // timestamp offset
                    messages += DeviceMessage.Button(bits.read(12), down = type == DEVICE_BUTTON_DOWN)
                }
                else -> break
            }
        }
        return messages
    }

    /** Reads a (device index, text) pair as used by version and name messages. */
    private fun readText(bits: BitReader): Pair<Int, String>? {
        if (bits.remaining < 14) return null
        val index = bits.read(7)
        val length = bits.read(7)
        if (bits.remaining < length * 7) return null
        val text = String(CharArray(length) { bits.read(7).toChar() }).trimEnd('\u0000')
        return index to text
    }

    private const val SERIAL_LENGTH = 16
    private const val DEVICE_INFO_BITS = SERIAL_LENGTH * 7 + 7 + 5 + 1
    private const val CONNECTION_INFO_BITS = 24

    /** Writes bit fields least significant bit first, 7 bits to a byte. */
    private class BitWriter {
        private val bits = ArrayList<Boolean>()

        fun write(value: Int, count: Int): BitWriter {
            for (i in 0 until count) bits += (value ushr i) and 1 == 1
            return this
        }

        fun toBytes(): ByteArray = ByteArray((bits.size + 6) / 7) { byteIndex ->
            var byte = 0
            for (i in 0 until 7) {
                val bit = byteIndex * 7 + i
                if (bit < bits.size && bits[bit]) byte = byte or (1 shl i)
            }
            byte.toByte()
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var position = 0
        val remaining: Int get() = data.size * 7 - position

        fun skip(count: Int) {
            position += count
        }

        /** Reads up to 32 bits; a full 32-bit field comes back as a signed Int. */
        fun read(count: Int): Int {
            var value = 0L
            for (i in 0 until count) {
                val bit = position + i
                if ((data[bit / 7].toInt() ushr (bit % 7)) and 1 == 1) value = value or (1L shl i)
            }
            position += count
            return value.toInt()
        }
    }
}

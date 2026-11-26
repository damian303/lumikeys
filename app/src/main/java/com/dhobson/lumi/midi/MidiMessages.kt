package com.dhobson.lumi.midi

/** Helpers for raw MIDI byte arrays. Pure Kotlin. */
object MidiMessages {
    /**
     * Splits a buffer of complete MIDI messages into one array per message, so they
     * can be sent individually. Expands running status (repeats the status byte) and
     * keeps SysEx (F0 ... F7) whole. Bytes that cannot start a message are dropped.
     */
    fun split(bytes: ByteArray): List<ByteArray> {
        val messages = ArrayList<ByteArray>()
        var runningStatus = 0
        var i = 0
        while (i < bytes.size) {
            val byte = bytes[i].toInt() and 0xFF
            when {
                byte == 0xF0 -> {
                    var end = i + 1
                    while (end < bytes.size && (bytes[end].toInt() and 0xFF) != 0xF7) end++
                    if (end >= bytes.size) return messages // unterminated SysEx: drop it
                    messages += bytes.copyOfRange(i, end + 1)
                    runningStatus = 0
                    i = end + 1
                }
                byte >= 0xF8 -> { // real-time: single byte
                    messages += byteArrayOf(byte.toByte())
                    i++
                }
                byte >= 0x80 -> {
                    val length = messageLength(byte)
                    if (i + length > bytes.size) return messages // truncated: drop it
                    messages += bytes.copyOfRange(i, i + length)
                    runningStatus = if (byte < 0xF0) byte else 0
                    i += length
                }
                runningStatus != 0 -> { // data byte continuing the previous channel status
                    val dataLength = messageLength(runningStatus) - 1
                    if (i + dataLength > bytes.size) return messages
                    messages += byteArrayOf(runningStatus.toByte()) + bytes.copyOfRange(i, i + dataLength)
                    i += dataLength
                }
                else -> i++ // stray data byte
            }
        }
        return messages
    }

    /** Total length in bytes of a non-SysEx message starting with [status]. */
    private fun messageLength(status: Int): Int = when {
        status < 0xF0 -> when (status and 0xF0) {
            0xC0, 0xD0 -> 2
            else -> 3
        }
        status == 0xF1 || status == 0xF3 -> 2
        status == 0xF2 -> 3
        else -> 1 // F4, F5 (undefined), F6 tune request, F7 stray end
    }
}

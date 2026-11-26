package com.dhobson.lumi.midi

/**
 * Splits a raw MIDI byte stream into messages.
 *
 * Android delivers MIDI as unaligned bytes: one buffer may hold several messages
 * or part of one. Handles running status, real-time bytes interleaved anywhere,
 * and SysEx spanning buffers. Not thread-safe; feed it from one thread.
 */
class MidiParser(
    private val onShortMessage: (status: Int, data1: Int, data2: Int) -> Unit,
    private val onSysEx: (ByteArray) -> Unit = {},
) {
    private var status = 0
    private var dataBytesNeeded = 0
    private var firstData = -1
    private var inSysEx = false
    private val sysEx = java.io.ByteArrayOutputStream()

    fun feed(data: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            feedByte(data[i].toInt() and 0xFF)
        }
    }

    private fun feedByte(byte: Int) {
        when {
            byte >= 0xF8 -> Unit // real-time: may appear anywhere, never disturbs state
            byte == 0xF0 -> {
                inSysEx = true
                status = 0
                sysEx.reset()
                sysEx.write(byte)
            }
            byte == 0xF7 -> {
                if (inSysEx) {
                    sysEx.write(byte)
                    onSysEx(sysEx.toByteArray())
                }
                inSysEx = false
                status = 0
            }
            byte >= 0xF1 -> { // other system common: cancels running status; not needed here
                inSysEx = false
                status = 0
            }
            byte >= 0x80 -> { // channel status; also terminates an unfinished SysEx
                inSysEx = false
                status = byte
                firstData = -1
                dataBytesNeeded = when (byte and 0xF0) {
                    0xC0, 0xD0 -> 1
                    else -> 2
                }
            }
            inSysEx -> if (sysEx.size() < MAX_SYSEX_BYTES) sysEx.write(byte)
            status == 0 -> Unit // data byte with no status: ignore
            dataBytesNeeded == 1 -> onShortMessage(status, byte, 0)
            firstData < 0 -> firstData = byte
            else -> {
                onShortMessage(status, firstData, byte)
                firstData = -1 // running status: next data byte starts a new message
            }
        }
    }

    private companion object {
        const val MAX_SYSEX_BYTES = 4096
    }
}

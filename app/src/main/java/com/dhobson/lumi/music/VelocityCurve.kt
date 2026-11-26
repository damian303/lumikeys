package com.dhobson.lumi.music

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Maps the velocity the keyboard sends to the velocity the synth plays.
 *
 * Measured on the LUMI over Bluetooth: gentle presses arrive at velocity 1 to 30
 * (often exactly 1, which is inaudible), normal ones around 40 to 60 and hard ones
 * at 90 to 127. The curves lift the bottom of that range so every press that
 * registers can be heard, while keeping loud presses loud.
 */
enum class VelocityCurve(val label: String, private val floor: Int, private val exponent: Double) {
    /** Any press is clearly audible; least difference between soft and loud. */
    LIGHT("Light", floor = 40, exponent = 0.55),

    /** Soft presses are quiet but audible. */
    MEDIUM("Medium", floor = 20, exponent = 0.75),

    /** The keyboard's own response, unchanged. */
    RAW("As sent", floor = 1, exponent = 1.0);

    /** [velocity] 1..127 in, 1..127 out; never decreasing as input rises. */
    fun apply(velocity: Int): Int {
        val input = velocity.coerceIn(1, 127)
        if (this == RAW) return input
        val shaped = (input / 127.0).pow(exponent)
        return (floor + (127 - floor) * shaped).roundToInt().coerceIn(1, 127)
    }

    companion object {
        val DEFAULT = LIGHT

        fun fromName(name: String?): VelocityCurve = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

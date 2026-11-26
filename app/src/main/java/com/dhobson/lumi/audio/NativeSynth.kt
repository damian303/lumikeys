package com.dhobson.lumi.audio

/** JNI surface of the native audio engine (Oboe stream + FluidSynth). */
object NativeSynth {
    init {
        System.loadLibrary("lumisynth")
    }

    /** Opens the audio stream and loads the SoundFont. Slow (reads the whole file); call off the main thread. */
    external fun start(soundFontPath: String): Boolean

    external fun stop()

    /** Starts or stops audio output without unloading the synth. */
    external fun setPlaying(playing: Boolean)

    /** Queues a short MIDI message for the audio thread. Safe from any thread. */
    external fun sendMidi(status: Int, data1: Int, data2: Int)

    /**
     * As [sendMidi], but to a bank of 16 channels of its own. Bank 0 is live playing
     * and the looper; bank [BACKING_BANK] is the learn mode's accompaniment, whose
     * channel 10 (index 9) is a drum channel like bank 0's.
     */
    external fun sendMidiToBank(bank: Int, status: Int, data1: Int, data2: Int)

    const val BACKING_BANK = 1

    /** [sampleRate, framesPerBurst, bufferFrames, xruns, exclusive (0/1), droppedMessages, latencyMillis (-1 unknown)]. */
    external fun stats(): IntArray

    /** Peak absolute output sample since the previous call (0 = silence, 1 = full scale). */
    external fun takePeak(): Float

    // ---- Looper: a loop of MIDI events played on the audio clock ----

    /**
     * Replaces the loop. [frames] (ascending) and [messages] (packed status | data1 << 8 |
     * data2 << 16) are parallel arrays. Playback keeps its musical position.
     */
    external fun loopSet(frames: IntArray, messages: IntArray, lengthFrames: Int, framesPerBeat: Double, beatsPerBar: Int)

    /** Starts from the top after a metronome count-in of [countInBeats]. */
    external fun loopStart(countInBeats: Int, record: Boolean)

    external fun loopStop()

    /** While recording, live notes are captured for [loopDrainRecorded]. */
    external fun loopSetRecording(recording: Boolean)

    external fun loopSetMetronome(on: Boolean)

    /** [running (0/1), recording (0/1), position in frames (negative during count-in), length in frames]. */
    external fun loopState(): LongArray

    /** Fills [out] with captured notes as (frame shl 32) or message; returns how many. */
    external fun loopDrainRecorded(out: LongArray): Int
}

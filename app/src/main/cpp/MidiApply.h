#pragma once

#include <cstdint>

#include <fluidsynth.h>

/**
 * The synth has two banks of 16 channels. Bank 0 is live playing and the looper;
 * bank 1 is the learn mode's accompaniment, which brings a whole MIDI file's worth
 * of channels and instruments of its own.
 */
constexpr int kChannelsPerBank = 16;
constexpr int kChannelBanks = 2;
constexpr int kDrumChannelInBank = 9;

/** Which bank a packed message is for (bits 24 and up). */
inline int midiMessageBank(uint32_t message) {
    return static_cast<int>((message >> 24) & 0x0F);
}

/**
 * Applies a packed short MIDI message to the synth. Audio thread.
 * Layout: status | data1 << 8 | data2 << 16 | bank << 24.
 */
inline void applyMidiMessage(fluid_synth_t *synth, uint32_t message) {
    const int status = static_cast<int>(message & 0xFF);
    const int data1 = static_cast<int>((message >> 8) & 0x7F);
    const int data2 = static_cast<int>((message >> 16) & 0x7F);
    const int bank = midiMessageBank(message);
    if (bank >= kChannelBanks) {
        return;
    }
    const int channel = (status & 0x0F) + kChannelsPerBank * bank;
    switch (status & 0xF0) {
        case 0x80:
            fluid_synth_noteoff(synth, channel, data1);
            break;
        case 0x90:
            if (data2 == 0) {
                fluid_synth_noteoff(synth, channel, data1);
            } else {
                fluid_synth_noteon(synth, channel, data1, data2);
            }
            break;
        case 0xB0:
            fluid_synth_cc(synth, channel, data1, data2);
            break;
        case 0xC0:
            fluid_synth_program_change(synth, channel, data1);
            break;
        case 0xD0:
            fluid_synth_channel_pressure(synth, channel, data1);
            break;
        case 0xE0:
            fluid_synth_pitch_bend(synth, channel, data1 | (data2 << 7));
            break;
        default:
            break;
    }
}

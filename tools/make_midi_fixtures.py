#!/usr/bin/env python3
"""Writes the MIDI files the unit tests read, using the mido library.

They are written by a different program from the app's own reader on purpose, so the
tests check the reader against someone else's idea of the file format. Requires mido.
Run from the repo root; output goes to app/src/test/resources/midi/.
"""
import os
import mido

OUT = "app/src/test/resources/midi"


def notes(track, channel, items, velocity=90):
    """items: (delta ticks before the note, pitch, length in ticks)."""
    for delta, pitch, length in items:
        track.append(mido.Message("note_on", channel=channel, note=pitch, velocity=velocity, time=delta))
        track.append(mido.Message("note_off", channel=channel, note=pitch, velocity=0, time=length))


def format1():
    """Three parts and a tempo track, 96 ticks per beat, 110 bpm."""
    mid = mido.MidiFile(type=1, ticks_per_beat=96)
    conductor = mido.MidiTrack()
    conductor.append(mido.MetaMessage("track_name", name="Test Song", time=0))
    conductor.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(110), time=0))
    conductor.append(mido.MetaMessage("time_signature", numerator=4, denominator=4, time=0))
    mid.tracks.append(conductor)

    melody = mido.MidiTrack()
    melody.append(mido.MetaMessage("track_name", name="Melody", time=0))
    melody.append(mido.Message("program_change", channel=0, program=73, time=0))
    # C5 D5 E5 (a beat each), a beat's rest, then G5 for two beats.
    notes(melody, 0, [(0, 72, 96), (0, 74, 96), (0, 76, 96), (96, 79, 192)])
    mid.tracks.append(melody)

    bass = mido.MidiTrack()
    bass.append(mido.MetaMessage("track_name", name="Bass", time=0))
    bass.append(mido.Message("program_change", channel=1, program=32, time=0))
    notes(bass, 1, [(0, 36, 192), (0, 43, 192), (0, 36, 192)])
    mid.tracks.append(bass)

    drums = mido.MidiTrack()
    drums.append(mido.MetaMessage("track_name", name="Kit", time=0))
    notes(drums, 9, [(0, 36, 24), (72, 38, 24), (72, 36, 24), (72, 38, 24)])
    mid.tracks.append(drums)
    mid.save(os.path.join(OUT, "format1.mid"))


def format0():
    """One track holding two channels, 480 ticks per beat, default tempo, chords,
    overlapping notes, and note-on with velocity 0 used as note-off."""
    mid = mido.MidiFile(type=0, ticks_per_beat=480)
    track = mido.MidiTrack()
    # Channel 0: a C major chord for a beat, then E4 and G4 overlapping.
    for pitch in (60, 64, 67):
        track.append(mido.Message("note_on", channel=0, note=pitch, velocity=100, time=0))
    track.append(mido.Message("note_on", channel=1, note=48, velocity=80, time=0))  # channel 1 bass, two beats
    track.append(mido.Message("note_on", channel=0, note=60, velocity=0, time=480))
    track.append(mido.Message("note_on", channel=0, note=64, velocity=0, time=0))
    track.append(mido.Message("note_on", channel=0, note=67, velocity=0, time=0))
    track.append(mido.Message("note_on", channel=0, note=64, velocity=100, time=0))   # E4 at 480
    track.append(mido.Message("note_on", channel=0, note=67, velocity=100, time=240))  # G4 at 720
    track.append(mido.Message("note_off", channel=0, note=64, velocity=0, time=240))   # E4 ends 960
    track.append(mido.Message("note_off", channel=1, note=48, velocity=0, time=0))     # bass ends 960
    track.append(mido.Message("note_off", channel=0, note=67, velocity=0, time=240))   # G4 ends 1200
    mid.tracks.append(track)
    mid.save(os.path.join(OUT, "format0.mid"))


def wide():
    """A melody spanning three octaves, to exercise fitting onto two."""
    mid = mido.MidiFile(type=1, ticks_per_beat=480)
    track = mido.MidiTrack()
    track.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(90), time=0))
    notes(track, 0, [(0, p, 480) for p in (48, 55, 60, 64, 67, 72, 76, 79, 84, 60)])
    mid.tracks.append(track)
    mid.save(os.path.join(OUT, "wide.mid"))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    format1(); format0(); wide()
    for name in sorted(os.listdir(OUT)):
        path = os.path.join(OUT, name)
        mid = mido.MidiFile(path)
        print(f"{name}: {os.path.getsize(path)} bytes, type {mid.type}, {mid.ticks_per_beat} ppq, {len(mid.tracks)} tracks")

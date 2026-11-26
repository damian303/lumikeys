#!/usr/bin/env python3
"""Hardware probe for the ROLI LUMI Keys / Piano M over USB MIDI (macOS).

Requires python-rtmidi. Subcommands:

  selftest            check SysEx encoding against captured examples (no hardware)
  ports               list MIDI ports
  probe               send topology + serial requests, print replies (read-only)
  listen SECONDS      print incoming MIDI for SECONDS
  lights [BASE]       light 8 keys via plain Note On at different velocities
  notes N:V [N:V..]   light given keys (NOTE:VELOCITY), 2s apart, hold 12s
  brightness IDX PCT  set LED brightness (persists on the device); IDX from `probe`
"""
import sys
import time

MANUFACTURER = [0x00, 0x21, 0x10]
TOPOLOGY_REQUEST = [0xF0, 0x00, 0x21, 0x10, 0x77, 0x00, 0x01, 0x01, 0x00, 0x5D, 0xF7]
SERIAL_REQUEST = [0xF0, 0x00, 0x21, 0x10, 0x78, 0x3F, 0xF7]

# (note offset from base, velocity, colour the 2020 ROLI program maps it to)
LIGHT_STEPS = [
    (0, 127, "white"),
    (2, 1, "pink-red"),
    (4, 22, "orange"),
    (5, 43, "yellow"),
    (7, 64, "green"),
    (9, 85, "cyan"),
    (11, 106, "blue"),
    (12, 126, "purple"),
]


def checksum(body):
    c = len(body)
    for b in body:
        c = (c * 3 + b) & 0xFF
    return c & 0x7F


def frame(device_index, body):
    return [0xF0, *MANUFACTURER, 0x77, device_index & 0x7F, *body, checksum(body), 0xF7]


def set_config_body(item, value):
    return [
        0x10,
        (item & 7) << 4,
        (item >> 3) | ((value & 3) << 5),
        (value >> 2) & 0x7F,
        (value >> 9) & 0x7F,
        (value >> 16) & 0x7F,
        (value >> 23) & 0x7F,
        (value >> 30) & 3,
    ]


def hexs(msg):
    return " ".join(f"{b:02X}" for b in msg)


def selftest():
    assert checksum([0x01, 0x01, 0x00]) == 0x5D, "topology checksum"
    assert frame(0, [0x01, 0x01, 0x00]) == TOPOLOGY_REQUEST, "topology frame"
    assert frame(0x2E, [0x01, 0x03, 0x00])[-2] == 0x63, "ping checksum"
    body = set_config_body(13, 64)
    assert body == [0x10, 0x50, 0x01, 0x10, 0, 0, 0, 0], hexs(body)
    assert checksum(body) == 0x0B, "setConfig checksum"
    assert set_config_body(36, 100) == [0x10, 0x40, 0x04, 0x19, 0, 0, 0, 0], "brightness 100"
    assert set_config_body(36, 50) == [0x10, 0x40, 0x44, 0x0C, 0, 0, 0, 0], "brightness 50"
    assert set_config_body(34, 0xFF0000FF) == [0x10, 0x20, 0x64, 0x3F, 0x00, 0x00, 0x7E, 0x03], "colour blue"
    print("selftest: SysEx encoding matches all captured examples")


def find_port(midi, label):
    ports = midi.get_ports()
    for i, name in enumerate(ports):
        low = name.lower()
        if "lumi" in low or "roli" in low or " block" in low:
            return i, name
    sys.exit(f"No LUMI/ROLI {label} port found. Ports: {ports}")


def open_ports():
    import rtmidi

    midi_in, midi_out = rtmidi.MidiIn(), rtmidi.MidiOut()
    i, in_name = find_port(midi_in, "input")
    o, out_name = find_port(midi_out, "output")
    midi_in.ignore_types(sysex=False, timing=True, active_sense=True)
    midi_in.open_port(i)
    midi_out.open_port(o)
    print(f"opened in='{in_name}' out='{out_name}'")
    return midi_in, midi_out


def describe(msg):
    status = msg[0]
    kind, ch = status & 0xF0, (status & 0x0F) + 1
    if status == 0xF0:
        note = ""
        if msg[1:4] == MANUFACTURER and len(msg) > 5:
            note = f"  [ROLI type {msg[4]:02X}, index byte {msg[5]:02X} -> index {msg[5] & 0x3F:02X}]"
        return f"sysex ({len(msg)} bytes){note}"
    names = {0x90: "note-on", 0x80: "note-off", 0xA0: "poly-aftertouch", 0xB0: "cc",
             0xD0: "channel-pressure", 0xE0: "pitch-bend", 0xC0: "program"}
    return f"{names.get(kind, 'other')} ch{ch} data={list(msg[1:])}"


def drain(midi_in, seconds):
    count, end = 0, time.time() + seconds
    start = time.time()
    while time.time() < end:
        got = midi_in.get_message()
        if got is None:
            time.sleep(0.002)
            continue
        msg = got[0]
        count += 1
        print(f"  {time.time() - start:6.3f}s  {hexs(msg)}   {describe(msg)}")
    return count


def cmd_ports():
    import rtmidi

    print("IN :", rtmidi.MidiIn().get_ports())
    print("OUT:", rtmidi.MidiOut().get_ports())


def cmd_probe():
    midi_in, midi_out = open_ports()
    print("unsolicited traffic (1s):")
    drain(midi_in, 1.0)
    print(f"send topology request: {hexs(TOPOLOGY_REQUEST)}")
    midi_out.send_message(TOPOLOGY_REQUEST)
    n = drain(midi_in, 2.0)
    print(f"send serial request: {hexs(SERIAL_REQUEST)}")
    midi_out.send_message(SERIAL_REQUEST)
    n += drain(midi_in, 2.0)
    print(f"replies received: {n}")


def cmd_listen(seconds):
    midi_in, _ = open_ports()
    print(f"listening for {seconds:.0f}s")
    n = drain(midi_in, seconds)
    print(f"messages received: {n}")


def cmd_lights(base):
    _, midi_out = open_ports()
    for offset, velocity, colour in LIGHT_STEPS:
        note = base + offset
        print(f"note-on  note={note} velocity={velocity:3d}  (2020 program: {colour})")
        midi_out.send_message([0x90, note, velocity])
        time.sleep(2.0)
    print("holding all 8 keys for 10s")
    time.sleep(10.0)
    for offset, _, _ in LIGHT_STEPS:
        midi_out.send_message([0x80, base + offset, 0])
    print("note-offs sent")
    time.sleep(0.2)


def cmd_notes(pairs, hold):
    """Light arbitrary keys: pairs are NOTE:VELOCITY strings, sent 2s apart."""
    _, midi_out = open_ports()
    notes = []
    for pair in pairs:
        note, velocity = (int(x) for x in pair.split(":"))
        notes.append(note)
        print(f"note-on  note={note} velocity={velocity:3d}")
        midi_out.send_message([0x90, note, velocity])
        time.sleep(2.0)
    print(f"holding for {hold:.0f}s")
    time.sleep(hold)
    for note in notes:
        midi_out.send_message([0x80, note, 0])
    print("note-offs sent")
    time.sleep(0.2)


def cmd_brightness(device_index, percent):
    midi_in, midi_out = open_ports()
    msg = frame(device_index, set_config_body(36, percent))
    print(f"send brightness {percent}%: {hexs(msg)}")
    midi_out.send_message(msg)
    drain(midi_in, 1.5)


def main(argv):
    selftest()
    if len(argv) < 2 or argv[1] == "selftest":
        return
    cmd = argv[1]
    if cmd == "ports":
        cmd_ports()
    elif cmd == "probe":
        cmd_probe()
    elif cmd == "listen":
        cmd_listen(float(argv[2]))
    elif cmd == "lights":
        cmd_lights(int(argv[2]) if len(argv) > 2 else 48)
    elif cmd == "notes":
        cmd_notes(argv[2:], 12.0)
    elif cmd == "brightness":
        cmd_brightness(int(argv[2], 0), int(argv[3]))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)

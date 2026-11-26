#!/usr/bin/env python3
"""Prototype of the ROLI BLOCKS config protocol, driven through the app's debug bridge.

The phone must be attached over adb with Lumi Keys running and connected to the keyboard.
Wire format follows ROLI's roli_blocks_basics (ISC licence): roli_HostPacketBuilder.h,
roli_HostPacketDecoder.h, roli_BlocksProtocolDefinitions.h.

  lumi_cfg.py topology                 request topology; print decoded reply
  lumi_cfg.py get ITEM [ITEM...]       request config items; print value / min / max
  lumi_cfg.py sync user|factory        request a full config sync; print everything returned
  lumi_cfg.py decode "F0 00 21 ..."    decode a captured device packet
"""
import os
import re
import subprocess
import sys
import time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
ACTION = "com.dhobson.lumi.DEBUG_SEND_MIDI"
HEADER = [0xF0, 0x00, 0x21, 0x10, 0x77]

CONFIG_NAMES = {
    0: "midiStartChannel", 1: "midiEndChannel", 2: "midiUseMPE", 3: "pitchBendRange", 4: "octave",
    5: "transpose", 8: "octaveTopology", 9: "midiChannelRange", 10: "strikeSensitivity",
    13: "pressSensitivity", 14: "liftSensitivity", 15: "fixedVelocity", 16: "fixedVelocityValue",
    20: "mode", 22: "scale", 27: "rootKey", 30: "?30", 32: "pressureTracking", 34: "globalKeyColour",
    35: "rootKeyColour", 36: "brightness", 40: "MPEZone", 41: "keyPitchBendAmount",
    100: "mode0.colourScheme", 107: "mode1.colourScheme", 114: "mode2.colourScheme", 121: "mode3.colourScheme",
}


def checksum(body):
    c = len(body)
    for b in body:
        c = (c * 3 + b) & 0xFF
    return c & 0x7F


class BitWriter:
    def __init__(self):
        self.bits = []

    def write(self, value, count):
        for i in range(count):
            self.bits.append((value >> i) & 1)
        return self

    def to_bytes(self):
        out = []
        for i in range(0, len(self.bits), 7):
            chunk = self.bits[i:i + 7]
            out.append(sum(bit << n for n, bit in enumerate(chunk)))
        return out


class BitReader:
    def __init__(self, data):
        self.bits = [(b >> i) & 1 for b in data for i in range(7)]
        self.pos = 0

    def remaining(self):
        return len(self.bits) - self.pos

    def read(self, count):
        value = sum(self.bits[self.pos + i] << i for i in range(count))
        self.pos += count
        return value


def frame(device_index, writer):
    body = writer.to_bytes()
    return HEADER + [device_index & 0x3F] + body + [checksum(body), 0xF7]


def topology_request():
    return frame(0, BitWriter().write(0x01, 7).write(0x01, 9))


def set_config(device_index, item, value):
    return frame(device_index, BitWriter().write(0x10, 7).write(0, 4).write(item, 8).write(value & 0xFFFFFFFF, 32))


def request_config(device_index, item):
    # Order per HostPacketBuilder::addRequestMessage: a 32-bit zero, then the item.
    return frame(device_index, BitWriter().write(0x10, 7).write(1, 4).write(0, 32).write(item, 8))


def request_sync(device_index, user):
    return frame(device_index, BitWriter().write(0x10, 7).write(3 if user else 2, 4))


def signed32(value):
    return value - (1 << 32) if value & 0x80000000 else value


def decode(packet):
    """Returns a list of (kind, details) for one device packet."""
    if packet[:5] != HEADER or packet[-1] != 0xF7:
        return [("not-roli", {})]
    index = packet[5] & 0x3F
    body, check = packet[6:-2], packet[-2]
    if checksum(body) != check:
        return [("bad-checksum", {"index": index})]
    reader = BitReader(body)
    if reader.remaining() < 32:
        return [("short", {})]
    reader.read(32)  # packet timestamp
    messages = []
    while reader.remaining() >= 7:
        kind = reader.read(7)
        if kind == 0:
            break
        if kind == 0x01:  # deviceTopology
            reader.read(8)  # protocol version
            devices, connections = reader.read(7), reader.read(8)
            for _ in range(devices):
                serial = "".join(chr(reader.read(7)) for _ in range(16))
                messages.append(("topology-device", {"serial": serial, "index": reader.read(7),
                                                     "battery": reader.read(5), "charging": reader.read(1)}))
            reader.read(connections * 24)
        elif kind == 0x02:
            messages.append(("packet-ack", {"counter": reader.read(10)}))
        elif kind in (0x06, 0x07):
            idx, length = reader.read(7), reader.read(7)
            text = "".join(chr(reader.read(7)) for _ in range(length))
            messages.append(("version" if kind == 0x06 else "name", {"index": idx, "text": text}))
        elif kind in (0x20, 0x21):  # controlButtonDown / controlButtonUp
            reader.read(5)  # timestamp offset
            messages.append(("button-down" if kind == 0x20 else "button-up", {"id": reader.read(12)}))
        elif kind == 0x18:  # configMessage
            command = reader.read(4)
            if command == 4:
                item = reader.read(8)
                messages.append(("config", {"item": item, "value": signed32(reader.read(32)),
                                            "min": signed32(reader.read(32)), "max": signed32(reader.read(32))}))
            elif command == 0:
                messages.append(("config-set", {"item": reader.read(8), "value": signed32(reader.read(32))}))
            elif command == 7:
                messages.append(("factory-sync-end", {}))
            elif command == 9:
                messages.append(("factory-sync-reset", {}))
            else:
                messages.append((f"config-command-{command}", {}))
        else:
            messages.append((f"unknown-type-0x{kind:02X}", {"index": index}))
            break
    return messages


def self_test():
    assert topology_request() == [0xF0, 0x00, 0x21, 0x10, 0x77, 0x00, 0x01, 0x01, 0x00, 0x5D, 0xF7]
    # The four mode frames that were sent to the keyboard and observed to work.
    assert set_config(0x14, 20, 3) == [0xF0, 0, 0x21, 0x10, 0x77, 0x14, 0x10, 0x40, 0x62, 0, 0, 0, 0, 0, 0x7E, 0xF7]
    assert set_config(0x14, 20, 0)[-2] == 0x5E
    # From benob's SYSEX.txt: brightness 50%, octave -4, "read configuration" = requestFactorySync.
    assert set_config(0x37, 36, 50)[6:14] == [0x10, 0x40, 0x44, 0x0C, 0, 0, 0, 0]
    assert set_config(0x37, 4, -4)[6:14] == [0x10, 0x40, 0x00, 0x7F, 0x7F, 0x7F, 0x7F, 0x03]
    assert request_sync(0x37, user=False)[6:8] == [0x10, 0x02]
    # Captured from this keyboard: the repeating 15-byte packet, and a topology reply.
    ack = [int(x, 16) for x in "F0 00 21 10 77 54 00 00 00 00 20 00 00 6D F7".split()]
    assert decode(ack) == [("packet-ack", {"counter": 0})]
    topology = [int(x, 16) for x in (
        "F0 00 21 10 77 54 00 00 00 00 10 10 20 00 00 66 25 61 5A 5A 66 65 19 2C 29 2B 24 59 64 5C "
        "1A 4A 5F 01 45 21 4C 6B 4C 2B 0E 60 01 25 00 00 05 F7").split()]
    kind, details = decode(topology)[0]
    assert kind == "topology-device" and details["index"] == 0x14 and details["serial"].startswith("LKB")


def send(packet, gap=None):
    hex_string = " ".join(f"{b:02X}" for b in packet)
    command = [ADB, "shell", "am", "broadcast", "-p", "com.dhobson.lumi", "-a", ACTION, "--es", "hex", f"'{hex_string}'"]
    if gap is not None:
        command += ["--ei", "gap", str(gap)]
    subprocess.run(command, capture_output=True, check=True)


def collect(seconds):
    time.sleep(seconds)
    log = subprocess.run([ADB, "logcat", "-d", "-v", "time", "-s", "LumiApp:I"], capture_output=True, text=True).stdout
    packets = []
    for line in log.splitlines():
        match = re.search(r"sysex in \(\d+ bytes\): ([0-9A-F ]+)$", line)
        if match:
            packets.append([int(x, 16) for x in match.group(1).split()])
    return packets


def clear_log():
    subprocess.run([ADB, "logcat", "-c"], check=True)


def describe(messages):
    for kind, details in messages:
        if kind == "config":
            name = CONFIG_NAMES.get(details["item"], "")
            print(f"  item {details['item']:3d} {name:22s} value={details['value']}  "
                  f"min={details['min']}  max={details['max']}")
        else:
            print(f"  {kind}: {details}")


def learn_index():
    clear_log()
    send(topology_request())
    for packet in collect(1.5):
        for kind, details in decode(packet):
            if kind == "topology-device":
                return details["index"]
    sys.exit("no topology reply from the keyboard")


def main(argv):
    self_test()
    if len(argv) < 2:
        sys.exit(__doc__)
    if argv[1] == "decode":
        describe(decode([int(x, 16) for x in argv[2].split()]))
    elif argv[1] == "topology":
        clear_log()
        send(topology_request())
        for packet in collect(2.0):
            print(f"packet ({len(packet)} bytes):")
            describe(decode(packet))
    elif argv[1] == "get":
        index = learn_index()
        print(f"device index 0x{index:02X}")
        clear_log()
        for item in argv[2:]:
            send(request_config(index, int(item)))
        for packet in collect(2.0):
            describe(decode(packet))
    elif argv[1] == "sync":
        index = learn_index()
        clear_log()
        send(request_sync(index, user=argv[2] == "user"))
        packets = collect(4.0)
        print(f"{len(packets)} packets")
        for packet in packets:
            describe(decode(packet))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)

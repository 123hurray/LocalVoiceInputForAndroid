#!/usr/bin/env python3
"""Linux reference receiver: capture one explicitly selected HID input device."""
import argparse
import sys
import zlib

MAX_HEX = 2 * 1024 + 8


def decode_frame(body):
    if not 10 <= len(body) <= MAX_HEX or len(body) % 2:
        raise ValueError("invalid frame length")
    payload = bytes.fromhex(body[:-8])
    if zlib.crc32(payload) != int(body[-8:], 16):
        raise ValueError("checksum mismatch")
    return payload.decode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("device", help="LocalVoice HID /dev/input/eventN device")
    args = parser.parse_args()
    from evdev import InputDevice, ecodes

    hex_keys = {getattr(ecodes, f"KEY_{c.upper()}"): c for c in "0123456789abcdef"}
    device = InputDevice(args.device)
    if not device.name.startswith("LocalVoice HID"):
        parser.error(f"select the LocalVoice HID device, got {device.name!r}")
    body = None
    # Keep framing keystrokes out of the focused application.
    with device.grab_context():
        for event in device.read_loop():
            if event.type != ecodes.EV_KEY or event.value != 1:
                continue
            if event.code == ecodes.KEY_F18:
                body = []
            elif event.code == ecodes.KEY_F19:
                if body is not None:
                    try:
                        text = decode_frame("".join(body))
                    except ValueError:
                        print("Discarded incomplete or corrupt HID frame", file=sys.stderr)
                    else:
                        print(text, flush=True)
                body = None
            elif body is not None:
                value = hex_keys.get(event.code)
                if value is None or len(body) >= MAX_HEX:
                    body = None
                else:
                    body.append(value)


if __name__ == "__main__":
    main()

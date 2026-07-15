# clawdpad-app

**Clawd's pocket brain** — an Android app that hosts the Claude Code
critter on a ROLI Lightpad Block with no computer involved.

Part of the [clawdpad](https://github.com/xsytrance/clawdpad) family. The
app speaks the ROLI BLOCKS SysEx protocol over Android MIDI: handshake,
keepalive, LittleFoot program upload, and animation streaming — all from
packets precomputed by the desktop project's proven protocol stack
(`clawdpad/tools/make_app_stream.py` regenerates `assets/stream.json`).

## Use

1. Install the APK (build: `gradle assembleDebug`, needs Android SDK 36).
2. **USB (works):** plug the Lightpad into the phone with a USB-C cable —
   the app auto-connects and Clawd appears. Buttons: full / chibi / wave /
   jump / QR-code.
3. **Bluetooth (experimental):** bridge the block with the MIDI BLE
   Connect app first. Transport + ACKs work; the block's firmware appears
   to gate API mode to USB, so the glass may stay in factory mode — see
   `clawdpad/docs/APP.md` field notes for the full investigation.

## Status

v0.2 — precomputed-stream beta. Roadmap (see clawdpad/docs/APP.md +
WEAR.md): live protocol port (topology + ACK tracking), on-device Clawd
renderer with touch reactions, foreground service, stats/feed/talk
screens, Wear OS sibling.

MIT. Built with Claude Code, at 7am, with a train to catch.

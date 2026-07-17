# clawdpad-app

**Clawd's pocket brain** — an Android app that hosts the Claude Code
critter on a ROLI Lightpad Block with no computer involved.

Part of the [clawdpad](https://github.com/xsytrance/clawdpad) family. The
app speaks the ROLI BLOCKS SysEx protocol over Android MIDI: handshake,
keepalive, LittleFoot program upload, and animation streaming — all from
packets precomputed by the desktop project's proven protocol stack
(`clawdpad/tools/make_app_stream.py` regenerates `assets/stream.json`).

## Use

1. **Install the APK** — grab it from
   [Releases](https://github.com/xsytrance/clawdpad-app/releases/latest) and
   open it on the phone; allow "install unknown apps" when Android asks. Check
   the `sha256` in the release notes against the download if you're careful,
   and you should be.
   Building it yourself: `gradle assembleDebug` (JDK 17, Android SDK 36 — no
   wrapper in this repo yet, so use a system Gradle ≥ 8.7).
2. **USB (works):** plug the Lightpad into the phone with a USB-C cable —
   the app auto-connects and Clawd appears. Buttons: full / chibi / wave /
   jump / QR-code.
3. **Bluetooth:** bridge the block with the MIDI BLE Connect app first.
   Transport and ACKs work.

   > **The "firmware gates API mode to USB" theory is dead** — corrected
   > 2026-07-17. It was never firmware: it was a packet-index bug, and the same
   > symptom (glass stuck in factory mode over BLE) was fixed on the web host
   > and Clawd then ran wirelessly off a battery block on the first try. See
   > `clawdpad/docs/MACBOOK.md` Phase 3.
   >
   > Nobody has retried BLE from *this* app since that fix, so its status here
   > is **unknown, not blocked** — and the odds are now good. Worth an evening.

## Status

**This section understates the app and needs Rod's eyes** (noted 2026-07-17).
It says "precomputed-stream beta" and lists as *roadmap* several things the
tree says already landed: `ClawdRenderer.kt` computes Clawd live on-device,
`Streamer.kt` + `Protocol.kt` do live topology/ACK sync ("the ritual is dead"),
`HostService.kt` is the foreground service, `Touch.kt` reacts, and `Costumes.kt`
is a whole wardrobe. `assets/stream.json` is still here, so the precomputed
path may also still exist — which of the two actually drives the glass today is
the open question.

Still genuinely ahead (see clawdpad/docs/APP.md + WEAR.md): stats/feed/talk
screens, the Wear OS sibling, in-app updates, and battle mode
(clawdpad/docs/BATTLE.md).

## A note on the renderer

`ClawdRenderer.kt` and `Costumes.kt` are a **third implementation** of Clawd's
art, alongside `web/clawd-core.js` (canonical) and `clawdpadd.py`. Two problems,
both known and neither urgent:

- it was ported from `clawdpadd.py`, which is the mirror, not the source — the
  one-arrow rule (clawd-core.js → everything else) came later, in blood;
- `clawdpad/tools/parity.py` byte-compares the desk and the browser, and cannot
  see this file at all. Nothing stops it drifting.

The protocol side *is* covered — `GoldenTest.kt`/`DecoderTest.kt` keep the
Kotlin ROLI stack honest against captured traffic, which is the harder half.
See `clawdpad/docs/POSES.md` and `clawdpad/docs/LEVELS.md` ("platforms port the
transport, never the art") for the direction this should eventually go.

MIT. Built with Claude Code, at 7am, with a train to catch.

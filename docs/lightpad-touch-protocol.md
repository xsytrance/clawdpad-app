# Lightpad Block touch protocol — reverse-engineered & verified

Status: **VERIFIED on hardware** (Lightpad Block XC5G over Bluetooth, 2026-07-20).
Decoder: `app/src/main/java/dev/clawdpad/host/Blocks.kt` · Tests: `TouchDecodeTest.kt`
· Raw capture provenance: `app/src/test/resources/touch-capture.txt`.

This documents how the block reports finger touches so the battle/training/paint
features can consume them, and the exact ritual used to capture and verify it.

---

## 1. Message framing

Every device→host message is a MIDI SysEx:

```
F0 00 21 10 77 49 <payload…> <chk> F7
└┬┘ └──┬───┘ └┬┘  └───────┬──────┘ └┬┘ └┬┘
 │     │      │           │         │   └ SysEx end
 │     │      │           │         └ payload checksum (Protocol.checksum, &0x7F)
 │     │      │           └ payload (7-bit bytes, see §2)
 │     │      └ device index byte (0x49 → 0x49 & 0x3F = 9)
 │     └ ROLI manufacturer ID (00 21 10)
 └ SysEx start
```

- Header = `F0 00 21 10 77` (`Protocol.SYSEX_HEADER`), then one device-index byte.
- Payload = `sysex[6 .. len-2]`. Checksum = `sysex[len-2]`, verified 168/168 on capture.
- Payload is a **7-bit-per-byte, LSB-first bit stream** (see `BitReader`/`BitWriter`).

Non-touch traffic on the same wire: a steady `type 0x20` heartbeat and a periodic
`type 0x00` status ping (both harmless, ignored by the touch path). A one-time
`Bn 78/7B 00` MPE-style reset burst fires on connect — it is **not** touch data
(an earlier investigation misread it as MPE; touch is SysEx).

## 2. Payload bit layout (LSB-first, from payload start)

```
[ packet timestamp : 32 ]            # 0 in captures; kept for framing parity
then 1+ touch groups:
    [ type      :  7 ]               # 0x11 move · 0x13 start · 0x15 end
    [ header    : 10 ]               # touchIndex = (header >> 5) - 1
    [ x         : 12 ]               # 0..4095, left → right
    [ y         : 12 ]               # 0..4095, top  → bottom
    [ velocity  :  8 ]               # ONLY on start/end (0x13/0x15)
```

- **Phase** = `type % 3` → 1 start, 2 move, 0 end (matches the general BLOCKS
  decoder's existing 0x10..0x15 branch).
- **touchIndex**: header `0x20`→finger 0, `0x40`→finger 1, `0x60`→finger 2.
- **No continuous pressure (z)** exists in this mode. Velocity is present only on
  start/end and is surfaced as `TouchEvent.z` so gameplay keeps a strike-strength
  signal (`z == 0` on plain moves). See §4 for how gameplay adapts.
- **Multitouch** packs extra fingers as consecutive `02 <finger>` groups in one
  message, but the **type byte appears only on the first group** (and there is a
  per-group trailer whose bit width isn't yet pinned down). The decoder therefore
  reliably yields **finger 0 and stops cleanly**; trailing fingers are dropped,
  not mis-decoded (regression-pinned in `realConcatenatedMultitouchDecodesFirstFingerSafely`).
  Full multi-finger support needs a dedicated two-finger capture ritual on hardware.
  Single-touch — the common path for all current gameplay — is fully decoded.

### The bug this replaced
The provisional layout was `tsOff(5) idx(5) x(12) y(12) z(8) [vel(8)]`. Two errors:
a **phantom `z:8` field** (doesn't exist) and velocity read on plain moves. The
10-bit offset to `x` was already correct (`tsOff5 + idx5` == `header10`). The old
`Touch.isTouchStart` "worked" only by coincidentally matching a timestamp byte.

## 3. Golden vectors (real captured taps)

Decoded left→right x, top→bottom y (0..4095). Each lands in the correct quadrant:

| gesture      | raw type/x/y            | decoded x | decoded y | vel |
|--------------|-------------------------|-----------|-----------|-----|
| centre tap   | `30 02 04 15 10 1B 54…` | 2069      | 2156      | 58  |
| top-left     | `30 02 04 53 03 61 18…` | 467       | 388       | 51  |
| top-right    | `30 02 04 03 1D 3B 10…` | 3715      | 236       | 130 |
| bottom-left  | `30 02 04 7A 02 6E 46…` | 378       | 3512      | 184 |
| bottom-right | `30 02 04 06 7D 60 7E…` | 3718      | 3459      | 255 |

Full SysEx for each is asserted in `TouchDecodeTest.realCornersLandInCorrectQuadrants`.

## 4. Gameplay adaptation to no-pressure hardware

Because this block reports no continuous pressure, two features were reframed
around what it *does* give — position, timing, and landing velocity:

- **`Gesture.Hold`** (guard): `steadiness` is now **positional** — full marks until
  the finger drifts `STEADY_SPAN` (== `HOLD_MAX_PATH`, 1.5 cells) from where it
  landed. `firmness` (was `avgZ`) is the **landing velocity** (`zPeak`), i.e. how
  hard the guard was set, since moves carry `z == 0`. Feeds battle guard strength
  (`Battle.holdMagnitude`), style learning (`ClawdState` HOLD channel), and the
  guard training game.
- **`HoldSteady`** training game: the dead pressure gauge became a **height** gauge —
  "hold your finger on the drifting target line and keep it there." Reuses the
  tested `BandTracker` scoring verbatim; it just tracks normalised finger height
  (`1 - y/15`) instead of pressure.

Pinned by `GestureEngineTest.wanderingHoldIsNotSteady` /
`holdFirmnessIsLandingVelocityNotMovePressure`.

## 5. The capture ritual (how to re-verify / extend)

1. Host the block in the app; confirm `mInputPortOpen=[true]` via `adb shell dumpsys midi`.
2. Turn on raw capture — either tap the visible **🎙 capture** button, or from adb:
   `adb shell am broadcast -a dev.clawdpad.CAPTURE --ez on true`
   (the old long-press status toggle was invisible; a status update overwrote its
   confirmation, so a button + broadcast hook were added).
3. Record: `adb logcat -v time -s clawd-capture > touch.log`
4. Do scripted gestures with ~3 s silent pauses between each (the gaps segment the
   log): centre tap, 4 corner taps, slow L→R drag, slow T→B drag, hard press, two
   fingers.
5. Segment by the time gaps; drop the rolling-clock byte; find the 12-bit x/y fields
   by (a) low jitter on a still hold and (b) full range on the matching drag, low
   range on the other. Validate corners fall in the right quadrant.
6. Add real vectors to `TouchDecodeTest.kt`; keep the raw log in
   `app/src/test/resources/touch-capture.txt`.

## 6. Build / install / dev rig

- No `gradlew`/`java` on PATH. Build with the borrowed Android Studio JDK + wrapper gradle:
  ```
  export JAVA_HOME=/home/xsyprime/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2
  $(ls /home/…/gradle-9.6.1/bin/gradle) testDebugUnitTest assembleDebug --console=plain
  ```
- SDK / adb: `/home/xsyprime/Android/Sdk/platform-tools`. `local.properties` → SDK path.
- Verified building & installing on a Pixel 10 Pro XL and a Pixel 8 (`shiba`);
  43/43 unit tests pass.

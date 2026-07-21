# CLAWD COMBAT

An arcade fighting game on the Lightpad blocks: two block-creatures roam a
shared arena and beat each other apart **limb from limb** — heads pop off and
bounce, arms cartwheel away trailing confetti-gore, and a charged **finisher**
blows the whole creature into flying parts. Built 2026-07-21.

Files: `Fighters.kt` (roster), `FightScene.kt` (engine + arena + gore),
`MainActivity.kt` (fighter picker + snap wiring), `HostService.kt` (snap
detection), `Streamer.kt` (two-block relay), `Scene.kt` (`renderSecond`).

---

## 1. Two modes

- **Single-player training** (built). You vs an indestructible sparring dummy
  (🤖 Tinbot) that dismembers gloriously then bolts itself back together.
- **2-player** (planned). Two humans, each their own block+phone, fighting over
  a network. Not built yet.

## 2. The magic: snap two blocks → one arena

Each Lightpad shows regular Clawd until you physically **snap a second block
onto it**. Then the two blocks become **one 30-wide arena** and the fight
begins across both screens. Pull them apart → the fight pauses.

- **Snap detection** — snapping/unsnapping makes the master (the BLE-connected
  block) push a **type-0x01 TOPOLOGY** SysEx: `nDev=2` when joined, `nDev=1`
  apart (decoded serials: master `…XC5G` idx 9, snap-on `…SH8T` idx 32; conns
  dev9.port2↔dev32.port7 & dev9.port3↔dev32.port6). `Blocks.decode` already
  parses this into `blocks.deviceCount`; `Host.onSnap(snapped, secondIdx)`
  fires on the transition (self-calibrating: the first topology after connect
  sets the baseline and never auto-starts). `MainActivity.onBlocksSnap`
  starts/pauses the duel.
- **The relay** — the snapped-on block (idx 32) is lit **through the master**
  over the DNA connectors. `Streamer.bootSecond()` replays our LittleFoot
  program to idx 32 (rewriting the device byte, renumbering with a separate
  packet counter), then a second `HeapStreamer(32)` diff-streams its frames via
  `sendTo(pkt, 32)` (the normal `send()` force-targets the master, so block 2
  needs the explicit path). Verified on hardware: streaming to idx 32 lights up
  SH8T.

## 3. The arena (why it's clean)

Everything lives in **arena coordinates** (x = 0..29, two blocks wide). Each
block is a **window**: block 1 draws arena cols 0..14 (`xoff=0`), block 2 draws
15..29 (`xoff=15`). `FightScene.render(t)` is block 1, `renderSecond(t)` is
block 2 — both draw the *whole* arena and rely on `Draw.px` clipping to their
15-col slice. Consequences:

- Fighters **roam the full width and cross the seam** — a creature at arena-x 14
  straddles both screens.
- Gore needs **no per-block bookkeeping**: a head knocked off at arena-x 16 just
  flies onto block 2 because both windows render all particles in arena coords.
- The sim advances **once per frame, in `render(t)` only**; `renderSecond`
  merely draws the same state (both are called with the same `t`).

## 4. Controls (on your block)

| Input | Move |
|---|---|
| Swipe ← / → | **Move** across the arena (chase them onto the other block) |
| Swipe ↑ | **Uppercut** (targets the head → pops it off) |
| Swipe ↓ | **Sweep** (targets the legs → topples) |
| Tap (high/mid/low by Y) | **Jab** |
| Hold | **Block** (firmness + steadiness = guard strength) |
| Circle-scrub | **Charge super** (bottom-row meter) → next attack = **FINISHER** |

Attacks only connect **in range** (`REACH = 5` arena cells) — spacing is the
game; too far reads "MISS". Movement is dash-with-friction; getting close lets
you land hits, then the opponent gets knocked back across the floor.

## 5. Combat model

- Each creature is 6 detachable **parts** (head, torso, 2 arms, 2 legs) with
  their own HP. Zero a limb → it **detaches** into tumbling `Debris` plus a
  `Gib` spray (accent-colored, silly not gross). Zero the torso → **K.O.**
- **Finisher** (super + heavy attack in range): blows every part off at once.
- Juice: screen-shake on impact, hit-freeze (`hitstop`), a K.O. freeze; the
  training dummy **reassembles** after ~1.7 s and the round restarts.
- No continuous pressure on this hardware, so a hard hit = swipe (heavy) or a
  fast tap (velocity); block strength comes from hold firmness + steadiness.

## 6. Roster (`Fighters.kt`)

Parody archetypes, each a dressed-up Clawd (tint/accent/power/speed/guard +
a signature finisher): 🐾 CLAWD (all-rounder), 🤖 TINBOT (iron wall),
🐱 PROWL (alley cat), 🐸 HOPPS (springheel), 👻 BOO (trickster),
🌸 PUFF (pink peril). Picked from the **CLAWD COMBAT** row in the app; the
last pick is who you play when you snap. (Currently the opponent is always the
Tinbot dummy.)

## 7. Status & next steps

- ✅ Snap → fight trigger, relay to block 2, roaming two-block arena, gore.
- Diagnostic `Log.i("clawd-snap", …)` left in `HostService` (fires only on
  snap/unsnap) — remove when polishing.
- Next: pick the opponent fighter (not just Tinbot); tune seam-crossing feel /
  BLE frame budget for two live screens; then the 2-player mode (two phones,
  networked) per the roadmap.

See `docs/lightpad-touch-protocol.md` for the touch decode this rides on.

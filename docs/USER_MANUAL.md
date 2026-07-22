# ClawdPad — User Manual

*He lives on the block. Pet him there.* — clawdpad v0.4

ClawdPad turns a **ROLI Lightpad Block** (the 15×15 pressure-sensitive LED pad)
into a living toy: a pet character, a wardrobe, a music visualizer, a paint
canvas, an arcade fighter, and — the headliner — a **real autonomous Pokémon
battle** that plays out across two blocks when you snap them together.

---

## 1. Getting started

### Install
1. Copy `clawdpad-poke.apk` to your phone.
2. Open it (from a file manager, or in Termux: `termux-open <path>/clawdpad-poke.apk`).
3. Tap **Install / Update**. First time, allow the installer to install from
   this source. It updates over any previous ClawdPad and keeps your data.

### Connect a block
1. Power on the Lightpad Block and make sure Bluetooth is on.
2. Open ClawdPad and tap **🔌 connect to my block**.
3. It shows *"looking for your block…"* and links up. Once connected, CLAWD
   appears on the glass — **pet him** by touching the pad.

The connection is held by **The Keeper**, a background service that
auto-reconnects if the block drops or sleeps, so you rarely have to reconnect
by hand.

---

## 2. The block basics

The glass is the screen **and** the controller. ClawdPad reads your touches as
gestures:

| Gesture | How |
|---|---|
| **Tap** | quick press-and-release |
| **Swipe** | press and slide across the pad |
| **Hold** | press and keep your finger down |
| **Scrub / circle** | drag back-and-forth or in a circle (used to "charge") |

Different modes use these differently — each mode tells you its controls in the
app's status line when you start it.

---

## 3. 🔗 Poképad — auto-battle across two blocks *(the main event)*

**Snap a second block onto the first** and a fully autonomous **Gen-III Pokémon
battle** begins — no button, no human control.

- Two random species drop in — **one full creature per block** (block 1 = the
  left fighter, block 2 = the right).
- They fight by **real Gen-III mechanics**: authentic base stats, the real
  17-type chart (fire beats grass, water beats fire, ground is immune to
  electric…), the real damage formula, critical hits, and a move-picking AI on
  both sides.
- Watch the beats play out: a **Poké-Ball drops and bursts open** to summon each
  mon, the attacker **lunges** with an impact streak, the defender **flashes and
  recoils**, and a fainted mon **greys out, tips over, and sinks away**.
- **HP bars** sit along the top of each block and a **banner** calls the action
  ("SUPER!", "RESIST", the move name). The winner holds the field.

**Pull the blocks apart** to end the battle.

> All 386 Gen-III species can appear. The art is original pixel work (no ripped
> sprites), and every fight is decided by the real math — nothing is faked.

---

## 4. 🥊 CLAWD COMBAT — arcade fighter

Tap **CLAWD COMBAT**, then pick your fighter:

| Fighter | Style |
|---|---|
| 🐾 **CLAWD** | all-rounder |
| 🤖 **TINBOT** | iron wall |
| 🐱 **PROWL** | alley cat |
| 🐸 **HOPPS** | springheel |
| 👻 **BOO** | trickster |
| 🌸 **PUFF** | pink peril |

**Controls:** **tap** to strike · **hold** to guard · **swipe** to dodge ·
**circle/scrub** to charge.

Snap a **second block** on and the arena spans **both blocks** as one wide
battlefield — hits knock fighters across the seam, with confetti-gore particles
on impact. Pull the blocks apart to pause.

---

## 5. ⚔️ Train & Battle — "Duelist's Hands"

Tap **TRAIN & BATTLE** to train CLAWD and fight a rival in a human-free shadow
mode tuned for the block. Your controls: **strike** to hit · **hold** to guard ·
**swipe** to dodge · **scrub** to charge. CLAWD earns **Levels & XP** as you go.

---

## 6. 🎭 His Moods

Tap **HIS MOODS** to cycle CLAWD's moods/animations — his idle personality on
the glass. Pet him to interact.

---

## 7. 👕 The Clawdrobe

Tap **THE CLAWDROBE** to dress CLAWD up. **24 costumes** are organized into
category tabs — mix and match hats, looks, and accessories. Your choice shows on
the block immediately.

---

## 8. 📣 Put Words On Him

Tap **PUT WORDS ON HIM** to scroll a custom message across the block as a
marquee — plus an art/message mode for putting text and simple art on the glass.

---

## 9. 🎵 Music mode

Start **music/dance** mode and, when prompted, **allow audio** — that's how he
hears. Play something with a beat and CLAWD **dances to it**, with lyric-spark
visuals reacting to the sound.

---

## 10. 🎨 Paint

Start **paint** mode and **finger-paint directly on the block** — touch the pad
and it glows where you draw.

---

## 11. 🎙 Capture *(advanced / developer)*

A capture toggle dumps decoded touch data to the log for reverse-engineering the
hardware. Turn it on, then read it on a computer with:

```
adb logcat -s clawd-capture
```

Most players never need this — it's for hacking on the block protocol.

---

## 12. Troubleshooting

- **"connect his block first — the games live on the glass"** → you started a
  mode before connecting. Tap **🔌 connect to my block** first.
- **Block won't connect** → make sure it's powered and Bluetooth is on; toggle
  Bluetooth and tap connect again. The Keeper retries automatically.
- **Snap doesn't start a battle** → make sure the first block is connected, then
  physically snap the second block onto it; you'll see *"🔗 SNAP!"* and the
  matchup. Pulling them apart ends the fight.
- **"App not installed" on update** → a signature mismatch; uninstall the old
  ClawdPad (long-press the icon → Uninstall), then install fresh.
- **Music mode does nothing** → grant the audio permission, then tap dance
  again.

---

## Credits & notes

- **Covenant:** *facts are sacred, feelings are free* — real stats, lore, and
  mechanics are used as data; all art is an original reinterpretation, never a
  ripped asset.
- Poképad targets **Gen III**. Real save-data teams are on the roadmap; today
  the snap-battle picks random species from the full dex.

*Made for Rod. Snap 'em together and watch them fight.* 🔥

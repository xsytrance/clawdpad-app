# POKÉMON VERA — design seed

A fan/personal Pokémon-like on the Lightpad blocks. The block is your
creature's physical home: **touch to summon**, it lives on the glass between
battles, and you **snap blocks together to battle** — 1v1 or, with more blocks
in the cluster, a **battle royale**. The app can **read your own save data** to
summon your real team.

Origin: user's idea 2026-07-21 ("Pokémon Vera", alongside Undertale Vera and
FFT Vera). This builds directly on the CLAWD COMBAT engine (see
`docs/clawd-combat.md`) — most of the hard infrastructure already exists.

---

## Why it's a natural fit (what already exists)

| Pokémon Vera needs | We already built (CLAWD COMBAT) |
|---|---|
| A creature that lives on the block | Clawd + the part-based creature renderer |
| Summon / interact by touch | Verified touch decode + gesture engine |
| Snap blocks → battle | Snap detection via topology (`Host.onSnap`) |
| Render a creature on a 2nd block | The BLE relay to the snapped-on device index |
| Creatures roam a shared field | The 30-wide two-block arena + seam-crossing |
| Battle royale across many blocks | ROLI topology supports up to 6 blocks in a cluster |
| Faint / K.O. animations | The dismemberment particle system (repurpose as a faint/shatter) |
| Persisted creatures | The `ClawdState` save pattern |

So the leap from here is mostly **content + data**, not new plumbing.

## The core loops

1. **Habitat.** Your summoned Pokémon lives on the block like a Tamagotchi —
   idles, blinks, reacts to touch (pet it = affection, Pokémon-Amie style).
2. **Summon ritual.** Draw a sigil / do a gesture to summon; it materialises
   pixel-by-pixel with its cry (the app already has a sound kit). Shiny = a
   sparkle + alt palette.
3. **Snap to battle.** Snap a second block → 1v1 across the two-block arena
   (we have this). Snap N blocks in the cluster → **battle royale**: the
   topology *connection graph* is the battlefield layout — adjacency decides
   who can hit whom, and Pokémon roam the multi-block field.
4. **Moves as gestures.** Each mon's real moveset maps to inputs: swipe =
   physical, hold-charge = special, draw a shape = signature/Z-move. Type
   matchups show as color flashes; STAB/super-effective = bigger shake + gore.
5. **Trade by snapping.** Snap two blocks, run a trade animation — the mon
   literally **walks across the seam** from one block to the other (arena
   seam-crossing already works).

## Read your own save data

Import **your own** save file (like PKHeX does) to summon your actual team:
nickname, level, real moves, IVs/EVs, nature, and **shiny detection** → shiny
sprite. Party or box → pick who to summon. Save formats per generation are
publicly documented (PKHeX is the open-source reference). Get the `.sav` onto
the phone via a file picker or `adb push`; parse party block → creature stats.
Scope this as a **personal / fan project over your own saves** — no ROMs, no
DRM, just reading data you own.

## Idea firehose (pick and choose)

- **Nuzlocke mode:** permadeath. A fainted mon *shatters* (reuse the gore
  particles) and is gone for good — brutal and perfect for the hardware.
- **Status as LED language:** burn = flickering orange, sleep = slow dim pulse,
  poison = purple throb, paralysis = jittery stutter, freeze = pale + still.
- **Raid / gym mode:** one block is the boss den (a big legendary that spans
  the whole glass), the other blocks are challengers ganging up.
- **Overworld across blocks:** snap blocks edge-to-edge to build a bigger map
  to walk a mon around; tall grass encounters flash on a random block.
- **Day/night + weather** on the glass (rain, sandstorm) affecting moves.
- **Breeding / eggs:** an egg lives on a block, hop-counts to hatch, cracks
  open with a reveal animation.
- **The "Vera" signature:** a custom Vera-starter and/or Vera legendary as the
  mascot mon, exclusive to this project.
- **Cry-as-haptics + audio:** each mon's summon/faint has a distinct chirp.
- **Type-colored aura** around each creature so matchups read at a glance.

## The honest hard parts

- **Sprites.** A pixel mon on 15×15 is tiny; hundreds of species is a lot of
  art. Start with a handful (your team + the Vera mon), lean on silhouettes +
  type-color + a couple signature features, add more over time. Procedural
  "species archetypes" (quadruped, bird, blob, serpent) can cover many.
- **Battle data.** Type chart, movepools, damage formula, stats — data-heavy
  but the fan data is public. Ship a small curated set first.
- **Multi-block (>2).** We've only tested 2 snapped blocks. Battle royale needs
  the N-device topology parsed (deviceCount + the connection graph, which we
  already decode) and the relay pointed at *each* block's index. Plumb it for 3
  before promising 6.
- **Save parsing.** One generation's format at a time; validate against a known
  save. Keep it read-only.

## Suggested first slice (when we pick this up)

1. One Vera-starter mon, summonable by gesture, living on one block (habitat).
2. Snap → 1v1 using the existing arena, with type-colored auras + a real faint
   (shatter) instead of the training dummy.
3. Then: import a real save → summon one imported mon into that same loop.

The engine's already fighting across two blocks — this is the game it was
secretly always going to become.

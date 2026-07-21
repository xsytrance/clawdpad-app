# POKÉMON VERA — RENDERING & ANIMATION PLAN

Companion to `pokemon-vera-master-plan.md`. Covers the whole visual layer:
every Pokémon on the block, Poké Balls, and all the animation systems
(summon, idle, attack, hit, faint, status, weather, HUD).

## The core problem

386 (Gen III) creatures on a **15×15 RGB grid** (~225 pixels), **original**
(covenant: no ripped sprites), **plus** balls and a dozen animation systems.
You can't hand-draw 386 tiny sprites well, and you *can't* rip them. So the
answer is a **system**, not an asset dump.

## Principle: procedural archetypes, data-seeded, original by construction

Every species = a recipe `{archetype, palette, size, features[]}` that a
procedural renderer draws **and animates**. The recipe is auto-seeded from real
PokéAPI metadata, then hand-refined for the iconic ones. This is:

- **Scalable** — 386 creatures = code (an archetype library) + a small data
  table, not 386 bitmaps.
- **Original by construction** — we author the archetypes; a 15×15 creature
  reads by **type + shape + signature**, evocative of the species without
  copying its official sprite or trademarked design (the covenant's "original
  reinterpretation").
- **Animated for free** — poses live on the archetype, so every species inherits
  breathing, attacking, fainting, etc.

## Data inputs (from the Phase-1 scrape — already confirmed reachable)

Per species PokéAPI gives us the seeds:
- **`shape`** (14: ball, squiggle, fish, arms, blob, upright, legs, quadruped,
  wings, tentacles, heads, humanoid, bug-wings, armor) → **archetype**.
- **`color`** (10: black/blue/brown/gray/green/pink/purple/red/white/yellow) →
  **base palette**.
- **types** → **accent + aura + attack FX**.
- **genus** ("Mouse Pokémon"), **is_legendary/mythical**, height/weight (→
  scale), gender ratio, evolution stage.

Example: Pikachu → `quadruped` + `yellow` + Electric ⇒ a yellow quadruped-ish
creature with electric-accent ears/tail and a spark aura. Seeded entirely from
data; refined by hand into a hero sprite.

---

## 1. Creature rendering system (`CreatureRenderer`)

A shared module: `draw(recipe, pose, t) -> 15×15 pixels`. Reused by summon,
battle, idle, and the companion screen.

### 1a. Archetype library (~14, mapped from `shape`)
Each archetype is a parametric pixel body with a **skeleton** (named anchor
points: head, core, limbs, tail, wing, mouth, eyes) that animation poses move.
Rough map:
- ball/blob → **Blob**; quadruped/legs → **Quadruped**; upright/arms/humanoid →
  **Biped/Humanoid**; squiggle → **Serpent**; fish → **Aquatic**; wings/
  bug-wings → **Avian/Winged**; tentacles → **Tentacled**; armor → **Armored**;
  heads → **Multi-head**. Plus specials: **Amorphous/Ghost**, **Mineral**,
  **Plant**, **Dragon**.

### 1b. Feature overlays (composable, on skeleton anchors)
ears, tail, horn(s), wings, fins, flame crest, shell, spikes, antennae, gem,
mane — each a small drawable placed relative to an anchor, so they ride the
animation. A species is `archetype + [features]`.

### 1c. Palette system
- body = `color`/primary type; **accent** = secondary type; shade/outline auto;
  **shiny** = deterministic hue-shift alt palette (from the PID in SaveTruth);
  a subtle **type aura** ring. All palettes are ours.

### 1d. Species recipe table (versioned data)
`{ id -> archetype, size, palette overrides, features[], signature pixels }`.
Auto-seeded from PokéAPI (`shape→archetype`, `color/type→palette`), then
hand-refined. Shipped in assets alongside the pokedex dataset.

### 1e. Fidelity tiers (where the hand-work goes)
- **Tier A — hero species** (starters, legendaries, mascots, evolved forms of
  the player's likely team; ~60): hand-tuned recipes, optional authored bitmap
  override for the silhouette.
- **Tier B — the long tail** (~326): pure procedural from data. Still reads by
  type + shape + a signature feature. Upgradeable to Tier A over time.

## 2. Poké Balls (`BallRenderer`)

- A ball at 15×15 is easy: two-tone sphere + equator band + center button.
  **Variants** by ball type (Poké red/white, Great blue, Ultra yellow/black,
  Master purple + M, Premier white, Net/Dive/Nest/etc.) — palette swaps.
- Used as: the thrown ball (summon/return), the small **team belt** HUD (up to 6
  dots showing party + fainted state), and a ceremonial "in-ball" idle.

## 3. Animation systems (all procedural — `render(t, state)`, no stored frames)

### 3a. Summon / throw (the Vera "ritual")
Ball arcs in on a parabola → lands → button flashes → **snaps open** → burst of
white/type light → creature **materialises** (dithered fade-in / particle
assembly out of the light) → settles into idle. ~1.0–1.5 s. Shiny adds a
sparkle + chime.

### 3b. Return
Creature dissolves into a **red beam**, drawn back into the ball, ball closes,
wobble-settles.

### 3c. Capture flourish (ceremonial only)
Ball wobble ×1–3 → click/star or break-out. Vera is **read-only** — never writes
the save — so capture is a *flourish* (e.g., first-summon ceremony), not a game
action.

### 3d. Idle
Breathing bob + blink; **type ambient** (fire flicker, water drip, electric
spark, leaf sway, psychic ripple); **shiny shimmer**; **friendship-driven mood**
from SaveTruth (a high-friendship mon bounces / leans toward your touch).

### 3e. Attack
Per move: a **wind-up pose** (archetype-level: lunge / rear / flap / coil) → a
**type-colored FX** — physical = a contact lunge across the seam, special = a
projectile that flies from one block to the other (already built) → impact.
Hero/signature moves get bespoke FX (a Thunderbolt arc, a Hyper Beam charge, a
Surf wave sweeping both blocks).

### 3f. Hit / effectiveness
Flash + knockback/squash + HP drain; **SUPER! / RESIST / CRIT** bursts
(type-colored) + screen shake (built).

### 3g. Faint
Collapse + **dissolve** (repurpose the CLAWD COMBAT shatter/gore particles as a
faint poof) → grey-out → returned to ball (or a memorial for Nuzlocke saves).

### 3h. Status (the LED language)
burn = orange flicker + embers · poison/toxic = purple throb + bubbles ·
paralysis = yellow stutter + sparks · sleep = dim pulse + "Z" · freeze = pale
+ ice crystallise · confusion = wobble + orbiting stars.

### 3i. Weather / field
Full-screen ambient across **both** blocks: rain, harsh sun, sandstorm, hail,
plus entry-hazard motes on the floor.

### 3j. Type FX vocabulary (shared kit)
One reusable per-type particle/color kit (fire embers, water droplets, electric
arcs, grass leaves, ice shards, rock chunks, psychic ripples, …) that attacks,
status, auras, and weather all draw from — so the whole game speaks one visual
language.

## 4. HUD & framing
HP bar (with drain animation), name + level (scroll), **type as color chips**,
status icon, the ball **team belt**, turn banner, SUPER/CRIT banners (built).
Two-block layout via the arena/relay we already ship.

## 5. Technical model (fits the codebase)
- **Everything procedural**: `render(t, state)` computes each frame — exactly
  the existing `Scene`/`ClawdRenderer`/`FightScene` pattern. No stored animation
  frames; poses + time drive motion. Cheap enough for 12fps diff-streaming
  (built) to two blocks (relay built).
- **One `CreatureRenderer`** used everywhere (summon, battle, idle, companion),
  parameterised by recipe + pose, so a species looks the same in every context.
- Data footprint: archetype code + a ~386-row recipe table + the pokedex
  dataset. Small.

## 6. Authoring & QA pipeline
- **Archetype editor** — design/tune archetypes + features with a live 15×15
  preview (small web tool or a Kotlin preview harness that renders to PNG).
- **Species recipe table** — auto-seed from PokéAPI, then a hand-refine pass on
  the hero tier.
- **Gallery / regression** — render all 386 to a contact-sheet PNG for review;
  visual-diff to catch regressions when an archetype changes.
- **On-device preview** — push any species to a real block to check readability
  at true LED scale / across-the-room viewing.
- **Covenant QA** — sign off each design as an *original reinterpretation*
  (evocative via type + shape + signature, not a copy of the official sprite or
  trademarked character); no ripped assets; original cries/sound; trademark-safe
  product name.

## 7. Build order (slots into the master plan)
Starts alongside **Phase 2** (engine) so battles have bodies, and completes in
**Phase 4** (embodiment):
- **ART-M1** — `CreatureRenderer` + 6–8 archetypes + palette system + `BallRenderer`
  + summon/idle/faint. (Enough to re-skin the current 8-species battle.)
- **ART-M2** — attack/hit FX + status LED language + weather; the shared type-FX kit.
- **ART-M3** — auto-seed all 386 from data + gallery review + hero-tier hand pass.
- **ART-M4** — signature-move FX, shiny, friendship mood, memorials; polish.

## 8. Covenant (art)
Original reinterpretations only, readable by **type + shape + signature** rather
than by copying official designs; no ripped sprites, no copied Pokédex text, no
official music; original cries. This is the same wall Ember and the FFT Vera
hold — the art is the "feelings are free" side; the stats/types/mechanics are
the sacred facts.

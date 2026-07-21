# POKÉMON VERA — MASTER PLAN

Not a knockoff. Three things a knockoff doesn't have, all at once:

1. **It's a MultiVera save-aware companion** — it reads *your real save*, and the
   creatures *are yours* (SaveTruth; **facts are sacred, feelings are free**).
2. **Real mechanics, all of them** — the actual type chart, base stats, moves,
   abilities, items, status, weather. Fire really is weak to Water because the
   real chart says so, not because we faked a number.
3. **A physical body** — your Pokémon live on Lightpad blocks and fight across
   them when you snap them together. No other Pokémon thing does this.

Under the covenant (from the FFT/Undertale Vera blueprints): read-only saves,
original product name, **original reinterpretations** (our sprites, our flavor —
never ripped assets or copied Pokédex text), your own saves, personal/fan
framing. Mechanical **facts** (a Pokémon's stats/types/movesets) are usable as
data; creative **expression** (dex prose, artwork, names/trademarks) gets
reinterpreted, never copied.

This is a multi-month build. This document is the map. It supersedes the seed in
`pokemon-vera-idea.md`.

---

## 0. Canonical scope (v1)

- **Target generation: Gen III (Ruby / Sapphire / Emerald / FireRed / LeafGreen).**
  Rationale: the richest *reliably parseable* saves (natures, abilities, real
  IVs/EVs, met data, friendship, PID-derived shininess) and a famously
  documented format — and if the mon came from a Gen-III game, Gen-III
  **mechanics** are the honest way to battle it (incl. the physical/special
  split *by type*). The architecture keeps later gens as a data/mechanics
  version, not a rewrite.
- **Data source: PokéAPI** (`pokeapi.co`) — confirmed reachable, complete:
  1,025 species, 937 moves, all abilities/items/learnsets/evolutions. The
  underlying **veekun/pokedex** dataset (CSV/SQLite) is an even better bulk
  source if we want offline generation without hammering the API.
- **Two runtimes, clear split:**
  - **Kotlin, on the phone, offline** — the battle engine, the bundled data,
    the Gen-III save parser. This drives the blocks with no server dependency
    (the toy must work on a table with no wifi).
  - **Python MultiVera backend (optional, networked)** — the *companion chat*
    (lore RAG + grounded LLM), reusing the existing Vera engine. The block is
    the embodied avatar; the phone/app does the talking.
  - **Python build-time generator** — the scrape: PokéAPI → normalized,
    versioned, compact dataset shipped in the app's assets.

## 1. The four truths (MultiVera contract), for Pokémon

1. **Save truth** (sacred, wins): the player's party & boxes and trainer state —
   see §4 for the full SaveTruth field list.
2. **Game-system truth**: type chart, base stats, move data, ability/item
   effects, formulas — the scraped dataset (§2).
3. **Lore truth**: species/region/character identity — *reinterpreted* (§6).
4. **Conversation truth**: the chat so far.

If a current-state fact isn't in SaveTruth, the model/engine isn't trusted to
know it. Unknown → `null`; ambiguous → `undetermined`. A human-visible **Truth
Audit** turns hallucinations into pipeline bugs. Non-negotiable verification gate
on every fact: parser → storage → engine/prompt input → output.

---

## Phase 1 — The data pipeline (the scrape)

**Goal:** one command turns PokéAPI into a compact, versioned, offline dataset in
the app assets.

- `tools/build_pokedex.py` — pulls species (base stats, types, abilities,
  gender ratio, evolutions), the full move list (type, power, accuracy,
  category, PP, priority, target, **effect + effect chance**, flags), abilities
  (effect text → normalized effect ids), items (effect), the type chart, and
  Gen-III **learnsets** (level-up + TM/tutor) so movepools are real.
- Normalize into stable ids and a small schema; drop to `app/src/main/assets/
  pokedex/gen3.json` (or a bundled SQLite). Include a `data_version` + source
  hashes.
- **Covenant filter:** keep mechanical fields; do **not** ship dex flavor text
  or sprites. Flavor/art are regenerated as our own in Phase 6.
- Verify: species count, spot-check known stats (Charizard 78/84/78/109/85/100),
  a few learnset/ability sanity checks. Fixture tests.

**Deliverable:** bundled `gen3` dataset + generator + data tests.

## Phase 2 — The complete battle engine (Kotlin)

**Goal:** a Gen-III-accurate simulator, autonomous, that the block scenes drive.

Have already (`Pokemon.kt`): 17-type chart, stat + damage formulas, STAB, crit,
random, phys/special-by-type, best-damage AI, autonomous `step()` loop, 8
hand-verified species. Expand — in this order, each with tests:

1. **Full move DB** from the dataset (replace the hand roster): category, PP,
   priority, target, secondary effects, flags.
2. **Stat stages** (−6..+6), **accuracy/evasion** stages.
3. **Status**: burn, poison, toxic, paralysis, sleep, freeze, confusion — real
   effects and turn timing.
4. **Abilities** (the big one): a hook system (on-switch, on-hit, damage/stat
   modifiers, immunities — Levitate, Flash Fire, Water/Volt Absorb, Intimidate,
   Levitate, etc.).
5. **Held items**: berries, Choice/Leftovers/Life-Orb-era (Gen-III subset),
   type-boosters.
6. **Weather** (sun/rain/sand/hail) and **entry hazards**.
7. **Move machinery**: multi-hit, charge/recharge/semi-invulnerable, recoil/
   drain, protect, priority ordering, switching, end-of-turn resolution.
8. **Turn engine** proper: priority → speed, full end-of-turn phase.

**Verification gate:** damage/outcomes checked against a reference (Smogon damage
calc / PokéAPI-derived expectations); a suite of known matchups with expected
ranges. This is where "real mechanics" is *proven*, not asserted.

**Deliverable:** a data-driven engine that resolves any two real Gen-III teams
correctly, headless and tested.

## Phase 3 — Save → SaveTruth (Kotlin, read-only)

**Goal:** import your real Gen-III save; produce the sacred contract.

- `Gen3SaveParser.kt`: container validation (emulator `.sav` / flash), section
  layout + checksums, block-shuffle, party + PC boxes. Per Pokémon: species,
  nickname, level/EXP, **IVs, EVs, nature, ability slot, moves+PP, held item,
  OT name/ID/gender, met location/level, friendship, Poké Ball**, PID →
  shiny/gender/nature/ability derivations, ribbons. Trainer: name/ID, badges,
  playtime, money, Pokédex seen/caught.
- Normalize → **SaveTruth** (versioned schema) — the canonical current-state
  contract. Read-only; unknown → `null`, ambiguous → `undetermined`.
- **Truth Audit** screen + parser fixtures (known saves → expected facts). The
  verification gate: fact survives parser → storage → battle input → display.

**Deliverable:** drop in a save → a trustworthy, inspectable SaveTruth; a real
team the engine can battle with.

## Phase 4 — Embodiment on the blocks

**Goal:** your real Pokémon, on the glass, fighting for real.

- Summon a mon from SaveTruth onto a block — an **original pixel reinterpretation**
  (per-species archetype + primary/secondary type color + a signature feature;
  procedural archetypes cover the long tail).
- Snap → **autonomous battle with REAL teams/stats/mechanics** across the
  two-block arena (reuse what's built: snap detection, the BLE relay, the
  30-wide arena, seam-crossing, the projectile/faint FX).
- **Embodied SaveTruth**: shiny → shimmer, friendship → leans into touch,
  **status as LED language** (burn flicker, sleep pulse, poison throb).
- Later: **battle royale** (>2 blocks via the N-device topology we decode) and
  **trading** (a mon walks across the seam).

**Deliverable:** snap two blocks → your actual team battles by the real rules.

## Phase 5 — The companion (MultiVera grounded chat)

**Goal:** your Pokémon *talk to you*, grounded in your save, facts sacred.

- Reuse the MultiVera engine: a **lore KB** (species/region/character identity,
  *reinterpreted*), RAG + intent routing, prompt builder that injects SaveTruth
  before lore, **grounded chat** with your mon (and rival/professor), truth
  audit, deterministic fallback (Spark mode). LLM backends: OpenRouter / Ollama
  / your own server, per the other Veras.
- Physical **"meet across saves"**: two blocks, two saves → grounded cross-save
  dialogue (the Council / Two-Save Divergence, physicalized).
- **Original product name** here (Undertale's is "Ember"); the phone chats, the
  block is the body.

**Deliverable:** a save-aware Pokémon companion with a body and a voice.

## Phase 6 — Depth & polish

Later gens (data + mechanics versions), more abilities/items, optional **human
control** (gesture move-select on your block; other side autonomous), original
sprite/flavor art passes, more of the arena FX, sound/cries (original),
performance/BLE budget for many blocks.

---

## Architecture at a glance

```
 build time (Python)         phone (Kotlin, offline)              optional (Python)
 ┌───────────────────┐       ┌──────────────────────────────┐    ┌──────────────────┐
 │ build_pokedex.py  │  ──▶  │ bundled gen3 dataset (assets)│    │ MultiVera backend│
 │  (PokéAPI scrape) │       │ battle engine (Pokemon.kt++) │    │  lore RAG + LLM  │
 └───────────────────┘       │ Gen3SaveParser → SaveTruth   │◀──▶│  grounded chat   │
                             │ block scenes (snap/relay/arena)│    └──────────────────┘
                             └──────────────────────────────┘
                                     ▲            │
                                 real save    two blocks
                                  (.sav)      (snap → battle)
```

## The non-negotiable verification gate (every phase)

1. Parser truth check. 2. Storage truth check. 3. Engine/prompt-input truth
check. 4. Live output QA (battle result / character answer) against reference.
5. Diff/build/test. If a fact can't be inspected end-to-end, it isn't trusted.

## Legal / covenant boundaries (baked in, not bolted on)

- **Facts** (stats, types, movepools, mechanics) — usable as data.
- **Expression** (dex text, sprites, music, names/trademarks) — **original
  reinterpretation only**; nothing ripped or copied.
- **Saves** — the player's own, read-only.
- **Product** — original name; personal/fan project.

## Milestones

- **M1** Phase 1 dataset + tests (the scrape).
- **M2** Phase 2 engine completeness, validated vs reference.
- **M3** Phase 3 Gen-III parser → SaveTruth + truth audit.
- **M4** Phase 4 real teams battling on the blocks.
- **M5** Phase 5 grounded companion chat + product name.
- **M6** Phase 6 depth (royale, gens, human control).

## Decisions needed from Rod

1. **Product name** (the "Ember" of this one).
2. **Confirm Gen III** as the v1 target (saves + mechanics).
3. **Companion runtime**: self-contained Kotlin battles on the blocks + a
   separate Python MultiVera backend for chat — confirm that split (vs. trying
   to do chat on-device).
4. **Where the code lives**: extend `clawdpad-app` (blocks/engine) and stand up
   a `pokemon-vera` repo for the MultiVera backend? Or one monorepo?

Recommended first move once confirmed: **build Phase 1** (the scrape) — it
unblocks everything and is low-risk.

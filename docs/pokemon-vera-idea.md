# POKÉMON VERA — design seed (MultiVera on the blocks)

> **Status 2026-07-21:** first real code landed — an **autonomous Gen-III
> battle engine** (`Pokemon.kt`: accurate base stats, 17-type chart, Gen-III
> stat + damage formulas, real moves, physical/special split by type, AI on
> both sides) and `PokeBattleScene.kt` (a fully autonomous battle across the
> two snapped blocks — moves fly across the seam, real HP/faint, no human
> control). Snapping two blocks now runs a real battle between two sample
> species. 49 unit tests pass (stat formula verified exact: Charizard
> maxHp 153 / Spe 120). NEXT: feed the combatants from parsed **save data**
> (the actual Vera pipeline below), and later optional human control.



**Corrected framing (2026-07-21).** This is a **MultiVera** app first, a toy
second. Like Ember (Undertale) and the FFT Vera: read the player's **real save
data**, normalize it into **SaveTruth**, and let the characters — here, your
own Pokémon — talk to you **grounded in your actual playthrough**, under the
covenant **"facts are sacred, feelings are free."** The Lightpad block is a new
*embodiment* for that engine: your save-aware Pokémon gets a **body** that lives
on your desk. Battles are the "feelings are free" candy layer, not the point.

Fits the MultiVera "add a new game" pattern (see fft-psx-vera
`GAME_VERA_TEMPLATE.md` / `GAME_VERA_BLUEPRINT.md`): save parser → SaveTruth
schema → truth-audit endpoint → grounded character prompt → verification gate.
Product should carry an **original name** (as Undertale's is "Ember"), read
**your own** saves, bundle **no copyrighted assets** (original pixel
reinterpretations on the glass = the covenant's "original reinterpretation").

---

## The four truths (per the blueprint), for Pokémon

1. **Save truth** (sacred, wins): your party & boxes — species, **nickname,
   level, real moves, IVs/EVs, nature, ability, OT name/ID, shininess, Poké
   Ball, met location + date, friendship**; badges; Pokédex seen/caught;
   playtime; money; trainer name/ID; rival name; Hall of Fame; current
   location; major flags.
2. **Game-system truth**: type chart, movepools, stat/damage formulas — curated.
3. **Lore truth**: species identity, professor/rival/gym personas, phase-locked
   knowledge.
4. **Conversation truth**: the chat so far.

If SaveTruth doesn't contain a current-state fact, the model isn't trusted to
know it. Unknown → `null`; ambiguous → `undetermined`. A **Save Truth Audit**
panel exposes every parsed fact so hallucinations show up as data-pipeline bugs.

## Which generation? (the real decision)

Save-format richness vs. parse difficulty vs. docs — through the "how many
*sacred facts* can we ground on" lens:

| Gen / platform | Sacred-fact richness | Parse difficulty | Verdict |
|---|---|---|---|
| **Gen 1** — R/B/Y (Game Boy) | Thin: species/level/moves/DVs/badges/dex, **no natures, no abilities, no held items** | **Easiest** (flat, well-documented) | Nostalgia + simplest first parse, but least for the voice to grab |
| **Gen 3** — Ruby/Sapphire/**Emerald**/**FireRed** (GBA) | **Rich**: natures, abilities, real IVs/EVs, moves, met loc+level, friendship, ribbons, PID-shininess, OT | Medium (block-shuffle + section checksums) but **famously documented** (Bulbapedia + PKHeX as reference) | **Recommended.** The sweet spot: max sacred facts, flat emulator `.sav`, low RE risk |
| **Gen 4/5** — DS | Richest | **Encrypted** per-mon (PRNG + block shuffle) — hardest | Save for later; don't start on the crypto |

**Recommendation: start on Gen 3 (GBA), Emerald or FireRed.** It's the "facts
are sacred, and there are *so many* sacred facts" generation — natures,
abilities, IVs, met-date, friendship all make your Charizard *specifically
yours*. Gen 1 is the romantic minimal target (great for a Spark-mode-tier first
slice) but the voice has less to stand on. DS is richest but the encryption is a
whole project — earn it later.

## The block as embodiment (where the hardware earns its place)

MultiVera has always lived on a screen. The block gives a save-aware character a
**body** — this is the genuinely new thing:

- **Embodied SaveTruth.** The creature *is* a truth display: shiny → it
  shimmers, high friendship → it leans into your touch, its real nature/level
  shape its idle. You can hold your save's facts in your hand.
- **The covenant as visual law.** Sacred facts render one way (steady, true);
  free voice/personality is the expressive animation layer. You can *see* the
  two-bucket wall.
- **Petting = the bond**, and it can reference sacred facts ("you caught me at
  [met location] on [met date] — I remember").
- **Snap two blocks = two save-aware characters MEET.** Not (only) a battle —
  a *meeting* of two real SaveTruths: your Pokémon and a friend's (their save),
  grounded cross-save dialogue. This is the Council / two-phone version.
- **"Across Your Saves," physical.** Two blocks, two of *your* saves → the same
  starter speaks to the divergence ("in this file I'm your Champion; in that one
  you boxed me at level 5"). Two-Save Divergence you can arrange on a table.
- **Chronicle / Report Card on the glass** — a save-grounded scroll.
- **Battles / trades** are the free-layer candy, and the CLAWD COMBAT arena
  (snap → shared field, seam-crossing, gore→faint) is ready to host them — but
  they *express* the companion, they aren't the product.

## Reuse from what's built (CLAWD COMBAT)

Snap detection (topology), the BLE relay to a 2nd block, the roaming two-block
arena + seam-crossing, touch decode + gestures, the creature renderer + particle
system, ClawdState persistence. The hardware/embodiment plumbing exists; the
**new work is the MultiVera pipeline**: Gen-3 save parser → SaveTruth →
truth-audit → grounded chat (phone does text/voice; block is the body).

## First slice (Vera-true)

1. Gen-3 `.sav` parser → SaveTruth for the **party** (verified through a truth
   audit), read-only.
2. Import a save → **summon one real Pokémon** onto a block (original pixel
   reinterpretation), idling as an embodied-truth avatar.
3. **Grounded chat** with it on the phone — it knows its own sacred facts, voice
   is free — with the audit panel proving every fact.
4. Then: two blocks → two save-aware creatures *meet* (grounded), and only after
   that, the optional battle/trade candy.

The verification gate is non-negotiable (parser → storage → prompt → live QA →
diff/test) — same as every Vera. See fft-psx-vera `GAME_VERA_BLUEPRINT.md`.

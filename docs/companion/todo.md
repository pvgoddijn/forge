# Companion Mode — open items

_Last updated 2026-11-20._

## Decisions waiting on the owner
- [ ] **Commander damage on load.** Every load (resume or undo) now clears commander damage along with tax (`Player.resetCommanderStats()`), because the saved game never held that damage and it pointed at removed cards. Tax is restored from `CompanionGameState`; damage is not. Keeping damage needs it saved the same way plus a small `Player` change. Commander damage tracking is out of scope for this version, so this only matters once it is added.
- [ ] **Slice 2 (zones and hand):** deferred; decide whether to drop or rework it.

## Slice 8 (quality pass)
- [x] "Keep photos for training" setting (e97d8597933): saves photo.jpg + run.json + labels.csv under `companion-captures`; pull with `adb pull /sdcard/Android/data/forge.app.companion/files/companion-captures`. Phone check pending; turn auto-add off while collecting.
- [ ] Owner takes a new photo batch with "Keep photos for training" on (labelled by the add itself); pull it with `adb pull`. The old camera-PoC captures (`phone-captures-2`) are not used (owner's call, 2026-11-07).
- [ ] Rerun `CardNameBenchmark` on the new set with crop-only reading; record in `slices/08-quality-pass.md`.
- [ ] Measure the auto-add threshold (placeholder 0.50 / lead 0.30, unmeasured; needs ~300 confident reads with none wrong for 1 in 100).
- [ ] AC-32 still missed (phone top-3 73.2 % vs 90 %). Most misses are blurry evening shots: tighten the capture gate (shoot only when steady and sharp; prompt "hold still" / "more light").
  - Gate logic in forge-card-ocr (`SharpnessGate`, `TitleSharpness`). Wired into `AssistedPipeline` (auto capture needs both gates; the shutter is never held back), the status line says "Hold still" / "More light", and the gate's last decision (verdict, timedOut, laplacianVariance, meanLuma, cornerSpread, exposureNs, trigger) goes into a kept photo's run.json as `captureGate`. Phone check pending. Title-band sharpness threshold 300 calibrated on the 55 phone captures (photo downscaled to 640x480): all 16 misses at or below 379, holds back 13 of them and 4 of 39 good reads; recheck on real analysis frames.
- [ ] Time photo → suggestions on the phone (target 2 s).
- [ ] Airplane-mode full 4-player Commander game check.
- [x] Push slice 8 and open its PR on the fork, stacked on slice 7.

## Core cleanup (done 2026-11-07, see `core-footprint.md`)
- [x] D1–D8 folded into their PRs; PRs #4–#11 force-pushed. Core diff 674 lines/28 files → 362/21.
- [ ] Manual check on the PC test build (`-Pcompanion-photos`): Companion game menu (no Concede), right-click Move… then Zoom, life tap, 2+ seats prompt note, End game… closes, plain hot-seat afterwards shows no Companion items.
- [x] Build without `-Pcompanion-photos` but run with `-Dforge.companion.photos=true` silently has no camera; log a warning.
- [ ] Owner: keep the "Core footprint" plan section folded into the early plan commit, or move it later?

## Proposed slices (owner to confirm)
- [ ] **Slice 9, virtual deck** (`companion-additional-features.md`): a player without their paper deck plays a Forge deck from a seeded, deterministic library. Spec says one phone and one operator, so: the virtual player's phone runs a "my deck" mode with the decklist and a seed agreed at setup; the operator logs draws like for a paper player and adds casts from hand. Shuffles, tutors, scry, mill, look-at-top-N and mulligans must all go through the seed, so it is a full slice.

## Phone checks not yet reported
- [ ] Commander prompts at Start; off-colour card → "Move to …", Retake, Cancel.
- [ ] Life tap with Ajani's Pridemate asks about the trigger.
- [ ] Main 1 combat prompt / "Ready creatures…".
- [ ] Sorcery logged outside a main phase is cast in Main 1 or Main 2.
- [ ] Instants offered only to players who could cast them, plus "Show all players".
- [ ] Long-press Move…, "Wrong player…", one-tap End Turn undo, no Concede.
- [ ] History with per-player filter; End game → Past games.
- [ ] Auto-add toggle and its Undo prompt.
- [ ] Coin flips and reveals show once (fixes are on slice 7, not yet on the phone).

## Known gaps
- [x] Finish-game and ante reveals no longer repeat once per seat on a shared screen (slice 8): a seat's face-down cards revealed when it leaves or the game ends went to seats already out of the game, which broke the "same run" order (it counted only seats still in); the run now follows every seat's order. The ante cards removed at the start (`revealAnte`) show once, naming each deck's owner.
- [x] The other start-of-game fan-outs show once on a shared screen too (slice 8): the cards the AI can't play (`revealAISkipCards`, via `GameAction.revealUnplayableByAI`) and the unsupported cards removed from the decks (`revealUnsupported`, a list dialog), each naming the deck's owner.
- [x] Undo and resume keep the spells cast this turn (slice 8): the storm count, each player's count and what was cast (`CompanionGameState`'s `p0spellcastthisturn=Original|Divination` lines, one per spell in cast order). Each is rebuilt as a new card of that name, on the face it was cast as, and its spell; what was true only of that cast (mana paid, X, targets) isn't kept, and a restored spell doesn't count again toward the spells cast this game. The spells cast last turn aren't saved, so an undo across a turn boundary keeps the engine's.
- [ ] Undo does not restore until-end-of-turn effects: a known limit (plan 2.3).
- [x] Split/adventure/MDFC sorcery halves now wait for a main phase (slice 8). A single face's name (Heart's Desire, Flamethrower Sonata, Memory) decides from that face alone, as only that face is cast; a land face (Agadeem, the Undercrypt) is played, not cast, and doesn't wait. The whole card's name counts every face it can be cast as: it waits when one is a sorcery and none is instant/flash, so Lovestruck Beast waits (either face) and Bonecrusher Giant doesn't.
- [x] A split card's half picked by its own name ("Fire", "Ice", "Commit", "Grind") is cast as that half (slice 8): `TableEditor.addCard` no longer calls `CardUtil.turnToRightFace` for a split half (it turned even the left half's name to `RightSplit`), keeps the card whole and offers only the named half's spell. An aftermath half ("Memory") still can't be cast from a hand, by the rules: the add fails with "couldn't be cast" and the hand is left as it was; it waits for a main phase first, as a sorcery. From hand, a known card is also found by one face's or half's name (a known Fire // Ice for "Fire", a known Lovestruck Beast for "Heart's Desire", a known Agadeem's Awakening for "Agadeem, the Undercrypt") and cast or played as that face; a failed add turns it back whole in the hand.
- [ ] Topsy Turvy phase order not handled by sorcery parking: the Main 1/Main 2 target ("before combat") and `parkedReady`'s `isAfter` assume normal order. Not trivial: the engine has no "next main phase" API, and `PhaseType.isBefore(_, true)` reverses steps within a group while `PhaseType.getNext(_, true)` doesn't, so the reversed turn order would have to be modelled here.
- [x] Card picker: a back-face name of a double-faced card ("Insectile Aberration") now also offers the card itself ("Delver of Secrets"), right after the back face (slice 8). The back-face names were already offered; split/adventure halves unchanged.
- [x] Cancel in the commander step no longer drops every seat's choice (slice 8): the step still ends with nothing started, but the seats already done keep their commanders, so the next Start only asks them to confirm (as a deck's commanders are) and picks up at the cancelled seat. A seat's choice moves with it when rows swap and is dropped when its deck changes (`CommanderChoices`).
- [x] Uncommitted camera-PoC docs from `feature/companion-mode` reviewed and folded in here (slice 8).

## From the core cleanup (D2, 2026-11-07)
- [ ] Unknown (placeholder) cards keep only Tapped, SummonSick, FaceDown and Counters across a save; links from known cards to them (remembered, imprinted, chosen, attached, attacking, ExiledWith) are dropped on load.
- [ ] Possible upstream bug: a manifested/cloaked card loaded from a GameState has an EmptySa with no card state → NPE in `Card.getFacedownImageKey`.

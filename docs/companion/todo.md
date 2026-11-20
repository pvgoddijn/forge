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
- [ ] Finish-game and ante reveals still repeat once per seat on a shared screen.
- [ ] Undo does not restore storm counts or until-end-of-turn effects.
- [ ] Split/adventure/MDFC sorcery halves don't wait for a main phase; Topsy Turvy phase order not handled.
- [ ] Card picker omits back-face names of double-faced cards; cancel in the commander step drops every seat's choice.
- [x] Uncommitted camera-PoC docs from `feature/companion-mode` reviewed and folded in here (slice 8).

## From the core cleanup (D2, 2026-11-07)
- [ ] Unknown (placeholder) cards keep only Tapped, SummonSick, FaceDown and Counters across a save; links from known cards to them (remembered, imprinted, chosen, attached, attacking, ExiledWith) are dropped on load.
- [ ] Possible upstream bug: a manifested/cloaked card loaded from a GameState has an EmptySa with no card state → NPE in `Card.getFacedownImageKey`.

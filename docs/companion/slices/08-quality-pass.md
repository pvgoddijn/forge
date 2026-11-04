# Slice 8 — Quality pass

**Depends on:** every slice that ships, because AC-33 plays a whole game with all of them. Its own measurements need less:
- **AC-31 (speed) and AC-32 without a deck:** slice 1's pipeline only (`forge-card-ocr`, `OcrCardNameReader`, `CardNameMatcher`).
- **AC-32 with a deck:** slice 6's deck ranking (`CardNameMatcher` with a preferred set, merged with the reader's names).
- **AC-33 (offline):** slices 0–7 as built. Slice 2 is deferred; if it's still not built, the run covers what is, and the report says what wasn't there.

So the accuracy and speed work can start right after slice 6, in parallel with slice 7. **Proves:** the spec's numbers on real hardware, or the gap with data.

## Result
The numbers in the spec are measured on real hardware and met, or the gap is reported.

## Acceptance criteria
- **AC-31** WHEN a capture is taken on a mid-range Android phone THE SYSTEM SHALL show suggestions within 2 s.
- **AC-32** WHEN tested against the reference photo set (normal indoor light, English cards, sleeved and unsleeved) THE SYSTEM SHALL include the correct card in the top 3 suggestions for ≥ 90 % of photos with no loaded deck, and ≥ 98 % when the card's deck is loaded.
- **AC-33** WHILE the device has no network connection THE SYSTEM SHALL support every v1 feature. At most, card artwork may fall back to a text rendering.

## Build
Small chunks, in order. Each is one or two files.

1. **`CardNameBenchmark` (new, `forge-card-ocr` test sources, not run in CI):** run by hand with `-Dforge.companion.benchmarkPhotos=<dir>`. Reads each photo's correct name from its file name or a `names.csv` beside it, runs the real pipeline (`OcrCardNameReader`: card finder, name band, PP-OCR, matching against Forge's card faces) and prints top-1 and top-3 accuracy, failures with what was read, and time per photo. Skips itself when the property isn't set, like the reader's real-model tests.
2. **`CardNameBenchmark` with decks:** a `decks/` folder of decklists, one per photo group. For those photos, rank with slice 6's deck merge and print the second accuracy figure.
3. **Reference set (taken by you):** about 200 photos of real cards with correct names, taken with the app's Assisted capture (slice 1) on the phone, so they look like what the reader really gets. Sleeved and unsleeved, normal indoor light. Photos stay out of the repo unless their licensing is clear. Spec A5.
4. **Timing, `AndroidCardPhotos` (forge-gui-android):** log the time from shutter to suggestions shown, split into capture, decode and read, with the models already loaded, and the first read (model loading) on its own line.
5. **Tuning, in `forge-card-ocr`:** the finder, name band crop, preprocessing and matching against the benchmark's failures. Fix failures rather than removing hard cards from the set.
6. **Engine costs, `CompanionTable` (forge-gui):** log how long each `save` takes and how big the snapshot is, and how long slice 4's undo takes (plan risk "a snapshot per op"). Measured on the phone over a real game.
7. **APK size:** report the increase per ABI. If it's too large, propose ABI splits in `forge-gui-android`'s build to the owner rather than doing them unasked (slice 1: about +44 MB for arm64).

## Oracle
1. The benchmark prints top-3 accuracy with and without loaded decks, from a stated command. At least 90 % and 98 %.
2. 📱 Timed on a named mid-range phone: 2 s or less from photo to suggestions, measured with the models loaded, and the first capture's time reported separately.
3. 📱 In airplane mode with the card image cache emptied: play a whole 4-player Commander game using every built feature: photo and typed adds, casts with targets, End Turn, a sorcery logged outside a main phase, combat through the engine for one turn, life changes, undo, a correction, a turn skip from Commander mode, auto mode, history, end game, a past game. Nothing fails, and card art falls back to text at worst. This also finishes slice 1's open airplane-mode check.
4. 📱 Kill the app mid-game during step 3, and relaunch: same state.
5. 📱 Report the APK size increase, and the save time and snapshot size from chunk 6.
6. 📱 6 players on the phone: every board readable (AC-23), or the gap reported.

**If a target is missed:** report it with the data, and let you decide. Do not lower a target or adjust the photo set to pass.

## After this slice
ADLC phase 6 (Learn): capture what was learned in `session-insights.md` or a project rule.

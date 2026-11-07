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
   - **Command** (from the repository root; several folders separated by `:`, each reported alone and then together):
     ```
     mvn test -pl forge-card-ocr -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CardNameBenchmark \
         -Dforge.companion.benchmarkPhotos=<folder>[:<folder>...] [-Dforge.companion.benchmarkDecks=<decks folder>]
     ```
     The summary lines start with `BENCH`. The name ends in `Benchmark`, so surefire's default includes never run it, and it skips itself without the property.
   - **Labels:** a `names.csv` or `labels.csv` in the folder, one row per photo: the photo (a file, or a subfolder holding one photo such as a camera PoC capture run's `photo.jpg`), the card's name, and optionally a group. A header names the columns (`file`/`photo`/`run`, `name`/`card`, `group`); without one they're in that order. `NO_CARD` or an empty name is a photo without a card, reported on its own. Without a csv, every photo in the folder and its subfolders, named after its card (`ashnods-altar.jpg`, `fire-ice-2.jpg` for a second copy), its group being the subfolder.
   - **Suggestions counted** are what the photo dialog lists (`CardPicker`): `mergeReading` of the reader's names and the matches for the text read when the read is confident, else the name search on the text read. It also prints the reader's own list, the reader's best score for right and wrong confident reads by bucket, and the auto-adds at `AutoAddPolicy.THRESHOLD` and `MIN_LEAD` with each wrong one. Photos are read from the file with no camera outline, as the desktop dev build reads a file.
   - **First run, 2026-11-07** on the dev PC (WSL2), the OCR spike's photos (`/mnt/wsl/git/forge-ocr-spike/work/oriented`, `.../phone-captures`), with one 100-card Boros decklist holding all their cards as `default.txt`: 30 oriented photos top-1 96.7 %, top-3 96.7 %; 56 phone captures of cards top-1 73.2 %, top-3 75.0 %; together (N = 86) top-1 81.4 %, top-3 82.6 %, the same with the deck. Median read 461 ms, p90 685 ms with the models loaded; the first read 1.1 s. At 0.50 / lead 0.30: 51 auto-adds, none wrong (95 % bound about 6 in 100); the 5 photos without a card read nothing confident. The 309 synthetic photos with a known name: top-3 88.3 %, 4 wrong in 232 auto-adds (1.7 %). **AC-32 is not met yet** (75 % and 83 % against 90 % and 98 %); the phone captures' misses are mostly titles read as nothing or a few letters, which a deck can't help.
2. **`CardNameBenchmark` with decks:** a `decks/` folder of decklists, one per photo group. For those photos, rank with slice 6's deck merge and print the second accuracy figure.
   - **Mapping:** a photo's group is its csv `group`, else its subfolder, else `default`; its deck is `decks/<group>.txt` beside the photos, or in the folder `-Dforge.companion.benchmarkDecks` names, in any format `DeckListNames` reads. Photos with a deck are ranked again with its cards preferred (`CardNameMatcher.match` and `mergeReading` with the deck set) and counted for the "with deck" line. The first run's decklist isn't in the repository, as the photos aren't.
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

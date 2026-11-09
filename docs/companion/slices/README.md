# Companion Mode — Slices

_ADLC phase 3 (Breakdown). Each file is self-contained: an implementer reads only that file, plus the code it names. [`../spec.md`](../spec.md) and [`../plan.md`](../plan.md) are the source if a bundle seems wrong. Fix the bundle rather than guessing._

| # | Slice | User-visible result | Needs | ACs |
|---|---|---|---|---|
| S | [OCR spike](S-ocr-spike.md) | Engine decision with numbers (not shipped) | About 30 photos from you | evidence for 7, 31, 32 |
| 0 | [Walking skeleton](00-walking-skeleton.md) (redone on the engine) | Start a real engine game, add a card by name and see its effects, survive a restart | — | 1, 2, 5, 14, 15, 16, 29, 34, 40; part of 17, 23, 30 |
| 1 | [Photo capture](01-photo-capture.md) (built from the camera PoC) | Photograph a card, pick from suggestions | 0 | 6, 7, 8, 11, 13 |
| 2 | [Zones and hand](02-zones-and-hand.md) | Cards go to the right zone, hand counts stay right | 0 | 18–23, hand part of 5 |
| 3 | [Turns and life](03-turns-and-life.md) | Next turn runs its effects, life totals, default target player, responses, optional combat | 2 | 17, 24, 25, 35, 36, 37, 38, 39, 41, life part of 5 |
| 4 | [Undo and correction](04-undo-and-correction.md) | Undo, and fix a card logged to the wrong player | 3 | 22a, 27 |
| 5 | [History](05-history.md) | Browse the history, end a game, view past games | 4 | 26, 28, 30 |
| 6 | [Decks and commanders](06-decks-and-commanders.md) | Loaded decks rank first, commanders start in Command | 2, and 1 for photo ranking | 3, 4, 12 |
| 7 | [Auto mode](07-auto-mode.md) | Setting to add confident matches at once | 1, 4 | 9, 10 |
| 8 | [Quality pass](08-quality-pass.md) | Targets measured and met on a real phone | all | 31, 32, 33 |

Every AC in the spec maps to a slice above.

## Rules for every slice

- **Branch:** stacked. Slice N+1 branches from slice N's branch.
- **Done locally:** it compiles, the slice's tests are green, and its oracle was observed running, not inferred.
- **Then Gate A:** CI green on the fork (`test-build.yaml`, `test-android-build.yml`, `ios-compat-gate.yml`). Halt if red.
- **Then Gate B:** an independent reviewer, not the author, checks it against the spec.
- **Engine (2026-11-03):** slices 0 and 2 are redone on Forge's rules engine (spec Q20, `plan.md` section 0). Undo (slice 4) restores snapshots (Q22).
- **Tests:** only engine-op rules (through the real main loop) and matcher behaviour go in CI. No UI or wiring tests (`CONTRIBUTING.md`).
- **PRs:** note AI authorship. Nothing goes to Card-Forge/forge until you and the maintainers have agreed.
- **Setup:** Java 17 release level, Maven, no new database. Follow Forge's code style, not the workspace Java 26/Gradle/JOOQ defaults.
- **Phone checks:** marked 📱. They need you to install the APK and report the result. A slice isn't done until they pass.

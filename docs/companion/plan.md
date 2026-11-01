# Companion Mode — Plan

_ADLC phase 2 (Plan). This is the **how** for the approved [`spec.md`](spec.md). `AC-n` refers to its acceptance criteria._

Status: **approved** · 2026-10-31

## 1. Approach

Three independent parts, joined by narrow interfaces:

```
 Screens (forge-gui-mobile)  ──►  Game log + replay (forge-gui)  ──►  JSONL file per game
        │
        ├─►  IDeviceAdapter.captureCardPhoto()      camera on Android, file chooser on dev build
        ├─►  CardNameReader                         photo → text (offline OCR)
        └─►  CardNameMatcher (forge-gui)            text → ranked suggestions from CardDb
```

Key decisions:

1. **The game is an append-only action log, and state is computed by replaying it.** History (AC-26), undo (AC-27), resume (AC-29) and the "wrong player" correction (AC-22a) all come from this one mechanism instead of four separate features. Undo and correction are themselves actions. Replay skips an undone action, and uses the corrected player for a reassigned card. This also gets AC-22a's "as if the card had never left" right, including when the hand count was clamped at 0 (AC-19), and it suits later sync.
2. **The tracker does not touch the rules engine, but it does reuse the match interface** (Q5). No `Game`, `HostedMatch` or running match. The game screen shows real `VPlayerPanel` boards, fed read-only views built from the replayed state.
3. **Name-only recognition** (Q10): the guide frame crops the name band, OCR reads it, and fuzzy matching against `CardDb` picks the card. No image index.
4. **OCR is chosen by measurement, not assumption.** See section 4.

## 2. Components

### Domain: `forge-gui`, package `forge.gamemodes.companion`
No UI or libGDX, so it can be tested on a plain JVM. This module already has TestNG, AssertJ and Gson.

| Type | Role |
|---|---|
| `CompanionAction` | Sealed interface with records: `GameStarted`, `CardAdded`, `CardMoved`, `HandCountChanged`, `TurnPassed`, `LifeChanged`, `PlayerCorrected`, `ActionUndone`, `GameEnded`. Each has a sequence number and timestamp. |
| `CompanionState` | Immutable result of a replay: players, zones, hidden counts, known cards, turn, life. Cards have per-game instance IDs, so two copies stay distinct. |
| `CompanionGame` | Holds the log. `apply(action)`, `undo()`, `state()`, `history()`. Applies the AC-18 to AC-21 rules. |
| `CompanionStore` | One file per game at `ForgeConstants.USER_DIR/companion/<gameId>.jsonl`. Appends and flushes one line per action. Ignores a torn last line. A game with no `GameEnded` is the active one (AC-30). |
| `CardNameMatcher` | Input: OCR or typed text, plus an optional deck. Output: ranked `Suggestion(card, confidence)`. Indexes `CardDb.getAllFaces()`, so either face of a double-faced card matches (AC-13). Uses `commons-text` Levenshtein (already in `forge-core`). Deck cards rank first, with fallback to all cards (AC-12, AC-15). |

### Capture: `IDeviceAdapter` plus each backend
- Add `default void captureCardPhoto(Consumer<File> onResult)` to `forge-gui/.../IDeviceAdapter.java`. The default reports "unsupported", so the iOS backend needs no changes.
- **Android:** a new `CardCaptureActivity` in `forge-gui-android`, using the platform Camera2 API with a preview and an on-screen guide showing where to put the card (AC-6). Add the `CAMERA` permission and the activity to the manifest. The app asks for permission when capture is first used, and a refusal sends the operator to the name search. `Main` starts the activity and receives the result through `onActivityResult`.
- Why not the system camera app: it can't show our guide, every capture leaves the app, and the guide is what makes cropping reliable. Camera2 adds no dependency.
- **Dev build:** `forge-gui-mobile-dev` opens a file chooser, so all of this can be tested without a phone.

### Recognition: new module `forge-card-ocr`
- `CardNameReader` interface (in `forge-gui`): `OcrResult read(File photo)`, returning text and confidence.
- A new module holds the implementation, and only `forge-gui-android` and `forge-gui-mobile-dev` depend on it. **`forge-gui-mobile` must not**, because that would pull native OCR code into the iOS build, which v1 doesn't target. The backend passes the reader in, the same way as the adapter.
- Runs off the UI thread. The model loads on first capture, and photos are deleted after reading.

### Screens: `forge-gui-mobile`, package `forge.screens.companion`
| Screen | Notes |
|---|---|
| `NewGameMenu.NewGameScreen.Companion` | New enum entry, using an existing skin icon (AC-1). Resumes the active game if there is one. |
| `CompanionSetupScreen` | Players, seat order, Me, deck and commander (AC-2 to AC-5). Reuses `FDeckImportDialog`. |
| `CompanionGameScreen` | A scrolling list of real `VPlayerPanel` boards, one per player (AC-23), plus next turn, life, undo, and capture and add-by-name buttons. |
| `CompanionBoard` | Copies a `CompanionState` into `PlayerView` and `CardView` objects. Hidden hand cards are card backs in the Hand zone. Holds no game state. |
| `CompanionController` | The `IGameController` the panels send taps to, registered as the match controller's spectator. A card tap moves the card; a player or hidden-card tap changes the hand count. |
| `CardSuggestionDialog` | Suggestions, target player, from/to zones, and search and cancel (AC-8, AC-17, AC-18). |
| `CardSearchDialog` | Type-to-search using `CardNameMatcher`. Partial OCR text can pre-fill it (AC-11, AC-14 to AC-16). |
| `CompanionHistoryScreen` | Filter by player. Also opens ended games read-only (AC-28, AC-30). |

- Card drawing comes with the panels. Board cards use `CardFactory.getCard` with the per-game instance ID, not `CardView.getCardForUi`, which shares one card per printing with ID -1 and so would merge copies.
- Match view changes stay small: null guards for a missing `MatchScreen`, `VPlayerPanel.showOnlyZones`, and `CardFlightOverlay.disable`. Entering the screen resets the match controller and card panels, because a finished match leaves both behind.
- The auto/confirm setting is a new `FPref` (AC-10).

## 3. Files touched outside new code

`IDeviceAdapter.java` · `NewGameMenu.java` · `ForgePreferences.java` · root `pom.xml` (new module) · `forge-gui-android`: `pom.xml`, `AndroidManifest.xml`, `Main.java`, `proguard.cfg` · `forge-gui-mobile-dev`: `pom.xml` and its adapter · localization strings.

Existing game modes are not changed.

## 4. Spike first: choose the OCR engine

The largest risk is whether OCR is accurate and fast enough on real phone photos. Before any slices, run a **throwaway** benchmark on the dev machine with a first batch of about 30 photos.

| Candidate | For | Against |
|---|---|---|
| **ONNX Runtime + PP-OCR model** (lead candidate) | ONNX Runtime has a Java API on desktop and Android, and an AAR on Maven Central under MIT. PP-OCR is built for natural photos. | Largest native library. Model licence and conversion still to confirm. |
| **Tesseract** (`Tesseract4Android`) | Mature, small language data. | Built for scanned pages, so it may struggle with photos. Distributed through JitPack, not Central. Licence to confirm from its repo. |

Measure: top-3 accuracy through `CardNameMatcher` (AC-32), time per photo, and APK size increase per ABI.

**Pass:** at least 90 % top-3 on the batch, and a clear path to 2 s on a phone. **If neither passes:** stop and revisit the spec with you, rather than quietly weakening AC-32.

Sizes, versions and licences above are not confirmed yet. The spike confirms them and records them in this file. The model file ships inside the APK.

## 5. Traceability and verification

| AC | Delivered by | Verified by |
|---|---|---|
| 1, 2, 3, 4, 5 | Setup screen, `GameStarted` | Run on the dev build. Replay test for starting state. |
| 6 | `CardCaptureActivity` | Run on a real phone. |
| 7, 13 | Reader + matcher | Benchmark photo set, airplane mode. |
| 8, 9, 10, 11 | `CardSuggestionDialog`, `FPref` | Run on the dev build with image files. |
| 12, 14, 15, 16 | `CardNameMatcher`, `CardSearchDialog` | Matcher tests: typos, deck ranking, fallback, tokens. |
| 17, 18, 19, 20, 21, 22, 22a | `CompanionGame` | Replay tests, including the clamp and correction edge cases. |
| 23 | `CompanionGameScreen` | Run with 6 players on a phone screen. |
| 24, 25 | Actions + screen | Replay tests. |
| 26, 27, 28 | Log, history screen | Replay tests, plus a run. |
| 29, 30 | `CompanionStore` | Test with a truncated file. Force-stop the app on a phone. |
| 31 | Whole pipeline | Timed on a mid-range phone. |
| 32 | Whole pipeline | Benchmark with the full photo set, about 200 photos (A5). |
| 33 | All | Whole game in airplane mode with an empty image cache. |

**Tests:** `CONTRIBUTING.md` asks agents not to add unnecessary tests to CI. So CI gets only the replay rules and matcher behaviour, where a regression would be silent. No UI or wiring tests. The photo benchmark runs by hand, outside CI.

**CI gates:** `test-build.yaml` and `test-android-build.yml`, run on the fork. `ios-compat-gate.yml` must stay green too.

## 6. Risks

| Risk | Mitigation |
|---|---|
| OCR not good enough on photos | Spike runs first, with a stop condition. Falling back to typing is always available. |
| APK grows too much for upstream | Measure in the spike. Ask maintainers. Could limit native code to the arm ABIs. |
| Maintainers don't want the feature or dependency | **Ask before building**, with spike numbers in hand. If they decline, it lives on your fork, and this design doesn't change. |
| `android-maven-plugin` struggles with the new AAR or native libraries | `sentry-android` already comes in as an AAR, so it should work. Slice 1 proves it early. |
| ProGuard strips OCR classes used through JNI | Keep rules in `proguard.cfg`. Only testable in a release-style APK. |
| 6 players don't fit on a phone screen | Boards scroll, at a minimum height that keeps cards legible. About three fit per screen at phone proportions, so AC-23 at 6 players means scrolling. |
| Match views need a running match | Checked on the dev build, including before and after a real AI match. Still to check on a phone. |
| Replay slows down on long games | Hundreds of small actions should be trivial. Cache state in memory, and only replay fully on load or undo. |
| No phone reachable from this environment | This distro is isolated from Windows and has no Android SDK yet. The SDK can be installed here. APKs get copied out through `\\wsl.localhost` and installed by you, so **phone checks (AC-6, 29, 31, 33) depend on you**. |

## 7. Project-rule notes

`/mnt/wsl/git/CLAUDE.md` asks for Java 26, Gradle and JOOQ by default. This plan follows **Forge's existing setup** instead: Java 17 release level, Maven, no database. Android manifests and Maven poms are XML because the project requires it. `session-insights.md` stays out of commits.

## 8. Build order (detail comes in phase 3)

- **Spike:** OCR engine choice, then contact maintainers.
- **Slice 0, walking skeleton:** menu entry → two players → add a card by name → appears on the battlefield → survives a restart. Dev build first, then an APK.
- **Slice 1:** capture → OCR → suggestions on Android, proving the native build.
- **Then:** full zones and hand rules · turns and life · undo and correction · history and past games · loaded decks and commanders · auto mode · full benchmark and airplane-mode pass.

## 9. Sources

- [ONNX Runtime Android on Maven Central](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android)
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android)

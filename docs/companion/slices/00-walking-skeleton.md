# Slice 0 — Walking skeleton

**Depends on:** spike passed, maintainers asked. **Proves:** menu → screens → domain → disk, on the dev build and in an APK.

## Result
The operator opens Companion, enters players, adds a card by typing, sees it on that player's battlefield, closes the app, and finds it still there.

## Acceptance criteria
- **AC-1** WHEN the user opens the mode selection in Forge mobile THE SYSTEM SHALL offer a "Companion" entry.
- **AC-2** WHEN the user starts a new game THE SYSTEM SHALL let them enter 2–6 players with a name and seat order, and mark exactly one as Me.
- **AC-14** THE SYSTEM SHALL let the operator add a card by typing at any time, without having to capture first.
- **AC-15** WHEN the operator types at least 2 characters THE SYSTEM SHALL show matching card names that tolerate typos. (Deck ranking comes in slice 6.)
- **AC-16** THE SYSTEM SHALL let tokens and emblems be added by name.
- **AC-29** WHEN the app is closed, killed or the phone restarts during a game THE SYSTEM SHALL resume that game in exactly the state it was left.
- **Part of AC-17:** the operator picks the target player before confirming.
- **Part of AC-23:** every player's name and Battlefield are visible at once.
- **Part of AC-30:** one active game at a time, resumed when Companion opens.

## Build
**`forge-gui`, package `forge.gamemodes.companion`** (no UI or libGDX; TestNG, AssertJ and Gson are available):
- `CompanionAction`: sealed interface of records, each with sequence number and timestamp. This slice needs `GameStarted` and `CardAdded`.
- `CompanionState`: immutable result of replaying the log. Cards have per-game instance IDs.
- `CompanionGame`: `apply(action)`, `state()`. State always comes from replaying the log. This is the core design, so don't mutate state directly.
- `CompanionStore`: one file per game at `ForgeConstants.USER_DIR/companion/<gameId>.jsonl`. Append and flush one line per action. Ignore a torn last line. A game with no `GameEnded` is active.
- `CardNameMatcher`: indexes `StaticData.instance().getCommonCards().getAllFaces()`, plus tokens. Uses Levenshtein from `commons-text`. Returns ranked `Suggestion(card, confidence)`.

**`forge-gui-mobile`, package `forge.screens.companion`:**
- `NewGameMenu.NewGameScreen.Companion` enum entry with an existing `FSkinImage` icon. Add a `lblCompanion` string.
- `CompanionSetupScreen`, `CompanionGameScreen`, `CardSearchDialog`. Subclass `FScreen`, and use the `forge.toolbox` widgets.
- **Changed in slice 2:** `CompanionGameScreen` now shows real `VPlayerPanel` boards, as the owner asked to reuse the existing interface. See `plan.md` section 2.

## Check early
- Whether `CardRenderer` and `CardView.getCardForUi` work with no match running. They contain some `MatchController` references. Fix only what actually gets hit. The fallback is image plus name.
- Whether Gson handles the sealed records. Write a small type adapter if needed.
- Whether 6 players fit on a phone screen. If not, use compact rows with tap to expand.
- Whether matching about 30k names feels instant while typing.

## Tests (CI)
- Replay: the same log gives the same state; a torn last line is ignored.
- Matcher: exact name, typo, either face of a double-faced card, token, under 2 characters gives nothing.

## Oracle
1. On the dev build (`forge-gui-mobile-dev`): New Game → Companion → enter 3 players → add "Sol Rng" by typing → pick Sol Ring for player 2 → it shows on player 2's battlefield.
2. Kill the process and relaunch. Companion resumes with the card there.
3. Repeat with 6 players, and add a token by name.
4. 📱 The same on an APK from the `android-debug` profile. This needs the Android SDK installed here.

## Out of scope
Camera, other zones, hand counts, turns, life, undo, history screen, decks.

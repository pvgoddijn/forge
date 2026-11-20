# Companion Mode — core footprint cleanup

_Plan from the 2026-11-07 audit. Owner's note: "move more code out of the existing packages, make this as unintrusive as possible; findPaperCard shouldn't be in GameState"._

Base: `021447acf8b`. Before: 674 changed core lines in 28 files. Target: about 300 in 20, with core Java outside the mobile screens going from 235 lines to about 32.

## Approach
Each change is committed with `git commit --fixup=<introducing sha>` on `feature/companion-slice-8`, which contains the whole stack. Then `GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash --update-refs 021447acf8b` folds every fix into the PR that introduced the code. Each branch tip is verified, then force-pushed with lease. Backup refs: `backup/cleanup-<branch>`.

## Kept in core (A), generic and upstreamable
- `GameRules.firstSeatStarts` and its check in `GameAction.determineFirstTurnPlayer`: generalizes the Puzzle no-toss rule.
- `Player.setLifeGainedTimesThisTurn`: completes the existing setters.
- `HostedMatch.endedGuis`: `afterGameEnd` once per GUI, which fixes mobile hot-seat too. It goes in its own commit.
- The `MatchExtension` seam, the `VPrompt` action and bar height, `NewGameMenu` entry, `VGameMenu`, `VPlayerPanel.tapLife`, `MatchScreen` concede guard.
- Build files: root pom, android pom, proguard, manifest. `lblCompanion*` strings.

## Moves (B/C)
| ID | Change | Introduced in |
|---|---|---|
| D1 | Reveal and notify grouping moves out of `GameAction`, `PlayerController` and `PlayerControllerHuman` into `CompanionPlayerController` overrides (protected `reveal`, `notifyOfValue`) plus a `CompanionTable` "same run" dedupe. The "(revealed to …)" suffix is dropped. | `7b2e8a5` |
| D2 | `GameState.findPaperCard` reverted. `CompanionGameState` takes placeholders out of the save text before `parse` (new `PlaceholderText`), records their positions, and puts them back after `applyGameOnThread`. It also scrubs `Id:` references to them. Save format unchanged. | `e6553a4` |
| D2b | `IDeviceAdapter` camera methods replaced by a companion `CardCamera` interface plus a `CardCameras` registry. Android and mobile-dev `Main` install the camera in one line. `DesktopCardPhotos` moves to `forge/app/companion/DesktopCardCamera`. | `0e19edef`, `439a9a8`, `6c3ab88`, `45f4bc0` |
| D3 | Coin and flip-onto-battlefield: a general "same event, one pop-up per screen" rule in `FControlGameEventHandler`, replacing the `answersForEveryLocalSeat` branches. `lblPlayerWonTheCoinToss` removed. | `d9daf2e` |
| D4 | `PlayerControllerHuman.answersForEveryLocalSeat`, `MatchController.isSingleOperator` and `getGameMenuExtension` removed. `getExtension()` gets a same-match guard. New `MatchExtension.showsTopPrompt()`. Card menu simplified to `List<FMenuItem> cardMenuItems(CardView)`. | `2ab4237`, `e6553a4`, `c210cd4`, `525cd03`, `e850499` |
| D5 | `FPref.COMPANION_AUTO_ADD` and `COMPANION_KEEP_PHOTOS` move to a new `CompanionPreferences extends AbstractPreferences` (`companion.preferences`). | `6856a62`, slice 8 |
| D6 | `NewGameMenu` static migration for dev builds deleted. | `b8ade1a` |
| D7 | Priority prompt note moves from `PlayerControllerHuman`/`InputPassPriority` to `MatchExtension.promptNote(PlayerView)`. | `032d153` |
| D8 | mobile-dev OCR dependencies go behind a `companion-photos` Maven profile, so the PC build doesn't ship onnxruntime and the models. | `6c3ab88` |

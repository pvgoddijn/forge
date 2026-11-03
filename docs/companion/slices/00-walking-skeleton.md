# Slice 0 — Walking skeleton (redone on the rules engine, 2026-11-03)

**Depends on:** nothing. The OCR spike gates slice 1, not this one. **Proves:** menu → setup → a real `Game` on `MatchScreen` → an operator op through the engine → snapshot → resume, on the dev build and in an APK.

The first slice 0 (branch `feature/companion-slice-0`) replayed our own action log. It is replaced by this one, for spec Q20. Kept: `CardNameMatcher`, `CardSearchDialog`, `CompanionSetupScreen`, the menu entry.

## Result
The operator opens Companion and enters 3–6 players. Forge's match screen opens with every player's board, each hand holding 7 hidden cards. The operator adds Gray Merchant of Asphodel by typing, cast from player 2's hand. The opponents' life goes down and player 2's hand goes to 6. They kill the app, relaunch, and find it all still there.

## Acceptance criteria
- **AC-1** WHEN the user opens the mode selection in Forge mobile THE SYSTEM SHALL offer a "Companion" entry.
- **AC-2** WHEN the user starts a new game THE SYSTEM SHALL let them enter 2–6 players with a name and seat order, and mark exactly one as Me.
- **AC-5** WHEN a game starts THE SYSTEM SHALL set every player's hidden hand count to 7 and life to 40. (Changing them before turn 1 comes in slice 2/3.)
- **AC-14, AC-15, AC-16** Add by typing, typo-tolerant, tokens and emblems by name. Already built; keep working.
- **AC-29** Resume after a kill, within the limits of spec Q22.
- **AC-34** A card added from Hand is cast without paying its costs, and its effects resolve.
- **AC-40** "Put onto the battlefield" places it without cast triggers, and ETB effects still resolve.
- **Part of AC-17:** the operator picks the target player.
- **Part of AC-23:** every player's board is on the one screen.
- **Part of AC-30:** one active game, resumed when Companion opens.

## Build
See `plan.md` sections 1 and 2.

**`forge-gui`, `forge.gamemodes.companion`:**
- `CompanionSetup` (record).
- `CompanionCards`: the placeholder `PaperCard` (from `CardRules.fromScript`, never in `CardDb`), the placeholder deck, and the per-player effect card (`SetMaxHandSize$ Unlimited`, can't lose the game).
- `CompanionLobbyPlayer` + `CompanionPlayerController extends PlayerControllerHuman`:
  - keeps the opening hand, and picks the starting player from the seat order
  - declares no attackers, and allows unlimited lands
  - non-active seats pass priority; the active seat stops in main phase, or whenever the stack isn't empty
  - holds an operator-op queue, run on the loop thread via `InputPassPriority.restartPrompt()` and `chooseSpellAbilityToPlay`
- `TableEditor`: `cast(player, paperCard)` (a placeholder leaves the hand first, then a free cast), and `putOntoBattlefield(player, paperCard)`.
- `CompanionStore`: after each settled op, write `companion/<id>/<n>.state` (`GameState` text, temp file then rename) and append to `companion/<id>.log`. Resume: latest snapshot, applied through `HostedMatch.setStartGameHook`.
- Remove `CompanionAction`, `CompanionState`, `CompanionGame`, `CompanionZone`, and the replay code in `CompanionStore`.

**`forge-game`:** no change. `CompanionGameState` takes the placeholders out of the save text before Forge's loader reads it, and puts them back after (`PlaceholderText`).

**`forge-gui`:** `HostedMatch` subscribes one `FControlGameEventHandler` per GUI.

**`forge-gui-mobile`:**
- `MatchScreen`: `MatchExtension.showsTopPrompt()` joins the hot-seat preference check at line 134.
- `VGameMenu` hook with an "Add card" item.
- Remove `CompanionGameScreen`, `CompanionBoard`, `CompanionController`, `CardMoveDialog`, and slice 2's `VPlayerPanel`/`CardFlightOverlay`/`VAvatar`/`MatchController` seams, unless the real match still needs one.

## Check early
- Several `PlayerControllerHuman`s on one mobile GUI: Me at the bottom, no rotated prompt, buttons reach the right seat.
- `restartPrompt` + op queue: the op runs on the loop thread, and SBA and triggers settle before the next prompt.
- Whether a `PaperCard` outside `CardDb` survives `Match.prepareAllZones`, the card renderer and image lookup.
- What a snapshot loses after a typical turn (list it in this file).
- 6 players on a phone-sized window: screenshot.

## Results of the early checks (2026-11-03, dev build, portrait 450×900)
- **Several humans on one GUI:** works. Me is the bottom board, there's no rotated second prompt (`MatchExtension.showsTopPrompt()`), and the prompt always belongs to the active seat. Not yet changed: `HostedMatch` still subscribes one event handler per human; it caused no visible problem with 6 seats, so it waits until it does.
- **Op queue:** Add card for a seat whose turn it isn't puts the spell on the stack. OK resolves it, then OK resolves the enters trigger. Life, hand and library update on every board.
- **Spells for a seat whose turn it isn't:** the card's normal spell list drops spells that can't be cast at this time. `TableEditor` uses the play-effect spell list instead, as "cast it without paying its mana cost" effects do.
- **Placeholder outside `CardDb`:** fine through the game state, rendering and the stack. A game state must give its set (`Set:???`), or loading looks the name up in `CardDb`. To show as a card back, the image key goes on the card's own state; `Card.setImageKey` sets the shared preview copy.
- **Resume:** kill, then relaunch, gives the same boards, life and hands. The start still shows Forge's coin-flip animation; who goes first comes from the seat order.
- **Next turn:** OK in main 1 and main 2 passes to the next seat, whose draw step draws an unknown card.
- **6 players:** every board is on one screen, each with life, hand count and library, but cards are small. Legibility on a real phone is the open question for slice 2.
- **What a snapshot loses:** commander damage and tax, monarch/initiative, day/night, effects lasting until end of turn, delayed triggers, per-turn counts, and emblems or effects (no `PaperCard`). The table effect is added again on load. Emblems typed in by the operator are lost on resume until `GameState` saves them (plan 2.1).

## Gate B (2026-11-03)
The first review found one High and five Medium issues. Fixed:
- **Auto-pass override:** Forge's auto-pass could skip the table's stops. Only End Turn may skip them now.
- **Ops racing a prompt:** an op that arrives as a prompt opens ends that prompt, and after any op the active seat is asked first.
- **Restart overwriting the save:** a restart reloads the latest save.
- **Card backs:** they now survive zone changes.
- **Companion flags in other matches:** `MatchController.getExtension()` returns Companion's extension only while its own match's game is shown.
- **Small items:** saves can't stop the game thread, a bad save is set aside, and players are picked by name.

Still open:
- **Main-loop tests:** CI tests drive `TableEditor` with AI controllers, not the main loop with a scripted operator. The op queue and the priority policy are checked only on the dev build.
- **Poison on load:** a loaded player with 10 poison loses before the table effect is back.
- **New game after the first:** there's no way to start one until "end game" (slice 5). Until then, delete the game's folder in `companion/`.
- **Single state file:** there's one `state.txt`, not numbered snapshots; that comes with undo (slice 4).
- **Unrolled failures:** an op that fails halfway isn't rolled back.

## Tests (CI)
Through the **real main loop**, with a scripted `CompanionPlayerController` answering prompts:
- Gray Merchant cast from a hidden hand drains opponents, and the hand goes 7 → 6.
- Elvish Visionary's ETB draw leaves the hand count unchanged.
- "Put onto the battlefield" with Gray Merchant still drains.
- A `GameState` round trip keeps placeholders, life and the battlefield.
- The matcher never suggests the placeholder.

## Oracle
1. Dev build: New Game → Companion → 3 players → the match screen shows 3 boards, each hand 7 hidden.
2. Add "Gray Mercant" by typing, for player 2, cast: both other players lose 2, player 2 gains 4, and player 2's hand is 6.
3. Kill the process and relaunch: same state.
4. 6 players: screenshot, all boards on one screen.
5. 📱 The same on an APK.

## Out of scope
Moves between zones, hand ±1, next turn, set life, undo, history, decks, seat names in prompts.

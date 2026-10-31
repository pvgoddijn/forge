# Slice 5 — History

**Depends on:** slice 4.

## Result
The operator can read what happened, filter by player, end a game, and browse past games.

## Acceptance criteria
- **AC-26** WHEN any action happens THE SYSTEM SHALL add it to the history with the turn number, the active player, the player affected, and what was done.
- **AC-28** WHEN viewing the history THE SYSTEM SHALL let the operator filter it by player.
- **AC-30** THE SYSTEM SHALL keep one active game at a time, and SHALL let the operator end it. Ended games stay available to browse, read-only.

## Build
- **Domain:** `GameEnded` action. `CompanionGame.history()` derives readable entries from the log, marking undone actions and corrections. `CompanionStore` lists ended games by date and players.
- **Screens:** `CompanionHistoryScreen` with a player filter. An end-game action with confirmation. A past-games list. A read-only game view with no capture, add, move or undo.
- Localize entry text through `Forge.getLocalizer()`.

## Tests (CI)
History entries carry turn, active player, affected player and text; undone entries are marked; the filter includes only the chosen player's entries; a game with `GameEnded` is not active.

## Oracle
On the dev build:
1. Play a short game with adds, moves, life changes, turns, an undo and a correction. The history shows every one, in order, as a readable line, with the undo and correction visible.
2. Filter to one player: only that player's entries show.
3. End the game: Companion now offers a new game, and that game is under past games.
4. Open it: history and zones are visible, and nothing can be changed.

## Out of scope
Exporting or sharing the history.

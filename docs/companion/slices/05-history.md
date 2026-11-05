# Slice 5 — History

**Depends on:** slice 4, for the structured log (`CompanionLogEntry`: turn, active seat, affected seats, kind, text) and its undone marks, which this slice shows. Through slice 4, it also uses slice 0's `CompanionStore` and op queue. It doesn't need slice 1, 2 or 3: the turn and active seat in each entry come from the game, which slice 0 already runs. **Proves:** the history builds itself, with nothing logged by hand, and survives a restart.

## Result
The operator can read what happened, filter it by player, end a game, start a new one, and browse past games read-only.

## Acceptance criteria
- **AC-26** WHEN any action happens THE SYSTEM SHALL add it to the history with the turn number, the active player, the player affected, and what was done.
- **AC-28** WHEN viewing the history THE SYSTEM SHALL let the operator filter it by player.
- **AC-30** THE SYSTEM SHALL keep one active game at a time, and SHALL let the operator end it. Ended games stay available to browse, read-only.
- **AC-27 (display part)** Undone actions stay in the history, marked as undone (slice 4 marks them).

## Two sources, one log
- **Operator actions** are already in the log: slice 4's entries, one per op, written by `CompanionTable.runPending`.
- **What the engine did** (a trigger's life drain, a draw, a token, a creature dying) is in Forge's `GameLog`, which a `GameState` save doesn't keep. So this slice copies it into our log as it happens: a game-thread `GameLog` observer appends a `kind=engine` entry under the step it belongs to. Only the useful types: `TURN`, `LIFE`, `DAMAGE`, `ZONE_CHANGE`, `DISCARD`, `STACK_RESOLVE`, `COMBAT`, `PLAYER_CONTROL`. Not `PHASE` or `MANA`. `TURN` is needed: a turn passes with Forge's own End Turn (slice 3), which isn't an op, so nothing else records it.
- An engine entry's affected seats are the seat names its text mentions. That's a heuristic; see "check early".

## Build
Small chunks, in order. Each is one or two files.

1. **`CompanionHistory` (new, forge-gui):** reads a game's log entries and turns them into history rows: turn, active seat, affected seats, text, undone or not. An undo entry marks the entries of the steps it names, and isn't a row of its own. `filter(String seat)` keeps the rows whose affected seats include that seat. Not the rows of that seat's turns: AC-26 makes "the player affected" its own field, and that is what a filter by player means.
2. **`CompanionTable` (forge-gui):** the `GameLog` observer from "two sources", added when a seat registers and removed with the old game's seats on a restart. Writes through `CompanionStore.appendLog`, so it never stops the game.
3. **`CompanionStore`:** `end(id)` writes the `ended` marker (built in slice 3, chunk 13); `listEnded()` returns ended games, newest first, each with its date and seat names; `loadLog(id)` returns the entries.
4. **`CompanionHistoryScreen` (new, forge-gui-mobile):** an `FList` of rows, newest at the bottom, a player filter (`FComboBox`, "Everyone" first), undone rows struck through or greyed. Text through `Forge.getLocalizer()` where it's ours; engine text is already Forge's.
5. **Companion's `MatchExtension`:** "History" in the game menu. *"End game…" was pulled forward into slice 3 (its chunk 13, 2026-11-04): it asks to confirm, calls `end(id)`, and leaves the match back to an empty setup, which closes slice 0's open item "new game after the first". `CompanionStore.end(id)` (chunk 3) is built with it. History, `listEnded()`, `loadLog(id)` and Past games stay in this slice.*
6. **`CompanionSetupScreen`:** with no active game, it offers New game and Past games. Today it resumes the active game straight away; it still does, and a finished game leaves it on New game.
7. **`CompanionPastGamesScreen` (new, forge-gui-mobile):** the list from `listEnded()`. Opening one shows its history (chunk 4's screen, read-only) and its final boards.
8. **Final boards for a past game:** a small `CompanionSnapshotSummary` (forge-gui) that reads the last snapshot's text into per-seat life, hidden hand count, known cards and zone lists, shown as a plain list. No `HostedMatch`: a live engine game would run its loop and accept taps, which a read-only view mustn't.

## Check early
- **Engine entries' affected seat:** whether matching seat names in `GameLogEntry` text is good enough for the filter. A seat named like a card ("Angel") would match wrongly. If it's poor, read the affected player from the game event instead (`GameEventPlayerLivesChanged`, `GameEventCardChangeZone`), on the game thread.
- **Entries during a skipped stretch:** an End Turn, or slice 6's turn skip, makes many engine entries between two saves. They belong to the step the next save writes, so an undo marks all of them undone.
- **Log size:** a long Commander game with engine entries. Measure the file after a real game; it should stay well under a megabyte.

## Tests (CI)
Pure, on log lines (no engine): rows carry turn, active seat, affected seats and text; an undo marks the right rows; the filter keeps only rows affecting the chosen seat; an older plain-text line reads as a notice; a game with `ended` isn't active. Through the main loop: a Gray Merchant cast writes engine entries for each life change.

## Oracle
On the dev build, 3 players:
1. Play a short game: adds, a life change, End Turns, an undo and a correction. The history shows every one in order as a readable line, the Merchant's drains as their own lines, the undone action marked, and the correction.
2. Filter to one player: only rows affecting that player.
3. Kill and relaunch: the history is the same, engine lines included.
4. End the game: Companion offers New game, and the game is under Past games.
5. Open it: history and final boards show, and nothing can be changed.

## Out of scope
Exporting or sharing the history (spec section 6). Replaying a past game step by step.

## Carried over from slice 4's review (2026-11-05)
- **L2:** a crash between `discardAfter` and `appendLog` in `CompanionTable`'s Undo leaves no undo entry. The next action reuses step n, so the old step-n entries never show as undone. The board is right; only the history is wrong. Fix it here, since this slice reads the log as history. For example, write the undo entry first and make `loadLog` ignore an undo whose target steps still exist, or detect reused step numbers by seq.
- **L3:** `CompanionTable.atPrompt` re-reads the whole log at every prompt, and every log entry lists the `states/` folder. The cost grows with game length and with this slice's engine entries. Cache the log and the latest step in memory.

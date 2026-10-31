# Slice 4 — Undo and correction

**Depends on:** slice 3.

## Result
Any mistake can be undone. A card logged to the wrong player, for example after forgetting to pass the turn, can be fixed with one action, and both hand counts come out right.

## Acceptance criteria
- **AC-27** WHEN the operator taps undo THE SYSTEM SHALL revert the most recent action that hasn't been undone, and SHALL keep showing it in the history marked as undone. Undo SHALL be repeatable back to the start of the game.
- **AC-22a** WHEN the operator uses "wrong player" on a card THE SYSTEM SHALL reassign it to the chosen player's same zone, restore the original player's hand as if the card had never left it, apply AC-19/AC-20 to the chosen player, and show the correction in the history.

A correction changes the player only. It does not change the turn number on the original entry, and does not pass the turn (A3).

## Build
- **Domain:** `ActionUndone(targetSeq)` and `PlayerCorrected(cardInstanceId, newPlayer)`. Both are appended to the log, and the log is never rewritten.
- **Replay:** skip undone actions. For a corrected card, use the new player for its original `CardAdded`. This gives the "as if it had never left" result, including the 0-clamp case, without special arithmetic.
- **Decide and test:** later moves of a corrected card, undoing a correction, and whether undo can target a game's `GameStarted` (it must not).
- **Screens:** undo button, and "wrong player" in the card menu.
- Cache state in memory, and replay in full only on load or undo.

## Tests (CI)
- Undo: repeated back to the start; an undone action stays in the log, marked.
- Correction: both counts right; original player's count was clamped at 0, so correcting does not give them an extra card; target player's count clamps at 0; a known card in the target's hand is used per AC-20; undoing a correction restores the original.
- A log with undo and correction survives reload.

## Oracle
On the dev build:
1. Player 1 and player 2 at 7 cards. Log a card to player 1 (count 6). Use "wrong player" → player 2. Now player 1 has 7, player 2 has 6, and the card is on player 2's battlefield.
2. Undo: the card is back on player 1, and counts are 6 and 7.
3. Undo again: card gone, counts 7 and 7.
4. Undo all the way back, with no crash. Restart: the state is the same.

## Out of scope
History screen (slice 5). Redo is not in the spec.

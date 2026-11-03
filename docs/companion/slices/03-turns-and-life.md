# Slice 3 — Turns and life

**Depends on:** slice 2.

## Result
A "next turn" button tracks whose turn it is. New cards default to that player. Life totals work.

## Acceptance criteria
- **AC-5 (life part)** WHEN a game starts THE SYSTEM SHALL set every player's life to 40, and let the operator change it before the first turn.
- **AC-17** WHEN a card is added THE SYSTEM SHALL target the active player by default and let the operator change the target player before confirming.
- **AC-24** WHEN the operator taps "next turn" THE SYSTEM SHALL make the next player in seat order active, and increase the turn number when play comes back around to the first player.
- **AC-25** THE SYSTEM SHALL let the operator change any player's life total.

## Build
- **Domain:** `TurnPassed` and `LifeChanged` actions. Turn number and active player in `CompanionState`. No phases or steps (Q16).
- **Screens:** next-turn button, active-player highlight, life controls, and starting values on the setup screen.

## Tests (CI)
Replay: seat order wraps and the turn number goes up only on wrap; life changes add up; life can go below 0.

## Oracle
On the dev build with 3 players:
1. Tap next turn 3 times: active goes 2 → 3 → 1, and the turn number goes up on the third tap.
2. Add a card: player 1 is preselected, and can be changed.
3. Change a life total: it updates and survives a restart.
4. Set starting life to 20 in setup: every player starts at 20.

## Out of scope
Other counters, commander damage, undo.

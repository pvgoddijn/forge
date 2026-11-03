# Slice 3 — Turns and life

**Depends on:** slice 2.

## Result
A "next turn" button tracks whose turn it is. New cards default to that player. Life totals work.

## Acceptance criteria
- **AC-5 (life part)** WHEN a game starts THE SYSTEM SHALL set every player's life to 40, and let the operator change it before the first turn.
- **AC-17** WHEN a card is added THE SYSTEM SHALL target the active player by default and let the operator change the target player before confirming.
- **AC-24** WHEN the operator taps "next turn" THE SYSTEM SHALL make the next player in seat order active, and increase the turn number when play comes back around to the first player.
- **AC-25** THE SYSTEM SHALL let the operator change any player's life total.
- **AC-41** Optional combat through the engine, for the game or for one turn. **Built early, on 2026-11-03, at the owner's request.** There are two game-menu items. "Combat this turn" is for one fight. "Combat every turn" is saved in `setup.json`. When either is on, Forge's own attack and block prompts appear for the seats, End Turn stops at the attack declaration, and the active seat stops after attackers, after blockers and before regular damage when there are first strikers. Combat steps aren't saved: a game state can't hold a combat in progress. With combat off, a creature that must attack (goad, Juggernaut) turns engine combat on for that turn, with a message, because declaring nobody isn't legal: blocks and damage then go through the engine too, so the operator doesn't set life for it. The stops come only when someone attacked.
  - Checked on the dev build: with every turn on, Grizzly Bears attacked Player 1, who didn't block and went from 40 to 38.
  - Checked on the dev build: with this turn on for turn 2, the attack prompt came on turn 2 and not on turn 3, though turn 3's player had a creature that could attack.
  - Not yet checked: a real block, first strike, the combat stops, and a forced attack with combat off.

  What's left for this slice: a creature logged this turn is summoning-sick, so it can't attack unless it has haste. Add a way to clear that, for creatures that were really on the table earlier.

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

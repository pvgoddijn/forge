# Slice 4 — Undo and correction

**Depends on:** slice 0 only, for the mechanism: `CompanionTable`'s op queue and its save at each settled point, `CompanionStore`, and `CompanionGameState`. Undo means reloading the save made before the last action (spec Q22, `plan.md` decision 6), so it works the same whatever the action was and needs nothing from slices 1–3.

**Stacked on:** slice 3, for branch order only. Its oracle undoes an End Turn and a life change, which slice 3 brings (End Turn is Forge's own yield, not an op, so it is undone like anything else: by the save before it). Built before slice 3, it would still work, with a smaller oracle.

**Not needed:** slice 2. The "wrong player" correction (AC-22a) only moves one card from one player to another and gives the first player's hand back. This slice builds that as a narrow `TableEditor.reassign`, not slice 2's general `moveTo`.

**Proves:** any mistake can be taken back, all the way to the start, and a restart keeps what was undone.

## Result
Undo takes back the last action, and can be repeated back to the start of the game. A card logged to the wrong player, for example after forgetting to pass the turn, can be given to the right player in one action, and both hand counts come out right.

## Acceptance criteria
- **AC-27** WHEN the operator taps undo THE SYSTEM SHALL revert the most recent action that hasn't been undone, and SHALL keep showing it in the history marked as undone. Undo SHALL be repeatable back to the start of the game. (Q22: the board, hands, life, counters and turn are restored exactly; effects lasting until end of turn only as far as the engine can save them.)
- **AC-22a** WHEN the operator uses "wrong player" on a card (for example it was logged after forgetting to pass the turn) THE SYSTEM SHALL reassign it to the chosen player's same zone, restore the original player's hand as if the card had never left it, apply AC-19/AC-20 to the chosen player, and show the correction in the history. Effects that already resolved are not redone; to replay them for the right player the operator undoes and logs the card again.

A correction changes the player only. It doesn't change the turn number on the original entry, and doesn't pass the turn (spec A3). This slice marks undone entries in the log; slice 5 shows them.

## How undo works
- **One undo step = one save.** `CompanionTable.save` writes a snapshot at each settled point (main phase, empty stack, the active seat asked), and only when the state changed. A cast with an ETB trigger is one step once it has resolved. Two ops that run before the next settled point are one step.
- **Undo is an op.** The menu item submits it through `CompanionTable.submit`, so it runs on the game loop thread like any other action. It applies snapshot n−1 to the running game in place (`CompanionGameState.applyNow`, as the start hook does), and marks the log entries of step n undone.
- **Undo while something is unsettled** (a spell on the stack, a combat step) goes back to the latest save, not the one before: the unsaved action is the one being undone.
- **A new action after an undo** writes step n again, over the undone snapshot. The undone log entries stay, marked. No redo (not in the spec).
- **Forge's own undo stays off.** The engine's `UndoManager` is in memory only, with barriers at every draw, phase and reveal (plan decision 6). The prompt's undo button (`VPrompt`'s `UndoButton`) is hidden in Companion, or routed to this undo.

## Build
Small chunks, in order. Each is one or two files.

1. **`CompanionStore` (forge-gui):** numbered snapshots, `<id>/states/<n>.txt`, written whole then renamed, as `state.txt` is now. `loadState` reads the highest one. A game saved by slice 0 has one `state.txt`: read it as step 0. Keep every step, so undo always reaches step 0 (owner's decision; plan risk "a snapshot per op").
   *As built:* the store keeps no step counter in memory; the files are the truth. `latestStep(id)` (the highest on disk, 0 for a slice 0 `state.txt`, `NO_STEP` = −1 before the first save), `loadState(id)` (the highest), `loadStep(id, n)`. `saveState(id, state)` writes `latestStep + 1` and returns it, so `CompanionTable.save` is unchanged; the first save is step 0. `saveState(id, n, state)` writes step n and drops every later step first. `discardAfter(id, n)` deletes the steps after n, highest first, so a crash part way leaves a state that existed. For chunk 4: **undo must call `discardAfter(id, n − 1)`** (before or with the apply), or a restart would load the undone step n again; the next save then numbers itself n with no extra call. A slice 0 `state.txt` moves to `states/0.txt` at the next save; a crash between creating `states/` and the move still reads it as step 0. A leftover `<n>.txt.tmp` is ignored. `loadActive` sorts games by the `states/` folder's time. No pruning: every step is kept (owner's decision, replacing "keep the last 500"), so there's no `oldestStep`. Tests: `CompanionStoreShould` (forge-gui).
2. **`CompanionLogEntry` (new record, forge-gui) + `CompanionStore`:** the log becomes one JSON object per line instead of a plain message: `seq`, `step` (the snapshot it led to), `turn`, `active` seat, `affected` seats, `kind` (add, undo, correction, notice, and later slices' kinds), `text`, and for an add, `fromHand` = `placeholder`, `known` or `none` (what left the hand, for AC-22a). A plain line from an older log reads as a notice. A torn last line is ignored. Slice 5 reads these fields; settle them here so the log isn't changed twice.
   *As built:* `CompanionLogEntry(seq, step, turn, active, affected, kind, text, card, fromHand, undid)`. Seats by setup name (unique); cards by name in `card`, never by ID (IDs don't survive a load: chunk 1's finding). Kinds: `add`, `action` (any other op that changed the game: life, ready), `undo`, `correction`, `notice` (also a failed op), `engine` (slice 5's `GameLog` copies, named now so the log isn't changed again); a kind the reader doesn't know reads as `notice`, and unknown fields are ignored. `step` is the step the next save writes (`latestStep + 1`), so everything between two saves, engine entries included, shares one step and one undo takes it all back; an undo entry's `step` is the step it went back to. `turn` 0 and `active` null when no game runs yet. Null fields are left out of the JSON.
   **Undone is derived, append-only:** an `undo` entry lists the steps it took back in `undid`; nothing is rewritten. `CompanionLogEntry.undone(log)` returns the seqs of entries whose step a *later* undo names (undo entries are never undone). After an undo to n − 1, the next action's entries are step n again but come after that undo, so they aren't marked. Chunk 4 appends `CompanionLogEntry.undo(n − 1, List.of(n), …)`; slice 5's `CompanionHistory` calls `undone`.
   `CompanionStore.appendLog(id, entry)` numbers the entry (`seq` = last + 1, read from the log at a game's first append, then kept in memory) and returns it; `loadLog(id)` (the name slice 5 asked for) reads them. A plain line from an older log reads as a `notice` numbered by position, step `NO_STEP`, turn 0. A line that starts as JSON but doesn't read (a crash cut it) is skipped; the next append starts a new line first, so the cut line can't swallow it.
   *Defaults until chunk 3:* `CompanionTable.runPending` writes `add` for a done `AddCard` (affected = its player, `card` = its name, `fromHand` = `none` when not from hand, else null: placeholder or known isn't known until `TableEditor.Result` says), `action` for other done ops with no affected seats (the life and ready ops are lambdas), and `notice` for a failed op. `tell` notices carry the add's player when they're about one.
3. **`TableEditor.Result` + `TableEditor.addCard`:** the result carries the affected seat and what left the hand, so `CompanionTable.runPending` can write the entry in chunk 2's shape.
4. **`CompanionTable`:** `undo()`, an op: find the latest step that isn't undone, apply the one before it with `CompanionGameState.applyNow`, append an `undo` entry naming the steps it undid, and set `lastSaved` to the restored text so the next save doesn't count it as a change. Also reset what isn't in a save: "combat this turn" (cleared unless the turn is the same), and slice 3's parked ops, which belong to the state being undone. Refuse at step 0, with a message.
5. **Companion's `MatchExtension` (forge-gui-mobile):** today an anonymous class in `CompanionSetupScreen.openGame`. If slice 3 hasn't moved it into its own `CompanionMatchExtension`, do that first, as a chunk of its own. Then an "Undo" game-menu item, disabled at step 0. Hide or reroute Forge's prompt undo button.
6. **`TableEditor`:** `reassign(Card card, Player to, String fromHand)`. Moves the card to the same zone of `to` and makes `to` its owner and controller. The original player gets back what left their hand: a placeholder for `placeholder`, nothing for `none` (their count was clamped at 0, so they don't gain a card). Then AC-19/AC-20 for `to`: their known copy of that name leaves their hand if they have one, otherwise one placeholder, otherwise nothing. No trigger runs again.
7. **Companion's `MatchExtension` + a small new `WrongPlayerDialog` (forge-gui-mobile):** "Wrong player…" in the game menu: pick the card from the cards added this turn (the log's add entries), then the seat. Submits `reassign`, which writes a `correction` entry. A card-tap menu would be nicer, but that hook is slice 2's (deferred).

## Decide and test
- `known` in AC-22a's "as if it had never left": the card was a known card in the wrong player's hand, so it goes back there as a known card and a fresh copy goes to `to`? Or does the operator really mean it was never theirs? Pick one, write it in the test, and tell the owner.
- A corrected card that moved again later (it died, or bounced). The log entry still names it; reassign the card where it is now, or refuse when it can't be found.
- Undoing a correction: an ordinary undo step, so nothing special.
- Undo never goes before step 0, the setup's start.

## Check early
- **In-place apply:** `applyNow` on a running game, between prompts, leaves no duplicate table effect or commander effect in the Command zone, and the game view updates every board.
- **Turn after undo across an End Turn:** the game's turn goes back; the game view's turn is known to lag after a load (`CompanionTable.currentTurn`).
- **Card identity after a load:** `GameState` writes `|Id:`. If IDs hold across a load, chunk 2 can name cards by ID instead of by name.

## Tests (CI)
Through the real main loop, with a scripted operator:
- Undo across a draw: End Turn draws for the next seat, undo puts the hand and the active seat back.
- Repeated undo back to step 0, then a refusal.
- An undone entry stays in the log, marked, and a new action after it writes the next step.
- Correction: both counts right; the original player was clamped at 0, so correcting doesn't give them an extra card; the target's known copy is used per AC-20; undoing the correction restores the original.
- A store with undo and correction entries survives reload, and a slice 0 `state.txt` loads as step 0.

## Oracle
On the dev build, 3 players:
1. Player 1 and player 2 at 7 cards. Log a card to player 1 (count 6). "Wrong player" → player 2. Player 1 has 7, player 2 has 6, and the card is on player 2's battlefield.
2. Undo: the card is back with player 1, counts 6 and 7.
3. Undo again: card gone, counts 7 and 7.
4. Cast Gray Merchant for player 2, then set a life total, then End Turn. Undo three times: back before the Merchant, every life total and hand as it was.
5. Undo all the way back with no crash. Kill and relaunch: the same state, and the next undo carries on from there.

## Out of scope
The history screen (slice 5). Redo (not in the spec). Effects lasting until end of turn after an undo (spec Q22, plan 2.3).

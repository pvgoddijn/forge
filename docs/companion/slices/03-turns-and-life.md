# Slice 3 — Turns and life (on the rules engine)

**Depends on:** slice 1. Its branch, `feature/companion-slice-3`, is stacked on `feature/companion-slice-1`. It needs nothing from slice 2: no hidden-hand rules beyond slice 0's, no known cards, no `moveTo`, and no name prompt. **Proves:** the operator can play a whole turn cycle on the engine. Turns pass with their effects, life totals can be set, a creature logged late can still attack, and a sorcery is cast in a main phase.

Rewritten on 2026-11-04 for the engine design (spec Q20, `plan.md` section 0). The first version planned `TurnPassed` and `LifeChanged` domain events, plus turn fields in `CompanionState`. That code no longer exists: the engine owns turns and life, and the operator's changes are `TableEditor` ops run on the game thread.

## Result
The operator sets starting life in setup, then plays turns with End Turn. The active player's avatar is ringed, the prompt shows the turn number, and the Add card dialog targets the active player. Tapping a player's life opens a small dialog to raise, lower or set it. A creature that was on the table before this turn, but was logged during it, can be marked ready, so it can attack when combat goes through the engine. A sorcery logged outside a main phase waits for one: Main 1 if the turn hasn't reached combat yet, otherwise Main 2.

## Acceptance criteria
- **AC-5 (life part)** WHEN a game starts THE SYSTEM SHALL set every player's life to 40, and let the operator change it before the first turn.
- **AC-17** WHEN a card is added THE SYSTEM SHALL target the active player by default and let the operator change the target player before confirming.
- **AC-24** WHEN the operator taps "next turn" THE SYSTEM SHALL make the next player in seat order active, and increase the turn number when play comes back around to the first player. *(The turn number doesn't match the engine; see decision D1.)*
- **AC-25** THE SYSTEM SHALL let the operator change any player's life total.
- **AC-35** WHEN an effect needs a choice THE SYSTEM SHALL ask the operator, naming the player who makes that choice at the table. *(This slice covers the prompt bar only; see chunk 8.)*
- **AC-36, AC-37** Effects that change life, draw, make tokens or add counters show by themselves. Passing the turn resolves end-of-turn and upkeep effects, and the draw raises the hand count. *Slice 0 already does this through the engine. This slice only checks it in its oracle.*
- **AC-38** WHILE a card is on the stack THE SYSTEM SHALL let the operator add a response from any player before it resolves. *Slice 0's op queue already does this. The active seat stops while the stack isn't empty, and Add card can target any seat. Checked in the oracle.*
- **AC-39** The operator is never refused because of the rules. *Partly met already: a free cast ignores costs and timing. The fallback for a spell with no legal target ("put the card in its zone instead") needs slice 2's zone choice, so that part waits for slice 2.*
- **AC-41** Optional combat through the engine, for the game or for one turn. *Built early, on 2026-11-03.* What's left for this slice is a way to let creatures logged late attack.
- **AC-42** *(new, 2026-11-04, owner request; not yet in `spec.md`)* WHEN the operator casts or adds a sorcery for the active player while the game isn't in a main phase with an empty stack THE SYSTEM SHALL first move the game on to a main phase and cast it there: Main 1 if the turn hasn't reached combat yet, otherwise Main 2. Effects and combat on the way SHALL resolve by the rules. *Spec wording to be added by whoever owns `spec.md`. See D5 for the edge cases.*

## What's already built (checked against the code on 2026-11-04)
| AC | Already there | Still to do |
|---|---|---|
| 5 | `CompanionSetup.life` (default `DEFAULT_LIFE = 40`), written by `initialState()` as `p<i>life=` and saved in `setup.json` by `CompanionStore` | `withLife(int)`, and a "Starting life" field on `CompanionSetupScreen` (chunks 1 and 2) |
| 17 | `TableEditor.addCard(Player, …)`. `CardSearchDialog`'s constructor already preselects `gameView.getPlayerTurn()` (lines 61–63), and the target can be changed in `cbPlayer` | Nothing. Check it in the oracle |
| 24 | The engine runs the turns. The prompt's End Turn button yields to the next seat's first main phase (`CompanionPlayerController.mayAutoPass`), and OK in main 2 does the same. `VAvatar.drawPlayerIndicator` (line 192) draws a cyan ring on the active player, and the priority prompt shows `Turn: N (name)` (`InputBase.getTurnPhasePriorityMessage`, line 163). After a load, `devModeSet` → `setPlayerTurn` updates the view's active player | Decision D1 on the turn number. Make the ring clearer if the 6-seat check fails (chunk 9, only if needed) |
| 25 | `Player.setLife(int, SpellAbility)` is the engine's way to set life, and dev mode and `GameState` use it. The table effect already stops anyone from losing at 0 or below | `TableEditor.setLife` (chunk 3), a life dialog, and a tap on the life label to open it (chunks 5–7) |
| 41 | "Combat this turn" and "Combat every turn" (`CompanionTable.engineCombat*`), the game-menu items in `CompanionSetupScreen`, and the forced-attack fallback in `CompanionPlayerController.declareAttackers` | Make late-logged creatures ready to attack (chunks 4 and 7) |
| 42 | Ops already run on the game thread from `CompanionPlayerController.chooseSpellAbilityToPlay` → `CompanionTable.runPending`, and a seat that returns `null` there passes priority, which is how the table already skips the steps it doesn't stop at. A free cast works at any time (`AbilityUtils.getBasicSpellsFromPlayEffect`), so today a sorcery is cast wherever the op happens to run | A parked op that waits for its main phase (chunks 10–12) |

## Decisions
- **D1: Turn number (owner decision).** Spec AC-24 says the number goes up when play comes back to the first seat, so it counts rounds. Forge's `PhaseHandler.turn` goes up on every player's turn, and that number is what the prompt, the game log and `CompanionTable.engineCombat(turn)` use. **Recommendation:** amend AC-24 to the engine's numbering: "make the next player in seat order active, and increase the turn number". Rounds become ambiguous with extra turns, and a second counter would differ from the game log. If the owner wants rounds, add `CompanionTable.round()` (the turn plus the seat count, ignoring extra turns) and show it through the chunk-8 prompt hook. Nothing else in this slice depends on this choice.
- **D2: A life change is a correction unless the operator says it's a real gain or loss** *(revised 2026-11-04, owner request: "with life edits ask if it needs to trigger lifegain, only when that would be possible")*. Spec AC-39 lists AC-25 as an override, but at a real table a life change is sometimes a correction and sometimes a gain or loss the app missed. So `TableEditor.setLife(Player, int, boolean fireTriggers)` takes the choice:
  - `fireTriggers = false` (correction): it goes through `Player.setLife` with the `LifeGained`, `LifeLost` and `LifeLostAll` trigger modes suppressed for the call, so a correction doesn't put another Ajani's Pridemate counter on the board. A suppression someone else set is left alone.
  - `fireTriggers = true` (real gain or loss): nothing is suppressed. `Player.setLife` fires `LifeGained` or `LifeLost`, and after a loss `setLife` also fires `LifeLostAll` for that player, as `LifeSetEffect` does, so "whenever one or more players lose life" triggers see it too.
  - The operator is asked only when it matters. `TableEditor.lifeChangeCouldTrigger(Player, int newLife)` (game thread) is true only when some active trigger on the table would fire for that gain or loss. It builds the same run parameters `Player.gainLife`/`loseLife` would and asks `TriggerHandler.getActiveTrigger(mode, params)`, the engine's own matching: the trigger's zones, `ValidPlayer` ("you" vs an opponent), amounts (Ob Nixilis's "exactly 1"), `FirstTime`, once-a-turn limits, conditions and Torpor Orb-style effects all count. It's false when the player can't gain (or lose) life at all, or when the life doesn't change. Approximations: the amount is the one asked for, before replacement effects change it; delayed triggers ("whenever you gain life this turn" from a resolved spell) aren't looked at; and it reads the engine's active-trigger list, which is refreshed at state-based actions, as the table runs between prompts.
  - Either way, the engine's replacement and static effects apply, for example "can't gain life" or doubled life gain. When the result isn't what the operator asked for, the op says so in its `Result` and doesn't force it. Forcing it would need a raw setter in core, which isn't worth it for edge cases.
- **D3: Summoning sickness is cleared only by the operator, per creature.** The engine sets sickness on everything that enters (`PlayerZoneBattlefield.add`, line 65) and clears it when its controller's turn begins (`PhaseHandler`, line 185). So a creature logged on another seat's turn is ready by its controller's turn, and only creatures logged during their controller's own turn are affected. Only the operator knows whether such a creature was cast this turn, which by the rules makes it sick, or was on the table since earlier and only logged late. So:
  - A game-menu item, "Ready creatures…", lists every summoning-sick creature on the table and shows nothing picked. The operator picks the ones that were there before the turn began.
  - The op `TableEditor.readyCreatures` calls `Card.setSickness(false)`, the same public engine call `GameState` makes for `removesummoningsickness`. There's no new rules flag and no change to core.
  - Creatures the operator doesn't pick keep the rules. Haste still works as usual.
  - It also makes `{T}` abilities usable.
  - `GameState` writes `|SummonSick` only for sick cards (line 324), so a ready creature stays ready through a save and a resume.
  - Rejected: a "was here before" option in the Add card dialog. It would add an input to every add, it couldn't fix a token made by an effect or a card added a minute ago, and "Put onto battlefield" has to keep the rules for real "put onto the battlefield" effects.
- **D4: No manual damage entry.** The spec gives slice 3 life totals only (AC-25). Damage to creatures, and commander damage, are later items (spec section 6). With combat at the table, the operator sets life after combat, as spec Q21 says.
- **D5: A sorcery moves the game on through the normal loop, never by setting the phase (AC-42).**
  - **How the engine tracks it.** `PhaseHandler.getPhase()` and `PhaseType.isBefore`/`isAfter` give the step. `PhaseHandler.getNumCombat()` counts combat phases begun this turn: it goes up at `COMBAT_BEGIN` (line 300) even when nobody attacks. `GameState` doesn't save that count, so after a resume it reads 0 in Main 2. The table therefore decides by **position in the turn**: before `COMBAT_BEGIN` (untap, upkeep, draw, or Main 1 with a non-empty stack) the target is **Main 1**. From `COMBAT_BEGIN` up to Main 2 with a non-empty stack, the target is **Main 2**.
  - **How the game gets there.** The op isn't run straight away. `CompanionTable` parks it with its target phase. While an op is parked, `CompanionPlayerController.chooseSpellAbilityToPlay` returns `null` (pass) at every stop until the active seat has priority in the target phase with an empty stack, and then runs it. The engine moves on as it always does: upkeep and draw triggers resolve, and an engine combat's blocks, damage and combat triggers resolve by the rules. Choices those effects need are still asked, because they're decisions, not priority stops. There's no `devModeSet`, no `endCombatPhaseByEffect`, and nothing new in `forge-game`.
  - **No going back.** A sorcery logged in the end step or cleanup, where only a trigger on the stack can stop the table, is cast in place, with a notice that the turn's main phases are over. Setting the phase back to Main 2 would run end-step triggers twice. The same goes for a parked op whose turn ends before it runs (a "skip your next main phase" effect): it's cast at once, with a notice.
  - **Whose sorcery.** Only a sorcery for the **active** player waits. One logged for another seat is cast in place as today: at the table it can only have been cast at instant speed, or it was logged for the wrong seat (slice 4's correction).
  - **What counts.** `Card.isSorcery()` on the printing being added, in `CAST` mode only. A permanent spell is sorcery-speed too, but the request names sorceries only. The predicate is one method (`TableEditor.waitsForMainPhase`), so widening it is a one-line change if the owner asks.
  - **With "Combat this turn" or "Combat every turn".** A sorcery logged at a combat stop (after attackers, after blockers, before regular damage) passes the rest of that combat: blocks are asked, damage and combat triggers resolve, and the trick stops are skipped, since logging a sorcery says combat is over. Then it's cast in Main 2. A forced attack (goad) works the same way. The table can't undo an attack already declared, so a sorcery the table cast before combat but logged after attackers lands in Main 2.
  - **With combat at the table (off, the default).** The engine sits in Main 1 while the table fights, so a sorcery logged then is cast in Main 1 at once, even if the table already attacked. Nobody can see the difference, apart from "this turn" counts that depend on combat. The operator taps OK first to get to Main 2.
  - **More than one parked op.** They run in order once the target is reached. A card added while an op is parked (any `Add card`) waits behind it, so cards reach the game in table order. Every other op (a life edit, `Ready creatures`) runs at once even while something is parked (owner's decision, 2026-11-04).
  - **Not saved.** The table saves only at main-phase stops with an empty stack, and the parked op runs before that save. If the app is killed while an op is parked, the op is lost and the operator logs it again. It's logged as "waiting for Main N" when parked, so the history shows it.

## Deferred to slice 2 (zones and hand)
Slice 2 is on hold until the owner decides what to do with it. This slice doesn't build any of the following, and doesn't need them:
- hand +1 and −1 (AC-22), and the hand part of AC-5 (an editable starting hand size)
- "Move…" for any card to any zone of any player (AC-22), and the from/to zone choice in the Add card dialog (AC-18)
- naming an unknown card when an effect makes it public (the name prompt)
- showing known cards in hand, so AC-23 in full. Slice 0 already puts every board and life total on one screen.
- the AC-39 fallback: putting a spell with no legal target straight into its zone

## Tests (CI)
These go in `forge-gui-desktop/src/test/java/forge/gamemodes/companion/TableEditorTest.java`, on real games built by the `AITest` harness and real `CompanionSetup`/`CompanionStore` objects, without mocks. The replay tests the first version planned (seat wrap, life sums) belonged to the removed `CompanionState`, so they're gone. The engine owns seat order and the turn count. Forge's own tests already cover them, so they're not retested here.
- **Starting life:** a table from `withDefaults(…).withLife(20)` starts every player at 20, and `CompanionStore.toJson`/`fromJson` keeps 20.
- **Set life:**
  - lowering to −3 leaves the player in the game, with no game over
  - raising life with Ajani's Pridemate on the battlefield and `fireTriggers = false` adds no counter, and a later real gain still does (D2)
  - with `fireTriggers = true` the same raise adds a counter, and Exquisite Blood gains Ann what Bo lost
  - with `fireTriggers = true` a loss of exactly 1 also fires `LifeLostAll` (Ob Nixilis, Captive Kingpin gets a counter)
- **Could a life change trigger (D2):** `lifeChangeCouldTrigger` is
  - true for Pridemate's controller gaining, false for that controller losing, and false for an opponent gaining
  - false with no life trigger on the table, and false for no change
  - true for an opponent of Exquisite Blood's controller losing, false for that opponent gaining or for the controller losing
  - true for Sanguine Bond's controller gaining, false for another player gaining
  - true for an opponent of Ob Nixilis losing exactly 1, false for losing 2
  - false for Pridemate's controller gaining when Erebos stops them gaining life
  - the life survives `CompanionGameState.capture` → `applyNow`
  - setting life to its current value is a no-op that still reports done
- **Ready creatures:**
  - Grizzly Bears put onto the battlefield for the active player is sick, and `CombatUtil.canAttack` is false
  - after `readyCreatures` it's true, and a second Bears that wasn't picked is still sick
  - readiness survives capture → apply
  - an id that's no longer on the battlefield is skipped, not an error
  - a non-creature id and an unknown id are skipped and named in the result
  - `sickCreatures` lists only the sick creatures: not a readied one, a haste one or a non-creature
- **Main phase for a sorcery (AC-42)**, on `TableEditor.mainPhaseFor(Game, Player, String, Mode)`, with the phase set by applying a game state with `activephase=`:
  - Divination for the active player in `UPKEEP` gives `MAIN1`
  - in `COMBAT_DECLARE_BLOCKERS` it gives `MAIN2`
  - in `MAIN1` with an empty stack it gives none (cast now)
  - in `END_OF_TURN` it gives none (no going back)
  - Divination for a non-active player gives none
  - Grizzly Bears gives none
  - Divination in `PUT_ONTO_BATTLEFIELD` mode gives none
- **Parking (AC-42)** on a real `CompanionTable` with a `CompanionStore` in a temp directory:
  - a parked op doesn't run before its target phase
  - it runs once the phase handler is in its target with an empty stack
  - an op submitted after it runs after it
  - when the turn number changes first, it runs at once and a notice is sent

  The walk through the loop is tested on the real main loop as well (chunk 12's two loop tests); the prompts on the device are checked in the oracle.

No UI tests (`CONTRIBUTING.md`). The life dialog, the menu item, the active-player ring and the prompt prefix are checked in the oracle.

## Oracle
On the dev build with 3 players (Ann = Me, Bo, Cy), unless a step says otherwise:
1. **AC-5:** in setup, set starting life to 20 and start. Every board shows 20. Kill the app and relaunch: still 20.
2. **AC-24, AC-37:** tap End Turn 3 times. The cyan ring moves Ann → Bo → Cy → Ann in seat order, the prompt's turn number goes 1 → 2 → 3 → 4 (or the round, if D1 goes that way), and each new active player's hand count goes up by 1 at their draw.
3. **AC-17:** open Add card. The active player is preselected, and picking another seat adds the card for that seat.
4. **AC-25:**
   - Tap Bo's life, tap −5 twice, then Set: Bo is at 10.
   - Set Cy to −3: Cy is still in the game.
   - Without any life trigger on the table, Set asks nothing.
   - With Ajani's Pridemate on Ann's board, raise Ann's life: Set asks "Trigger life gain/loss effects?". No (the default): the Pridemate gets no counter. Raise it again and answer Yes: it gets one.
   - With the Pridemate still there, lower Ann's life or raise Bo's: Set asks nothing.
   - Restart: the totals are the same.
   - While a target prompt is open, tapping a life total picks that player as the target, and no life dialog opens.
5. **AC-38:** on Ann's turn, cast Lightning Bolt for Ann at Bo. While it's on the stack, add Counterspell for Cy, targeting the Bolt. OK, OK: Bolt is countered and Bo's life doesn't change.
6. **AC-41:**
   - On Ann's turn, put Grizzly Bears onto the battlefield for Ann and cast a second Grizzly Bears for Ann. Turn on "Combat this turn", then End Turn: the attack prompt can't pick either Bears.
   - Next turn cycle, repeat, but use "Ready creatures…" first and pick only the first Bears. The attack prompt offers that Bears only. Bo doesn't block and goes down 2.
   - Ready a Bears, then kill and relaunch before combat: it's still ready.
7. **AC-41, still open from 2026-11-03:** a real block, first strike with its stop before regular damage, the stops after attackers and blockers, and a forced attack (Juggernaut) with combat off.
8. **AC-35 (if chunk 8 is built):** cast Gray Merchant for Bo, then cast an edict-style spell for Cy that makes Ann sacrifice. The prompt bar names Ann as the one choosing.
9. **AC-36:** Gray Merchant for Bo drains Ann and Cy with no further input. Already covered by slice 0, so rerun as a regression check.
10. **AC-42:**
    - Put a card with an upkeep trigger on Bo's board (for example Phyrexian Arena), then End Turn from Ann. At the trigger's stop in Bo's upkeep, cast Divination for Bo: the trigger resolves, Bo draws for the turn, and Divination is cast in Main 1. The prompt says Main 1, and Bo's hand count shows all three draws.
    - With "Combat this turn" on, Bo attacks Ann with a ready creature. At the stop after attackers, cast Divination for Bo: Ann is asked for blocks, damage resolves, and Divination is cast in Main 2.
    - With combat off, in Bo's Main 1, cast Divination: it's cast at once in Main 1.
    - With an end-step trigger on the stack, cast Divination for Bo: it's cast in place, with a notice that the main phases are over.
    - During Bo's upkeep stop, cast Divination for Cy: it's cast in place.
11. 📱 On an APK with 6 players: the active ring is easy to tell apart, the life dialog's buttons are big enough to hit, and "Ready creatures…" shows the cards readably.

## Known gaps
- Prompts in pop-up dialogs (choose a mode, choose from a list) still don't name the deciding seat. Chunk 8 covers only the prompt bar, because `IGuiGame.one`/`many` aren't given the player. Naming the seat there means changing `PlayerControllerHuman`'s `getGui()` calls (`plan.md` 2.1), which is left for the owner to schedule.
- A life change can be changed or refused by a replacement or static effect on the table (D2). The op reports it and doesn't force the value.
- The life-trigger question (D2) doesn't see delayed triggers, and it judges the amount asked for, before replacement effects. So it can rarely miss a question or ask one whose trigger then doesn't fire.
- A sorcery whose sorcery half is on another face (split, adventure, MDFC back) doesn't wait for a main phase (AC-42): only the main face's type is read.
- Two sorceries logged in one parked window: when the first runs, it resolves without a stack stop for responses (AC-38). Tricks logged during a parked combat wait until after the parked sorcery, since cards added behind a parked op keep the table's order (the operator is told "… waits behind …"). Life edits and other non-card ops don't wait: they run at once.
- "Combat this turn" is still lost on a resume after the app was closed (by design, 2026-11-03).
- The yield-suggestion prompt from slice 0 (`YIELD_SUPPRESS_ON_OWN_TURN=false`) can still skip main 2 on OK.
- AC-42 can't know about combat at the table when engine combat is off, and a parked op is lost if the app is killed before it runs (D5).
- A save doesn't hold this turn's life tallies (life gained, times gained, lost, gained by the team). Starting or resuming a game sets them to 0 (`CompanionGameState.applyNow`), since Forge sets life from a state by a gain or loss that would otherwise count. A resume mid-turn (a save from Main 1 or 2) so loses what was really gained or lost earlier in that turn: a second gain can trigger "the first time each turn" again, and spectacle or "an opponent lost life this turn" don't see an earlier loss. The state is applied only at game start (the start hook), so a running game's tallies are never touched; undo (slice 4) will have to decide again.

## Out of scope
Everything deferred to slice 2 (above), other counters, commander damage, damage to creatures, undo (slice 4), history (slice 5).

## Build
Each chunk is one or two files, in this order, and can be tested on its own: compile, then run the named test or dev-build check. Forge's style applies: Java 17, Maven, TestNG in `forge-gui-desktop`. Every op runs through `CompanionTable.submit`, so it's on the game thread at a priority prompt (`CompanionPlayerController.chooseSpellAbilityToPlay` → `runPending`), and `TableEditor` methods never touch the UI. Chunks 10–12 (AC-42) don't depend on chunks 1–9 and can go first if the owner wants them sooner.

1. **Starting life in the setup record.**
   - Files: `forge-gui/src/main/java/forge/gamemodes/companion/CompanionSetup.java` and `TableEditorTest.java`.
   - Add `CompanionSetup withLife(int life)`, built like `withEngineCombat`.
   - The compact constructor throws `IllegalArgumentException` when `life < 1`.
   - Test: `startingLifeComesFromSetup`. A 3-seat table from `withDefaults(…).withLife(20)`, applied with `CompanionGameState.of(setup.initialState()).applyNow(game)`, has every player at 20. A `CompanionStore` JSON round trip keeps 20.
   - Check: `mvn -pl forge-gui-desktop -am test -Dtest=TableEditorTest`.
2. **The "Starting life" field on the setup screen.**
   - File: `forge-gui-mobile/src/forge/screens/companion/CompanionSetupScreen.java`.
   - Add an `FLabel` "Starting life" and an `FTextField` limited to four digits as `LifeDialog` limits its field (initial value `CompanionSetup.DEFAULT_LIFE`; built, revised 2026-11-04 from an `FNumericTextField`, which overflowed on 10+ digits), laid out in `doLayoutAboveBtnStart` below the −/+ player buttons.
   - `startMatch()` uses `CompanionSetup.withDefaults(seats).withLife(value)`, and shows an error dialog when the value is below 1.
   - Check: on the dev build, oracle step 1.
3. **`TableEditor.setLife`.**
   - Files: `forge-gui/src/main/java/forge/gamemodes/companion/TableEditor.java` and `TableEditorTest.java`.
   - Add `public static Result setLife(Player player, int life, boolean fireTriggers)` (built; revised 2026-11-04 from a two-argument version that always suppressed).
   - Without `fireTriggers`, suppress `TriggerType.LifeGained`, `LifeLost` and `LifeLostAll` with `TriggerHandler.suppressMode` for the call to `player.setLife(life, null)`. In a `finally`, clear only the modes that weren't already suppressed (`isTriggerSuppressed` before).
   - With `fireTriggers`, call `player.setLife(life, null)` unsuppressed, and after a loss run `LifeLostAll` with `AbilityKey.mapFromPIMap({player: lost})`.
   - Then `settle(game)`. It returns `Result(true, "<name>'s life is set to N")`. When `player.getLife() != life` afterwards, it returns `Result(false, "<name>'s life is M, not N: something on the table changes life gain or loss")`, as D2 describes.
   - Add `public static boolean lifeChangeCouldTrigger(Player player, int newLife)`, as D2 describes.
   - Tests: the "Set life" and "Could a life change trigger" cases under Tests.
4. **`TableEditor.readyCreatures`.**
   - Files: `TableEditor.java` and `TableEditorTest.java`.
   - Add `public static Result readyCreatures(Game game, Collection<Integer> cardIds)` (built 2026-11-04). For each id, `game.findById(id)`. When it's a creature on the battlefield, call `card.setSickness(false)`. Skip the rest.
   - It returns `Result(true, "Ready to attack: A, B")`, or `Result(false, "Nothing to ready")` when no creature was readied. Either way, skipped ids are named after it: `" (skipped, not a creature on the battlefield: Sol Ring, #123)"`, with `#id` for an id the game doesn't know.
   - Add `public static List<Card> sickCreatures(Player player)` (built 2026-11-04): the player's creatures in play with `Card.isSick()`, so haste counts as ready. It's for a game-thread caller; chunk 7's menu reads `CardView.isSick()` instead.
   - Tests: the "Ready creatures" cases. Use `CombatUtil.canAttack(card)` once the phase handler is in the active player's turn, and `assertFalse(card.isSick())`.
5. **A seam for tapping a life total.**
   - Files: `forge-gui-mobile/src/forge/screens/match/MatchExtension.java` and `forge-gui-mobile/src/forge/screens/match/views/VPlayerPanel.java`.
   - Add `default boolean tapLife(PlayerView player) { return false; }`, documented as "true if the mode handled the tap" (built 2026-11-04).
   - In `VPlayerPanel.LifeLabel.tap` (line 1671), first ask `MatchController.getExtension()`, which is non-null only while a mode's own match is shown. If it returns true, stop there. Otherwise keep today's `selectPlayer` call, so every other mode is unchanged.
   - Check: it compiles, and a normal Constructed match on the dev build still targets a player by tapping their life.
6. **The life dialog** (built 2026-11-04).
   - Files: `forge-gui/src/main/java/forge/gamemodes/companion/CompanionTable.java` and a new `forge-gui-mobile/src/forge/screens/companion/LifeDialog.java`.
   - `CompanionTable.atPriorityPrompt()` returns true when any registered seat's `getInputQueue().getInput()` is an `InputPassPriority`. That's the test `endPriorityPrompt` already uses; reuse it.
   - `LifeDialog extends FDialog`, titled "Life: <name>", with buttons Set and Cancel.
   - It shows the pending value in large type, with −5, −1, +1 and +5 buttons and a field for typing a value. It starts from `PlayerView.getLife()`. As built: the value is one large-type `FTextField` (numeric keyboard), not an `FNumericTextField`, which clamps at 0 and throws on overlong input. The field keeps only an optional `-` and up to six digits, a `+/-` button flips the sign (a numeric keyboard may have no minus), and Set does nothing while the field holds no number.
   - Set calls `table.submit(game -> …)`, finding the player by name as `CardSearchDialog.addCard` does. In that op, on the game thread, so the question and the change see the same table:
     - `boolean fire = TableEditor.lifeChangeCouldTrigger(p, value) && <ask>`, where `<ask>` is "Trigger life gain/loss effects?" with Yes and No, default No (it's a correction unless the operator says otherwise; D2). Ask through the GUI of the seat whose priority prompt is open: `IGuiGame.confirm(null, "Trigger life gain/loss effects?", false, List.of("Yes", "No"))`. When the query is false, nothing is asked and `fire` is false. As built: `CompanionTable.confirm(game, question, defaultIsYes)` does this, picking the registered seat whose player is the game's priority player (the one whose `chooseSpellAbilityToPlay` is running the ops), and answers the default when no seat has a GUI.
     - then `TableEditor.setLife(p, value, fire)`.
   - Check: it compiles. It isn't wired yet.
7. **Wire life and ready creatures into Companion's match** (built 2026-11-04).
   - File: `CompanionSetupScreen.java`, in the anonymous `MatchExtension` in `openGame`.
   - `tapLife(PlayerView p)`: when `table.atPriorityPrompt()`, open `new LifeDialog(table, p).show()` and return true. Otherwise return false, so a target prompt still gets the tap.
   - In `addGameMenuItems`: when any battlefield card in `MatchController.instance.getGameView().getPlayers()` has `CardView.isSick()`, add `FMenuItem("Ready creatures…", FSkinImage.ATTACK, …)`.
   - The item opens `GuiChoose.many("Ready creatures", "Pick the ones that were on the table before this turn", 0, n, sick, null, chosen -> table.submit(game -> TableEditor.readyCreatures(game, ids(chosen))))`.
   - As built: the sick list is read from the views on the UI thread when the menu opens (the menu is rebuilt each time), so there's no game-thread query and no empty picker: with no sick creature the item isn't shown. The picker is `GuiChoose`'s callback form, so nothing blocks; an empty pick submits nothing, and `readyCreatures` re-checks each id on the game thread.
   - Check: on the dev build, oracle steps 4 and 6, including the life-trigger question appearing only with a matching trigger on the table.
8. **AC-35 in the prompt bar (optional; build it if the owner keeps AC-35 in this slice).**
   - Files: `MatchExtension.java` and `forge-gui-mobile/src/forge/screens/match/MatchController.java`.
   - Add `default String promptMessage(PlayerView seat, String message) { return message; }`.
   - `MatchController.showPromptMessage` and `showPromptMessageNoCancel` (lines 281–289) pass the message through `getExtension()` when it's non-null.
   - Then add a one-line override in `CompanionSetupScreen`'s extension that returns `seat.getName() + " decides: " + message`.
   - Check: oracle step 8, and another mode's prompt is unchanged.
9. **Active-player highlight (only if oracle step 11 or the 6-seat dev-build check shows the ring is hard to see).**
   - Files: `MatchExtension.java` and `forge-gui-mobile/src/forge/screens/match/views/VAvatar.java`.
   - Add `default boolean emphasizesActivePlayer() { return false; }`.
   - When it's true, `drawPlayerIndicator` draws the turn ring at full width even in the compact layout (`ringWidth`), and skips the lime priority ring that overlaps it.
   - Then add a one-line override in `CompanionSetupScreen`.
10. **Which main phase a sorcery needs (AC-42)** (built 2026-11-04).
    - Files: `TableEditor.java` and `TableEditorTest.java`.
    - Add `static boolean waitsForMainPhase(PaperCard printing, Mode mode)`: `mode == CAST` and the printing's rules type is a sorcery. It's checked on the printing, before any card is made.
    - Add `public static PhaseType mainPhaseFor(Game game, Player player, String name, Mode mode)`. It returns `null` when:
      - the name isn't a card, or `!waitsForMainPhase`
      - `player` isn't `ph.getPlayerTurn()`
      - the phase is a main phase and the stack is empty
      - the phase is `END_OF_TURN` or `CLEANUP`

      Otherwise it returns `MAIN1` when `ph.getPhase().isBefore(PhaseType.COMBAT_BEGIN)`, and `MAIN2` if not (D5).
    - Tests: the seven "Main phase for a sorcery" cases.
    - As built: it also returns `null` when the phase handler has no phase (before the game starts). The tests cover every step of the turn (untap to cleanup) through a data provider, a sorcery in Main 1 and in Main 2 with a spell on the stack (`p0putonstack=`), which waits for that same main phase, an instant (Lightning Bolt) and an unknown name, besides the seven cases.
11. **Parked ops in the table (AC-42)** (built 2026-11-04).
    - Files: `CompanionTable.java` and `TableEditorTest.java`.
    - Add `public record AddCard(String player, String name, boolean fromHand, TableEditor.Mode mode) implements Op`. Its `run` finds the player by name, as `CardSearchDialog.addCard` does today, and calls `TableEditor.addCard`.
    - Give `Op` a `default PhaseType mainPhaseNeeded(Game game) { return null; }`. `AddCard` returns `TableEditor.mainPhaseFor(...)`.
    - In `runPending`, before running an op, ask it for `mainPhaseNeeded`. When that's non-null, move the op and everything still queued into a `parked` list, with the target phase and `ph.getTurn()`. Log "<name> waits for Main N" and stop.
    - Add `boolean isParked()` and `boolean parkedReady(Game game)`. The second is true when the phase handler is at the target with an empty stack, or when the turn has changed. In that case, `tell` "The turn ended before Main N: <name> is cast now".
    - Add `void runParked(Game game)`, which runs the parked ops in order, through the same result and log path as `runPending`.
    - Tests: the four "Parking" cases. Use `CompanionGameState.of(...).applyNow` with `activephase=UPKEEP`, then `MAIN1`, on an `AITest` game. Call `runPending`, `parkedReady` and `runParked` directly. They're package-private, and the test is in the same package.
    - As built:
      - `parkedReady` changes nothing. The "turn ended" notice is told by `runParked`, which the controller calls right after it. `parkedReady` is also true when the same turn is past the target (a skipped Main 1).
      - `runParked` asks each op `mainPhaseNeeded` again before running it. A Main 1 that was skipped makes the sorcery wait for Main 2. A second sorcery that finds the first on the stack waits for it to resolve. Past Main 2 (end step, cleanup) it's cast with the notice "The main phases of this turn are over: <name> is cast now". When the turn changed, every parked op runs at once, without being asked again, and the notice names the first.
      - While ops are parked, `runPending` puts newly queued card-adding ops (`Op.waitsBehindParked`, true only for `AddCard`) behind them instead of running them, with a "… waits behind …" notice; any other op, such as a life edit, runs at once (D5, "more than one parked op"). It returns true when it parked an op as well as when it ran one, so the controller asks the loop again and its parked branch (chunk 12) takes over.
      - "<name> waits for Main N" goes through `tell`, so the operator sees it and it's in the log.
      - `AddCard.run` reports a player name that isn't in the game as a failed `Result`, not an exception. `mainPhaseNeeded` returns `null` then, and an exception from `mainPhaseNeeded` also runs the op at once.
      - A restart (`register` with another game's seats) drops the parked ops, with a notice. They belonged to the old game.
      - `CardSearchDialog` still submits its lambda. Switching it to `AddCard` is chunk 12.
      - Tests: the four parking cases, plus a sorcery in a combat step waiting for Main 2, Main 1 with something on the stack, a skipped Main 1, two parked sorceries, ops that don't wait (an instant, another seat's sorcery, put onto the battlefield, a life edit), and an unknown player name.
12. **Let the controller wait for the main phase, and submit sorceries as `AddCard` (AC-42)** (built 2026-11-04).
    - Files: `CompanionPlayerController.java` and `forge-gui-mobile/src/forge/screens/companion/CardSearchDialog.java`.
    - In `chooseSpellAbilityToPlay`, before `runPending`:
      - When `table.isParked()` and `table.parkedReady(game)`, call `table.runParked(game)`, set priority to the active seat, and return an empty list, as after any op.
      - When `table.isParked()` and it isn't ready, call `table.runPending(game)`: a card joins the parked ones, any other op runs now. If it took nothing, return `null`, so every seat passes and the loop moves on through triggers, the draw and any engine combat.
      - `submit` still ends the prompt as before.
    - `declareAttackers` and blocker prompts are unchanged. They aren't priority, so the defenders are still asked during an engine combat.
    - `CardSearchDialog.addCard` submits `new CompanionTable.AddCard(player, name, fromHand, mode)` instead of its lambda.
    - Check: on the dev build, oracle step 10.
    - As built:
      - The parked branch is the first thing `chooseSpellAbilityToPlay` does, for every seat. When the parked ops aren't ready yet it still calls `runPending`, so a life edit or other non-card op submitted while parked runs at the next stop, and a card joins the parked ones behind the first (only cards keep the table's order).
      - Passing moves the game on: each `null` is a real pass, so with an empty stack the step ends once every seat has passed, and with something on the stack it resolves. `runParked` only parks again for a main phase still ahead in the turn (or the same one with a non-empty stack), and a changed turn runs everything, so it can't loop at one stop.
      - Combat: `declareAttackers` and `declareBlockers` are turn-based actions the phase handler calls on the declaring seat's controller, not priority, so they're asked as before. The trick stops after attackers and blockers are passed while parked (D5).
      - Tests (CI, in `TableEditorTest`, through the real `PhaseHandler.mainLoopStep` with a `CompanionPlayerController` subclass on every seat; it answers only where the seat would show the operator a prompt, plus the attack and block declarations and the one-ability choice the match screen makes without asking):
        - from the untap step, with Phyrexian Arena on the active player's board, Divination logged at the trigger's stop: the operator is asked only at the trigger and then in Main 1 with Divination on the stack; the trigger resolved (−1 life), both draws happened, notice "Divination waits for Main 1".
        - with "Combat this turn" on, Grizzly Bears attacks, Divination logged at the stop after attackers: the defender is asked for blocks, the blockers stop is passed, 2 damage is dealt, and Divination is on the stack in Main 2.
        - a life edit submitted in the draw step while Divination is parked is made in the draw step, not after Divination in Main 1.
        - With the parked branch disabled, both fail (the seat reaches a real prompt).
      - Still for the oracle: the prompts themselves on the device (target, "may" and attack and block dialogs while parked), and the screen while the seats pass (no prompt is shown until the target is reached).

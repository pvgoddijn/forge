# Slice 6 — Decks and commanders

**Depends on:**
- **Slice 0:** `CompanionSetup`, `CompanionStore` (`setup.json`), `CompanionMatch`, `TableEditor`, and the op queue in `CompanionTable`.
- **Slice 1:** the capture flow. The commanders are photographed at setup, and Retake reopens the camera: `IDeviceAdapter.photographCard`, `CardNameReader`, and `CardSearchDialog.addByPhoto`. Photo ranking by deck (AC-12) changes what the reader's names are ranked against.
- **Slice 3:** its parked ops (`CompanionTable` chunks 11–12, for its AC-42: a sorcery waits for a main phase). Skipping ahead to the player a card belongs to is the same mechanism with a later target: the op waits, every seat passes, and the engine runs the turns in between, as End Turn does. The default target, the active seat (AC-17), is already in `CardSearchDialog`.
- **Slice 4, only for its oracle:** undoing a turn skip. Not needed to build.

**Not needed: slice 2 (deferred).** The earlier plan said commanders and typed search needed it, because zones were ours to build then. On the engine they aren't. A commander gets into the Command zone through the starting game state (`|IsCommander`, which `GameState` already reads). It's cast from there through a small `TableEditor` change in this slice. Typed search only needs `CardNameMatcher`. Moving a commander by hand, other than casting it, would use slice 2's `moveTo` if that is built; the engine's own "put it in the command zone instead?" replacement covers the usual case.

**Proves:** the app can refuse a card that can't be in a player's deck, and use that to catch the operator's most common slip, a forgotten End Turn.

## Result
At setup, each player's commander is photographed. A player's decklist can be attached too, and its cards rank first in suggestions. During the game, Commander mode (on by default) won't add a card that's outside a player's commander's colour identity. If the card would fit one of the next players, the app offers to move the turn ahead to that player and add it there.

## Acceptance criteria
From the spec:
- **AC-3** WHEN setting up a player THE SYSTEM SHALL let the user optionally attach a loaded deck, imported the same way Forge's deck import accepts decks, and optionally pick a commander for that player.
- **AC-4** WHEN a player has a commander THE SYSTEM SHALL place it in that player's Command zone at game start.
- **AC-12** WHEN a loaded deck exists for the target player THE SYSTEM SHALL rank that deck's cards above other matches, and SHALL still offer matches from the whole card database.
- **AC-15 (deck part)** The target player's loaded deck cards appear first in typed search.

New (owner, 2026-11-04). **Not in `spec.md` yet; numbered here as proposals after slice 3's AC-42, to add to the spec with a decision-log entry:**
- **AC-43** THE SYSTEM SHALL offer a "Commander mode" setting for each game, on by default, on the setup screen and in the game menu. WHILE it is on THE SYSTEM SHALL not add a card that is illegal for the target player's commander: outside the commander's colour identity, as Forge's deck rules decide it (partners and backgrounds combined, plus cards whose own rules allow other colours).
- **AC-44** WHEN a game is set up with Commander mode on THE SYSTEM SHALL ask for each player's commander in seat order, photo first, with typing and "no commander" as alternatives, and offer a second commander when the first can have a partner or background. Only cards that can be a commander are suggested.
- **AC-45** WHEN Commander mode is on AND a card added for the active player is illegal for them AND it is legal for one of the next players in turn order THE SYSTEM SHALL name the first such player and how many turns ahead they are, and offer: move the turn ahead to that player and add it there; Retake (a new photo); Cancel. Moving ahead SHALL run every skipped turn's steps, so their draws and upkeep effects happen, and SHALL be one action for undo.
- **AC-46** WHEN Commander mode is on AND the card is legal for no player THE SYSTEM SHALL refuse it, saying why, and offer Retake and Cancel. The same goes WHEN the operator picked a target that isn't the active player and the card is illegal for that target.

What isn't checked, so a legal play is never blocked:
- tokens, emblems, and names that aren't in the card database;
- a card added "From elsewhere": it can be someone else's card (Gonti, a stolen spell, a card cast from an opponent's graveyard). Only "From hand" is the player's own deck. **Owner to confirm.**
- a seat with no commander;
- Forge's Commander banned list. `DeckFormat.Commander.isLegalCard` has it, but tables often agree to play banned cards, and a banned card says nothing about whose turn it is. **Owner to confirm.**

**Conflict with the spec:** AC-39 and Q20 say the tracker never refuses the operator because of the rules. AC-43 and AC-46 do refuse. The way out is that Commander mode can be switched off at any time from the game menu, and the refusal always says so. The spec needs a line saying that.

## How the turn skip goes through the engine
The dialog decides on the UI thread; the engine only ever acts on the game loop thread, through the existing interfaces:
1. `CardSearchDialog` (UI thread) asks `CommanderLegality` about the card, the target seat, the active seat (`GameView.getPlayerTurn`) and the seat order (`CompanionSetup.seats`). Pure, no game access.
2. On "Move to Carol's turn", it submits slice 3's `CompanionTable.AddCard` op for Carol, with a target turn: `AddCard` gains an optional `skipToSeat`. Its `mainPhaseNeeded` then asks for Main 1 of that seat's turn, not a main phase of this one.
3. `CompanionTable.runPending` parks it, as slice 3 parks a sorcery. While it's parked, `CompanionPlayerController.chooseSpellAbilityToPlay` returns `null`, so every seat passes and the engine moves on. Every skipped turn runs its untap, upkeep, draw and end steps by the rules, because those turns really happened at the table. Choices their triggers need are still asked, naming the seat.
4. `parkedReady` is true at Main 1 of the target seat's turn with the stack empty. Slice 3's "the turn changed, so cast it now" rule doesn't apply to a skip: a turn change is the point. The op then runs through `runParked`, and only after that does the game save. So the whole skip and the card are one save, and one undo step (slice 4). As slice 3 says of parked ops, a kill during the skip loses it, and the operator logs the card again.
5. No core change: `PhaseHandler.setPlayerTurn` isn't used. It would jump straight to the seat and skip the draws that happened at the table.

Skipped turns run no engine combat, even with "combat every turn" on: those fights are over, and the operator sets their outcome by hand. A creature that must attack in a skipped turn is the exception; see "check early".

## Build
Small chunks, in order. Each is one or two files.

1. **`CommanderLegality` (new, forge-gui, `forge.gamemodes.companion`):** `isLegal(PaperCard card, List<PaperCard> commanders)`. Colour identity from `CardRulesPredicates.hasColorIdentity`, plus `DeckFormat.allowsOffColorIdentity` and `approvesAdditionalColor` with the commanders' `DeckRuleColorIdentity` rules, as `DeckFormat.isLegalCardForCommanderPredicate` builds them. Not that predicate itself: for one partner commander it also accepts any card that could partner with it, which is right in the deck editor and wrong here. A seat with no commanders accepts everything. Tests in chunk 1 (see Tests).
2. **`CommanderLegality`:** `firstLegalSeatAfter(PaperCard card, CompanionSetup setup, String activeSeat)` returns the first later seat in turn order whose commanders allow the card, and how many turns ahead it is, or none. Turn order is `setup.seats()` order, wrapping round, never the active seat itself.
3. **`CompanionSetup` (record):** `Seat` gains `List<String> commanders` and `String deckList` (empty when none); the setup gains `boolean commanderMode`, with `withCommanderMode` like `withEngineCombat`. `initialState` writes `p<i>command=<name>|IsCommander` for each commander, and the library is 100 less the commanders and the hand. **`CompanionStore`:** `setup.json` version 3 with those fields. An older save reads with no commanders and Commander mode off, as it started without them.
4. **`CompanionMatch`:** when any seat has a commander, add the Commander variant to the rules (`GameRules.addAppliedVariant(GameType.Commander)`), so the engine's command-zone rules apply: casting from the Command zone, the "put it in the command zone instead?" replacement, tax. Decks stay empty `Deck`s: the libraries remain placeholders (spec A6), so hand counts stay right.
5. **`TableEditor.addCard`:** when the player's Command zone holds a commander of that name, cast that card from there, whatever "From" says. The hand isn't touched.
6. **`CardPicker` (new, forge-gui-mobile) + `CardSearchDialog`:** take the photo-then-search flow out of `CardSearchDialog` into a picker that returns a card name to a callback, with an optional `Predicate<PaperCard>` filter and a title ("Alice's commander"). `CardSearchDialog` then uses the picker and keeps the player, from and mode fields and the submit.
7. **`CompanionSetupScreen`:** a "Commander mode" check box, on by default. With it on, Start runs a commander step: for each seat in seat order, `CardPicker` with `DeckFormat.Commander.isLegalCommanderPredicate()` as the filter, photo first. Then "Add partner" when `CardRules.canBePartnerCommanders` allows a second, "No commander", and Next. The game starts after the last seat.
8. **`CardSearchDialog`:** on Add, with Commander mode on, a card from hand, and a real card: `CommanderLegality` for the target. Illegal and the target is active: `firstLegalSeatAfter`, then AC-45's three-button `FOptionPane` ("Move to Carol's turn (2 turns ahead)", "Retake", "Cancel"), or AC-46's two-button one. Retake reopens the camera through the picker, keeping the player and mode; for a typed card it is "Edit" and returns to the search. Cancel closes it with nothing added.
9. **`CompanionTable`:** `AddCard.skipToSeat`, and a parked target that is a seat's turn as well as a phase: `parkedReady` waits for that seat's Main 1 and doesn't fire on a turn change. Clear parked ops on an undo or restart, so one never runs in another game state. Tests beside slice 3's "Parking" ones, the same way: `applyNow` a state, then call `runPending`, `parkedReady` and `runParked` directly.
10. **`CompanionPlayerController`:** no engine combat in a skipped turn, even with "Combat every turn" on: `declareAttackers` declares nobody while a skip is parked and the turn isn't the target's. The forced-attack case is in "check early".
11. **Companion's `MatchExtension`:** a game-menu item "Commander mode: on/off", saved in `setup.json` like "Combat every turn".
12. **Deck import, `CompanionSetupScreen` + a small `CompanionDeckDialog` (new, forge-gui-mobile):** paste a decklist, parsed with `DeckImportController` (forge-gui). `FDeckImportDialog` is tied to `FDeckEditor`'s config (`FDeckEditor:588`); use it only if it opens without an editor, otherwise call the controller directly. Also allow a saved deck. The deck's Commander section fills that seat's commanders, so the commander step only confirms them. The decklist text goes in `setup.json`, so the game stays self-contained if the saved deck changes.
13. **`CardNameMatcher`:** `match(text, limit, Set<String> preferred)`. Preferred names rank first among the matches that pass the matcher's own cut-off; the rest follow. A deck never hides a match from outside it. `CardSearchDialog` passes the target seat's deck.
14. **Photo ranking:** after a read, `CardSearchDialog` merges the reader's names with `match(reading.textRead(), …, deck)`, deck cards first, so a deck card the reader ranked 4th or missed can still come up (AC-12). With Commander mode on, cards illegal for the target show greyed, but stay in the list.

## Check early
- **Commander variant on a placeholder game:** `Match.prepareAllZones` with `GameType.Commander` applied and empty decks, then `GameState` putting the commanders in. The commander effect is made by `GameState` on load (`p.createCommanderEffect`), so it should survive resume and undo.
- **"Can't lose the game":** the table effect also stops a loss by 21 commander damage. That's right for v1 (commander damage is out of scope), but write it down.
- **A creature that must attack in a skipped turn:** declaring nobody isn't legal there (slice 3's forced-combat case). Proposed: the skip stops at that combat with slice 3's notice, the operator declares it, and the skip carries on.
- **A trigger needing a choice in a skipped turn:** the prompt names the seat, and the skip carries on after it.
- **Retake after a skip was offered:** nothing is parked until the operator confirms, so Retake and Cancel have nothing to drop. Check that a second Add while a skip is parked queues behind it, as slice 3 says.

## Tests (CI)
- `CommanderLegality`, with Forge's real card database (as `TableEditorTest` loads it): Llanowar Elves is legal under Marwyn, the Nurturer and not under Krenko, Mob Boss; Sol Ring is legal for everyone; a hybrid card follows Forge's rule; a partner pair's identity is the union of both; a seat with no commander accepts anything.
- `firstLegalSeatAfter`: finds the next legal seat in turn order, wraps round past the last seat, never returns the active seat, and returns none when nobody can play it.
- `CardNameMatcher` with a deck: a deck card ranks above a closer-spelled non-deck card only within the rule set here (write the rule in the test); a non-deck card is still offered; with no deck, ranking is unchanged.
- Parking with a seat target, on an `AITest` game as in slice 3: not ready during the next seat's turn, ready at the target seat's Main 1, and not cast early on a turn change. The walk through the loop (each skipped seat draws once) is a dev-build check, as CI doesn't drive the main loop yet (slice 0's open item).
- A commander in `initialState` is in the Command zone after the start hook, and still there after a `GameState` round trip.

## Oracle
On the dev build with image files (`-Dforge.companion.photos=true`), 3 players in seat order Alice, Bob, Carol:
1. Setup with Commander mode on: photograph Krenko, Mob Boss for Alice, Talrand, Sky Summoner for Bob, and Marwyn, the Nurturer for Carol. The game starts with each one in its player's Command zone.
2. Alice's turn: photograph Llanowar Elves. The app says it isn't legal for Alice, but is for Carol, 2 turns ahead. Choose move ahead: Bob's and Carol's draw steps run (each hand +1), it's Carol's turn, and the Elves are on Carol's battlefield.
3. Undo once: Alice's turn again, no Elves, the hands as before.
4. Photograph Swords to Plowshares (white): refused, legal for nobody, with Retake and Cancel. Retake opens the camera. Cancel leaves the game unchanged.
5. Turn Commander mode off in the game menu: Swords to Plowshares is added. Turn it back on.
6. Add Krenko for Alice: it's cast from the Command zone, and Alice's hidden hand count doesn't change.
7. Paste a decklist for Bob. Type part of a name matching both a deck card and other cards while targeting Bob: the deck card comes first, others still listed. Target Carol, who has no deck: normal ranking.
8. Photograph a card in Bob's deck while targeting Bob: it ranks first.
9. Kill and relaunch: commanders, Commander mode and deck ranking are all still there.
10. 📱 Step 1's setup on a phone, with real cards.

## Out of scope
Commander damage, commander tax tracking beyond what the engine does, the banned list (unless the owner wants it), library tracking (spec A6), and checking deck size or singleton.

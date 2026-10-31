# Slice 6 — Decks and commanders

**Depends on:** slice 2 for commanders and typed search. Photo ranking also needs slice 1.

## Result
A player's deck can be imported. Its cards rank first in suggestions and search. A commander starts in the Command zone.

## Acceptance criteria
- **AC-3** WHEN setting up a player THE SYSTEM SHALL let the user optionally attach a loaded deck, imported the same way Forge's deck import accepts decks, and optionally pick a commander for that player.
- **AC-4** WHEN a player has a commander THE SYSTEM SHALL place it in that player's Command zone at game start.
- **AC-12** WHEN a loaded deck exists for the target player THE SYSTEM SHALL rank that deck's cards above other matches, and SHALL still offer matches from the whole card database.
- **AC-15 (deck part)** The target player's loaded deck cards appear first in typed search.

## Build
- **Setup screen:** reuse `forge-gui-mobile/src/forge/deck/FDeckImportDialog.java`. It is currently opened from `FDeckEditor.java:588`, so check whether it can be used outside the editor, and make the smallest change needed. Also allow picking a saved deck. Commander: from the deck's commander section, or by name.
- **Domain:** `GameStarted` stores each player's deck list and commander, so a game stays self-contained if the saved deck later changes.
- **`CardNameMatcher`:** an optional deck parameter. Deck cards rank first, then everything else. A deck must never hide a match from outside it.

## Tests (CI)
Matcher: a deck card ranks above a closer-spelled non-deck card only within the rules you set here, so state them in the test; a non-deck card is still offered; with no deck, ranking is unchanged. Replay: a commander starts in Command.

## Oracle
On the dev build:
1. Paste a decklist for player 1, and set a commander. The game starts with it in player 1's Command zone.
2. Type part of a name matching both a deck card and other cards while targeting player 1: the deck card comes first, and others are still listed.
3. Target player 2, who has no deck: normal ranking.
4. Capture a photo of a card in the deck: it ranks first.
5. Move the commander from Command to Battlefield: the hidden hand count doesn't change.
6. Restart: the deck ranking still works.

## Out of scope
Commander damage, commander tax, library tracking.

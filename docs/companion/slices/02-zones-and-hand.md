# Slice 2 — Zones and hand

**Status (2026-11-04): deferred.** The owner decides later whether to build it, fold parts of it into other slices, or drop it. Slice 3 is stacked on slice 1 and doesn't wait for this one.

**Depends on:** slice 0 only (`TableEditor`, the op queue in `CompanionTable`, the placeholder card in `CompanionCards`). It doesn't need slice 1 or 3. **Proves:** any card can be moved anywhere through the engine, and hidden hand counts stay right when it is.

**Needed by:** nothing that is planned. Slice 4's "wrong player" builds its own narrow `TableEditor.reassign`, and slice 6 casts a commander from the Command zone through its own `TableEditor` change. If this slice is built later, its `moveTo` becomes the general version of both.

## What the engine already does (2026-11-04)
Much of the first goal already works without this slice, because the engine holds the zones:
- **AC-18:** a card added with "Cast it" goes where the rules send it: a permanent to the battlefield, an instant or sorcery to the graveyard. "Put onto battlefield" covers the rest.
- **AC-19, AC-20:** `TableEditor.addCard` with "From hand" takes the player's known copy if their hand holds one, otherwise one placeholder, and none if the hand is empty.
- **AC-21:** a card an effect returns to a hand (a bounce) is a real card there, so it's a known card.
- **Hand count:** the engine's draws add placeholders, and `CompanionCards.hiddenInHand` counts them.
- **AC-23:** `MatchScreen` stacks every player's board, with life and hand count on the avatar and the zones as tabs. Hands are closed at the start (`MatchExtension.showsHandsAtStart`), so the operator taps a hand to see known cards.

What's missing is a move the operator makes by hand (one the engine didn't make), hand ±1, and naming a placeholder that becomes public.

## Result
Any card can be moved to any zone of any player, and a hand count can be nudged by one. When an effect discards, mills or reveals an unknown card, the operator can say what it was.

## Acceptance criteria
- **AC-5 (hand part)** WHEN a game starts THE SYSTEM SHALL set every player's hidden hand count to 7, and let the operator change it before the first turn.
- **AC-18** WHEN a card is added THE SYSTEM SHALL use these defaults, which the operator can change before confirming: the card comes **from** Hand; it goes **to** Battlefield if it's a permanent, otherwise to Graveyard. (Cast and "put onto battlefield" already do this. This slice adds a "to" zone for a card that is neither cast nor put onto the battlefield, such as a discarded one.)
- **AC-19** WHEN a card leaves Hand and is not a known card THE SYSTEM SHALL reduce that player's hidden hand count by 1, but never below 0.
- **AC-20** WHEN a card leaves Hand and is a known card THE SYSTEM SHALL remove that known card and leave the hidden count unchanged.
- **AC-21** WHEN the operator moves a card from any zone to Hand THE SYSTEM SHALL record it as a known card in that hand.
- **AC-22** THE SYSTEM SHALL let the operator move any card between any two zones of any players (for example when control changes), and change any player's hidden hand count by ±1 (for example draw or discard).
- **AC-23** WHEN viewing the game THE SYSTEM SHALL show, for every player at once: name, life, the hidden hand count, the known cards in hand, and the Battlefield. Graveyard, Exile and Command SHALL be reachable with one tap.

## Build
Small chunks, in order. Each is one or two files, and compiles and runs on its own. See `plan.md` decisions 3 and 4. Every operator action goes through `CompanionTable.submit`, so it runs on the game loop thread and the game saves after it.

1. **`TableEditor` (forge-gui):** `moveTo(Card card, Player player, ZoneType zone)`, through `GameAction.moveTo` so zone-change triggers fire. A move to another player's battlefield changes control (`Card.setController`, then `controllerChangeZoneCorrection`). A move out of a hand needs no special case: the card is real, so it's a known card (AC-20). Returns a `Result` like `addCard`.
2. **`TableEditor`:** `changeHand(Player player, int delta)`. +1 adds `CompanionCards.newPlaceholder`; −1 removes one placeholder, and does nothing when there is none (AC-19's clamp). No draw trigger: the table already drew, or the operator is fixing a count.
3. **`TableEditor`:** an `addCard` overload with a "to" zone, for a card that is neither cast nor put onto the battlefield (AC-18's Graveyard default for a discard, or Exile). The existing `addCard` keeps its signature and calls it.
4. **`CardSearchDialog` (forge-gui-mobile):** a "to" drop-down beside "Cast it / Put onto battlefield", defaulted from the card's type once one is chosen (AC-18).
5. **`CompanionSetupScreen`:** a starting hand size field (AC-5), written into `CompanionSetup.handSize`. The library size follows: 99 less the hand.
6. **`MatchExtension` + `MatchScreen` (forge-gui-mobile):** a card-tap hook, `addCardMenuItems(CardView, FDropDownMenu)`, empty by default. Companion adds "Move…", which asks for the player and zone with Forge's own choosers, then submits `TableEditor.moveTo`. Offered only at a priority prompt (plan risk "ops through `restartPrompt`").
7. **`MatchExtension` + `VPlayerPanel`:** an avatar-tap hook. Companion adds "Hand +1" and "Hand −1".
8. **`CompanionPlayerController`:** when an effect makes a placeholder public (a discard, mill, reveal or search; plan decision 3), ask the operator to name it, using `CardNameMatcher`. Skip leaves it unknown. Where to hook is the first "check early" item.
9. **`CompanionPlayerController`:** the seat's name in prompt titles (plan 2.1, AC-35), only if slice 3 hasn't done it.

## Check early
- **Where a placeholder becomes public:** a random discard is chosen by the engine, not by a controller, so `chooseCardsToDiscardFrom` won't see every case. Candidates: a game-thread listener on `GameEventCardChangeZone` from Hand or Library to a public zone, or a hook in `GameAction.changeZone`. Prefer the one that needs no core change, otherwise the smallest hook.
- **Control change through `moveTo`:** the card keeps its new controller through a save and resume.
- **Known cards in a closed hand:** whether the operator notices a known card when hands start closed. If not, open a hand row as soon as it holds a real card.

## Tests (CI)
Through the real main loop, with a scripted operator: a move to Hand leaves a real (known) card there, and moving it out leaves the placeholder count alone; hand −1 at 0 stays 0; a move to another player's battlefield changes control and survives a `GameState` round trip; a discard of a placeholder asks for a name.

## Oracle
On the dev build with 3 players:
1. Add Sol Ring to player 1, cast: Battlefield, hidden count 6.
2. Add Lightning Bolt to player 1, targeting player 2: Graveyard, player 2 loses 3, hidden count 5.
3. Move Sol Ring to Hand: it shows as known, count still 5. Move it back out: count still 5, known card gone.
4. Hand −1 on a player with 0: count stays 0.
5. Move a card to player 2's Battlefield: it shows there, under player 2's control.
6. Kill and relaunch: everything is the same.
7. Cast Mind Rot at player 2 (two hidden cards): the operator is asked to name the two discards, or skip.
8. 📱 With 6 players, all AC-23 information fits or is one tap away.

## Out of scope
Turns and life (slice 3), undo and wrong-player correction (slice 4), commanders (slice 6), library tracking (spec A6).

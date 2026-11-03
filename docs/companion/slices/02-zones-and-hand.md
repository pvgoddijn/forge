# Slice 2 — Zones and hand

**Depends on:** slice 0.

## Result
Cards land in the right zone by default and can be moved anywhere. Hand counts and known cards stay correct. This delivers the first goal: seeing what opponents have played and what's known about their hands.

## Terms
**Hidden hand count:** how many cards a player holds that the app doesn't know. **Known card:** a card known to be in a hand, for example bounced back. It is shown alongside the count.

## Acceptance criteria
- **AC-5 (hand part)** WHEN a game starts THE SYSTEM SHALL set every player's hidden hand count to 7, and let the operator change it before the first turn.
- **AC-18** WHEN a card is added THE SYSTEM SHALL use these defaults, which the operator can change before confirming: the card comes **from** Hand; it goes **to** Battlefield if it's a permanent, otherwise to Graveyard.
- **AC-19** WHEN a card leaves Hand and is not a known card THE SYSTEM SHALL reduce that player's hidden hand count by 1, but never below 0.
- **AC-20** WHEN a card leaves Hand and is a known card THE SYSTEM SHALL remove that known card and leave the hidden count unchanged.
- **AC-21** WHEN the operator moves a card from any zone to Hand THE SYSTEM SHALL record it as a known card in that hand.
- **AC-22** THE SYSTEM SHALL let the operator move any card between any two zones of any players (for example when control changes), and change any player's hidden hand count by ±1 (for example draw or discard).
- **AC-23** WHEN viewing the game THE SYSTEM SHALL show, for every player at once: name, life, the hidden hand count, the known cards in hand, and the Battlefield. Graveyard, Exile and Command SHALL be reachable with one tap. (Life appears in slice 3.)

## Build (redone on the rules engine, 2026-11-03)
The engine holds the zones, so most of this slice is operator ops on `TableEditor` and the placeholder rules (`plan.md` decisions 3 and 4).
- `TableEditor`: `moveTo(card, player, zone)` for any card to any zone of any player; `handPlusOne`/`handMinusOne` (add or remove a placeholder, never below 0). A card added "from Hand" removes a placeholder unless that player's hand holds the real card (AC-19, AC-20). A move to Hand makes the real card a known card (AC-21).
- Add dialog: from/to zones with the AC-18 defaults from the card's type, plus "cast" or "put onto the battlefield".
- Tapping a card on `MatchScreen` during the operator's prompt offers "Move…". Tapping an avatar offers hand ±1, using Forge's own chooser.
- `CompanionPlayerController`: when an effect would reveal, discard, mill or search a placeholder, ask the operator to name the card. If they skip, it stays unknown.
- Seat name in prompt titles (AC-35), if slice 0 shows the existing titles confuse.

## Tests (CI)
Through the real main loop: default destination per card type; hidden count down by one and clamped at 0; a known card leaves without changing the count; a move to Hand creates a known card; a move between players; a discard of a placeholder asks for a name.

## Oracle
On the dev build with 3 players:
1. Add Sol Ring to player 1: Battlefield, hidden count 6.
2. Add Lightning Bolt to player 1: Graveyard, hidden count 5.
3. Move Sol Ring to Hand: it shows as known, count still 5. Move it back out: count still 5, known card gone.
4. With a hand count of 0, add a card: count stays 0.
5. Move a card to player 2's Battlefield: it shows there.
6. Restart: everything is the same.
6a. Cast Mind Rot at player 2 (two hidden cards): the operator is asked to name the two discards, or skip.
7. 📱 With 6 players, all AC-23 information fits or is one tap away.

## Out of scope
Turns, undo, wrong-player correction (slice 4), commanders (slice 6).

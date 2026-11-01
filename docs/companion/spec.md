# Companion Mode — Spec

_ADLC phase 1 (Specify). This document is the **what**, not the how: no technology choices belong here. Those go in `plan.md`._

Status: **approved** · 2026-10-31

## 1. Problem

At a paper Magic game, especially Commander with 3–6 players, it's hard to keep track of what each opponent has played and what is known about their hand. Companion Mode is a new mode in Forge mobile. One person at the table uses their phone to log the real game: they photograph cards as they're played, or type a card's name, and the app keeps a per-player view of every zone plus a full history of the game.

**First goal:** at a glance, see what each opponent has played and what is known about their hand.

## 2. Users and context

- **Operator:** one person with one phone, logging actions for every player at the table, including themselves.
- **Table:** 2–6 players, with Commander as the main format.
- **Setting:** often no reliable network (game stores, kitchen tables), with normal indoor lighting and sleeved cards.

## 3. Glossary

| Term | Meaning |
|---|---|
| **Game** | One tracked paper game: its players, their zones, the turn state and the history. |
| **Player** | A seat at the table, with a name and a seat order. Exactly one player is marked **Me**, the operator. |
| **Zone** | Hand, Battlefield, Graveyard, Exile or Command, kept for each player. |
| **Hidden hand count** | How many cards a player holds that the app doesn't know. |
| **Known card** | A card the app knows is in a player's hand, for example because it was revealed or bounced back. It is shown alongside the hidden count. |
| **Capture** | Taking a photo of a single card so the app can identify it. |
| **Suggestion** | A candidate card offered after a capture or a name search. |
| **Loaded deck** | A decklist attached to a player, used to rank suggestions. |
| **Action** | Any change to the game: a card moved, the turn passed, a life total changed, and so on. Every action is recorded. |
| **History** | The ordered list of actions in a game. |

## 4. Scope (v1)

In scope:
- A new **Companion** entry in Forge mobile's mode selection.
- Game setup: 2–6 players with names, seat order, Me, and an optional loaded deck and commander for each.
- Card identification from a photo of **one** card, reading its printed name, entirely on the device.
- Adding a card by typing its name. This is always available.
- Zones for each player, moving cards between them, and a hand count with known cards.
- A turn counter and whose turn it is.
- Life totals.
- A full action history, with undo, saved automatically, and browsable for past games.
- Android as the target platform, plus the desktop mobile-dev build for development, where a capture can come from an image file.

## 5. Acceptance criteria (EARS)

### 5.1 Entry and setup
- **AC-1** WHEN the user opens the mode selection in Forge mobile THE SYSTEM SHALL offer a "Companion" entry.
- **AC-2** WHEN the user starts a new game THE SYSTEM SHALL let them enter 2–6 players with a name and seat order, and mark exactly one as Me.
- **AC-3** WHEN setting up a player THE SYSTEM SHALL let the user optionally attach a loaded deck, imported the same way Forge's deck import accepts decks, and optionally pick a commander for that player.
- **AC-4** WHEN a player has a commander THE SYSTEM SHALL place it in that player's Command zone at game start.
- **AC-5** WHEN a game starts THE SYSTEM SHALL set every player's hidden hand count to 7 and life to 40, and let the operator change both before the first turn.

### 5.2 Capture and recognition
- **AC-6** WHEN the operator starts a capture THE SYSTEM SHALL show a camera view and take a photo of one card.
- **AC-7** WHEN a photo has been taken THE SYSTEM SHALL identify the card by reading its printed name and matching it against Forge's card database, without any network connection.
- **AC-8** WHEN identification finishes in confirm mode THE SYSTEM SHALL show the best suggestion plus up to 3 alternatives, a "search by name" option, and a cancel option.
- **AC-9** WHEN identification finishes in auto mode and the best suggestion's confidence passes the threshold THE SYSTEM SHALL add the card at once and offer an undo prompt. Below the threshold it SHALL behave as in confirm mode.
- **AC-10** THE SYSTEM SHALL default to confirm mode and SHALL offer a setting to switch to auto mode.
- **AC-11** WHEN the name cannot be read confidently THE SYSTEM SHALL open the name search with any partially read text already filled in.
- **AC-12** WHEN a loaded deck exists for the target player THE SYSTEM SHALL rank that deck's cards above other matches, and SHALL still offer matches from the whole card database.
- **AC-13** WHEN the card is double-faced THE SYSTEM SHALL recognize it from either face's name.

### 5.3 Adding by name
- **AC-14** THE SYSTEM SHALL let the operator add a card by typing at any time, without having to capture first.
- **AC-15** WHEN the operator types at least 2 characters THE SYSTEM SHALL show matching card names that tolerate typos, with the target player's loaded deck cards first.
- **AC-16** THE SYSTEM SHALL let tokens and emblems be added by name.

### 5.4 Zones, hand, and moves
- **AC-17** WHEN a card is added THE SYSTEM SHALL target the active player by default and let the operator change the target player before confirming.
- **AC-18** WHEN a card is added THE SYSTEM SHALL use these defaults, which the operator can change before confirming:
  - the card comes **from** Hand;
  - it goes **to** Battlefield if it's a permanent, otherwise to Graveyard.
- **AC-19** WHEN a card leaves Hand and is not a known card THE SYSTEM SHALL reduce that player's hidden hand count by 1, but never below 0.
- **AC-20** WHEN a card leaves Hand and is a known card THE SYSTEM SHALL remove that known card and leave the hidden count unchanged.
- **AC-21** WHEN the operator moves a card from any zone to Hand THE SYSTEM SHALL record it as a known card in that hand.
- **AC-22** THE SYSTEM SHALL let the operator move any card between any two zones of any players (for example when control changes), and change any player's hidden hand count by ±1 (for example draw or discard).
- **AC-22a** WHEN the operator uses "wrong player" on a card (for example it was logged after forgetting to pass the turn) THE SYSTEM SHALL reassign it to the chosen player's same zone, restore the original player's hand as if the card had never left it, apply AC-19/AC-20 to the chosen player, and show the correction in the history.
- **AC-23** WHEN viewing the game THE SYSTEM SHALL show, for every player at once: name, life, the hidden hand count, the known cards in hand, and the Battlefield. Graveyard, Exile and Command SHALL be reachable with one tap.

### 5.5 Turns and life
- **AC-24** WHEN the operator taps "next turn" THE SYSTEM SHALL make the next player in seat order active, and increase the turn number when play comes back around to the first player.
- **AC-25** THE SYSTEM SHALL let the operator change any player's life total.

### 5.6 History, undo, persistence
- **AC-26** WHEN any action happens THE SYSTEM SHALL add it to the history with the turn number, the active player, the player affected, and what was done.
- **AC-27** WHEN the operator taps undo THE SYSTEM SHALL revert the most recent action that hasn't been undone, and SHALL keep showing it in the history marked as undone. Undo SHALL be repeatable back to the start of the game.
- **AC-28** WHEN viewing the history THE SYSTEM SHALL let the operator filter it by player.
- **AC-29** WHEN the app is closed, killed or the phone restarts during a game THE SYSTEM SHALL resume that game in exactly the state it was left.
- **AC-30** THE SYSTEM SHALL keep one active game at a time, and SHALL let the operator end it. Ended games stay available to browse, read-only.

### 5.7 Quality targets
- **AC-31** WHEN a capture is taken on a mid-range Android phone THE SYSTEM SHALL show suggestions within 2 s.
- **AC-32** WHEN tested against the reference photo set (normal indoor light, English cards, sleeved and unsleeved) THE SYSTEM SHALL include the correct card in the top 3 suggestions for ≥ 90 % of photos with no loaded deck, and ≥ 98 % when the card's deck is loaded.
- **AC-33** WHILE the device has no network connection THE SYSTEM SHALL support every v1 feature. At most, card artwork may fall back to a text rendering.

## 6. Out of scope for v1 (later, in priority order)

1. **Commander damage** tracking (first, since Commander is the main use).
2. Other counters: poison, energy, experience, commander tax.
3. Library tracking: card count per library, then the unseen cards from a loaded deck.
4. Several cards in one photo, such as the whole battlefield.
5. Identification from artwork, for stylized, Universes Beyond and unreadable names.
6. Non-English cards.
7. Exporting or sharing the history.
8. Sync between several devices (much later).
9. iOS.
10. Rules enforcement of any kind. This mode is a tracker, not a game engine.

## 7. Constraints

- The aim is an **upstream contribution** to Card-Forge/forge. Ask the maintainers early whether the feature fits.
- Every new dependency must be **open source and GPLv3-compatible**. Nothing proprietary or tied to a vendor's services.
- PRs written mostly by an AI agent must say so (`CONTRIBUTING.md`).

## 8. Assumptions to confirm

These are defaults filled in during writing, not discussed explicitly:

- **A1** Starting hand count 7 and life 40, editable before turn 1 (AC-5).
- **A2** Default from/to zones by card type (AC-18).
- **A3** A "wrong player" correction changes the player only. It does not change the turn number on the original history entry or pass the turn (AC-22a).
- **A4** Undone actions stay visible in the history, marked as undone, rather than disappearing (AC-27).
- **A5** The reference photo set for AC-32 is something we build: around 200 photos of real cards, defined in `plan.md`.
- **A6** Library tracking is out of scope for v1 and on the later list. Tracking known positions in a library (scry, tutors to the top) is not planned.

## 9. Decision log

| # | Decision |
|---|---|
| Q1 | Full zone tracking, kept light. Opponents' hands are tracked as a hidden count plus known cards. |
| Q2 | Android for v1. The desktop mobile-dev build captures from image files. iOS later. |
| Q3 | One device, one operator. Multi-device sync much later. |
| Q4 | Aim upstream, so open-source dependencies only. |
| Q5 | A tracker, not the rules engine. Reuse the card database, card rendering, match views and deck import. |
| Q6 | 2–6 players with a Command zone. Commander damage is the first "later" feature. |
| Q7 | One card per photo. |
| Q8 | Confirm mode by default, with an auto-mode setting. Typing to search is always available. |
| Q9 | English cards in any frame or treatment, double-faced cards by either face. Tokens and emblems by name. Non-English later. |
| Q10 | Read the name only and match against the existing card database. No downloaded recognition data, fully offline. |
| Q11 | 2 s to suggestions. Correct card in the top 3 for 90 % of photos, 98 % with a loaded deck. |
| Q12 | Every action recorded, so the history builds itself. Autosave and resume, undo, one active game. |
| Q13 | Loaded decks rank suggestions, with fallback to the full card database. |
| Q14 | These docs live on `feature/companion-mode` in `docs/companion/`. |
| Q15 | Unreadable names fall back to typing with the partially read text filled in. Artwork matching later. |
| Q16 | Turn counter and active player. No phases. |
| Q17 | History viewable in-game and for past games, filterable by player. Export later. |
| Q18 | Life totals in v1. Other counters later. |
| Q19 | Cards can move between players, plus a "wrong player" correction for cards logged to the wrong seat. |

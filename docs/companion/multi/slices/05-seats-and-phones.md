# Slice 05: Seats and the phones list

**Branch:** `feature/companion-multi-05-seats`, stacked on `feature/companion-multi-04-reconnect`. **Plan item:** section 9, M1-2, first part (the foreground service is 06; the unbounded reconnect moved to 04).
**Depends on:** 04 (`ShareState` with own seat and roster fields, reconnect = fresh join), 02 ("Stop following", past games), 00 (`ShareDialog`, `JoinGameScreen`, `CompanionViewExtension`).
**Proves:** phones are tied to seats, the host sees and controls who's connected.

## Result
After typing the code, a joining phone picks one of the free seats or "Watch". Its own seat is at the bottom of its board, highlighted, with turn order kept. The host's seat is never offered. The host's share dialog has a "Phones" list: each phone's name, its seat (or "watching"), and whether it's connected, with Remove. A removed phone is told so and keeps the game in its past games; it may join again with the code. A phone that already has its own unfinished Companion game is asked to end it before joining.

## Acceptance criteria
Delivered in full:
- **AC-103** WHEN a phone joins THE SYSTEM SHALL let it claim one unclaimed seat or join as a spectator. A seat can be held by only one phone, and the host's phone holds the host operator's own seat.
- **AC-104** THE SYSTEM SHALL allow up to 6 seated phones (the host included) and SHALL NOT set a fixed limit on spectators.
- **AC-109** THE SYSTEM SHALL let the host see which phones are connected, and which seat each holds, and SHALL let the host remove a phone from the game.

Completes:
- **AC-107** WHEN viewing a shared game on a joined phone THE SYSTEM SHALL show the same overview as AC-23, live, with the phone's own seat at the bottom if it has one, and offer the history with the player filter (AC-28). _Here: own seat at the bottom (N2); spectators see the host's Me. Phone legibility check with 6 seats: 09._

Spec assumptions it implements: **B3** (a removed phone may rejoin with the code), **B5** (a seated phone that leaves frees its seat; the seat's player stays in the game).

## Design for this slice
Plan 2.1 (`MatchExtension`, `MatchController`), 2.2 (`CompanionDevice`, `CompanionShareHost`), 2.3 (`ShareDialog`, `JoinGameScreen`, `CompanionViewExtension`), 3.3 (`claim`, `roster`, `removed`), 4.1 (Joining, One active game per phone, Phones), N2, Q29.

### `forge-gui`, `forge.gamemodes.companion.share`
- **`CompanionDevice`** (record, new): this install's random id and display name, in `companion/device.json`. Sent in `hello` as `device`; the name defaults to the device model and is editable in the join screen.
- **Messages:** `hello` gains `device`; `welcome` gains `roster`; new `claim` (J→H: a seat name, or null to watch), `roster` (H→J: each phone's device name, seat, connected), `removed`.
- **`CompanionShareHost`:**
  - `claim`: a taken seat is refused (first claim the host receives wins); the host's Me seat is the host's. A reconnecting device gets its own seat back when it claims it again (`ShareState.ownSeat` on the phone). Spectators are unlimited; the admission limit is set well above 6 + spectators (net play uses 64, `FServerManager.java:95-97`).
  - Roster: host session state, not log (plan decision 7). Sent on every change.
  - Remove: send `removed`, detach, close, free the seat (B5). The token stays (B3).
- **`CompanionClient`:** sends `claim` after `welcome` (order: `hello` → `welcome` → `claim` → catch-up → attach). On `removed`: `store.end(id)` so it's a past game, read-only (Q29), and leave the match screen with "The host removed this phone".

### `forge-gui-mobile`
- **`MatchExtension`** (`forge/screens/match/MatchExtension.java`): hook `bottomPlayer(players)`, default null.
- **`MatchController.openView`:** when the extension names a bottom player, rotate the panel list so that player comes first, keeping turn order (`MatchController.java:226-247`). Also the overlay null check (plan 2.1): with no local player a touch calls `hostedMatch.pause()` (`:258-276`), and on a joined phone `hostedMatch` is null or a stale match.
- **`CompanionViewExtension`:** `bottomPlayer` = own seat, highlighted; spectators return the host's Me (N2).
- **`JoinGameScreen`:** after `welcome`, a seat list (free seats, from the roster) plus "Watch". A refused claim shows the list again.
- **`ShareDialog`:** a "Phones" section: device name, seat or "watching", connected, Remove (with a confirmation).
- **`CompanionSetupScreen`:** "Join game" with an unfinished own game asks to end it first (`CompanionStore.loadActive`, `CompanionStore.java:352-376`; Q29). A followed copy (`share.json` role `joined`) counts as that game too.

- **Spectator warning:** the share dialog shows a warning when more devices are connected than the safe number measured in slice 01 (Results, [Phone] step 8). It is not a hard cap: more devices can still join (spec AC-104).

### Protocol involved
`hello`(device) → `welcome`(roster) → `claim` → catch-up → attach; live `roster`; `removed`.

## Not in this slice
- Read-only switch per phone: **M2-1** (in M1 every joined phone is read-only, AC-108, slice 00).
- Roster addresses and takeover port (for M3): **09**.
- Foreground service: **06**.
- Any acting from a seat: **M2**.

## Check first
1. **Two claims for one seat** at once (two joiners tapping together): exactly one gets it, the other sees the list again.
2. **Rotation keeps turn order** with 6 seats and Me in each position (`MatchController.java:226-247`), on the host (unchanged, no extension hook) and on a joined instance.
3. **Seat on reconnect:** after 04's reconnect, the phone is back in its seat without being asked.
4. **Open (from 02's Gate B): following into an ended game.** `createWithId` keeps an existing `ended` marker, so after Stop following a fresh join to the same host writes into a past game. Decide: clear `ended` when following again, or refuse.

## Tests (CI)
None new: the plan lists dev checks for AC-103/104/109 (plan 6), and these are UI and wiring.

## Oracle
**Instances:** one host and several joiners, each with its own `userDir`:
```
REPO=/path/to/forge; JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
for n in host j1 j2 j3 j4 j5 j6; do mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties; done
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) &
(cd /tmp/companion-j1 && java -jar "$JAR" width=450 height=900) &   # and so on for j2..j6 as needed
```
1. Host: 6 players, Me = seat 1. Share. j1 joins. OK: the seat list offers seats 2–6 and Watch, not seat 1.
2. j1 claims seat 3. OK: j1's bottom panel is seat 3, highlighted, and the other seats follow in turn order (4, 5, 6, 1, 2). The host's Phones list shows j1, seat 3, connected.
3. j2 joins. OK: seat 3 is not offered. j2..j5 claim seats 2, 4, 5, 6. OK: all six seats are held (AC-104, host included).
4. j6 joins. OK: only Watch is offered; j6 watches, and its bottom panel is the host's Me (seat 1). Start one more instance as a second spectator: OK, also accepted.
5. Host: Remove j1. OK: j1 leaves the match screen with "The host removed this phone", the game is in j1's past games, read-only; the host's list no longer shows j1, and seat 3 is free again.
6. j1: Join game with the same code. OK: j1 may join (B3); seat 3 is offered.
7. Kill j2 (seat 2). OK: the host's list shows j2 as not connected. Restart j2. OK: it reconnects into seat 2 without a seat prompt.
8. An instance with its own unfinished Companion game picks Join game. OK: it's asked to end that game first.
9. Host, a new unshared game: v1 behaviour (AC-114).

**[Phone]** 1. Two phones (as slice 01): phone B claims a seat; it's at the bottom. Phone A removes it; phone B shows the notice and has the game in its past games.

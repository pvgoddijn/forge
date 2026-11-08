# Slice 15: Real table

**Branch:** `feature/companion-multi-15-real-table`, stacked on `feature/companion-multi-14-host-search` (rename to match 14's branch once its bundle exists). **Plan item:** section 9, item 10 (real-table beta test).
**Depends on:** 00–14 all landed and their phone checks passed: M1 (follow along), M2 (acting, the floor, Paid), M3 (manual takeover, finding the new host).
**Proves:** the finished feature holds up through a whole evening at a real table, not just in single-feature checks: a long game, real Wi-Fi, real batteries, people joining late, and players who didn't build it.

## Result
A group plays one full Commander game of about 2 hours with 4–6 players. One phone hosts. The other players' phones are seated and act on the game (M2), Paid included. At least one spectator watches, including the old tablet. Along the way Wi-Fi drops and phones reconnect by themselves, someone joins late, and once the host is handed over by hand (M3). At the end the history says who logged what, the host phone hasn't overheated or run flat, and the owner has the players' feedback in a list. Code changes in this slice are only fixes for what the game turns up, each named in Results. **Maintainers are contacted only after this beta test (the owner's call, 2026-11-08), not before.**

## Acceptance criteria
Nothing new. Re-checked at a real table, over a long game, with real players:
- **AC-102** WHEN a user picks "Join game" in Companion and scans the QR code or types the join code THE SYSTEM SHALL connect to that shared game over the local network, without internet. _Here: every player's phone, including late joiners, by scan or code._
- **AC-104** THE SYSTEM SHALL allow up to 6 seated phones (the host included) and SHALL NOT set a fixed limit on spectators. _Here: 4–6 seated plus spectators; the host's warning (05) shows only past the safe number._
- **AC-106** WHEN the host's game changes THE SYSTEM SHALL show the change on every connected phone within 1 s on the same local network. _Here: over the whole game, not just in a test._
- **AC-111** WHILE a joined phone can't reach the host THE SYSTEM SHALL keep showing the last known state, clearly marked as disconnected, and SHALL reconnect automatically when the host can be reached again. _Here: real Wi-Fi drops._
- **AC-112** THE SYSTEM SHALL save the complete shared game on every joined phone as it changes, so that a joined phone that is closed, killed or restarted resumes the game where it left off and rejoins automatically.
- **AC-113** WHEN the host ends the game THE SYSTEM SHALL end it on every joined phone and keep it in their past games, read-only (AC-30).
- **AC-115** WHILE connected to the host THE SYSTEM SHALL let a seated phone that isn't read-only perform every v1 action (AC-14 to AC-25, AC-27), on any player, including capture and identification on that phone itself. _Here: including Paid on a mana payment prompt from the phone with the floor (plan 4.2)._
- **AC-119** WHEN any action happens THE SYSTEM SHALL record which phone logged it, and show that attribution in the history (extending AC-26).
- **AC-120** WHEN any phone taps undo THE SYSTEM SHALL revert the most recent action of the whole game that hasn't been undone (AC-27). WHEN that action was logged by another phone THE SYSTEM SHALL first show who logged it and what it was, and ask for confirmation.
- **AC-122 / AC-123** "Take over as host" on a seated phone continues the game from its latest saved state and shows a new join code. _Here: once, by hand, mid-game._
- **AC-124** WHEN a phone that followed the old host, or the old host itself, reaches the new host THE SYSTEM SHALL make it follow the new host without anyone's approval, bringing its copy in line.

Also **Q27** (the host's foreground service and notification) over the full game, and spec **B4** (1 s with up to 8 phones).

## Design for this slice
None of its own. Plan 4.1–4.3 and the bundles 00–14 are the design. A fix found here goes in the smallest place that fixes it, with the test its original slice would have named, and is listed in Results with the step that found it.

## Not in this slice
- New features from the feedback: they go into the list below and into `../todo.md`, not into code here.
- Contacting the Card-Forge maintainers: **after** this slice's Results are written and the owner has read the feedback list.
- The JSON wire format: its own branch (`../todo.md`).

## Check first
1. **Every earlier phone check passed:** the Results sections of 01, 06, 09 and the M2/M3 bundles have no open FAIL.
2. **APK:** one APK from this branch on every phone and the tablet (the protocol fingerprint, 07, refuses mixed builds; note it if one is refused anyway).
3. **Host phone charged** to 100 %, its battery percentage and temperature noted at the start (Android: Settings → Battery; `adb shell dumpsys battery` if a laptop is at hand).
4. **Network:** a router with no internet, or venue Wi-Fi, or the host's hotspot. Note which. Mobile data on on every phone.

## Tests (CI)
None new. v1's and the earlier slices' tests stay green; any fix brings its own test as its original slice would.

## Oracle
**[Phone] real-table beta test** (the owner and 3–5 other players; the host phone, one phone per seated player, at least one spectator phone, the old tablet as a second spectator, optionally a laptop with `forge-gui-mobile-dev` as a big-screen spectator):
1. **Setup:** the host starts a Companion game for 4–6 players, Commander, commanders set. Share. OK: the code and QR show, the sharing notification appears.
2. **Join:** each player joins by scan (typed code for any phone without a camera) and claims their seat; the old tablet joins as "Watch". OK: every seat is held by the right phone, each phone has its own seat at the bottom, the host's Phones list matches the table. Note how long the slowest join took.
3. **Late joiner:** hold one player (or the tablet) back until about turn 3, then join. OK: it gets the full board and the whole history, undone entries included (AC-105), and follows from then on.
4. **Acting (M2):** play normally, with each player logging their own actions from their phone: add by name and by photo, targets on their own phone, OK and End Turn, life changes, moves, an engine combat with blocks on the defender's phone, an undo of another player's action (with its confirmation). OK: each action lands once, in order, and shows on every phone within about 1 s.
5. **Paid:** at least three times, a player with the floor taps **Paid** on a mana payment prompt on their own phone (one of them with a non-mana cost too, such as {T} or a sacrifice). OK: the cost is taken as paid on the host, the spell or ability goes on the stack, the other costs are still asked for on that phone, and the host's own Paid tap does nothing meanwhile.
6. **Wi-Fi drops:** at least twice during the game, a player turns their Wi-Fi off for about 30 s and back on, and once a phone is locked or backgrounded for a few minutes. OK: the disconnected banner, no actions offered while disconnected (AC-117), then it reconnects and catches up by itself with nobody touching the host. Once, kill and restart a joined phone's app: OK, it rejoins its seat by itself (AC-112).
7. **Host battery and heat:** every 30 minutes, note the host phone's battery percentage and whether it is warm, with its screen on and the sharing notification showing. Keep the host's app in the foreground except once for about 2 minutes in the background (another app). OK: the host keeps serving the whole time, and the phones keep updating while it's in the background (Q27). FAIL: the host phone gets too hot to hold, or would not last a 2-hour game from a full charge.
8. **One manual takeover:** around the middle of the game, with the host still up, a seated player picks "Take over as host" and confirms. OK: the new host shows a new join code, the old host and the other phones follow it without anyone's approval (AC-124), the board is the latest saved step (anything unsaved is lost, as the confirmation said), and play goes on from the new host. Note how long until every phone followed.
9. **History attribution:** after the game, open the history on two phones and on the host. OK: every entry says which phone logged it ("· Anna's phone"), the player filter works, and the logs match across phones.
10. **End:** the host ends the game. OK: it ends on every phone and is in each phone's past games, read-only (AC-113). The spectator warning (05) never showed, or showed only past the safe number.
11. **Feedback:** ask each player, and write down their answers as a list: what was confusing, what was slow, what they missed, whether they'd use it again, and anything that went wrong that they noticed before the owner did.

Write into a "Results" section of this file: the date, players and phones (model, Android version) and the tablet, the network, the battery and temperature readings over time, every drop and how long reconnecting took, the takeover timing, every FAIL with the step that found it and its fix, and the **beta-test feedback list**. Then the owner decides whether to contact the Card-Forge maintainers (plan 5, "Changes outside Companion", and `../todo.md`, "Before asking the maintainers").

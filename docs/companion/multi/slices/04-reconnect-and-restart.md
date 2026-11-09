# Slice 04: Reconnect and restart

**Branch:** `feature/companion-multi-04-reconnect`, stacked on `feature/companion-multi-03-steps`. **Plan item:** section 9, M1-1b second part (`share.json`, resume and auto-rejoin, N6, Stop following), plus M1-2's "unbounded reconnect with fresh attach", moved here because both deliver AC-111.
**Depends on:** 03 (complete verbatim copy: log + steps), 02 (`ShareState` minimal, the resume guard, "Stop following"), 00 (attach, `epoch`).
**Proves:** a shared game survives everything short of a takeover: Wi-Fi drops, a killed joined phone, a killed host.

## Result
When a joined phone loses the host, its board stays on screen with net play's "reconnecting" banner within about 15 s, and it keeps trying for as long as it's in the game. When the host is back, the board catches up by itself. A joined phone that is killed and reopened goes straight back into the game; if the host can't be reached, it shows the last board as text plus the history, and keeps trying. A host that is killed and reopened resumes the game (v1) and shares it again on the same code, so its phones come back by themselves. If the host restarts the game from the match screen, joined phones follow the new board.

## Acceptance criteria
Delivered in full:
- **AC-111** WHILE a joined phone can't reach the host THE SYSTEM SHALL keep showing the last known state, clearly marked as disconnected, and SHALL reconnect automatically when the host can be reached again. A joined phone restarted while the host is unreachable SHALL show a text summary of the last saved state plus the history, not the board.
- **AC-112** THE SYSTEM SHALL save the complete shared game on every joined phone as it changes, so that a joined phone that is closed, killed or restarted resumes the game where it left off and rejoins automatically. _The host's own backgrounding (its foreground service) is 06._

**Deviation (owner's decision, 2026-11-09):** Companion never goes into a game by itself on opening; unfinished games are carried on from **Load game** (`CompanionLoadGameScreen`, `ActiveGame.list`). So AC-112's "rejoins automatically on restart" is delivered as **"rejoins automatically when picked in Load game, without a code"**, and "a host that is killed and reopened resumes the game" as "Continue in Load game resumes it and shares it again on the same code". AC-111's restarted phone shows N6 once its game is picked there. The Result above, the `CompanionSetupScreen` design and Oracle steps 2–4 and 6 and [Phone] 2 and 4 read with that change; the Results section says how.

Also delivers AC-105's "or reconnects" part: a reconnect is a fresh join (catch-up, then a fresh attach).

## Design for this slice
Plan 2.1 (`FGameClient`, `CompanionSetupScreen`), 2.2 (`ShareState`), 3.1 (15 s idle), 3.2 (new `Game` → fresh attach), 4.1 (Connection loss, Persistence, End), N6.

### `forge-gui`, net code (hooks only, additive)
- **`FGameClient`:**
  - An overridable backoff. Companion: 0.5 s doubling to 5 s, **never giving up** (net play: `{1, 5, 15, 45, 60}` then fail, `FGameClient.java:55`, `:329-336`).
  - An overridable reader idle (`READER_IDLE_SECONDS = 45` is a static final, `:55`, used at `:114`): Companion uses 15 s on both sides (plan 3.1), so "disconnected" shows quickly. (The plan lists the backoff and `markSeated` hooks but not this one; it's needed for 3.1.)
  - `markSeated()`: reconnect only runs for a seated client (`:309`), and `seated` is only set from `openView` (`setGameControllers`, `:268-271`) or a lobby update (`:492`). A restarted joined phone that hasn't had `openView` yet must still retry.
  - The reconnect loop waits `RESUME_WATCH_SECONDS` (5 s, `:57`) for a resume or lobby update (`awaitResumeOrLobby`, `:350-357`), else counts the attempt as failed. Companion's `welcome` (or the following `openView`) must count as that answer (check first).

### `forge-gui`, `forge.gamemodes.companion.share`
- **`ShareState`** (`share.json`): role (`hosting` / `joined`), token, host device and addresses, port, own seat (05 fills it), last roster (05), current term (M3). The host writes one too.
- **`JoinCode`:** the token now lasts for the game, from `share.json` (spec B3: the same code works after a restart).
- **`CompanionShareHost`:**
  - Detaches a dropped phone **at once** (no parking as `FServerManager` does): a view-only phone has nothing to resume.
  - Restart: listen on the **same port and token** from `share.json` (the next free one if the port is taken, which changes the code; say so on the host).
  - A new `Game` (restart from the match screen, a resume) gets a **fresh attach** for every peer, from the match's start hook (`CompanionMatch.java:35`, `match.setStartGameHook`). Same path as net play's next game, with a new tracker per game (`ProtocolGuiGame.java:277-287`).
  - An ended host keeps answering `end` until the app leaves Companion (plan 4.1).
- **`CompanionClient`:** the backoff, idle and `markSeated` hooks above. On reconnect: login, `hello(have)`, catch-up (03), a **fresh attach** (full state), so no delta baseline is assumed. A phone that reconnects to an ended game gets `end`.

### `forge-gui-mobile`, `forge.screens.companion`
- **`CompanionSetupScreen`:** resume by `share.json` role, replacing 02's "Stop following"/"Join again" prompt:
  - **hosting:** v1 resume (`openGame`, `CompanionSetupScreen.java:317-343`), then start sharing again (same port and token).
  - **joined:** reconnect from `share.json`. While the host can't be reached: **N6**, the last step as text plus the history, through the existing `PastGameScreen` path (`CompanionPastGamesScreen.java:64-79`: `CompanionPastGamesScreen.boardsText(CompanionSnapshotSummary.of(...))` with `loadFinalState`, which writes nothing, `CompanionStore.java:161-164`), with a "Stop following" button, and keep retrying; when it connects, open the match screen.
  - A phone that missed the game's end shows "Stop following" (plan Q29).
- **`CompanionMatchExtension`:** "Share game…" writes the host's `share.json`.
- **Banner:** net play's reconnect banner is used as is: `FGameClient` calls `onReconnectStateChanged` (`FGameClient.java:455`), which mobile's `MatchController` overrides (`MatchController.java:959`, `ReconnectModals`). Check that "gave up" never shows for Companion.

### Protocol involved
No new messages. Reconnect = the full join order: `LoginEvent` → `hello`(have) → `welcome` → `want` → `step`… → `entries`… → attach → `epoch`.

## Not in this slice
- Seats (a reconnecting phone reclaims its seat): **05**. Until then everyone reconnects as a spectator.
- The host's foreground service, so a backgrounded host keeps serving: **06**. In this slice, a backgrounded Android host may be frozen; that's expected.
- Keeping the screen on: **09**.
- Finding a host whose address changed: **09** (stored addresses) and M3-2 (search).

## Check first
1. **Reconnect handshake:** `FGameClient`'s reconnect loop (`attemptReconnect` → `connect()` → `awaitResumeOrLobby`, `FGameClient.java:338-357`) accepts a Companion host's answer and doesn't fail every attempt after 5 s.
2. **No `loadState` on the follower:** a joined phone restarted with entries past its latest step (kill it between an entry and its step) writes nothing. This is the test below; run it first.
3. **Port reuse:** a host restarted within seconds can bind the same port (`SO_REUSEADDR` on the server channel).
4. **Restart from the match screen** on the host: joined phones get a fresh attach and a new `epoch`, and the old forwarder is shut down.

## Tests (CI)
Named in plan 6; no others.
- `forge-gui`, next to `CompanionStoreShould`: **a following store restarted with entries past its latest step writes nothing** (no settle `UNDO`) **and stays byte for byte the host's** (AC-112 test).
- `forge-gui-desktop`, next to `TableEditorTest`, real localhost sockets: **drop the socket, act on the host, reconnect: view and data match** (AC-111 test).

## Oracle
**Two instances** (as in slice 00):
```
REPO=/path/to/forge; JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
for n in host join; do mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties; done
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) & (cd /tmp/companion-join && java -jar "$JAR" width=450 height=900) &
```
`H` and `J` as in slice 03.
1. Host shares, joiner joins. Kill the **host** process. OK: within about 15 s the joiner shows the reconnect banner over the last board. Wait 3 minutes. OK: the banner still says it's reconnecting (never "gave up").
2. Restart the host and open Companion. OK: the host resumes the game and shares again with the **same code**; the joiner's banner goes away and its board matches the host's, with no action on the joiner.
3. Host: add a card while the joiner is killed. Restart the joiner, open Companion. OK: it goes straight to the match screen, the new card is there, `diff -r $H/states $J/states` and `cmp $H/log.txt $J/log.txt` are clean.
4. Kill both. Start only the joiner, open Companion. OK: a text summary of the last board plus the history, not the board (N6), and `$J` is unchanged (compare with a copy). Start the host. OK: the joiner switches to the live match screen by itself.
5. Host: restart the game from the match screen menu. OK: the joiner shows the new board.
6. Host: end the game while the joiner is killed. Start the joiner. OK: it shows that the game ended (or "Stop following" if the host has left Companion), and the game is in its past games.
7. Host, a new unshared game: v1 behaviour (AC-114).

**[Phone]** (two phones, as slice 01; the host stays in the foreground, screen timeout 10 min):
1. Joined phone: turn Wi-Fi off for 30 s, then on. OK: the banner shows, then the board catches up by itself, including an action the host logged meanwhile.
2. Joined phone: swipe the app away, reopen, open Companion. OK: back in the game, up to date.
3. Joined phone: send it to the background for 2 minutes (home button), then reopen. OK: it reconnects and catches up.
4. Host phone: swipe the app away, reopen, open Companion. OK: it resumes and shares on the same code; the joined phone comes back by itself.

## Results
Built in two chunks on `feature/companion-multi-04-reconnect`: A (net hooks, lasting share, fresh attach per game; `forge-gui`) and B (mobile UI, lifecycle, this section).

**Chunk A, as built.** `FGameClient` gets overridable hooks, net play's values unchanged (`FGameClientDefaultsShould`): `reconnectDelayMillis` (Companion: 0.5 s doubling to 5 s, never `GIVE_UP`), `connectTimeoutMillis` (5 s), `readerIdleSeconds` (15), `markSeated`, `keepsTrackerAcrossConnections`. Check first 1: Companion's `welcome` counts as the reconnect's answer (`resumeAnswered`), and a reconnect attempt first closes the previous one if its welcome never came. `CompanionClient` reports a `Link` (`CONNECTING`, `CONNECTED`, `RECONNECTING`, `ENDED`, `CLOSED`) to `LinkListener`s on non-GUI threads, and `rejoin(game)` follows a held copy again and keeps trying from the first connect (blocks at most 5 s, never throws for an unreachable host). `ShareState` gains `HOSTING` (port, token, addresses), `isFollowed`, `hosted` and `delete`; `CompanionShareHost.start` writes it, binds the earlier port first (check first 3: the stop waits for the server channel to close, so a restart within seconds binds it again) else the next free one (`codeChanged`); `stop` keeps `share.json`, `stopForGood` deletes it. A dropped phone is let go at once; each new game the table starts gets every attached phone a fresh attach (check first 4, `CompanionTable.addGameStartListener`); a phone joining an ended game gets the end and is let go.

**Chunk B: carrying on from Load game.** `CompanionResume` (`forge-gui`, no libGDX) decides; the screens follow it:
- **Own game, hosted** (`share.json` role hosting): Continue opens it as v1 (`CompanionSetupScreen.openGame`), and `CompanionMatchExtension.shareAgainWhenStarted` starts a `CompanionShareHost` once the game has started (a one-shot game-start listener, then the UI thread), which reads the port and token from `share.json`. If the port was taken (`codeChanged`), the host gets a notice with the new code(s). A share that can't start says so, and the game goes on unshared.
- **Own game, not shared** (no `share.json`, or stopped for good): v1, nothing shared (AC-114).
- **Followed game** with a readable `share.json`: the dialog offers Stop following, **Type a code** (for a host whose code changed: `JoinGameScreen` with the old code to edit) and **Continue**. Continue opens `CompanionRejoinScreen` and calls `CompanionClient.rejoin(id)` with the code from `ShareState.read(...).code()`, nothing typed. Until the host's board arrives it shows **N6**: a status line from the link (connecting; "the host can't be reached right now, this device keeps trying"; connected, waiting for the game), the last saved board as text and the history through the past-game path (`CompanionPastGamesScreen.boardsText(CompanionSnapshotSummary.of(...))` with `loadFinalState` and `loadFinalLog`, read before the client starts, writing nothing), **History** and **Stop following**. The board's `openView` opens the match screen over it, read-only (`CompanionViewExtension`); leaving the match comes back to it ("You left the shared game."), and Back from it leaves. When the host ended the game (`ENDED`): "The host ended the game. It's in your past games." (the extension's existing copy-ended notice), and Stop following hides, as it's a past game already. A phone whose host left Companion (it missed the end) keeps trying, and Stop following is its way out (plan Q29).
- **Followed game with a damaged `share.json`**: Continue opens `JoinGameScreen` with an empty code field, as before.

**Reconnect notice in the match (AC-111).** Mobile's `MatchController.onReconnectStateChanged` drives net play's `ReconnectModals` from `OnlineLobbyScreen.getfGameClient()`, which is null for Companion, so Companion showed nothing; and the modal counts to net play's fifth attempt. Not reused: `MatchExtension.showsOwnReconnectState()` (default false) makes `MatchController` skip net play's dialogs, and `CompanionViewExtension` listens to the `Link` (posted to the UI thread) and shows `CompanionReconnectNotice` over the board while `RECONNECTING`: "Lost the connection to the host. Reconnecting to the host… This device keeps trying for as long as you're in the game.", with **View board** (hides it; back does too) and **Leave**. No count, no "gave up". It goes on `CONNECTED` (the welcome), `ENDED`, `CLOSED` or leaving. It shows only over the board; before the board arrives, N6's status line says the same.

**Second `openView` (reconnect, or a new game on the host).** Each reconnect's attach and each new host game sends `openView` again, with no `afterGameEnd` before it, so mobile stacked a new `MatchScreen` on the old one, and Leave went back to the stale board. `MatchController.openView` now replaces the shown match screen (`Forge.openScreen(view, true)`) when the current screen is the previous match view and the extension follows a remote game. Net play and local games are unchanged (they get `afterGameEnd` first). Not handled: an `openView` while another screen (History) is on top of the match leaves the old match screen in the back stack.

**Lifecycle on the host.**
- "Stop sharing" in the share dialog → `stopForGood`: `share.json` goes, so Continue later doesn't share again. The phones keep trying on the old code until they leave or stop following (no `end` is sent: the game hasn't ended).
- Leaving the match without ending it → `stop`: `share.json` stays, so Continue shares again on the same code; the phones keep trying meanwhile.
- End game → the share is **kept running**, answering the end to the phones still attached and to those that come back (plan 4.1), in a static `endedShare`; it's stopped (`stopForGood`) when the next Companion game opens on this device (the next `CompanionMatchExtension`), or with the app. Not done: stopping it when the user leaves Companion for another Forge mode without opening another game (no hook for that on mobile yet).
- App backgrounded: nothing; a backgrounded Android host may be frozen until 06's foreground service. App killed: `share.json` stays, so Continue shares again.
- "Share game…" writes the host's `share.json` through chunk A's `start()`; the extension doesn't write it again.

**Tests.** `CompanionResumeShould` (`forge-gui`, real store): what Continue does for an own, a hosted, a stopped-for-good, a followed and a damaged-`share.json` game; what's shown for each link with and without the board; the notice only over the board while reconnecting; every status key has an English text. `CompanionStoreShould.writeNothingWhenAFollowingStoreRestartsWithEntriesPastItsLatestStep` (check first 2, AC-112): a follower copy with an entry past its latest step, restarted: listing, the last board and the history leave it byte for byte the host's, with no settling `UNDO`. The desktop end-to-end tests are chunk A's (`CompanionReconnectEndToEndTest`: drop and reconnect on the same code, a port taken, a restarted phone that never gives up, the host's new game, an ended game). The libGDX screens (`CompanionRejoinScreen`, `CompanionReconnectNotice`, the Load game dialog, `MatchController`'s changes) aren't run by any test: no display here.

**For the owner to run by hand** (Oracle adapted to Load game; the hand-test guide has them as A31–A39 and B19–B23):
1. As Oracle 1: kill the host; the joiner shows the reconnect notice within about 15 s, and after 3 minutes it still says it's reconnecting.
2. Restart the host, **Load game → the game → Continue**: it resumes and shares on the same code (no notice); the joiner catches up with no action. Leave on the joiner goes back to where it joined from, not to an older board.
3. Kill the joiner, act on the host, restart the joiner, **Load game → the game → Continue**: no code typed, the match screen with the new card; copies identical.
4. Kill both, start the joiner, Load game → Continue: N6 (status, last saved board as text, History, Stop following), its folder unchanged; start the host and Continue there: the joiner opens the board by itself.
5. As Oracle 5, if the host's match screen offers a restart (none was found; the hand-test guide marks it n/a otherwise).
6. End the game on the host while the joiner is killed; the host stays in Companion; the joiner's Continue says the host ended it, and it's a past game. If the host has opened another game meanwhile, the joiner keeps trying, and Stop following moves it to past games.
7. As Oracle 7.
[Phone] 2 and 4 go through Load game → Continue the same way.

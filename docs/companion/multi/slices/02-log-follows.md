# Slice 02: The log follows

**Branch:** `feature/companion-multi-02-log`, stacked on `feature/companion-multi-01-phones`. **Plan item:** section 9, M1-1a.
**Depends on:** 00 (server, peer, client, the extra inbound handler, `hello`/`welcome`), 01 (flushes on the game thread, Wi-Fi binding).
**Proves:** every joined phone keeps the host's log **byte for byte**, history included, and an ended game is kept read-only in its past games.

## Result
While following a shared game, the joined phone's game menu has "History": the same entries as on the host, undone entries marked, with the player filter. A phone that joins late still gets every entry from the start. When the host ends the game, the joined phone leaves the match screen and finds the game in its past games, read-only. If the joined phone is killed and restarted, Companion doesn't resume the followed game as its own: it offers "Stop following".

## Acceptance criteria
Delivered in full:
- **AC-113** WHEN the host ends the game THE SYSTEM SHALL end it on every joined phone and keep it in their past games, read-only (AC-30).

Delivered in part:
- **AC-105** WHEN a phone joins or reconnects THE SYSTEM SHALL give it the full current game and the full history, undone entries included. _Here: the full history. Steps: 03. Reconnect: 04._
- **AC-107** WHEN viewing a shared game on a joined phone THE SYSTEM SHALL show the same overview as AC-23, live, with the phone's own seat at the bottom if it has one, and offer the history with the player filter (AC-28). _Here: the history with the player filter. Own seat: 05._
- **AC-112** THE SYSTEM SHALL save the complete shared game on every joined phone as it changes, so that a joined phone that is closed, killed or restarted resumes the game where it left off and rejoins automatically. _Here: the log and setup are saved as they change. Steps: 03. Resume and rejoin: 04._
- **AC-114** WHILE no other phone is connected THE SYSTEM SHALL let the host keep logging exactly as in v1, without a network. _Here: the store hook only feeds an in-memory journal; proven by the test below._

## Design for this slice
Plan 2.1 (`CompanionLogEntry`, `CompanionStore`), 3.3 (data stream, journal, catch-up), 4.1 (Sharing, Joining, Viewing, End), decision 4.

### `forge-gui`, `forge.gamemodes.companion`
- **`CompanionLogEntry`:** optional `term` (absent = 0), `host` (absent = the game's creator), `by` (device id; absent = the host operator), `byName`, through `toLine`/`fromLine` (`CompanionLogEntry.java:215`, `:245`). v1 lines read unchanged. Nothing sets `by`/`byName` yet (M2-1) or `term`/`host` (M3-1). The `HOST` kind is **not** added here: there is no writer before M3-1, and an unknown kind already reads as `NOTICE` (`:280-288`).
- **`CompanionStore`:**
  - A **journal hook**, told of each entry appended, each failed append, setup saved and game ended (steps saved and discarded are added in 03). The hook may stamp `term`/`host`/`by`/`byName` before numbering (unused until M2/M3).
  - `appendLog` gets a success flag. Today it takes the seq even when the write fails (`CompanionStore.java:289-291`) and swallows the failure (`:301-303`). The hook is told of the failure, and the journal sends a **tombstone** (`gap`) for that seq instead of the entry.
  - `appendVerbatim(id, entries)`: keeps each seq, refuses a gap that no tombstone fills, writes a tombstone as a `NOTICE` line ("an entry was lost on the host"), skips an entry already held with the same bytes, **updates `lastSeq`** (`:60`) so a later `appendLog` (after M3's takeover) numbers after it, and fsyncs once per chunk (only `writeWhole` fsyncs today, `:398-410`).
  - A way to create a followed game's folder under the **host's game id** (`create` makes its own UUID, `:68-77`, and `saveSetup` assumes the folder exists, `:79-82`).
  - A following store **never calls `loadState`**: it writes a settle `UNDO` (`:131-143`), which would break byte-for-byte equality (plan 0.1).

### `forge-gui`, `forge.gamemodes.companion.share`
- **`ShareJournal`** (new): fed by the hook in write order: an entry when appended (or `gap` when the append failed), setup when changed, `end` last. Rebuilt from the store on demand; a seq missing on disk becomes a tombstone the same way. With no peer it only fills memory (AC-114).
- **Messages:** `hello` gains `have` = {`seq` of its last entry}; `welcome` gains `setup` and latest `seq`; new `entries` (verbatim log lines), `gap` {seq}, `setup`, `end`.
- **`CompanionShareHost`:** installs the hook on "Share game…". **Order on join:** `hello` → `welcome` → catch-up (entries after `have.seq`, with `gap` for any missing seq) → attach (view). After that, each peer gets the journal live, in order.
- **`CompanionClient`:** writes each data message to its store on one share executor (`appendVerbatim`, `saveSetup`, `end`), and never applies it to anything. The data is on disk before it's shown.
- **`ShareState`** (new, minimal; **pulled forward from M1-1b**): `share.json` in the followed game's folder with `role` = `joined`, host address, port, token. 04 extends it.

### `forge-gui-mobile`, `forge.screens.companion`
- **`CompanionViewExtension`:** game menu "History", opening `CompanionHistoryScreen` (it takes a list, `CompanionHistoryScreen.java:43`) from the phone's own copy (`loadLog`, a pure read, `CompanionStore.java:309-329`). On `end`: leave the match screen with a notice "The host ended the game", to past games.
- **`CompanionSetupScreen`:** an active game whose `share.json` says `joined` is **never** opened with `openGame` (which calls `table.latestState()` → `loadState`, `CompanionSetupScreen.java:317-323`, `CompanionTable.java:643-646`). Instead it offers "Stop following" (`store.end`, so it becomes a past game) or "Join again" (`JoinGameScreen`). 04 replaces this with an automatic rejoin.
- Past games of a followed game open through the existing read-only path (`CompanionPastGamesScreen.java:64-79`, `loadFinalState`/`loadFinalLog`, which write nothing). The board text is the initial state until 03 brings the steps.

### Protocol involved
Data stream (plan 3.3): `hello`(have.seq) → `welcome`(setup, latest seq) → `entries` / `gap` → live `entries` / `gap` / `setup` → `end`.

## Not in this slice
- Steps (`writeStep`, `stepHashes`, `want`, `step`), `discardAfter` on an `UNDO` entry, `resetTracker`: **03**.
- Automatic rejoin, N6's text summary, host resuming its share, unbounded reconnect: **04**.
- Seat claim, roster, Remove (and "a removed phone keeps its copy as an ended game"), one active game per phone: **05**.
- `by`/`byName` shown in the history (`CompanionHistoryScreen`): **M2-1**. `HOST` kind and `term`: **M3-1**.

## Check first
1. **The resume guard:** kill a joined dev instance mid-game and reopen Companion. Its game folder must not change (compare a checksum of `log.txt` and `states/` before and after). This is why `share.json` is pulled forward.
2. **Order of writes:** the hook runs where `appendLog` runs (the game thread). The journal must hand messages to peers in write order even though sending happens elsewhere.
3. **Rebuild with a hole:** a log on disk with a missing seq rebuilds with a tombstone for it.

## Tests (CI)
All named in plan 6; no others.
- `forge-gui`, `CompanionStoreShould` (or a sibling test class next to it): **field round trip** of `term`/`host`/`by`/`byName`, and a v1 line reads as before (AC-119 test, here because the fields land here).
- `forge-gui`, same place: **a failed append on the host becomes a tombstone on the phone, and later entries still append** (AC-112 test). Fail the host's write by making its log unwritable.
- `forge-gui-desktop`, next to `TableEditorTest`: **shared with no peer writes the same log and steps as unshared** (AC-114 test): the same scripted actions on two tables, one sharing.
- `forge-gui-desktop`, same place, real localhost sockets: **a phone joining after undos gets an identical log** (AC-105 test, log part; 03 extends it to steps).

## Oracle
**Two instances** (as in slice 00):
```
REPO=/path/to/forge; JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
for n in host join; do mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties; done
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) & (cd /tmp/companion-join && java -jar "$JAR" width=450 height=900) &
```
1. Host: 3 players, add three cards, undo the last, add one more. Share. Joiner: join. OK: joiner's History shows the same rows as the host's, the undone one marked.
2. OK: `cmp /tmp/companion-host/user/companion/<id>/log.txt /tmp/companion-join/user/companion/<id>/log.txt` reports no difference, and the joiner's folder has the **host's** game id.
3. Host: add a card. OK: a new row on the joiner's History (reopen it), and the logs still compare equal.
4. Joiner: History → filter by player 2. OK: only player 2's rows.
5. Kill the joiner and restart it, open Companion. OK: it offers "Stop following" / "Join again", not the board, and `log.txt` is unchanged (`cmp` against a copy taken before the kill). "Join again" with the code: OK, it follows again and the logs compare equal.
6. Host: end the game. OK: the joiner leaves the match screen with "The host ended the game"; its past games list the game; opening it shows the history, read-only.
7. Host, a new unshared game: v1 behaviour (AC-114).

**[Phone]** 1. On two phones (as slice 01), repeat steps 1, 3 and 6. OK: the same results.

## Results
Chunk A (`08dd11b65b8`): log entry fields, the store's journal hook, `appendVerbatim`, `createWithId`. Chunk B1 (`93a5ce232b8`): `ShareJournal` and the data messages. Chunk B2 (`d72e0a541cc`): the host shares the journal, the client writes the copy (`CompanionClient`, `ShareState`).

Chunk C (`forge-gui-mobile` screens, the resume guard, the host's stop):

1. **History on a joined phone.** `CompanionViewExtension`'s game menu is History, then Leave. History opens `CompanionHistoryScreen` with the phone's own copy: `store.loadLog(game)` (a pure read) for the game `welcomedTo()`, and the seat names from `store.loadSetup(game)`, so the player filter is the host's. A copy that can't be read shows an error instead (`lblCompanionHistoryUnreadable`). The history is read when opened, not live: reopen it for new rows (Oracle step 3).
2. **The end, and a copy that fails.** The extension listens to the client's copy (`addCopyListener`). On `ended` (the copy is already marked ended on disk) or the first `failed`, it posts to the UI thread (`FThreads.invokeInEdtLater`): if the match screen is shown it leaves as Leave does (`MatchController.instance.afterGameEnd()`, which calls `matchLeft`, closing the client), else it calls `matchLeft` directly; then it shows the notice (`lblCompanionHostEnded`, or `lblCompanionCopyFailed` with the error) over the screen it's back on. `JoinGameScreen`'s status shows the same notice instead of "You left the shared game". Only the first of ended/failed acts. A copy that failed stays an active followed game, so the setup screen offers it again (below). If the phone had just left on its own when the end is written, only the notice shows.
3. **The resume guard.** `ActiveGame` (new, `forge-gui`, `forge.gamemodes.companion.share`): `find(store)` = `loadActive()` plus whether the game has a `share.json` (`ShareState.exists`, so true even when it's damaged) and, when it reads, its `JoinCode`. Writes nothing to the game's folder. `CompanionSetupScreen.onActivate` opens only a game of the phone's own with `openGame`; a followed one gets a dialog (`lblCompanionFollowedGame`) with **Stop following** (`ActiveGame.stopFollowing` = `store.end`: a past game) and **Join again** (`new JoinGameScreen(store, game, code)`: the code filled in from `share.json`, or empty to be typed when it doesn't read; Join then calls `client.followAgain(game)` before connecting). Dismissing the dialog changes nothing; it's offered again next time. A `followAgain` that can't read the copy shows `lblCompanionCopyUnreadable` on the join screen, with nothing set up.
4. **Past games** of a followed game: `CompanionPastGamesScreen.open` uses `loadFinalState`/`loadFinalLog`, which write nothing (checked in `ActiveGameShould`: the store's files are byte-identical after both). The boards are the initial state until 03 brings the steps.
5. **Host: the end is sent before the share stops.** End game on the host does `store.end` (the journal queues `end` for each phone) and the match leaving then calls `share.stop()`. `stop()` now, after unhooking the journal, finishes each admitted connection on its own event loop: drains what its subscription still holds, then `writeAndFlush(EMPTY_BUFFER)` with `ChannelFutureListener.CLOSE`, so the close waits for those writes. It waits for the closes at most `STOP_FLUSH_MILLIS` (1 s) in all, logging if one didn't make it, then closes the peers and shuts the groups down as before. Before this, the end reached the phone only because `shutdownGracefully` runs pending tasks before closing channels, and a write still in Netty's outbound buffer at the close would have been dropped. `stop()` must not be called on one of the share's event loops (it isn't: the UI thread from the dialog, the game/UI thread from `matchLeft`).
6. **Strings** (en-US): `lblCompanionHostEnded`, `lblCompanionCopyFailed`, `lblCompanionHistoryUnreadable`, `lblCompanionCopyUnreadable`, `lblCompanionFollowedGame`, `lblCompanionStopFollowing`, `lblCompanionJoinAgain`, `lblCompanionStopFollowingFailed`.
7. **Tests:**
   - `ActiveGameShould` (new, `forge-gui`, 4 tests, a real store on disk): a game of the phone's own resumes; a followed game is never resumed and finding it writes nothing (every file under the store compared before and after) and gives the stored code; a damaged `share.json` still guards, with no code; Stop following makes it a past game, and the past games' read path (`loadFinalLog`, `loadFinalState`) writes nothing. Mutation: with the `share.json` check removed, 2 of the 4 fail.
   - `CompanionShareEndToEndTest.aPhoneSeesTheEndWhenTheHostStopsSharingRightAfter` (new, real localhost sockets): after the attach, three entries, `store.end` and `host.stop()` back to back; the phone's copy is ended, its listener heard `ended` once, and its log equals the host's. It passed before the `stop()` change too (see 5: Netty's graceful shutdown happened to run the drain first), so it guards the behaviour rather than proving the flush.
   - Full `mvn -o test -pl forge-gui-desktop,forge-gui-mobile -am`: green (forge-gui 232, forge-gui-desktop 1776 with 6 skipped).
8. **Not verified (needs the UI, so by hand):** the History menu item and its filter on the joined phone, leaving the match screen on the end and the notice over the screen it lands on, the setup screen's Stop following / Join again dialog, and Join again with the code filled in. The `afterGameEnd` path from a copy listener is the same call as Leave, but untested off the UI.

**Oracle still to run by hand:** steps 1 to 7 above on two desktop instances, with step 5's checksum of `log.txt` and `states/` before and after reopening Companion; and [Phone] steps 1, 3 and 6.

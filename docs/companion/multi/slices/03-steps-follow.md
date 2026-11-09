# Slice 03: Steps follow

**Branch:** `feature/companion-multi-03-steps`, stacked on `feature/companion-multi-02-log`. **Plan item:** section 9, M1-1b, first part (steps; the restarts are 04).
**Depends on:** 02 (journal hook, `ShareJournal`, `appendVerbatim`, `gap`, `ShareState` minimal, the resume guard), 00 (`epoch`).
**Proves:** every joined phone holds the host's step files byte for byte, cut back on an undo exactly as the host's, so the copy is complete (spec 4: "M3 needs no change to what M1 stores").

## Result
A joined phone's copy of the game now includes every saved board. When the host ends the game, the joined phone's past game shows the real final board (life, hands, zones), not the starting one. A phone that joins a long game gets every step; a phone that rejoins only fetches steps it lacks. After a long run of undos the joined phone stays responsive.

## Acceptance criteria
Delivered in part:
- **AC-105** WHEN a phone joins or reconnects THE SYSTEM SHALL give it the full current game and the full history, undone entries included. _Here: the steps (with 02's history and 00's view, the join is now complete). Reconnect: 04._
- **AC-112** THE SYSTEM SHALL save the complete shared game on every joined phone as it changes, so that a joined phone that is closed, killed or restarted resumes the game where it left off and rejoins automatically. _Here: the steps are saved as they change, so the saved copy is complete. Resume and rejoin: 04._

## Design for this slice
Plan 2.1 (`CompanionStore`, `GameClientHandler`), 3.2 (epoch), 3.3 (catch-up rule, journal), decision 4.

### `forge-gui`
- **`CompanionStore`:**
  - `writeStep(id, n, text)` through `writeWhole` (`CompanionStore.java:398-410`, fsync then rename), writing the host's text verbatim.
  - `stepHashes(id)`: `{n: sha256}` of the steps on disk (`states/<n>.txt`, `:29-35`).
  - The journal hook is now also told of each **step saved** and each **steps discarded** (`saveState` `:84-106`, `discardAfter` `:108-117`).
- **`GameClientHandler.resetTracker()`**, protected: drops the client tracker's card views. The tracker keeps every `CardView` it has seen (`GameClientHandler.java:97-148`), and after an undo or resume renumbers the board those are stale.

### `forge-gui`, `forge.gamemodes.companion.share`
- **`ShareJournal`:** a step when saved; on discard the following `UNDO` entry carries it.
- **Messages:** `welcome` gains latest `step` and the step index `{n: sha256}`; new `want` (J→H: step numbers missing or with a different hash) and `step` {n, sha256, text}.
- **`CompanionShareHost`:** after `welcome`, wait for `want`; send the wanted steps **first**, then the entries after `have.seq` (02), then live. Steps first means a phone cut off halfway never holds entries that point at a step it doesn't have.
- **`CompanionClient`:**
  - **Catch-up rule** (plan 3.3): after `welcome`, `discardAfter(hostLatest)` for steps past the host's latest; compare the step index with `stepHashes`, and send `want` for steps it lacks **and steps whose hash differs**. (Cutting back the log with `truncateLogAfter` only happens after a takeover: M3.)
  - Each `step` → `writeStep`, checked against its sha256. An `UNDO` entry runs `discardAfter(entry.step)` before it's appended, as on the host, **only when its seq is after the welcome's latest seq**: an older `UNDO` (in the catch-up) is already reflected in the welcome's step index, and applying it would delete steps the host saved after it.
  - On `epoch` (after every full view send): `resetTracker()`, which runs once that full view is applied (see Results).
- Still: the following store never calls `loadState` (02).

### Protocol involved
`hello`(have.seq) → `welcome`(setup, latest seq, latest step, step index) → `want` → `step`… → `entries`/`gap`… → live `entries` / `step` / `gap` / `setup` / `epoch` → `end`.

## Not in this slice
- Restarts, rejoin, N6 text summary, unbounded reconnect, host re-sharing on the same port and token, re-attach on a new host `Game`: **04**. (The test "a following store restarted with entries past its latest step writes nothing" is in 04, where the restart path is built.)
- `truncateLogAfter`, `terms`, divergence: **M3**.
- Seats and roster: **05**.

## Check first
1. **Undo on the joined copy:** after a host undo, the joined phone's `states/` has exactly the host's files (the `UNDO` entry's `discardAfter` plus the next `step`).
2. **Steps on a game saved before steps existed:** a v1 game with only `state.txt` (`CompanionStore.java:29-35`, migrated at the next save, `:206-219`) shared mid-game sends a consistent step 0.
3. **Big join** (plan Risks, measured here because steps first travel here): a game with a few hundred steps (script it, or play a long test game); time a fresh join on a phone and note the bytes. A rejoin sends only new steps.
4. **`resetTracker` timing:** it runs only after the full state that the `epoch` follows has been applied, so the board never shows with an empty tracker.

## Tests (CI)
Named in plan 6; no others.
- `forge-gui-desktop`, next to `TableEditorTest`, real localhost sockets: extend 02's **joining after undos** test: the joined store's log **and steps** are identical to the host's, and the view is full (AC-105).

## Oracle
**Two instances** (as in slice 00):
```
REPO=/path/to/forge; JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
for n in host join; do mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties; done
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) & (cd /tmp/companion-join && java -jar "$JAR" width=450 height=900) &
```
With `H=/tmp/companion-host/user/companion/<id>` and `J=/tmp/companion-join/user/companion/<id>`:
1. Host: 3 players, add four cards (a step each), undo twice, add one. Share; joiner joins. OK: `diff -r $H/states $J/states` and `cmp $H/log.txt $J/log.txt` show no difference.
2. Host: add a card, then undo. OK: after each, the same `diff -r` and `cmp` show no difference.
3. Joiner: Leave, then "Join again". OK: the joiner's log line for `want` names no step (nothing missing), and the copies still compare equal.
4. Tamper: stop the joiner, edit one line in `$J/states/1.txt`, rejoin. OK: `want` names step 1, and after the join `diff -r` is clean again.
5. Host: end the game. Joiner: past games → open it. OK: the boards text shows the final life and zones (compare with the host's past game), not the starting state.
6. Host, a new unshared game: v1 behaviour (AC-114).

**[Phone]** 1. Two phones (as slice 01): play 10+ actions with undos, then end. OK: the joined phone's past game shows the same final boards as the host's.

## Results
Built in two chunks on `feature/companion-multi-03-steps`: A (store, journal, messages, `GameClientHandler.resetTracker`) and B (protocol wiring and the end-to-end tests).

**Protocol as built.** The host sends `welcome`, then waits for `want` (`CompanionShareHost.WANT_SECONDS`, 15 s, as the admission; the phone's own wait for the game after the welcome is 30 s) and closes a phone that doesn't send one. On `want`, on the phone's event loop: the wanted steps, read from disk then (`Subscription.steps`, not under the journal's lock), then the drain (catch-up and anything live so far), then the attach. A drain sends nothing until the steps went, so a live item between `welcome` and `want` waits behind them. The phone computes `want` on its copy thread after making the folder: `discardAfter(hostLatest)`, then every step of the index it lacks or holds with another hash; an empty `want` when it holds them all. A phone whose welcome fails (a bad game id) sends no `want` and is let go by the host's timeout.

**`resetTracker` timing (check first 4).** The host sends `epoch` right after a full state (`setGameView`) on the same connection. On the phone the IO thread reads them in that order: the protocol handler registers the full state's cards in the tracker and queues its apply on the GUI thread, then `epoch` reaches the data handler, which queues the reset on the GUI thread behind it. The reset drops every card view and registers again those of the board the GUI now shows (the full state's), in one GUI-thread task, so the tracker is never empty between the two and the board is never shown against an empty tracker. A later delta's args are decoded only once the GUI thread has run everything queued before it (`GameClientHandler.decodeArgs`), so they resolve against the reset tracker. Not covered: the IO thread's frame decoder also reads the tracker, as it did before this slice, unsynchronised with the GUI thread.

**Tests** (`CompanionShareEndToEndTest`, real localhost sockets):
- `aPhoneJoiningAfterUndosGetsAnIdenticalCopy` (was `...IdenticalLog`): a host with an undo in its log; the joined phone's log and `states/` are byte-identical (and `stepHashes` equal), a fresh phone wants every step, and the steps are written before the first entries. The catch-up's old `UNDO` doesn't cut the later steps. Then a live add, a live undo and an add after it: identical after each.
- `aPhoneJoiningAgainWantsOnlyTheStepsItLacksOrHoldsWrong`: rejoin holding everything sends an empty `want`; after two host actions it wants exactly those two steps; after `states/1.txt` is edited on the phone it wants step 1, and the copy is identical again.
- `aClientJoinedMidGameFollowsTheHostAndChangesNothing`: after an undo's epoch, the phone's tracker knows every new card id and none of the old ones.
- Mutation checks, each caught by the test meant for it: applying every `UNDO` regardless of seq; sending entries before steps; wanting only missing steps (ignoring hashes); no `resetTracker` on epoch.

**For the owner to run by hand:** the Oracle steps above (two instances, and the two-phone run), and check first 3, the big join: time a fresh join of a game with a few hundred steps and note the bytes (all wanted steps are read and sent in one go on the phone's event loop, and count towards the phone's 30 s wait for the game). Check first 2 (a v1 game with only `state.txt` sends it as step 0) is covered by chunk A's store tests, not end to end.

**Gate B fixes** (the commit after `b09bbd1ec9a`): tests for three rules no test held. The welcome's `discardAfter(hostLatest)`: `aPhoneJoiningAgainWantsOnlyTheStepsItLacksOrHoldsWrong` goes on with a host undo while the phone is away and no action after (an empty `want`, step 4 gone from the phone, the copies identical), and `CompanionClientShould` tests `wantedSteps` alone (deleting the discard fails both). Steps first: `aHostWriteBeforeThePhonesWantComesAfterTheWantedSteps` holds the phone's want back (a protected no-op hook, `CompanionClient.beforeWant`, overridden by the test) while the host acts, and the wanted steps still come before any entry (dropping the drain's `|| !stepsSent` fails it). A damaged step: `CompanionClientShould` tampers a serialized `Step`'s text (`intact()` false), and the client, on an embedded channel, reports it `failed` and writes no file, while an intact step after it is written (skipping the `intact()` check fails it). API 26 safety: probed with the SDK's D8 (build-tools 35 and 36, `--min-api 26`, no core library desugaring in this build): it backports `Optional.isEmpty`/`or`, `String.isBlank`/`strip`/`repeat`, `List`/`Map.copyOf` and `Predicate.not`, but not `Stream.toList`, `String.lines`, `Collectors.toUnmodifiable*`, `InputStream.readAllBytes`/`transferTo`, `Path.of` or `Files.readString`. The stack's own uses changed anyway (`CompanionStore.stepText` and `JoinGameScreen.join`: `!isPresent()`; `CompanionViewExtension`: `collect(Collectors.toList())`, a real crash below API 34). A bytecode scan of the Android-shipped modules finds `Stream.toList` still in v1 Companion (`CompanionTable` sick creatures, `CompanionMatchExtension` history and ready creatures) and upstream (`FormatChoices`), and `Optional.isEmpty` (backported) in v1's `AutoAdd` and upstream's `RSSReader`: left as they are.

Known edges, not fixed: a phone mid-join (welcomed, its `want` not yet in) when the share stops gets no `end`, as its drain sends nothing before the steps (`CompanionShareHost.Admission.finish` says so; 04's rejoin covers it). The tracker reset on the GUI thread can race the IO thread, which still resolves and registers ids against the same unsynchronised tracker (`GameClientHandler.resetTracker` says so). The `Tracker.removeAll` change in `forge-game` (5 lines, additive) was approved by the owner on 2026-11-09 and is listed in plan 5.

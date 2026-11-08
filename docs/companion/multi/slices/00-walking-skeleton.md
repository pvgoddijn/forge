# Slice 00: Walking skeleton, dev build

**Branch:** `feature/companion-multi-00-skeleton`, from `feature/companion-multi`. **Plan item:** section 9, slice 0, Oracle 1 (Oracles 2 and 3 and the measurement are slice 01).
**Depends on:** v1 slice 08 landed on `feature/companion-multi` (spec 7: the v1 action types must be stable). No earlier multi slice.
**Proves:** host shares → a second instance joins **after the game started** → it gets the full board → host actions show live → a host undo (which renumbers every card) shows too.

## Result
On the dev build, the operator starts a Companion game with 3 players and adds a card. They open the game menu, pick "Share game…", and see a 16-character code. A second dev instance, with its own user folder, opens Companion → "Join game", types the code and sees the same board: every seat, hands as card backs, the command zone. The host adds Gray Merchant of Asphodel; the joined board shows it, and the life changes, within a second. The host undoes; the joined board goes back too. Nothing the joined instance taps changes the host's game.

## Acceptance criteria
Delivered in full:
- **AC-108** WHILE in M1 THE SYSTEM SHALL keep every joined phone read-only.

Delivered in part (rest in the slice named):
- **AC-101** WHILE a game is active THE SYSTEM SHALL let the operator share it, which shows a join code as text and as a QR code. _Here: the text code only. QR: slice 08._
- **AC-102** WHEN a user picks "Join game" in Companion and scans the QR code or types the join code THE SYSTEM SHALL connect to that shared game over the local network, without internet. _Here: typed code, dev build on one PC. Real phones: 01. Scan: 08._
- **AC-105** WHEN a phone joins or reconnects THE SYSTEM SHALL give it the full current game and the full history, undone entries included. _Here: the full current game (the view). History: 02. Steps: 03. Reconnect: 04._
- **AC-106** WHEN the host's game changes THE SYSTEM SHALL show the change on every connected phone within 1 s on the same local network. _Here: one joined instance on the dev build. Measured on phones: 01, 09._
- **AC-107** WHEN viewing a shared game on a joined phone THE SYSTEM SHALL show the same overview as AC-23, live, with the phone's own seat at the bottom if it has one, and offer the history with the player filter (AC-28). _Here: the live overview. Everyone is a spectator, so the bottom panel is the host's Me (plan N2). History: 02. Own seat: 05._
- **AC-114** WHILE no other phone is connected THE SYSTEM SHALL let the host keep logging exactly as in v1, without a network. _Here: sharing with no peer, and not sharing, change nothing in v1._

## Design for this slice
Plan sections 1, 2.1, 2.2, 3.1, 3.2, 3.4. One engine, on the host; the joined instance is a net-play view of the host's `HostedMatch` (plan "Owner decision").

### Changes to existing code (`forge-gui`)
- **`CompanionGameState.applyNow`:** fire `GameEventSnapshotRestored(true)` before the apply and `(false)` after it. Today a Companion load fires neither (only `GameSnapshot.java:68-70` and `UndoManager` do). With them, each remote proxy sends a full state (`ProtocolGuiGame.java:546-549`) and the host's own screen rebuilds through `onSnapshotRestored` (`FControlGameEventHandler.java:56-59`, `:320-330`; mobile `MatchController.java:468`). Needed because undo and resume renumber every card (`GameState.java:1390-1392`): deltas over old ids would name nothing.
- **`ProtocolGuiGame.attachMidGame()`**, public, called on the game thread: `sendFullState()`, then `syncManager.registerNewObjects(gameView)` and set `objectsRegistered`. Without it a proxy attached after the game started never sees `GameEventGameStarted` (`:531-541`), so it never resends after an undo (`:546-549`), and its first flush sends the whole graph a second time (`:560-565`). Registering after the full send makes the delta baseline exactly what the phone has.
- **`CompanionTable.runSystem(Runnable)`:** run on the game loop thread before the op queue, **not logged, no `ranSinceSave`** (unlike `runOne`, `CompanionTable.java:920-967`). Runs only from `chooseSpellAbilityToPlay` (`CompanionPlayerController.java:40-82`), so it never runs while a target or choice prompt blocks the game thread; an attach waits for the next priority prompt (plan 3.4, accepted). Wake the loop the way `submit` does (`CompanionTable.java:595-607`).
- **`FGameClient`:** one protected hook, additive: an extra inbound handler before `GameClientHandler` in the fixed pipeline (`FGameClient.java:110-118`), for Companion's data messages. Nothing else in `FGameClient` changes in this slice.

### New code (`forge-gui`, package `forge.gamemodes.companion.share`)
Messages are small `NetEvent` classes (strings, ints, lists) in a `forge.` package, so `WireClassFilter` takes them (`WireClassFilter.java:33-50`) and ProGuard keeps them (`proguard.cfg:74`). Give each an explicit `serialVersionUID`.
- **`CompanionShareHost`:** its own `ServerBootstrap` from net play's **public** pieces: `CompatibleObjectEncoder`/`Decoder` (`CompatibleObjectEncoder.java:16-24`, `CompatibleObjectDecoder.java:25-47`), `IdleStateHandler` + `HeartbeatEvent` with a **15 s** reader idle (plan 3.1), `RemoteClient`, `RemoteClientGuiGame`, and a public `GameProtocolHandler` subclass (nested is fine) that also handles `requestResync` as `GameServerHandler.beforeCall` does (`GameServerHandler.java:41-53`). Port **36744**, or the next free one up to 36753 (net play uses 36743, `ForgeNetPreferences.java:28`). It **never** calls `HostingServer.set` (`FServerManager.java:281`), so no other mode changes, and it doesn't use `FServerManager`. Admission: inherited `LoginEvent`, then `hello` with the right token → `welcome`, then attach. **No version check** in this slice (07 adds it). Everyone is a spectator. Netty callbacks never touch the game or the UI (plan 3.4).
- **`CompanionPeer`:** one joined instance: a `RemoteClient` + `RemoteClientGuiGame` + `GameEventForwarder`. **Attach**, on the game thread through `runSystem`: `setGameView(null)` then `setGameView(game.getView())`, `setForwarder`, subscribe the forwarder, `addObserver` on every seat's input queue, `setOriginalGameController(seatView, gate)` for **every seat**, then `openView(all seats)`, as `ReplaySession.attach` does (`ReplaySession.java:83-97`) plus the seat registration; then `attachMidGame()`; then send `epoch`. **Detach** on disconnect and on the host leaving: remove the observers, `shutdownForwarder`. Every seat being local on the client means the client creates a `NetGameController` per seat (`FGameClient.java:268-277`), hands are visible as on the host (`AbstractGuiGame.java:260-271`), and the no-human overlay is never built.
- **`PeerGate`** (`IGameController`): what the host invokes for a peer's inbound screen calls. Drops everything (`concede`, `drawOfferAction`, `undoLastAction` included) and answers any request with null, as `GameProtocolHandler` does for a missing controller (`GameProtocolHandler.java:62-74`). This is all of AC-108 on the host side.
- **`CompanionClient extends FGameClient`:** login is the inherited `LoginEvent` (`GameClientHandler.java:231-245`); then `hello`; receives `welcome` and `epoch` through the extra inbound handler. The view goes through the inherited `GameClientHandler` to `MatchController.instance` (`GuiMobile.java:356-359`); the client keys its tracker on the first `setGameView` (`GameClientHandler.java:95-117`). Reconnect stays net play's for now (04 changes it).
- **Messages** (one file of nested classes, or one per message): `hello` {token, game = null}, `welcome` {game id}, `epoch` {n}. The epoch counts the host's full sends to that peer (fresh attach, undo, checksum resync `ProtocolGuiGame.java:220-226`), sent on the data stream after each one (plan 3.2). Nothing reads it yet besides a log line; 04 and M2-1 use it.
- **`JoinCode`** (record): Crockford base32 of IPv4 (4 bytes) + port (2) + random share token (4) = 16 characters, `XXXX-XXXX-XXXX-XXXX` (plan 3.4). Token random per share in this slice (kept across restarts from 04).
- **`LocalAddresses`:** usable IPv4 addresses (site-local, up, not loopback, cellular or VPN) by walking `NetworkInterface.getNetworkInterfaces()` itself. Don't reuse `FServerManager.getAllLocalAddresses` (`:645`): it starts with `getLocalAddress`, which routes towards 8.8.8.8 (`:647`, `:631`) and fails without internet. Raw interface names here; friendly names in 09.

### New and changed code (`forge-gui-mobile`, package `forge.screens.companion`)
- **`ShareDialog`** (new): one code per usable address, labelled with the interface name. No QR (08), no Phones list (05).
- **`CompanionMatchExtension`:** a "Share game…" game-menu item that starts `CompanionShareHost` for this table and opens `ShareDialog`. Stopping: the share stops when the host leaves the match.
- **`JoinGameScreen`** (new): a code field, Join, then the match screen once `openView` arrives. Sets `CompanionViewExtension` **before** `connect()` (`MatchController.setExtension`, after `hostMatch()` clears it, `MatchController.java:880-884`).
- **`CompanionViewExtension`** (new, a `MatchExtension`): `offersConcede` false, `showsHandsAtStart` false, `promptActionLabel` null, no game-menu items that act, nothing from `tapLife` or `addCardMenuItems`. Its menu offers only "Leave" (disconnect, back to Companion).
- **`CompanionSetupScreen`:** a "Join game" button that opens `JoinGameScreen` (plan 2.1 cites `CompanionSetupScreen.java:46`, the store, and `:317`, `openGame`).

### Protocol involved
View stream from net play, unchanged: `setGameView` → `openView(all seats)` → full state (`ProtocolGuiGame.java:258-266`, `:289-295`) → deltas plus events at each priority, end of turn and input-queue change (`GameEventForwarder.java:14-30`, `:41-75`). Data: `LoginEvent`, `hello`, `welcome`, `epoch`. Order on join: `hello` → `welcome` → attach.

### Build chunks (each compiles and leaves v1 unchanged)
1. `CompanionGameState` events + `ProtocolGuiGame.attachMidGame` + `CompanionTable.runSystem`, with the engine test below.
2. `CompanionShareHost`, `CompanionPeer`, `PeerGate`, messages, `JoinCode`, `LocalAddresses`.
3. `FGameClient` hook + `CompanionClient`.
4. `ShareDialog`, "Share game…", `JoinGameScreen`, `CompanionViewExtension`, "Join game".

## Not in this slice
- Real phones, Wi-Fi binding, cross-runtime, flushes on the game thread, the 8-consumer measurement: **01**.
- Any data besides `hello`/`welcome`/`epoch`: no log, no steps, no setup, no `end`, nothing saved on the joined instance: **02, 03**.
- Re-attach when the host starts a new `Game` (restart from the match screen, resume): **04**. Until then a joined instance shows the old board after a host restart, and must rejoin.
- Reconnect beyond net play's own backoff, `share.json`, restart of either side: **04**.
- Seats, roster, Phones list, Remove, own seat at the bottom: **05**. Foreground service: **06**. Fingerprint and refusals: **07**. QR: **08**.
- `MatchController` overlay null check (plan 2.1): not expected to be hit, because every seat is local on the client. If the "no local player" branch (`MatchController.java:258-276`) is hit, add the null check here and say so.
- `GameClientHandler.resetTracker`: **03**.
- Every M2/M3 item.

## Check first
1. **`attachMidGame` baseline:** after the attach, the first flush's packet is a delta, not the whole graph (log the byte size of the full send and of the next flush).
2. **v1 regression from the snapshot events:** an **unshared** v1 game's undo and resume still look and play the same on the host's screen (the host's `MatchController.onSnapshotRestored` now runs; `MatchController.java:468`). Check it before going further.
3. **Login flow:** the inherited `LoginEvent` path in `FGameClient`/`GameClientHandler` doesn't wait for a lobby update before the client accepts `setGameView`/`openView`. If it does, find the smallest hook.
4. **Rendering on the joined instance:** a placeholder in hand and library ("Unknown card", not in `CardDb` on the client), the command zone with the commander and the table effect (`CompanionCards.java:35-38`, `:60-63`, `:80-95`). List anything that renders oddly here (plan Risks).
5. **Two instances on one PC:** confirm the recipe below and correct it in this file and in later bundles if it differs.

## Tests (CI)
One engine test, in `forge-gui-desktop` next to `TableEditorTest` (`forge-gui-desktop/src/test/java/forge/gamemodes/companion/`), reusing `HeadlessNetworkClient`/`HeadlessNetworkGuiGame` (`forge-gui-desktop/src/test/java/forge/net/`):
- **Late attach then undo** (plan 6, AC-105 engine test): a peer attaches after the game started; the host undoes; the peer gets a full state with the new ids, then deltas that resolve against them; its first flush after the attach doesn't resend the whole graph.

No other new tests: v1's `TableEditorTest` and `CompanionStoreShould` must stay green unchanged.

## Oracle
**Two instances** (confirm in check 5):
```
REPO=/path/to/forge
mvn -pl forge-gui-mobile-dev -am package -DskipTests
for n in host join; do
  mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties
done
JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) &
(cd /tmp/companion-join && java -jar "$JAR" width=450 height=900) &
```
The launcher takes `./` as its assets folder when `./res` exists (`GameLauncher.java:23`) and reads `forge.profile.properties` there (`ForgeConstants.java:39`, `ForgeProfileProperties.java:59-65`).

Each step is OK when the observed result matches, FAIL otherwise.
1. **Host:** Companion → new game, 3 players → add Sol Ring for player 1 (the game has started). OK: v1 behaviour.
2. **Host:** game menu → "Share game…". OK: a code `XXXX-XXXX-XXXX-XXXX` per usable address, labelled with an interface name, and no loopback address.
3. **Joiner:** Companion → "Join game" → type the code. OK: within a few seconds the match screen shows all 3 boards, Sol Ring on player 1's battlefield, each hand as card backs, the command zone with the table effect. FAIL: an empty board, an error, or a hang.
4. **Host:** add "Gray Merchant of Asphodel" for player 2, cast. OK: on the joiner, within about 1 s of the host's board changing, the spell appears (stack, then battlefield), the other players lose 2, player 2 gains 4, player 2's hand count drops by 1.
5. **Host:** undo. OK: the joiner's board goes back to step 3's state (Gray Merchant gone, life back) without rejoining. FAIL: the joiner keeps the old board, shows ghost cards, or logs an IdRef error.
6. **Host:** add any card again after the undo. OK: it shows on the joiner (deltas resolve against the renumbered ids).
7. **Joiner:** tap a card, tap OK, press Ctrl+Z, open the game menu. OK: nothing changes on the host; the menu offers no Concede, Offer Draw, Undo or Add card (AC-108).
8. **Host:** leave the match, then start a new **unshared** game, add a card and undo. OK: exactly v1 (AC-114).
9. Write the results of the "Check first" items into a "Results" section of this file, as v1's slice 0 did.

No phone checks in this slice: they are 01.

## Results
Chunk 1 (engine), by `CompanionLateAttachTest` (in-process `ProtocolGuiGame` on a recording `IRemote`, 3 seats, setup state plus two cards):
1. **`attachMidGame` baseline: corrected.** As designed (`sendFullState()` then `registerNewObjects`) the first flush after the attach still carried the whole graph: 614 new objects, 8,107 bytes, against a full state of 8,977 bytes. `registerNewObjects` only starts dirty tracking and deliberately leaves each object to go again, in full, in the next delta (`DeltaSyncManager.walkAndRegister`). `attachMidGame` now runs one `collectDeltas` walk and drops its packet (it registers every object as sent and clears its dirty bits), then `sendFullState()` with the walk's sequence number. First flush after: 2 new objects, 3 changed, 1,501 bytes. No change to `DeltaSyncManager`.
   - The attach as described (`setGameView(null)`, `setGameView(view)`, `openView`, `attachMidGame`) sends the full state **4 times** (the fallback in `setGameView`, the fallback and `sendFullState` in `openView`, then `attachMidGame`'s). Correct, but 01's measurement should count it.
   - Net play's own undo resend (`ProtocolGuiGame.handleGameEvents`) sends the full state, then a delta whose new objects are the whole renumbered graph again, for the same reason. Unchanged here (net play mustn't change); worth the same fix later if 01 finds the undo resend too big.
2. **v1 regression: engine unchanged, host screen to be checked by hand.** `TableEditorTest` (183) and forge-gui's companion tests, `CompanionStoreShould` included, are green unchanged. What the events now do on the host's screen: `FControlGameEventHandler` queues a refresh of every player's life, mana and zones (a redraw), and mobile's `onSnapshotRestored` clears card animation state (`VCardDisplayArea.CardAreaPanel.resetForUndo`). **One audible change:** `EventVisualizer` mutes sounds between the two events and plays the "rewind" sound at the end, so a Companion undo, and also every game start and resume (`startFromLatest` goes through `applyNow`), now plays the rewind sound when sound is on, and the load's own sounds are muted. Check on the dev build; if the sound at start is unwanted, `startFromLatest` can skip the events (no view is attached before the game starts in this slice; 04's fresh attach sends its own full state).

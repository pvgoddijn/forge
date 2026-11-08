# Companion Mode, Multiple Phones: Plan

_ADLC phase 2 (Plan). This is the **how** for the approved [`spec.md`](spec.md). `AC-1xx` refers to its criteria, `AC-n` (n < 100) to [v1](../spec.md)._

Status: **draft, rewritten for the owner's single-engine decision** · 2026-11-08 · awaiting owner review

## Owner decision this plan is built on

Card ids are renumbered whenever a game state is loaded: a state names cards by `Id:` only to link attachments inside the load (`forge-game/.../GameState.java:1390-1392`), and the loaded cards get new ids. So two phones running their own engines can't share card identity. **There is one engine, on the host.** Joined phones are views of the host's game. There's no mirror engine, no pen, no hand-over and no snapshot loss when a phone acts. When a joined phone acts (M2), the action runs on the host's engine, and any prompt it raises opens on that phone, which answers it with the host's card ids.

## Open product decisions

Settled by the owner:

| Q | Decision | Where |
|---|---|---|
| Q25 | AC-126 "later takeover" means the **higher term**, not the later clock time | 4.3 |
| Q27 | The host runs a foreground service with a notification while sharing | 2.4, 3.4 |
| Q29 | A phone that missed the game's end shows "Stop following". Joining needs the phone's own active game ended first. A removed phone keeps its copy as an ended game. A restarted host resumes sharing. If the host is the hotspot and dies, takeover isn't possible | 4.1, 4.3, Risks |

Made obsolete by the single engine:

| Q | Was | Now |
|---|---|---|
| Q22 | Accept what every pen hand-over loses | No hand-overs happen. Only a **takeover** loads a snapshot, which loses what v1's resume loses (4.3) |
| Q24 | Under the pen, every prompt opens on the attacker's phone | Prompts open on the phone that has **the floor** (4.2). Blocks are decision N3 |
| Q28 | A pen holder that stays away | Replaced by N4: the floor goes back to the host when the acting phone disconnects |
| (old c) | AC-106 narrowed to settled points | **Withdrawn.** The view stream carries the stack and combat as they happen (3.2) |
| Q26 | Joined phone's bottom panel = the host's Me | Now cheap: decision N2 |

New decisions this design needs from the owner (the plan is written for the default shown):

| | Decision | Default | Affects |
|---|---|---|---|
| N1 | **AC-110 / B2:** "compatible" means the **same app build**, not only the same log format and card database. The view travels as Java serialization of Forge's own classes (3.1), and a property-order difference between builds corrupts it silently | Accept: refuse unless the build version, Companion protocol version and card database fingerprint are all equal. Dev builds report `GIT` (`BuildInfo.java:45-51`), so two dev builds match | spec B2, AC-110 |
| N2 | A joined phone shows **its own seat at the bottom**, keeping turn order | Accept (a small hook, 2.1). Spectators see the host's Me at the bottom | AC-107 |
| N3 | In an engine combat, the **block declaration** opens on the defending seat's phone when that phone is connected and allowed to act, otherwise on the phone with the floor | Accept | 4.2 |
| N4 | When the phone with the floor disconnects mid-prompt, an open dialog is cancelled, as Forge net play cancels it (an empty answer, so the engine's default or cancel), and an open board prompt (targets, attackers, OK) moves to the host's operator, who finishes it | Accept | 4.2 |
| N5 | After a **takeover**, what the old host had live but not yet saved as a step is lost: an unresolved stack, an engine combat in progress, a parked sorcery or turn skip, End Turn yields, "combat this turn", and the life and commander-damage tallies a load resets (v1 plan §2.3) | Accept | 4.3 |
| N6 | A **joined phone restarted while the host can't be reached** shows the last saved step as text (the past-games board summary) plus the full history, not the match board, until it reconnects. The match board needs the host's view | Accept | 4.1 |
| N7 | While a phone has the floor, prompts raised later by what's already on the stack (an ETB "may", OK to resolve) go to the phone with the floor, which is the phone that acted **last**. They don't go to whoever cast that stack item | Accept | 4.2 |

## 0. What exists, and why it shapes this plan

### 0.1 v1 Companion (this branch)
- **One real game, one GUI.** `CompanionMatch.start` registers every seat with the same `IGuiGame` (`CompanionMatch.java:26-34`). `HostedMatch.startGame` gives each human controller that GUI, an `FControlGameEventHandler`, and one `openView` per GUI (`HostedMatch.java:219-244`, `:262-264`).
- **Ops run on the game loop thread.** `CompanionTable.submit` queues an op and ends the priority prompt (`CompanionTable.java:595-607`). The controller runs the queue in `chooseSpellAbilityToPlay` (`CompanionPlayerController.java:40-82`). Every op that runs is logged and sets `ranSinceSave` (`CompanionTable.java:920-967`).
- **Steps and log.** A step is saved only at a settled point, a main phase with an empty stack (`CompanionTable.java:987-993`, `save` `:1160`). Steps go in `states/<n>.txt` (`CompanionStore.java:29-35`). Undo applies step n−1 in place, `discardAfter`, and appends an `UNDO` entry (`CompanionTable.java:692-751`). The log only describes what happened and can't be replayed. `appendLog` renumbers from a `lastSeq` cache (`CompanionStore.java:60`, `:284-305`) and swallows a failed write (`:300-302`). Only `writeWhole` fsyncs (`:398-410`).
- **A load renumbers cards** (above) and resets the life-this-turn tallies and commander stats (`CompanionGameState.java:114-119`, `:148-152`).
- **Some ops are lambdas.** Ready creatures (`CompanionMatchExtension.java:180`) and set life (`LifeDialog.java:90-98`, which calls `table.confirm` inside the op) can't travel. `AddCard`, `Reassign` and `Move` are records (`CompanionTable.java:93`, `:144`, `:175`), and `Move` already names its card by id.
- **The match screen hooks.** `MatchController.setExtension` is static and set before the match starts (`CompanionSetupScreen.java:342`). The game menu, the life tap and the card menu ask `getGameMenuExtension()`, which is non-null only when `isSingleOperator()` (`MatchController.java:893-912`; `VGameMenu.java:30-31`, `VPlayerPanel.java:1673`, `VCardDisplayArea.java:839`). The prompt bar's action button uses `getExtension()` with no such check (`MatchScreen.java:133-136`). Ctrl+Z calls `getGameController().undoLastAction()` directly (`MatchScreen.java:897-899`).

### 0.2 Forge net play already does "host runs the engine, remote renders a GameView"
- **`ProtocolGuiGame`** turns `IGuiGame` calls into `ProtocolMethod` events and keeps the peer's `GameView` current through a per-consumer `DeltaSyncManager` (`ProtocolGuiGame.java:36-52`). It knows nothing about transport: it takes an `IRemote` (`IRemote.java:6-10`), "an embedding host can consume them in process" (`ProtocolGuiGame.java:39-41`). It sends a full state on `openView` (`:289-295`) and on `GameEventSnapshotRestored(false)` (`:543-549`). It sends a full state on a checksum resync too (`:220-226`, `:249-252`). While paused, `sendAndWait` returns null without sending (`:166-169`).
- **`RemoteClientGuiGame`** adds the Netty transport: events are wrapped for Java serialization, and the codec follows the game's tracker (`RemoteClientGuiGame.java:23-56`). **`RemoteClient`** holds the channel and a `ReplyPool`, encodes on the calling thread and blocks in `sendAndWait` (`RemoteClient.java:88-152`). It can swap channels on reconnect (`:50-54`).
- **Wire:** `CompatibleObjectEncoder` = 4-byte length + LZ4 + Java serialization with tracker IdRefs (`CompatibleObjectEncoder.java:16-24`, `:77-119`). `CompatibleObjectDecoder` caps frame and decompressed size (`CompatibleObjectDecoder.java:25-47`). `WireClassFilter` allows only `forge.`, `java.util.`, Guava collections and a few `java.lang` types (`WireClassFilter.java:33-50`). It's package-private, but every decoder stream applies it (`:16-23`).
- **`GameEventForwarder`** batches events and flushes them at priority, at end of turn, and whenever an observed input queue changes (`GameEventForwarder.java:14-30`, `:41-75`). So a remote view gets what happened **before** the game thread blocks for input, the stack and combat steps included.
- **A hidden spectator already exists: `ReplaySession`.** It attaches a `ProtocolGuiGame` (`RecordingGuiGame`) to a local game the way `HostedMatch` wires a remote client: `setGameView`, `setForwarder`, subscribe the forwarder, observe every human's input queue, `setReplayGui`, `openView` (`ReplaySession.java:83-97`). It's the template for Companion's remote views. It's off for more than one human (`:52-57`), so a Companion game is never recorded.
- **The client.** `FGameClient` builds the pipeline (encoder, decoder, idle handler, `GameClientHandler`) (`FGameClient.java:99-125`), sends `LoginEvent` with the build version on connect (`GameClientHandler.java:231-245`), keeps a client tracker for IdRefs (`:93-152`), and reconnects with backoff 1, 5, 15, 45, 60 s, then gives up (`FGameClient.java:55`, `:329-397`). `ReplayClient extends FGameClient` is a precedent for a subclass (`ReplayClient.java:14`). `NetGameController` sends the screen's actions to the host and carries no seat (`NetGameController.java:26-110`).
- **The server.** `FServerManager` is a singleton (`FServerManager.java:82`, `:214-219`) tied to `ServerGameLobby`: a login takes a lobby slot (`:1089-1178`), a reconnect resumes by user name (`:1100-1131`, `resumeAndResync` `:929-972`), and a timeout converts the seat to AI (`:974-1016`). It only **warns** on a version mismatch (`:1161-1174`). `GameServerHandler` is package-private and calls `FServerManager.getInstance()` (`GameServerHandler.java:12-14`). Starting it sets `HostingServer` (`FServerManager.java:281`), which makes every match subscribe a network log listener (`HostedMatch.java:185-187`) and arms AFK timeouts (`HostingServer.java:37-40`). **No remote spectators**: every connection is a lobby slot.
- **Disconnect mid-prompt.** On `channelInactive` the server cancels every pending reply with null (`FServerManager.java:1239-1249`, `ReplyPool.java:86-94`), so a blocked dialog returns its default (`ProtocolGuiGame.java:404-424`). On resume it re-shows the current input through `getInputQueue().updateObservers()` (`FServerManager.java:961-969`).

## 1. Approach

```
 host phone                                                          joined phone (Forge app)
 ┌──────────────────────────────────────────┐   Netty, LZ4 + Java    ┌──────────────────────────────┐
 │ HostedMatch ─ real Game ─ CompanionTable │   serialization        │ CompanionClient              │
 │   seats' GUI: host MatchController,      │                        │  (extends FGameClient)       │
 │   or the floor holder's proxy (M2)       │ ── view: setGameView,  │   GameClientHandler          │
 │ CompanionShareHost (own small server)    │    openView, applyDelta│   └► MatchController         │
 │   per phone: CompanionPeer               │    (and M2 prompts) ─► │      + CompanionViewExtension│
 │     = RemoteClient + RemoteClientGuiGame │ ── data: log lines,    │ CompanionStore (verbatim     │
 │     + GameEventForwarder                 │    step files, setup,  │   copy: data, never loaded)  │
 │   journal ◄─ CompanionStore hook         │    roster, end ──────► │                              │
 │   op queue = CompanionTable.submit       │ ◄─ hello, claim; M2:   │                              │
 │                                          │    op, answers, undo   │                              │
 └──────────────────────────────────────────┘                        └──────────────────────────────┘
```

Key decisions:

1. **Reuse net play's view stack; don't write a JSON protocol.** The view is the hard part: a live `GameView` with deltas, the stack and combat as they happen, IdRefs, checksums, resync, and prompts that block for an answer. Net play has all of that, in production on desktop and mobile. A JSON protocol would have to re-describe `GameView`, or ship snapshots (which renumber, so they can't carry M2 prompts), and would need its own prompt forwarding. The costs of reuse:
   - The wire is Java serialization of Forge classes, so both phones need the same build (N1).
   - A later browser listener (spec §6.1) can't read it. It would need a second, JSON view endpoint on the same server, fed from the same `ProtocolGuiGame` hooks (`encodeEvents`, `onDeltaSent`). Nothing here blocks that, but nothing here builds it either.
   - The class filter allows lambdas (`SerializedLambda`, `WireClassFilter.java:44-50`), the same exposure net play accepts. B1 already says anyone with the code can join.
2. **Our own small server, not `FServerManager`.** The singleton is a lobby with slots, AI takeover and AFK timers, and starting it flips `HostingServer` for every match (0.2). Companion needs spectators without a limit, seat claims, read-only phones, kick, and its own data stream. `CompanionShareHost` builds its own `ServerBootstrap` from the **public** pieces: `CompatibleObjectEncoder`/`Decoder` (and through them `WireClassFilter`), `IdleStateHandler` + `HeartbeatEvent`, `RemoteClient`, `RemoteClientGuiGame`, `GameProtocolHandler`. It never calls `HostingServer.set`, so no other mode changes. Net play's lobby, `LoginEvent` slot logic, draft, UPnP and chat are bypassed.
3. **Each joined phone is a spectator-style view on the host's `HostedMatch`**, attached like `ReplaySession` attaches its hidden GUI (0.2), on the game loop thread, at join and at every new game (a resume or restart makes a new `Game`).
4. **Joined phones store the host's log and step files as data**, byte for byte, through the v1 store. They never load them while following. They're for the history (AC-105/107), for a restart (AC-112), and for M3, where the new host loads the latest step into its own fresh engine. Renumbering is harmless then, because it's again the only engine.
5. **M2: actions are ops on the host's queue, and prompts follow "the floor".** A joined phone sends a Companion op as data. The host runs it through `CompanionTable.submit` in arrival order (AC-118). While it runs, and until the table settles again, the phone that acted **has the floor**: every seat's GUI is that phone's proxy, so targets, modes, "may" and OK all open there, with the host's card ids (4.2).
6. **Terms, freshness, catch-up and stale-host truncation stay from the old plan**, over the data stream (3.3, 4.3).
7. **Control is not log.** Seat claims, read-only and removal are host session state, sent as a roster. Attribution goes on each log entry (`by`, `byName`).

## 2. Components

### 2.1 Changes to existing code
Each change leaves an unshared game exactly as in v1 (AC-114).

| Change | Why |
|---|---|
| `CompanionGameState.applyNow` fires `GameEventSnapshotRestored(true)` before the apply and `(false)` after it. Today a Companion load fires neither (only `GameSnapshot.java:68-70` and `UndoManager` do). With them, every remote proxy sends a full state (`ProtocolGuiGame.java:543-549`), and the host's own screen rebuilds through `onSnapshotRestored` (`FControlGameEventHandler.java:56-59`, `:320-330`; mobile `MatchController.java:468`) | Undo and resume renumber every card. Joined phones need a full resend, not deltas over ids that no longer exist |
| `CompanionLogEntry`: optional `term` (absent = 0), `host` (absent = the game's creator), `by` (device id; absent = the host operator), `byName`, and `time` on a `HOST` entry (M3). Through `toLine`/`fromLine` (`CompanionLogEntry.java:215`, `:245`). An unknown kind already reads as `NOTICE` (`:280-288`) | AC-119, AC-125/126. v1 lines read unchanged |
| `CompanionStore`: a **journal hook**, told of each entry appended, step saved, steps discarded, setup saved and game ended, **only after the write succeeded**. `appendLog` swallows a failure today (`:300-302`), so it gets a success flag. The hook may stamp `term`/`host`/`by`/`byName` before numbering. `appendVerbatim(id, entries)` keeps each seq, refuses a gap, skips an entry already held with the same bytes, **updates `lastSeq`** (`:60`) so a later `appendLog` (after M3's takeover) numbers after it, and fsyncs once per chunk. Also `writeStep(id, n, text)` through `writeWhole` (`:398`), `stepHashes(id)`, `share.json`, and `truncateLogAfter(id, seq)` (a whole rewrite, resetting `lastSeq`; M3) | Decision 4 |
| `CompanionTable`: `runSystem(Runnable)`, run on the game loop thread before the op queue, **not logged, no `ranSinceSave`** (unlike `runOne`, `:920-967`). It's used to attach a view, resync and switch the floor. M2: `origin` (device) on each op, the **floor** (4.2), and records `ReadyCreatures(ids)` and `SetLife(seat, life, fire)` in place of the two lambdas. `confirm` (`:628-637`) then asks the floor's GUI | Decision 3, 5 |
| `CompanionPlayerController`: M2 only. The screen's entry points (`selectButtonOk`/`Cancel`, `selectCard`, `selectPlayer`, `selectAbility`, `selectStackItem`, `useMana`, `undoLastAction`, which is `table.undo()` at `:99-102`) ignore a call from the host's own screen while a remote phone has the floor. The remote path marks its calls, so they pass | 4.2 |
| `MatchExtension`: two hooks with defaults. `bottomPlayer(players)`, used by `MatchController.openView` to rotate the panel list so that player comes first, which keeps turn order (`MatchController.java:226-247`). And `actsWithoutLocalOperator()`, which `getGameMenuExtension()` also accepts (`:910-912`), so a joined phone's extension gets the game menu, life tap and card menu in M2 | N2; M2 entry points |
| `MatchController.openView`: with no local player, a touch calls `hostedMatch.pause()` (`:258-276`). On a joined phone `hostedMatch` is null or a stale match. Joined phones avoid that branch by getting every seat as a local player (2.2), and the overlay gets a null check anyway | Robustness |
| `FGameClient`: protected hooks, all additive: an extra inbound handler before `GameClientHandler` (the pipeline is fixed at `:110-118`); an overridable backoff (Companion: 0.5 s doubling to 5 s, **never giving up**, versus `:55`, `:347-353`); `markSeated()` (today only set from `openView` or a lobby update, `:271`, `:492`) | Reuse the client unchanged in every other way |
| `RemoteClient.getReplyPool()` made public (`:225`), and a public `GameProtocolHandler` subclass for Companion (`GameServerHandler` is package-private and bound to the singleton, `GameServerHandler.java:12-14`). It also handles `requestResync`, as `GameServerHandler.beforeCall` does (`:41-53`) | Decision 2 |
| `CompanionSetupScreen` (`:46`, `:317`): "Join game", and resuming by `share.json` role: *hosting* starts sharing again; *joined* reconnects (N6 while unreachable) | AC-102, 112 |
| `CompanionHistoryScreen`: shows `byName` | AC-119 |
| `IDeviceAdapter` (`forge/interfaces/IDeviceAdapter.java:27-60`), with defaults: `canScanCodes()`, `scanCode(Consumer<String>)`, `bindToLocalNetwork(boolean)` (no-op), `keepServing(boolean)` (no-op) | AC-102, 3.4 |

**Not touched:** `forge-game`, desktop Swing, `FServerManager`, the lobby. In `forge/gamemodes/net/**` only the visibility and hook changes above.

### 2.2 New code: `forge-gui`, package `forge.gamemodes.companion.share`
Messages are small `NetEvent` classes (strings, ints, lists) in a `forge.` package, so `WireClassFilter` takes them, and ProGuard keeps them (`-keep class forge.** { *; }`, `proguard.cfg:74`).

| Type | Role |
|---|---|
| `CompanionDevice` (record) | This install's random id and display name (`companion/device.json`) |
| `JoinCode` (record) | The typed code and QR text (3.4) |
| `Compat` | Build version (`BuildInfo.getVersionString`), Companion protocol version, card database fingerprint (edition codes + card count). Equal on both sides, or refused (N1) |
| `CompanionShareHost` | The server (decision 2): admission, `hello`/`claim`, roster, Remove, read-only, the journal, and one `CompanionPeer` per phone |
| `CompanionPeer` | One phone: a `RemoteClient` + `RemoteClientGuiGame` + `GameEventForwarder`. **Attach** (game thread, through `runSystem`): `setGameView(null)` then `setGameView(game.getView())`, `setForwarder`, subscribe the forwarder, `addObserver` on every seat's input queue, `setOriginalGameController(seatView, gate)` for **every seat**, then `openView(all seats)`, as `ReplaySession.attach` does (`ReplaySession.java:84-97`) plus the seat registration. **Detach** on disconnect, Remove and game end: remove the observers, `shutdownForwarder`. On reconnect, a fresh attach. Every seat is local on the phone, so the client creates a `NetGameController` per seat (`FGameClient.java:268-277`), hands stay visible to everyone as on the host (`AbstractGuiGame.java:260-271`; spec §6.4), and the no-human overlay is never built |
| `PeerGate` (`IGameController`) | What the host invokes for a phone's inbound screen calls. M1: drops everything (`concede`, `drawOfferAction` and `undoLastAction` included) and answers any request with null, as `GameProtocolHandler` does for a missing controller (`GameProtocolHandler.java:62-74`). M2: forwards to the seat whose input is current, only while this phone has the floor (4.2) |
| `CompanionClient extends FGameClient` | Login is the inherited `LoginEvent` (name, build version); then `hello`, the data stream into the store, seat claim, unbounded reconnect. The view goes through the inherited `GameClientHandler` to `MatchController.instance` (`GuiMobile.java:356-359`) |
| `ShareState` (record) | `share.json`: role, token, host device and addresses, port, own seat, last roster (the M3 peer list), current term |
| `LocalAddresses` | Usable IPv4 addresses (site-local, up, not loopback, cellular or VPN), reusing `FServerManager.getAllLocalAddresses` (`FServerManager.java:645`), not `getLocalAddress` (`:631`), which routes towards 8.8.8.8 |
| M2: `CompanionOps` | The ops a phone may send (`AddCard`, `Reassign`, `Move`, `ReadyCreatures`, `SetLife`, `Undo`, `EndTurn`, `PassPriority`, the combat and commander switches), all data, card ids are the host's, plus the view **epoch** they were made against (4.2) |
| M3: `PeerProbe`, `Takeover` | 4.3 |

### 2.3 Screens: `forge-gui-mobile`, package `forge.screens.companion`
| Screen | Notes |
|---|---|
| `ShareDialog` | Code as text and QR (ZXing `BitMatrix` → libGDX `Pixmap`), the interfaces it names, and "Phones": seat, connected, Remove, and (M2) a read-only switch |
| `JoinGameScreen` | Typed code (and Scan where `canScanCodes()`), then a seat or "Watch", then the match screen once `openView` arrives |
| `CompanionViewExtension` (`MatchExtension`) | The joined phone's extension, set before `connect()` (`MatchController.setExtension`, after `hostMatch()` clears it, `:880-884`). `offersConcede` false, `showsHandsAtStart` false, `bottomPlayer` = own seat (N2), game menu: History (from the local store's log), Stop following. M1: `promptActionLabel` null and no acting items, so `tapLife`, `addCardMenuItems`, the action button, Ctrl+Z (dropped by `PeerGate`) and the game menu offer nothing that acts. M2, seated and not read-only: the v1 items, sending ops (4.2) |
| `CardSearchDialog`, `LifeDialog`, `MoveDialog`, `WrongPlayerDialog` | M2: take a small `TableActions` interface instead of `CompanionTable`. `CompanionTable` implements it locally. `RemoteTable` sends ops and reads what the dialogs need (`setup`, `offerSeats`, `addedThisTurn`, `canUndo`, the switches) from a host status message sent with the roster at each prompt |
| `MatchScreen` | Unchanged. Net play's reconnect banner shows the connection state (`NetworkGuiGame.java:579`) |

### 2.4 Android: `forge-gui-android`
- `QrScanActivity` reuses the Camera2 controller and its YUV frames (`forge/app/camera/FrameAnalyser.java`), with ZXing's `QRCodeReader` on the Y plane.
- **Wi-Fi binding.** On a Wi-Fi network with no internet (a bare router, or the host's hotspot), Android keeps cellular as the default, and a socket to the host's LAN address leaves over cellular. `bindToLocalNetwork(true)` finds the Wi-Fi `Network` (a `NetworkCallback` for `TRANSPORT_WIFI`, which needs `ACCESS_NETWORK_STATE`) and calls `bindProcessToNetwork` while joined, or while a new host probes peers (M3). Netty NIO can't take `Network.getSocketFactory()`, so the process binding is what applies. It's undone on leaving. A host that *is* the hotspot only accepts connections, so it needs no binding.
- **Foreground service (Q27).** Android 12+ freezes a backgrounded app, which would stop the host serving. While sharing, a `specialUse` foreground service with a notification ("Sharing a Companion game") runs through `keepServing(true)`. Joined phones run none: they reconnect on resume.
- **Permissions.** Already declared: `INTERNET`, `ACCESS_NETWORK_STATE` (`src/main/AndroidManifest.xml:27-28`) and `FOREGROUND_SERVICE` (`:25`). New: `FOREGROUND_SERVICE_SPECIAL_USE`, the service entry with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (needed at `targetSdkVersion` 35, `:7`), and `POST_NOTIFICATIONS`.
- **No new transport on Android.** Netty 4.1.115 (`forge-gui/pom.xml:50-54`) and LZ4 are already in the APK, and mobile online play uses this exact pipeline. ProGuard keeps `forge.**` and `net.jpountz.**` (`proguard.cfg:74`, `:100`). This removes the old plan's WebSocket keep-rule risk.

### 2.5 New dependency
**ZXing `com.google.zxing:core`** (Apache-2.0, pure Java) in `forge-gui-mobile`, to draw and read the QR code. No QR library is in the repo today. iOS takes `forge-gui-mobile` (`forge-gui-ios/pom.xml:93`), and pure Java passes `ios-compat-gate.yml`. `canScanCodes` defaults to false, so iOS offers typed codes only.

## 3. Transport and protocol

### 3.1 Wire
Net play's framing as is: 4-byte length, LZ4, Java serialization (0.2), heartbeat through `IdleStateHandler` and `HeartbeatEvent` (the server's 45 s reader idle, `FServerManager.java:76`, `:257`; the client writes every 15 s, `FGameClient.java:53-54`). Companion lowers the reader idle to 15 s on both sides, so AC-111's "disconnected" shows quickly. One port per host: **36744** by default (net play uses 36743, `ForgeNetPreferences.java:28`), or the next free one up to 36753. The code carries the port.

### 3.2 View stream (from net play, unchanged)
- **Join:** attach (2.2) → `setGameView` → `openView(all seats)` → full state (`ProtocolGuiGame.java:258-266`, `:289-295`). The client keys its tracker on the first `setGameView` (`GameClientHandler.java:95-117`).
- **Live (AC-106):** the forwarder flushes a delta plus events at each priority, each end of turn, and each input-queue change (0.2). Companion's ops end the priority prompt and prompt again (`CompanionPlayerController.java:57-62`), so each op reaches the phones at once. **The stack, triggers waiting for OK, an engine combat and attacks or blocks being declared all show as they happen.** AC-106 is **not** narrowed.
- **Undo, resume, takeover:** a full state, through `GameEventSnapshotRestored` (2.1). A new `Game` (restart from the match screen, a resume) gets a fresh attach from the match's start hook. That's the same path as net play's next game in a match, with a new tracker per game (`ProtocolGuiGame.java:277-287`).
- **Safety net:** sampled checksums and `requestResync` (`DeltaSyncManager.java:47-53`, `:583-602`), handled by our server handler (2.1).

### 3.3 Data stream (Companion events)
**J** joined phone, **H** host, **P** any peer (M3).

| Dir | Event | Content |
|---|---|---|
| J→H | `LoginEvent` (net play's) | name, build version |
| J→H | `hello` | `proto`, `cardDb`, `device`, `token`, `game` (null on first join), `have` = {`seq`, `term` (number, time, host) of its last entry} |
| H→J | `refuse` | reason + the host's versions (AC-110). The host shows a notice too |
| H→J | `welcome` | game id, host device, term, `setup`, `roster`, latest `seq`, latest `step`, step index `{n: sha256}`, and (M3) where the phone's log diverged |
| J→H | `claim` | a seat name, or null to watch |
| J→H | `want` | step numbers missing or with a different hash |
| H→J | `entries` / `step` / `setup` / `roster` / `end` / `removed` | verbatim log lines; `n`, sha256, text; as named |
| J→H (M2) | `op` | an op from `CompanionOps` with its view epoch |
| H→J (M2) | `op-result`, `status` | an op's result or notice for the phone that sent it; what `RemoteTable` reads |
| P↔P (M3) | `status?`/`status`, `fetch` → `entries` + `step`s, `new-host` | 4.3 |

**Order on join:** `hello` → `welcome` → `claim` → catch-up (data) → attach (view). History rows appear as their entries arrive.

**Catch-up rule.** After `welcome`, the phone cuts back what the host lacks: entries past the host's seq (`truncateLogAfter`; this only happens after a stale host, M3) and steps past the host's latest (`discardAfter(hostLatest)`). Then it compares the step index with its own hashes and sends `want`. The host sends entries after `have.seq`, then the wanted steps. An `UNDO` entry applied in order runs `discardAfter(entry.step)`, as on the host. The hash comparison catches the rest.

**Journal.** The store hook feeds the host's journal in write order: an entry when appended, a step when saved, setup when changed, `end` last. Each session sends in that order. A joined phone writes each message to its store (`appendVerbatim`, `writeStep`) and never applies it to anything.

### 3.4 Join code, threads, lifecycle
- **Typed code:** Crockford base32 of the IPv4 address (4 bytes), port (2) and a random share token (4) = 16 characters, `XXXX-XXXX-XXXX-XXXX`. **QR:** `forge-companion:<code>?g=<game id>&a=<other addresses>`. A scanner tries every address in parallel. The dialog names interfaces ("Hotspot", "Wi-Fi"), because a hotspot address may be randomised per session. The token lasts for the game and is kept in `share.json` (B3: a removed phone may rejoin).
- **Threads.** Netty callbacks never touch the game or the UI. Attach, detach, resync and floor changes go through `CompanionTable.runSystem` (game loop thread: `sync*` needs it, `ProtocolGuiGame.java:49-51`). Store writes run on one share executor per side. UI changes go through `FThreads.invokeInEdtLater`. The client side is net play's: deltas are applied on the GUI thread in order (`GameClientHandler.java:61-76`).
- **Attach waits for a priority prompt.** `runSystem` runs from `chooseSpellAbilityToPlay`. While the host sits in a target or choice prompt, a join completes once it's answered. Accepted: seconds at most.
- Screen kept on while sharing or joined (`preventSystemSleep`, `IDeviceAdapter.java:27`, Android `src/forge/app/Main.java:870`).

## 4. Behaviour by milestone

### 4.1 M1: follow along
- **Sharing (AC-101, 114):** "Share game…" starts `CompanionShareHost` and the service, writes `share.json`, installs the store hook and shows the code. With no phone connected, the hook only feeds the in-memory journal, so logging is exactly v1. The journal is rebuilt from the store on demand.
- **Joining (AC-102–105):** `JoinGameScreen` → login → `hello` → `welcome` (or `refuse`) → seat or Watch (`claim`). A taken seat is refused. The host's Me seat is the host's. Spectators are unlimited (the admission limit is set well above 6 + spectators, as net play's 64 is, `FServerManager.java:95-97`). Then catch-up, then attach. The phone's game folder uses the **host's game id**.
- **One active game per phone (Q29):** a phone with its own unfinished game is asked to end it first (`CompanionStore.loadActive`, `:352`).
- **Viewing (AC-106–108):** Forge's own `MatchScreen` with the host's `GameView`: board, zone tabs, stack, combat, card zoom, the command zone with the commander and table effect (`CompanionCards.java:80-95`). Placeholders show as card backs, because the hidden image key is on the card state the view carries (`CompanionCards.java:35-38`, `:60-63`). Known cards in hand show, as on the host. Own seat at the bottom and highlighted (N2). History and its player filter come from the phone's own copy of the log (`CompanionHistoryScreen` takes a list). **Nothing acts** (AC-108): the extension offers no acting item, and `PeerGate` drops every inbound screen call, so tapping a card, OK, Ctrl+Z, Concede or Offer Draw do nothing on the host.
- **Phones (AC-109):** roster with seat and connection; Remove sends `removed`, detaches, closes, and frees the seat (B5). The removed phone keeps its copy as an ended game (Q29) and may rejoin (B3).
- **Connection loss (AC-111):** the last view stays on screen with net play's reconnect banner. `CompanionClient` retries without end. On reconnect: login, `hello(have)`, catch-up, a **fresh attach** (full state), so no delta baseline is assumed. The host detaches a dropped phone at once rather than parking it as `FServerManager` does, because a view-only phone has nothing to resume.
- **Persistence (AC-112):** the data is on disk before anything is shown. A restarted joined phone reconnects from `share.json`. If the host can't be reached, it shows the last step as text (`CompanionSnapshotSummary`, as past games do, `CompanionPastGamesScreen.java:64-79`) and the history, and keeps retrying (N6). A restarted host resumes the game (v1), and sharing on the same port and token, so its phones come back by themselves.
- **End (AC-113):** `end` → `store.end(id)` → past games, read-only. A phone that missed it shows "Stop following" (Q29). An ended host keeps answering `end` until the app leaves Companion.

### 4.2 M2: act together
**Ops.** A seated phone that isn't read-only builds the same op the host's dialogs build, from its view (card ids are the host's), and sends `op` with the **view epoch** it was made against. The epoch counts the host's full resends (each undo, resume, new game). The host stamps the op with `origin`, `by` and `byName` and submits it (`CompanionTable.submit`), so it queues with the host's own actions in **arrival order** (AC-118). Nothing is merged or deduplicated. An op whose epoch is old is refused ("the board changed: try again"), because a card id from before a load names nothing, or, after a resume, another card. The host refuses ops from spectators and read-only phones (AC-121). With no connection the phone offers no action and keeps no queue (AC-117).

**The floor: whose screen prompts open on.**
- The floor is the host's operator by default. When an op from phone X **starts running** on the game loop thread, X takes the floor: `runSystem` sets every seat controller's GUI to X's `RemoteClientGuiGame` (`PlayerControllerHuman.setGui`, `PlayerControllerHuman.java:152-154`). The seats are already registered on X's proxy (2.2), so `setCurrentPlayer`, `updateButtons` and `showPromptMessage` for any seat are accepted (`AbstractGuiGame.java:92-101`). An op from the host's operator takes the floor back the same way.
- While X has the floor, everything the engine asks opens on X: the cast's targets and modes, "may" and choices (dialogs through `sendAndWait`), the priority prompt with OK and End Turn for the stack, attackers in an engine combat, and the table's own `confirm` (the life dialog's "real gain or loss?"). X's screen sends its taps through `NetGameController`. `PeerGate` hands them to the controller of the seat whose input is current, because only one seat has an input at a time. The prompts are net play's own, so a target is picked on X's board by the host's card id.
- The floor returns to the host at the next **settled prompt**: the active seat's priority prompt in a main phase with an empty stack, nothing parked and no End Turn yield. That's the point where `atPrompt` saves a step (`CompanionPlayerController.java:71-76`). Later prompts from the stack go to the phone with the floor (N7).
- The switch only happens on the game loop thread, between inputs. After a switch, `getInputQueue().updateObservers()` re-shows the current input on the new floor, as `FServerManager` does on resume (`:961-969`). Event handlers keep the host's GUI, because `FControlGameEventHandler` keeps the GUI it was built with (`FControlGameEventHandler.java:43-46`). So the host's board stays live, and its prompt bar says "Anna's phone is acting…". Before the game ends, the GUI is set back to the host's, so `endCurrentGame` closes the right screen (`HostedMatch.java:391-410`).
- **The host's own taps while X has the floor** are ignored by the controller (2.1). The operator's own actions join the queue and take the floor when they run.
- **Blocks (N3):** when the engine asks a defending seat to declare blockers, that one prompt opens on the defending seat's phone if it's connected and acting, else on the floor. Then the floor goes back to the holder.
- **Actor disconnects mid-prompt (N4):** `channelInactive` cancels X's pending replies (`ReplyPool.cancelAll`), so an open dialog returns null and the engine takes its default or cancels. X's proxy is paused, so nothing more is sent (`ProtocolGuiGame.java:166-169`). `runSystem` gives the floor back to the host and re-shows the current input there, and the host's operator finishes it. This mirrors net play's timeout handling, which clears the seat's inputs (`FServerManager.java:1011-1013`), without handing the seat to the AI.
- **Results and notices** of X's op (`setOnResult`, `setOnNotice`, `CompanionSetupScreen.java:333-339`; the auto-add prompt, `CompanionMatchExtension.java:122-139`) go to X as `op-result`, not to the host's screen.

**Per action:** every v1 action is an op (AC-115): add by name or photo, capture and identification on the phone itself (only the card name travels), move, wrong player, set life (with `fire` answered through the floor's `confirm`), ready creatures, the combat and commander switches, End Turn and OK (`EndTurn`/`PassPriority` ops, or the phone's own prompt buttons when it has the floor), turn skip, and undo. "Auto-add photos" and "Keep photos" stay each phone's own preferences. **Default target (AC-116, B6):** the active seat on every phone, unchanged.

**Undo (AC-120):** the menu's Undo or Ctrl+Z on X. If the latest entry it would take back has a `by` other than X, X first shows "Logged by Anna's phone: Cast Lightning Bolt. Undo it?", from its own copy of the log. Then it sends `Undo`, which is v1's undo on the host. Its full resend renumbers the board everywhere (2.1). Ctrl+Z's `undoLastAction` from a phone is routed to the same path by `PeerGate`, so it gets the confirmation too.

**Attribution (AC-119):** `by`/`byName` on every entry written while sharing. History shows "· Anna's phone".

### 4.3 M3: takeover
Every seated phone also listens on the port in its roster entry. From M1 on, the roster carries each phone's addresses and port, and `share.json` keeps them.

1. **Start (AC-122):** "Take over as host", after a confirmation. It doesn't need the old host to be gone.
2. **Freshness (AC-123):** `status?` to every roster peer, waiting at most 3 s. A reply holds game id, its last entry's term and seq, its latest step, and whether it's a host. Terms are ordered by **(term number, the HOST time on the term's `HOST` entry, host device id)** (Q25). Fresher = higher term in that order, or the same term with a higher seq. As in Raft, one writer per term means equal terms at a seq share the prefix.
3. **Catch-up:** `fetch` from the freshest peer from the last *common* seq (the highest seq with the same term on both, found from the peer's list of term starts). The phone cuts its log back if it must (`truncateLogAfter`), runs `discardAfter(peer latest)`, appends the tail verbatim and fetches the steps it lacks. It shows "Caught up: 4 actions", with their text.
4. **Become host:** append a `HOST` entry (term = highest seen + 1, host = this phone, its time). Then start a **fresh engine from the latest step**: v1's resume path (`CompanionTable` + `CompanionMatch.start` + `startFromLatest`, `CompanionTable.java:654-664`), in host mode. Its card ids are new, which is fine: it's the only engine now. `CompanionShareHost` starts, and `new-host` goes to every peer (AC-124). Each peer reconnects with `hello(have)` and gets a fresh attach (a full view).
5. **No peers (AC-127):** nobody answers, so the phone becomes a host with no followers, exactly AC-114.
6. **Stale host returns (AC-125):** a phone that thinks it's host, or follows a host that's gone, probes the roster every 10 s. When it finds a higher term, it stops its own match and server, follows that host, and catches up. `welcome` says where its log diverged. It keeps the dropped entries in memory, cuts back (`truncateLogAfter`, a whole rewrite through `writeWhole`), runs `discardAfter(host latest)`, reconciles steps, and shows its operator the dropped entries that changed the game, to log again. The new host retries `new-host` to unreachable peers every 10 s.
7. **Two takeovers at once (AC-126):** the higher term wins (Q25). Clocks only break a same-number tie. The loser applies step 6.
8. **Lost in a takeover (N5):** whatever the old host had live past its latest saved step. That's an unresolved stack or engine combat, parked ops and a turn skip, yields, `combatTurn` (`CompanionTable.java:223`), and, from the load itself, the life-this-turn tallies and commander damage (`CompanionGameState.java:114-119`, `:148-152`), plus any op still in the old host's queue. Entries the old host had logged but not stepped stay in the history.
9. **Hotspot host dies (Q29):** the network goes with it, so takeover can't reach anyone. The takeover message says so. Venue Wi-Fi survives.

## 5. Files touched

- **`forge-gui`:** `CompanionGameState` (snapshot events), `CompanionLogEntry`, `CompanionStore`, `CompanionTable` (`runSystem`, origin, floor, the two op records), `CompanionPlayerController` (M2 gate), `IDeviceAdapter`. Net code, visibility and hooks only: `FGameClient`, `RemoteClient`. New: `forge.gamemodes.companion.share.*`.
- **`forge-gui-mobile`:** `MatchExtension`, `MatchController` (`bottomPlayer`, `actsWithoutLocalOperator`, overlay null check), `CompanionSetupScreen`, `CompanionHistoryScreen`, the four dialogs (`TableActions`), new `ShareDialog`, `JoinGameScreen`, `CompanionViewExtension`, `RemoteTable`; `pom.xml` (ZXing).
- **`forge-gui-android`:** `AndroidManifest.xml` (activity, service, two permissions), the service class, `Main.java` (`scanCode`, `bindToLocalNetwork`, `keepServing`).
- **Not touched:** `forge-game`, desktop Swing, `FServerManager`, `ServerGameLobby`, the lobby screens.

## 6. Traceability and verification

| AC | Delivered by | Verified by |
|---|---|---|
| 101 | `ShareDialog`, `JoinCode`, ZXing | Dev: code shows. Phone: QR scans |
| 102 | `JoinGameScreen`, `QrScanActivity`, `CompanionClient`, Wi-Fi binding | Two phones on a router with no internet, and on a hotspot, mobile data on |
| 103, 104 | `claim`, roster | Dev: 6 seated instances (own `userDir` each) + a spectator |
| 105 | Catch-up (3.3) + attach (3.2) | **Test:** a phone joining after undos gets an identical log and steps, and a full view |
| 106 | Forwarder deltas (3.2) | Phone stopwatch, up to 8 phones (B4), including a spell on the stack and an engine combat |
| 107 | `MatchScreen`, `bottomPlayer`, local history | Dev; phone with 6 seats for legibility |
| 108 | `PeerGate` M1, no acting items | Dev: tap card, OK, Ctrl+Z, Concede, Offer Draw on a joined instance change nothing |
| 109 | Phones list, `removed` | Dev |
| 110 | `Compat`, `refuse` | **Test:** a different build version is refused with a reason on both sides |
| 111 | Idle timeout, unbounded backoff, banner, fresh attach | **Test:** drop the socket, act on the host, reconnect, view and data match. Phone: Wi-Fi off/on; joined phone backgrounded |
| 112 | Verbatim store, `share.json`, N6 | Phone: kill the app, with the host up and down. Host backgrounded 2 min with its service |
| 113 | `end`, Stop following | Dev |
| 114, 127 | Hook only journals | v1 tests unchanged (`TableEditorTest`, `CompanionStoreShould`). **Test:** same actions shared with no peer write the same log and steps as unshared |
| 115, 116 | Ops, floor | **Engine test:** an `AddCard` from a peer opens its target prompt on the peer's proxy, the answer by host id resolves it, and the floor returns at the settled prompt. Phone: capture on a seated phone |
| 117 | No connection, no actions | Dev |
| 118 | One queue, epoch | **Test:** ops from two peers and the host run in arrival order; a stale epoch is refused |
| 119 | `by`/`byName` | **Test:** field round trip; v1 line reads as before |
| 120 | Undo op with confirmation | Dev |
| 121 | Roster flag | Dev |
| 122–124, 127 | `PeerProbe`, `Takeover`, fresh engine | **Test:** three in-process nodes, take over from the freshest and from a stale one |
| 123 | Catch-up before host | Dev: "Caught up: n actions" |
| 125 | Divergence → truncate + `discardAfter` + list | **Test:** an isolated old host logs and saves, returns, gets the exact dropped list and no step past the new host's latest |
| 126 | Term order | **Test:** same-number takeovers converge; a higher term beats a later clock |
| N4 | Disconnect mid-prompt | **Engine test:** close the actor's socket during a target prompt; the game doesn't hang and the prompt is the host's |

**Tests in CI.** `CONTRIBUTING.md:85` asks agents not to add tests that aren't needed. Only regressions that would be silent get tests: real localhost sockets for sync and catch-up (several in-process nodes on `CompanionStore`), the field round trip, "shared, no peer" = unshared, queue order and epoch, takeover and divergence. Engine tests go in `forge-gui-desktop` next to `TableEditorTest`, reusing net play's headless harness (`forge-gui-desktop/src/test/java/forge/net/HeadlessNetworkClient.java`, `HeadlessNetworkGuiGame.java`): a joined view gets a full state after undo, the floor, disconnect mid-prompt. No UI tests. Phone checks are done by hand. CI gates: `test-build.yaml`, `test-android-build.yml`, `ios-compat-gate.yml`.

**Several instances on one PC:** the dev build stores games in `USER_DIR/companion` (`CompanionSetupScreen.java:46`), and host and joiner use the same game id, so each extra instance runs with its own `userDir` (`ForgeProfileProperties.java:47`). Slice 0 documents the command.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Builds differ, so Java serialization breaks silently | Refuse on build version (N1). Dev builds both report `GIT` |
| Companion's in-place load bypasses delta tracking | Fire `GameEventSnapshotRestored` (2.1). Checksums and resync remain the net (3.2) |
| A remote view of a placeholder, the table effect or a hidden hand renders oddly (no `PaperCard` for "Unknown card" on the client) | Slice 0 checks a placeholder in hand and library, the command zone and the commander on a joined instance |
| The floor switch races an input being shown | Switch only on the game loop thread between inputs, then `updateObservers()` (4.2). Engine test |
| A phone holding the floor stalls the table (thinking, backgrounded) | Visible on the host as "Anna's phone is acting…". A disconnect returns the floor (N4). If needed later: "Take back" for the host |
| Attach waits for the host's open prompt | Accepted (3.4) |
| Venue Wi-Fi isolates clients | Clear error pointing to the host's hotspot |
| Android routes over cellular on a Wi-Fi with no internet | Process binding (2.4), checked on phones first in M1-1 |
| Android 12+ freezes a backgrounded host | Foreground service (Q27) |
| Hotspot address randomised, several interfaces | Every address in the QR; interfaces named; parallel tries |
| Host address changes mid-game | Retry stored addresses; else scan again (same token) |
| Host is the hotspot and dies | No takeover possible; said plainly (Q29) |
| A big join (hundreds of 10 KB steps) | Steps by hash, so a rejoin sends only new ones; LZ4 per frame. Measured in slice 0 |
| Card database fingerprint differs between desktop dev and phone builds of one release | Checked in M1-3; fall back to hashing the names the game uses |
| Takeover loses the old host's live state | N5; same limits as v1 resume |
| A later browser listener can't read the wire | Needs a separate JSON endpoint (decision 1) |
| An attacker on the LAN | Out of scope (B1). Token prevents wrong joins; class filter and size caps from net play |
| iOS | Untouched; typed code only |

## 8. Project-rule notes

`/mnt/wsl/git/CLAUDE.md` is a generic workspace file. As in v1 (plan §7), this plan follows **Forge's setup**: Java 17 (`pom.xml:27`), Maven, no database, Netty and Java serialization as Forge net play already uses them, Forge's threads (no virtual threads on Android). Manifests and poms are XML because the project needs them. `session-insights.md` stays out of commits.

## 9. Build order

Starts after v1 slice 08 (spec §7). Phase 3 turns each item into a slice file in `docs/companion/multi/slices/`.

0. **Slice 0, walking skeleton (dev build):**
   - `CompanionShareHost` with one `CompanionPeer`, `CompanionClient`, login + `hello` + `welcome` with no version check, typed code only, everyone a spectator
   - attach through `runSystem`, `CompanionViewExtension` (no acting items), `PeerGate` dropping everything
   - `GameEventSnapshotRestored` from `applyNow`
   - **Oracle:** a second dev instance with its own `userDir` types the host's code, sees the board, and the host adds a card by name, which shows **live** on the joined board, then an undo, which shows too
   - Measures latency and a full state's size. Checks placeholder, command zone and commander rendering. No data stream yet.
1. **M1-1, data and persistence:** log fields, store hook (successful writes only), `appendVerbatim`/`writeStep`/`stepHashes`, the data stream and catch-up, local history, `share.json`, resume and auto-rejoin (host and joiner), N6, `end` → past games, Stop following. Phone check first: joining over a no-internet hotspot with mobile data on, with Wi-Fi binding. AC-105, 111–113.
2. **M1-2, seats and phones:** seat claim, roster, Phones list, Remove, own seat at the bottom (N2), one active game per phone, the foreground service, unbounded reconnect with fresh attach. AC-103, 104, 107–109.
3. **M1-3, real phones:** QR and `QrScanActivity`, `LocalAddresses` with interface names, `Compat` with refusals (N1, AC-110), keeping the screen on. Phone checks for AC-102, 106, 111.
4. **M2-1, ops without prompts:** `op` + epoch, `CompanionOps`, `TableActions`/`RemoteTable`, `status`, life correction, wrong player, ready creatures, switches, undo with confirmation, attribution, read-only. AC-117–121.
5. **M2-2, the floor:** GUI switch, `PeerGate` routing, the host's tap gate, add by name with its target prompt on the phone, OK and End Turn, `op-result`, disconnect mid-prompt (N4). AC-115, 116.
6. **M2-3, every action:** capture and auto-add on seated phones, move, real life change, turn skip, engine combat with blocks on the defender's phone (N3). AC-115.
7. **M3-1, takeover:** every seated phone listens, `status?`/`fetch`, catch-up, `HOST` entry, fresh engine from the latest step, `new-host`, peers re-attach. AC-122–124, 127.
8. **M3-2, stale host and split brain:** periodic probes, divergence and truncation with the dropped list, term order. AC-125, 126.

Nice-to-haves, unplanned: LAN discovery (a UDP beacon); desktop Forge as a table display (the same client works there, as net play's does); a JSON view endpoint for browsers.

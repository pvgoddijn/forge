# Companion Mode, Multiple Phones: Plan

_ADLC phase 2 (Plan). This is the **how** for the approved [`spec.md`](spec.md). `AC-1xx` refers to its criteria, `AC-n` (n < 100) to [v1](../spec.md)._

Status: **draft** · 2026-11-07 · awaiting owner review

## 0. What v1 actually built, and why it shapes this plan

The spec's note for Plan (§10) calls the v1 "action log in JSON lines" a natural wire format. That was true of the first v1 plan. The revised v1 (plan §0, 2026-11-03) runs Forge's rules engine, and the code on this branch works like this:

- **The state is a snapshot, not a replay.** After every settled action the table saves the whole game as Forge game-state text, one file per **step**: `companion/<id>/states/<n>.txt` (`CompanionStore.java:31-35`, `CompanionTable.save`, `CompanionTable.java:1160`). The text comes from `CompanionGameState.capture` (`CompanionGameState.java:34`) and is applied with `applyNow` (`:84`).
- **The log only describes.** `log.txt` holds one `CompanionLogEntry` JSON line per thing that happened (`CompanionLogEntry.java:43`). It can't be replayed: engine prompts aren't recorded, and the engine's determinism is unproven (v1 plan §2.3).
- **Undo rewrites steps.** Undo applies step n−1, deletes the later step files (`CompanionStore.discardAfter`, `:108`) and appends an `UNDO` entry naming the steps it took back (`CompanionTable.java:692-756`). The next action writes step n again with new content. The log is append-only, the steps folder isn't.
- **Seq numbers are the store's.** `appendLog` renumbers every entry as last + 1 (`CompanionStore.java:284-303`). A resume can append an entry of its own (`settleLog`, `:142`).
- **Player order is the host's.** The state's `p0`…`p5` follow `CompanionSetup.gameOrder()` (`CompanionSetup.java:128`), which rotates the seats to start at Me. A state only applies correctly to a game built from the same setup.

The v1 plan's `CompanionAction`, `CompanionState` and `CompanionGame` no longer exist. This plan works with what does exist. **The wire format is therefore the log entries plus the step snapshots**, not a replayable action list.

## 1. Approach

```
 host phone                                                   joined phone
 ┌───────────────────────────────┐   WebSocket, JSON per frame  ┌──────────────────────────────┐
 │ CompanionTable (HOST)          │ ─ entries, steps, setup ──►  │ ShareFollower                │
 │   └ CompanionStore ─ journal ─►│   roster, end                │   └ CompanionStore (verbatim)│
 │ ShareHost (Netty server)       │ ◄─ hello(have), claim ────── │ CompanionTable (MIRROR)      │
 │   sequencer, roster, pen       │ ◄─ M2: pen, commit ───────── │   real Game, never moves on  │
 └───────────────────────────────┘                              │   by itself; applies steps   │
                                                                 └──────────────────────────────┘
```

Key decisions:

1. **Every phone runs the real engine and keeps the complete game.** A joined phone builds the same match from the host's setup (`CompanionMatch.start`, `CompanionMatch.java:25`), as a **mirror**: its seats never pass priority, and it only changes by applying the host's steps through the op queue, the way undo already applies a step (`CompanionTable.java:717-720`). It stores the host's log lines and step files byte for byte. So every phone shows Forge's own `MatchScreen` (AC-107), has the full history (AC-105), survives a restart with v1's resume path (AC-112), and can become host by flipping one mode (M3). Nothing stored changes between M1 and M3.
2. **The host is the only sequencer.** It numbers the entries, as v1 already does. A joined phone's log is always an exact prefix of the host's, and its step files equal the host's for every step it has.
3. **Sync is "since".** A joining or returning phone says what it has: game id, its last entry's `seq` and `term`, and a hash of each step file. The host replies with the missing entries, then any step files the phone lacks or has with a different hash, and then streams new ones. Steps are reconciled by hash rather than by position. That covers undo rewriting a step while a phone was away, with no special case.
4. **Terms from day one, for M3.** Every entry carries the `term` it was written in (0 before any takeover) and, from term 1 on, the `host` device that sequenced it. A term is identified by (number, host). As in Raft, one writer per term means that two logs with the same entry identity at a seq have the same prefix up to it. Catch-up, the freshness check and truncating a stale host all rely on that.
5. **M2 acting uses a pen, not remote execution.** A seated phone that wants to act asks the host for the **pen**. Requests are granted in the order they arrive (AC-118). The holder runs the action on its own engine, which is at the host's latest step, so the action's prompts (targets, "may", modes, attackers) appear on the phone that acts. At each settled point it sends the entries and the step it made as a **commit**. The host checks that the commit's base is its head, writes the commit, applies the step and broadcasts it. Section 4.2 gives the reasons.
6. **Transport: Netty WebSocket, one text frame per JSON message** (section 3).
7. **Control is not log.** Seat claims, removing a phone, read-only and the pen are host session state, broadcast as a roster. Attribution goes on each log entry (`by`, `byName`), so the history needs no roster lookup and past games stay readable.

## 2. Components

### 2.1 Changes to existing v1 code
Each change leaves an unshared game exactly as in v1 (AC-114).

| Change | Why |
|---|---|
| `CompanionLogEntry`: optional fields `term` (int, absent = 0), `host` (device id, absent = the game's creator), `by` (device id of the phone that logged it, absent = the host operator) and `byName` (its label then). These go through `toLine`/`fromLine` (`:215`, `:245`). Unknown kinds already read as `NOTICE` (`:280`), so a later `HOST` kind (M3) needs no migration. | AC-119, AC-125/126. Lines written by v1 read unchanged. |
| `CompanionStore`: a **journal hook**. A listener is told, after the write, about each entry appended, step saved, steps discarded, setup saved and game ended, and it may **stamp** an entry before it's numbered (`term`, `host`, `by`). There's no listener for an unshared game. | One hook catches everything the host writes, so the host side needs no change to `CompanionTable`. |
| `CompanionStore`: `appendVerbatim(id, entry)` keeps the entry's seq and refuses a gap. `writeStep(id, n, text)` writes host bytes with `writeWhole` (`:398`). `stepHashes(id)`, `share.json` read and write, and later (M3) `truncateLogAfter(id, seq)`. | A joined phone stores the host's log and steps unchanged. |
| `CompanionTable`: a mode, `HOST` (v1) or `MIRROR`. A mirror never appends or saves on its own and copies no engine log (`EngineLog`, `:449`). Its resume reads without `settleLog`, like `loadFinalState` (`CompanionStore.java:161`). Its op queue takes `ApplyStep`, which applies the latest stored step with `CompanionGameState.applyNow` and coalesces, so only the newest step is applied. Its `logAtPrompt` is fed from the replicated log. M3 adds `becomeHost()`. | Decision 1. |
| `CompanionPlayerController.chooseSpellAbilityToPlay` (`:40`): in a mirror, every seat waits at a read-only **follow prompt** (an `InputSyncronizedBase` with a message and no buttons, after `InputPlaybackControl` for `WatchLocalGame`, `forge/gui/control/WatchLocalGame.java:21`), and a submitted op ends it as `restartPrompt` does today (`CompanionTable.java:605-610`). It never returns null (pass), so the mirror's turns never move on by themselves. | AC-108; the prompt line doubles as the "disconnected" banner (AC-111). |
| `CompanionMatchExtension.addGameMenuItems` (`CompanionMatchExtension.java:50`): "Share game…" and "Phones…" on the host. On a joined phone, the acting items are disabled (M1) or follow the pen and read-only (M2), and "Stop following" is added. | AC-101, 108, 109, 117. |
| `CompanionSetupScreen` (`:46`, `:317`): a "Join game" entry. Resuming a game whose `share.json` says *joined* opens it as a mirror and reconnects. One whose `share.json` says *hosting* starts sharing again. | AC-102, 112. |
| `CompanionHistoryScreen`: shows `byName` on rows logged by a joined phone. | AC-119. |
| `IDeviceAdapter` (`forge/interfaces/IDeviceAdapter.java:43-60`): `canScanCodes()` and `scanCode(Consumer<String>)` with defaults, next to the capture defaults. | AC-102 QR. |

The **setup is shared verbatim**, Me included. That keeps the host's player order (`CompanionSetup.gameOrder`, `initialState`, `:128`, `:143`), so its steps apply unchanged. A joined phone's own seat lives in `share.json` and is shown as a highlight. The bottom panel stays the host's Me seat. Putting the phone's own seat at the bottom would mean rotating the state's `pN` keys, which is a later, optional change.

### 2.2 New code: `forge-gui`, package `forge.gamemodes.companion.share`
The JSON mapping is written by hand, as `CompanionStore` already does (`CompanionStore.java:41-42`: Gson's record reflection isn't dependable on Android).

| Type | Role |
|---|---|
| `CompanionDevice` (record) | This install's random id and display name, kept in `companion/device.json`. |
| `JoinCode` (record) | Encodes and decodes the typed code and the QR text (section 3.3). |
| `Compat` | The protocol version, the log format version, and the card database fingerprint, which is a hash of the edition codes and card count `StaticData` loaded. Two phones are compatible when all three are equal (B2, AC-110). |
| `ShareMessage` | The message types (3.2) and their JSON. |
| `ShareHost` | Netty WebSocket server. It keeps the **journal** fed by the store hook, one sender per session (in order, chunked catch-up), the roster (device → seat, connected, read-only, address), removing phones, and in M2 the pen queue and commits. |
| `ShareFollower` | Netty WebSocket client. It sends HELLO with what it has, applies entries and steps to the store and then submits `ApplyStep`, reconnects with backoff (0.5 s, doubling to 5 s), and sends seat claims. In M2 it holds the pen and commits. |
| `ShareState` (record) | `share.json`: role, game token, host device and addresses, port, own seat, last known roster with each phone's addresses and port (the M3 peer list), and the current term. |
| `LocalAddresses` | Usable IPv4 addresses for the code: site-local, up, not loopback, cellular (`rmnet*`) or VPN. It reuses the enumeration in `FServerManager.getAllLocalAddresses` (`forge/gamemodes/net/server/FServerManager.java:645`) and doesn't rely on `getLocalAddress` (`:631`), which routes towards 8.8.8.8 and returns "localhost" on a hotspot with no internet. |
| M3: `PeerProbe`, `Takeover` | STATUS/FETCH between phones, choosing the freshest phone, the NEW_HOST broadcast, and term comparison (section 5). |

### 2.3 Screens: `forge-gui-mobile`, package `forge.screens.companion`
| Screen | Notes |
|---|---|
| `ShareDialog` | The join code as text and as a QR image (ZXing `BitMatrix` → libGDX `Pixmap`), the addresses it holds, and a "Phones" list with seat, connected or not, Remove, and in M2 a read-only switch. |
| `JoinGameScreen` | A typed code field (dev build and phones), "Scan" where `canScanCodes()`, then a seat choice from the unclaimed seats or "Watch" (spectator), then `MatchScreen` as a mirror. |
| `MatchScreen` | Unchanged. The follow prompt shows "Following Anna's phone" or "Not connected to Anna's phone: showing the last state, reconnecting…". The own seat is highlighted through the `MatchExtension` hook. |

### 2.4 Android: `forge-gui-android`
- `QrScanActivity` reuses the Camera2 `CameraController` and its YUV frame stream (`forge/app/camera/FrameAnalyser.java`). A `FrameAnalyser` runs ZXing's `QRCodeReader` on the Y plane. It needs no OCR and no new camera code.
- Permissions: `INTERNET` and `ACCESS_NETWORK_STATE` are already declared (`src/main/AndroidManifest.xml:27-28`). Enumerating interfaces and opening sockets needs nothing more. Plain `ws://` to a LAN address isn't subject to Android's cleartext policy, which covers HTTP stacks, not raw sockets. `targetSdkVersion` is 35 (`:7`), so the local-network permission announced for later Android versions doesn't apply yet (Risks).
- Netty is already in the APK: `forge-gui` depends on `netty-all` 4.1.115 (`forge-gui/pom.xml:50-54`), Android doesn't exclude it (`forge-gui-android/pom.xml:140`), and mobile online play uses it (`forge-gui-mobile/src/forge/screens/online/OnlineMenu.java`). ProGuard has only `-dontwarn io.netty.**` (`proguard.cfg:24`) and no keep rule. The WebSocket codec is new on Android, so slice M1-3 checks it on a phone and adds a narrow keep rule if shrinking breaks it.

### 2.5 New dependency
**ZXing `com.google.zxing:core`** (Apache-2.0, which is GPLv3-compatible; pure Java; on Maven Central) goes in `forge-gui-mobile`. It encodes the QR code and, on Android, decodes it. The repo has no QR library today: a grep of every pom and Java file for zxing or qrcode finds nothing. iOS takes `forge-gui-mobile` (`forge-gui-ios/pom.xml:93`), and pure Java is safe for `ios-compat-gate.yml`. Sharing isn't offered on iOS, because `canScanCodes` defaults to false and no iOS work is planned.

## 3. Transport and protocol

### 3.1 WebSocket over raw TCP
| | Netty raw TCP, one JSON per line | **Netty WebSocket, one JSON per text frame** (chosen) |
|---|---|---|
| Framing | Our own line splitter. Step text has newlines, so JSON escapes them | Message frames built in |
| Liveness | Our own ping | Ping/pong frames + `IdleStateHandler` |
| Size of a join (a long game is a few hundred steps of 5–15 KB, very repetitive) | Our own gzip | `permessage-deflate` from Netty's `WebSocketServerCompressionHandler`, roughly 10× |
| Browser listener later (spec §6.1) | Impossible without a second server | Same port. An HTTP GET can later serve a page |
| Dependency | `netty-all`, present | `netty-all`, present. The codecs are in the same jar |
| Cost | Least code | A handshake handler on each side, about 50 lines |

The WebSocket path is at `/companion`, with a 16 MB frame limit. Each step goes in its own frame. Netty runs one `NioEventLoopGroup(1)` for Companion. Messages that touch the store go through one single-thread executor per side, so writes stay in host order. Jetty is a dependency too, but it's excluded on iOS (`forge-gui-ios/pom.xml:86`) and adds nothing that Netty lacks.

**Port:** 36744 by default, so it doesn't clash with Forge net play's 36743 (`ForgeNetPreferences.java:28`), and the next free port up to 36753 if that one is taken. The code carries the port, so nobody has to configure it.

### 3.2 Messages
Each message is a JSON object with a type `t`. **J** marks a joined phone, **H** the host, **P** any peer (M3).

| Dir | Type | Content |
|---|---|---|
| J→H | `hello` | `proto`, `logFormat`, `cardDb`, `app` (for messages only), `device`, `name`, `token`, `game` (null on first join), `have` = {`seq`, `term`, `host` of its last entry; `steps` = {n: sha256}} |
| H→J | `refuse` | the reason, plus the host's three versions. The host also shows its operator a notice (AC-110) |
| H→J | `welcome` | game id, host device, term, `setup`, `roster`, and a `diverged` count (M3) |
| H→J | `entries` | a chunk of log lines, verbatim |
| H→J | `step` | `n`, `sha256`, the text |
| H→J | `setup` / `roster` / `end` / `removed` | as named |
| J→H | `claim` | a seat name, or null to watch |
| J→H (M2) | `pen?`, `commit`, `pen-done` | section 4.2 |
| H→J (M2) | `pen`, `pen-queue`, `commit-refused` | as named |
| P↔P (M3) | `status?` → `status`; `fetch` → `entries` + `step`s; `new-host` | section 5 |

**Catch-up rule.** After `welcome`, the host sends the entries after the phone's `have.seq`, then a `step` for each host step whose hash the phone lacks or has differently. A step file only changes after an undo to below it, and that undo is an `UNDO` entry. A phone applying entries in order runs `discardAfter(entry.step)` for each `UNDO` entry, as the host did, so after the entries every step it still has matches the host's. The hash check is a safety net that self-heals, at the cost of one index per join (a few hundred hashes).

**Live stream.** The host's journal sends each write in the order it was written: an entry when appended (entries come before their step's save, `CompanionLogEntry.java:31-32`), a step when saved, setup on change, `end` last. A joined phone writes each message to its store, fsync included, then submits one coalescing `ApplyStep`. Rows show in the history as soon as their entries arrive. The board changes at the host's next settled save.

### 3.3 Join code
- **Typed code:** Crockford base32 of the IPv4 address (4 bytes), port (2) and a random share token (4): 16 characters, shown as `XXXX-XXXX-XXXX-XXXX`. It doesn't carry the game id: the host serves one game and returns the id in `welcome`. This differs from the steer, because a UUID makes a typed code too long. A wrong code fails to connect, or the token doesn't match and the phone says so.
- **QR:** `forge-companion:<typed code>?g=<game id>&a=<other addresses>`. Every usable address goes in (Wi-Fi and hotspot interfaces both). A scanning phone tries them in parallel and keeps the first that answers.
- **Hotspot:** when the host is the hotspot, its address is on `ap0`/`swlan0`/`wlan1` and Android may randomise it per session. `LocalAddresses` lists it like any other, and the share dialog names the interface ("Hotspot", "Wi-Fi") so the operator picks the right code. The typed code uses the first address. The dialog can switch it.
- The token stays the same for the game (B3: a removed phone may rejoin). It's stored in `share.json`, so phones can rejoin after a restart without a new code.

### 3.4 Threads and Android lifecycle
- Netty callbacks never touch the game or the UI. Store writes run on the share executor, game changes go through `CompanionTable.submit` (the game loop thread, as for every op), and UI changes go through `FThreads.invokeInEdtLater` (`forge/gui/FThreads.java:26`), which libGDX runs on the render thread.
- While a game is shared or joined, the screen is kept on through `preventSystemSleep(true)` (`IDeviceAdapter.java:27`, Android `Main.java:870`). That's the cheapest guard against Android suspending a backgrounded socket.
- If the app is paused anyway (screen locked, app switched), the connection may drop. A joined phone reconnects on resume through the normal backoff, and sync makes up the gap. A paused host shows as unreachable to joined phones until it resumes (AC-111). A foreground service (`FOREGROUND_SERVICE` is already declared, `AndroidManifest.xml:25`) is the fallback if phone checks show the drops are too frequent. It isn't planned up front, because Android 14+ demands a service type and a notification.

## 4. Behaviour by milestone

### 4.1 M1: follow along
- **Sharing (AC-101, 114):** "Share game…" starts `ShareHost`, writes `share.json` (role host, token, port), installs the store hook and shows `ShareDialog`. With no phone connected, the hook only fills the journal, so logging is exactly v1. The host keeps its journal in memory and rebuilds it from the store on demand, because the store is the source.
- **Joining (AC-102, 103, 104, 105):** `JoinGameScreen` → `hello` → `welcome` → the phone picks a seat or Watch (`claim`). The host refuses a seat that's taken, gives Me's seat to the host itself, and lets any number of spectators watch. Then catch-up, then a mirror `MatchScreen`. The game folder uses the **host's game id**, so the copy is the same game on every phone.
- **One active game per phone:** `CompanionStore.loadActive` resumes the most recent unfinished game (`:352`). A phone with its own active game is asked to end it before joining.
- **Viewing (AC-106, 107, 108):** a mirror shows Forge's board, zone tabs and history. The player filter (AC-28) works because the history reads the same log. The own seat is highlighted. Nothing on the mirror can act.
- **Phones list (AC-109):** the roster with seat, connected or not, and Remove. Remove sends `removed`, closes the socket and frees the seat (B5). The removed phone stops reconnecting and keeps its copy as an ended game. It may rejoin with the code (B3).
- **Connection loss (AC-111):** a missed pong (5 s ping, 15 s timeout) or a closed socket → the follow prompt says "not connected", and the phone reconnects with `hello(have)`.
- **Persistence (AC-112):** every message is in the store before it's applied, so a killed phone resumes from its own files and reconnects with what it has. A restarted host resumes sharing on the same port and token, so its phones come back on their own.
- **End (AC-113):** `end` → `store.end(id)` → the game moves to past games, read-only (`CompanionPastGamesScreen.java:47`). A phone that missed the end stays "not connected" until its operator taps "Stop following", which ends it locally. A host that ends its game keeps serving `end` to returning phones until the app leaves Companion.

### 4.2 M2: act together
- **Why a pen and not remote execution.** If the host ran a joined phone's action, every engine prompt the action raised (a Lightning Bolt's target, an ETB "may", attackers) would open on the host's screen. Alternatively, Forge's net play would have to forward `PlayerControllerHuman`'s inputs, which is the protocol spec §10 rejects. The host's game loop would also block on Wi-Fi. `Move` ops name a card by id (`CompanionTable.java:175`), and ids differ between phones' games. With the pen, the acting phone's engine is already at the host's step, so the whole v1 flow runs there unchanged, capture and OCR included (AC-115, M-Q16).
- **Flow:** the operator confirms an action → `pen?` → the host queues requests in arrival order and grants the head (`pen`, with its head seq) → the phone waits until it has that seq, then runs the op. The table runs in a **local HOST mode whose store writes are buffered**, not written. At each settled save it sends `commit` = {base (term, seq, step), entries, step text, or `undoTo`} → the host checks base = head, stamps `by`/`byName`/`term`, appends, writes the step, applies it to its own game with `ApplyStep`, and broadcasts. The holder gets its own entries back through the stream, which is the only path into its store. `pen-done` at the next idle prompt, or the host takes the pen back when the holder disconnects.
- **The host's own operator** asks for the pen too, which is instant when it's free. While another phone holds it, the host's seats wait at the follow prompt ("Anna is logging…"), and the operator's action waits its turn (AC-118). Nothing is merged or deduplicated.
- **Refusal:** a commit whose base isn't the head can only follow a lost pen (a disconnect). The holder puts its mirror back to the latest step and says "not logged: you lost the connection", so the operator can redo it. AC-117: with no connection, nothing can ask for the pen, and there's no offline queue.
- **Default target (AC-116, B6):** unchanged. The add dialog's default is the active seat on every phone.
- **Undo (AC-120):** it takes the pen like any action. Before the undo, if the latest entry it would take back that changed the state has a `by` other than this phone, the phone shows "Logged by Anna's phone: Cast Lightning Bolt. Undo it?" The undo itself is v1's `Undo` op on the holder, committed as `undoTo`.
- **Read-only (AC-121):** a roster flag. The host refuses `pen?` from a read-only phone or a spectator, and those phones grey out their actions.
- **Attribution (AC-119):** `by`/`byName` on every entry written after sharing starts. The history shows "· Anna's phone" for entries not logged by the host.
- **Engine fidelity cost:** a commit is applied to the host as a snapshot, so effects that a snapshot doesn't save ("until end of turn" pumps, delayed triggers, v1 plan §2.3) are lost at the hand-over between phones. That's the same limit undo already has. Each phone's own action keeps them while it holds the pen.

### 4.3 M3: takeover
Every phone in a shared game also listens, on the port it reported in its roster entry. From M1 on, the roster carries each phone's addresses and port, and `share.json` keeps them.

1. **Start (AC-122):** "Take over as host" on a seated phone, after a confirmation. It doesn't need the host to be unreachable.
2. **Freshness (AC-123):** `status?` to every roster peer, waiting at most 3 s. Each reply holds the game id, its last entry's (term, host, seq), and whether it's a host. Fresher means a higher term identity, or the same term with a higher seq.
3. **Catch-up:** `fetch` from the freshest peer since this phone's last *common* seq. The common seq is the highest seq whose entry has the same term identity on both, found from the peer's list of term starts. The phone cuts its log back to that seq if it must, appends the tail verbatim, and reconciles steps by hash. It then shows "Caught up: 4 actions" with their text.
4. **Become host:** append a `HOST` entry with term = highest seen + 1, host = this phone and the wall-clock time. `CompanionTable` switches from mirror to host in place, so there's no match restart. `ShareHost` starts on its port, and `new-host` goes to every peer (AC-124). A peer that sees a higher term reconnects to the new host with `hello(have)`. The old host, if reachable, does the same and follows.
5. **No peers (AC-127):** steps 2–3 find nobody, and the phone becomes a host with no followers, exactly AC-114.
6. **Stale host returns (AC-125):** a phone that thinks it's host, or follows a host that's gone, probes the roster with `status?` every 10 s. When it finds a higher term, it follows that host. The `welcome` reply says where its log diverged. It keeps its dropped entries in memory, cuts its log back to the common seq (`truncateLogAfter`, a whole rewrite through `writeWhole`), reconciles steps, and shows its operator the dropped entries that changed the game, to log again. The new host also retries `new-host` to unreachable roster peers every 10 s.
7. **Two takeovers at once (AC-126):** a takeover started after seeing another's `HOST` entry gets a higher term and wins. Two takeovers with the same term number are resolved when they meet: the `HOST` entry with the later time wins, and on equal times the larger device id. The loser applies step 6. Clocks matter only for this tie.
8. **Lost in a takeover:** an action a pen holder hadn't committed, and the old host's pen queue. The pen starts free under the new host.

## 5. Files touched outside new code

- **`forge-gui`:** `CompanionLogEntry`, `CompanionStore`, `CompanionTable`, `CompanionPlayerController` (2.1), `IDeviceAdapter` (scan defaults).
- **`forge-gui-mobile`:** `CompanionSetupScreen`, `CompanionMatchExtension`, `CompanionHistoryScreen`, plus `pom.xml` (ZXing).
- **`forge-gui-android`:** `AndroidManifest.xml` (the `QrScanActivity` entry, no new permissions), `Main.java` (`scanCode`), possibly `proguard.cfg` (a Netty WebSocket keep rule, only if the phone check needs it).
- **Not touched:** `forge-game`, `forge/gamemodes/net/**`, desktop Swing. No existing mode changes. Every new path starts from a Companion menu item.

## 6. Traceability and verification

| AC | Delivered by | Verified by |
|---|---|---|
| 101 | `ShareDialog`, `JoinCode`, ZXing | Dev build: the code shows. Phone: the QR scans. |
| 102 | `JoinGameScreen`, `QrScanActivity`, `ShareFollower` | Two phones on a router with no internet, and on the host's hotspot. |
| 103, 104 | Roster, `claim` | Dev build with 3 instances. |
| 105 | Catch-up rule (3.2) | **Test:** a follower joins after undos and gets an identical log and steps. |
| 106 | Journal → `step` → `ApplyStep` | Phone check with a stopwatch log line (B4: up to 8 phones). |
| 107 | Mirror `MatchScreen`, highlight, history | Dev build. |
| 108 | Follow prompt, disabled menu | **Engine test:** a mirror never passes priority, and its turn stays put until a step arrives. |
| 109 | Phones list, `removed` | Dev build. |
| 110 | `Compat`, `refuse` | **Test:** a mismatched `logFormat` is refused, and both sides get a reason. |
| 111 | Ping timeout, backoff, follow prompt banner | **Test:** kill the socket, append on the host, reconnect, catch up. Phone: Wi-Fi off and on. |
| 112 | Verbatim store, `share.json`, resume | **Test:** restart a follower from its folder and catch up. Phone: kill the app. |
| 113 | `end`, Stop following | Dev build. |
| 114, 127 | The hook only journals | v1 tests unchanged (`forge-gui-desktop/.../TableEditorTest.java`, `CompanionStoreShould`). |
| 115, 116 | Pen, buffered local host mode, commit | Dev build: a target prompt on the acting instance. Phone: capture on a seated phone. |
| 117 | No pen without a connection | Dev build. |
| 118 | FIFO pen queue, base check | **Test:** two `pen?` requests are granted in arrival order, and a stale base is refused. |
| 119 | `by`/`byName` | **Test:** a round trip of the new fields, and a v1 line reads as before. |
| 120 | Undo commit with confirmation | Dev build. |
| 121 | Roster flag, refused `pen?` | Dev build. |
| 122–124 | `PeerProbe`, `Takeover`, `new-host` | **Test:** three in-process nodes, take over from the freshest and from a stale one. |
| 125 | Divergence → truncate + list | **Test:** an isolated old host appends, then returns, and gets the exact dropped list and a log equal to the new host's. |
| 126 | Term identity + tie-break | **Test:** two same-term takeovers converge on one host. |

**Tests in CI.** `CONTRIBUTING.md` asks agents not to add unnecessary tests, so CI gets only the tests where a regression would be silent:
- sync and catch-up over **real localhost sockets**, with several in-process nodes driving `CompanionStore` directly (no engine, `forge-gui`)
- the log field round trip
- the pen order and base check
- takeover and divergence (M3)
- one engine test, in `forge-gui-desktop` with `TableEditorTest`'s harness: a mirror applies steps and never moves on by itself

There are no UI tests. Phone checks (QR scan, hotspot, screen-off and resume, the 1 s target with several phones) are done by you.

**CI gates:** `test-build.yaml`, `test-android-build.yml`, `ios-compat-gate.yml`, as in v1.

**Two instances on one PC:** the dev build stores games in `USER_DIR/companion` (`CompanionSetupScreen.java:46`), and the host and a joined phone use the same game id. The second instance therefore runs with its own `userDir` in `forge.profile.properties` (`ForgeProfileProperties.java:47`). Slice 0 documents the command.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Venue Wi-Fi isolates clients (common in shops and hotels) | Clear error ("can't reach the host: this Wi-Fi may block phones from talking to each other; use the host's hotspot"). The hotspot is the fallback the spec names. |
| Android suspends or kills a backgrounded app's socket (Doze, OEM task killers) | The screen stays on while sharing; resume and catch-up are cheap; a foreground service if phone checks demand it (3.4). |
| The hotspot address is randomised, or the host has several interfaces | Every address goes in the QR; the dialog names interfaces; scanning tries all of them. |
| The host phone is the hotspot and dies, so the network goes with it | Takeover can't help, because there's no network left. Told plainly in the takeover message. Venue Wi-Fi survives it. |
| The host's address changes mid-game (DHCP, hotspot restart) | A joined phone retries its stored addresses. Otherwise it scans again (same token, same game, catch-up). LAN discovery (a spec nice-to-have) would fix it later. |
| ProGuard strips Netty's WebSocket classes | Phone check in M1-3. Add a narrow `-keep` only if needed. |
| A big join is slow (hundreds of 10 KB steps) | `permessage-deflate`, and steps reconciled by hash so a rejoin sends only new steps. Measured in slice 0. |
| A mirror's applied state differs from the host's view (temporary effects aren't in a step) | Same limit as v1 resume (v1 plan §2.3). Joined phones only display. In M2 the cost is at hand-over between phones (4.2). |
| Followers see the board only at settled points, so a spell waiting on the stack isn't shown | Its log entry shows straight away. A spec note under AC-106 (ambiguities). |
| A mirror's own engine does something by itself after a step is applied (state-based actions, a trigger) | Steps are saved settled, so state-based actions have already run. `applyNow` fires no triggers. The engine test asserts that the mirror's state equals the step after apply. |
| The card database fingerprint differs between builds of the same release (desktop dev vs phone) | Check in slice 0. If it does, fall back to hashing the card names the game uses. |
| Android's announced local-network permission | Not enforced at `targetSdkVersion` 35. Revisit when the SDK is raised. |
| NAT, internet, clock skew | Not relevant: LAN only, and the host sequences. Clocks matter only for AC-126's tie-break. |
| iOS | Untouched. ZXing and the new code are pure Java and pass the compat gate. Sharing isn't offered there. |
| An attacker on the LAN | Out of scope (B1). The token only prevents wrong joins. |

## 8. Project-rule notes

`/mnt/wsl/git/CLAUDE.md` is a generic workspace file. As in v1 (plan §7), this plan follows **Forge's setup**: Java 17 release level (`pom.xml:27`), Maven, no database, Netty and Gson as Forge already uses them, and threads as Forge uses them (no virtual threads on Android's Java 17 level). Android manifests and poms are XML because the project requires it. `session-insights.md` stays out of commits.

## 9. Build order

The work starts after v1 slice 08 has landed (spec §7). Phase 3 turns each item into a slice file in `docs/companion/multi/slices/`.

0. **Slice 0, walking skeleton (dev build):**
   - log fields (`term`, `host`, `by`, `byName`), `CompanionDevice`, the store hook, `appendVerbatim`/`writeStep`/`stepHashes`
   - a minimal `ShareHost`/`ShareFollower` over WebSocket with `hello`/`welcome`/`entries`/`step` and `Compat` in the handshake
   - mirror mode with the follow prompt, "Share game…" showing the typed code, and "Join game" with a text field
   - **Oracle:** two dev instances. The host adds a card by name, and the joined instance shows it on its board and in its history.
   - Measures latency and join size. Tests: catch-up over localhost, the field round trip, mirror never moves on.
1. **M1-1, connection loss and persistence:** ping/backoff, the "not connected" banner, `share.json`, resume and auto-rejoin, the host resumes sharing, catch-up after undo while away, `end` → past games, Stop following. AC-111–113.
2. **M1-2, seats and phones:** seat choice and claim, the roster, the Phones list, Remove, own-seat highlight, history filter on the mirror, one active game per phone. AC-103, 104, 107, 109.
3. **M1-3, on real phones:** QR (ZXing) and `QrScanActivity`, `LocalAddresses` with hotspot naming, refusal messages (AC-110), the ProGuard check, keeping the screen on. Phone checks for AC-102, 106 and 111 on Wi-Fi and hotspot.
4. **M2-1, the pen:** `pen?`/`pen`/`commit`, buffered local host mode, add by name from a seated phone with its prompts, attribution in the history. AC-115 (add), 116, 117, 118, 119.
5. **M2-2, every action:** capture and auto mode on seated phones, the remaining v1 actions (life, End Turn, wrong player, move), undo with confirmation, the read-only switch. AC-115, 120, 121.
6. **M3-1, takeover:** every phone listens, `status?`/`fetch`, catch-up, the `HOST` entry, mirror → host, `new-host`. AC-122–124, 127.
7. **M3-2, stale host and split brain:** periodic probes, divergence and truncation with the dropped list, the same-term tie-break. AC-125, 126.

Nice-to-haves, unplanned: LAN discovery (a UDP beacon), desktop Forge as a table display (the mirror already works on the dev build), and putting the phone's own seat at the bottom.

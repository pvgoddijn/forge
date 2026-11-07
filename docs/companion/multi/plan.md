# Companion Mode, Multiple Phones: Plan

_ADLC phase 2 (Plan). This is the **how** for the approved [`spec.md`](spec.md). `AC-1xx` refers to its criteria, `AC-n` (n < 100) to [v1](../spec.md)._

Status: **draft, revised after review** · 2026-11-07 · awaiting owner review

## Open product decisions

The owner is deciding these now. The plan is written for the **default** shown. A different answer changes the sections named.

| | Decision | Plan's default | Affects |
|---|---|---|---|
| a | Accept that every pen hand-over loses what a snapshot doesn't hold: "until end of turn" effects, delayed triggers, life gained or lost this turn, commander damage (4.2) | Accept, and keep hand-overs rare: prompt-free actions run on the host as remote ops, with no hand-over | 4.2, Risks |
| b | In an engine combat run under the pen, every seat's prompt, the blockers' choice included, opens on the attacker's phone, which holds the pen | Accept: the defending players say their blocks and the attacker's operator enters them | 4.2 |
| c | AC-106 narrowed: the board on followers changes only at settled points (a main phase with an empty stack); the history row shows at once | Accept, and note it under AC-106 in the spec | 3.2, 6 |
| d | AC-126 "later takeover" means the **higher term**, not the later wall-clock time. After a network split, the side whose takeover reached the higher term number wins even if the other side's was later on the clock | Accept | 4.3 |
| e | A joined phone's bottom panel shows the host's Me seat; its own seat is only highlighted | Accept for M1–M3; own seat at the bottom stays a nice-to-have | 2.1 |
| f | The host shows a foreground-service notification ("Sharing a Companion game") while sharing | Accept: it's needed to keep the host serving when backgrounded on Android 12+ | 3.4 |
| g | A pen holder that stays away from a hand-over point: an automatic timeout, or the host's operator takes the pen back | No timeout. The host's operator can take the pen back, after a confirmation that the holder's uncommitted actions are lost | 4.2 |
| h | Misc: an offline phone that missed the game's end shows "Stop following"; joining needs the phone's own active game ended first; a removed phone keeps its copy as an ended game; a restarted host resumes sharing; a host that is the hotspot and dies makes takeover impossible | Accept all as written in 4.1 and Risks | 4.1, 4.3, Risks |

## 0. What v1 actually built, and why it shapes this plan

The spec's note for Plan (§10) calls the v1 "action log in JSON lines" a natural wire format. That was true of the first v1 plan. The revised v1 (plan §0, 2026-11-03) runs Forge's rules engine, and the code on this branch works like this:

- **The state is a snapshot, not a replay.** After every settled action the table saves the whole game as Forge game-state text, one file per **step**: `companion/<id>/states/<n>.txt` (`CompanionStore.java:31-35`, `CompanionTable.save`, `CompanionTable.java:1160`). The text comes from `CompanionGameState.capture` (`CompanionGameState.java:34`) and is applied with `applyNow` (`:84`).
- **Steps are saved only at settled points.** `atPrompt` saves only in a main phase with an empty stack (`CompanionTable.java:987-993`), and not at all while ops are parked (a sorcery waiting, a turn skip), because the controller passes before reaching it (`CompanionPlayerController.java:43-55`). Between two saves the live game holds things no step has: the stack, an engine combat, a seat's End Turn yield, parked ops and the turn-skip target (`CompanionTable.java:847-893`), and the "combat this turn" switch (`combatTurn`, `:223`).
- **The log only describes.** `log.txt` holds one `CompanionLogEntry` JSON line per thing that happened (`CompanionLogEntry.java:43`). It can't be replayed: engine prompts aren't recorded, and the engine's determinism is unproven (v1 plan §2.3).
- **Undo rewrites steps.** Undo applies step n−1, deletes the later step files (`CompanionStore.discardAfter`, `:108`) and appends an `UNDO` entry naming the steps it took back (`CompanionTable.java:692-751`). The next action writes step n again with new content. The log is append-only, the steps folder isn't.
- **Seq numbers are the store's.** `appendLog` renumbers every entry as last + 1, from an in-memory `lastSeq` cache (`CompanionStore.java:60`, `:284-303`). It doesn't fsync (`:296-298`; only `writeWhole` does, `:403`), and it swallows a failed write, still returning the numbered entry (`:299-301`). A resume can append an entry of its own (`settleLog`, `:142`).
- **Player order is the host's.** The state's `p0`…`p5` follow `CompanionSetup.gameOrder()` (`CompanionSetup.java:128`), which rotates the seats to start at Me. A state only applies correctly to a game built from the same setup.
- **Card ids aren't kept by a load.** A state names cards by `Id:` only to link attachments within the load (`GameState.java:1390-1392`); the loaded cards get new ids. Two phones' games therefore give the same card different ids.
- **A snapshot loses some state.** `applyNow` clears the life-this-turn tallies (`CompanionGameState.java:114-119`) and resets commander stats, commander damage included (`:144-146`). "Until end of turn" effects and delayed triggers aren't in a state at all (v1 plan §2.3).

The v1 plan's `CompanionAction`, `CompanionState` and `CompanionGame` no longer exist. This plan works with what does exist. **The wire format is therefore the log entries plus the step snapshots**, not a replayable action list.

## 1. Approach

```
 host phone                                                   joined phone
 ┌───────────────────────────────┐   WebSocket, JSON per frame  ┌──────────────────────────────┐
 │ CompanionTable (HOST)          │ ─ entries, steps, setup ──►  │ ShareFollower                │
 │   └ CompanionStore ─ journal ─►│   roster, end                │   └ CompanionStore (verbatim)│
 │ ShareHost (Netty server)       │ ◄─ hello(have), claim ────── │ CompanionTable (MIRROR)      │
 │   sequencer, roster, queue     │ ◄─ M2: op, pen, commit ───── │   real Game, never moves on  │
 └───────────────────────────────┘                              │   by itself; applies steps   │
                                                                 └──────────────────────────────┘
```

Key decisions:

1. **Every phone runs the real engine and keeps the complete game.** A joined phone builds the same match from the host's setup (`CompanionMatch.start`, `CompanionMatch.java:25`), as a **mirror**: its seats never pass priority, and it only changes by applying the host's steps through the op queue, the way undo already applies a step (`CompanionTable.java:721`). It stores the host's log lines and step files byte for byte. So every phone shows Forge's own `MatchScreen` (AC-107), has the full history (AC-105), survives a restart with v1's resume path (AC-112), and can become host by flipping one mode (M3). Nothing stored changes between M1 and M3.
2. **The host is the only sequencer.** It numbers the entries, as v1 already does. A joined phone's log is always an exact prefix of the host's, and its step files equal the host's for every step it has.
3. **Sync is "since".** A joining or returning phone says what it has: game id, and its last entry's `seq` and term. The host's `welcome` carries its own latest seq, its latest step number and an index `{n: hash}` of its step files. The phone first cuts back whatever the host lacks (log entries past the host's seq, step files past the host's latest step, `discardAfter(hostLatest)`), then asks for the steps it lacks or has with a different hash. The host sends the missing entries and those steps, then streams new ones. That covers undo rewriting a step while a phone was away, and a phone that wrote ahead of the host (a lost pen, a stale host in M3), with no special case.
4. **Terms from day one, for M3.** Every entry carries the `term` it was written in (0 before any takeover) and, from term 1 on, the `host` device that sequenced it. Terms are **ordered by (term number, the time on the term's `HOST` entry, host device id)**, everywhere: catch-up, the freshness check, the stale-host check and the tie-break. Term 0 has time 0 and the game's creator as host. As in Raft, one writer per term means that two logs with the same term at a seq have the same prefix up to it.
5. **M2 acting: remote ops first, the pen only for actions that may prompt.** A prompt-free action (a life correction, "wrong player", readying creatures, undo, the table switches) is sent to the host as a **remote op** and runs on the host's game, so nothing is handed over. An action that may open an engine prompt (any add, a move, End Turn, a real life gain or loss) needs the **pen**: the holder runs it on its own engine, which is at the host's latest step, so the prompts (targets, "may", modes, attackers) appear on the phone that acts, and it sends what it wrote as a **commit**. Remote ops and pen requests share one queue, served in arrival order (AC-118). The pen changes hands only at a **hand-over point**, where the live game equals its latest step. Section 4.2 gives the rules and the reasons.
6. **Transport: Netty WebSocket, one text frame per JSON message** (section 3).
7. **Control is not log.** Seat claims, removing a phone, read-only, the queue and the pen are host session state, broadcast as a roster. Attribution goes on each log entry (`by`, `byName`), so the history needs no roster lookup and past games stay readable.

## 2. Components

### 2.1 Changes to existing v1 code
Each change leaves an unshared game exactly as in v1 (AC-114).

| Change | Why |
|---|---|
| `CompanionLogEntry`: optional fields `term` (int, absent = 0), `host` (device id, absent = the game's creator), `by` (device id of the phone that logged it, absent = the host operator), `byName` (its label then), and on a `HOST` entry (M3) `time`. These go through `toLine`/`fromLine` (`:215`, `:245`). Unknown kinds already read as `NOTICE` (`:280`), so the `HOST` kind needs no migration. | AC-119, AC-125/126. Lines written by v1 read unchanged. |
| `CompanionStore`: a **journal hook**. A listener is told about each entry appended, step saved, steps discarded, setup saved and game ended, **only after the write succeeded**: `appendLog` swallows a failed write (`:299-301`), so it gets a success flag, and the hook isn't told about an entry that isn't on disk. The listener may **stamp** an entry before it's numbered (`term`, `host`, `by`, `byName`). There's no listener for an unshared game. | One hook catches everything a host writes. In M1 the host side needs no change to `CompanionTable`; M2 does (below). |
| `CompanionStore`: `appendVerbatim(id, entries)` keeps each entry's seq, refuses a gap, skips an entry it already has with the same bytes, **updates the `lastSeq` cache** (`:60`) so a later `appendLog` (after M3's `becomeHost`, or by a pen holder) numbers after it, and fsyncs once per chunk. `writeStep(id, n, text)` writes host bytes with `writeWhole` (`:398`). `stepHashes(id)`, `share.json` read and write, and `truncateLogAfter(id, seq)` (a whole rewrite through `writeWhole`, resetting `lastSeq`), built in M2 for a refused commit and reused in M3. | A joined phone stores the host's log and steps unchanged. |
| `CompanionTable`: a mode, `HOST` (v1) or `MIRROR`. A mirror never appends or saves on its own and copies no engine log (`EngineLog`, `:449`). Its resume reads without `settleLog`, like `loadFinalState` (`CompanionStore.java:161`). Its op queue takes `ApplyStep`, which applies the latest stored step with `CompanionGameState.applyNow` and coalesces, so only the newest step is applied. Its `logAtPrompt` is fed from the replicated log. | Decision 1. |
| `CompanionTable`, `ApplyStep` details. **Ending the follow prompt:** `submit()` and the input-queue observer only end an `InputPassPriority` (`endPriorityPrompt`, `:602-607`; observer `:436-440`), so neither ends the follow prompt. The table gets an end path for it: `submit` and the observer also end a `FollowPrompt` when that's the seat's input. **After apply:** `applyNow` re-enables triggers (`GameState.java:659`) and runs `checkStateEffects` (`:687`), so a state trigger can land on the stack, or ask a target, on a mirror. `ApplyStep` runs with `tableActing` set, the mirror's controller declines any choice asked during it, and the stack is emptied again afterwards, so a mirror shows what the step holds. **On a host (M2)**, applying a commit must leave the table as `Undo` does (`:721-737`): refresh `cachedLog` (`:369`) with the committed entries, `setLatestStep(n)` (`:388`), `lastSaved = capture(game)`, `ranSinceSave = false`, clear every seat's yield, and set `combatTurn` from the commit. M3 adds `becomeHost()`. | Decision 1, AC-108, 115. |
| `CompanionPlayerController.chooseSpellAbilityToPlay` (`:40`): in a mirror, every seat waits at a read-only **follow prompt** (an `InputSyncronizedBase` with a message and no buttons, after `InputPlaybackControl` for `WatchLocalGame`, `forge/gui/control/WatchLocalGame.java:21`), ended by the table's own path above. It never returns null (pass), so the mirror's turns never move on by themselves. `undoLastAction` (Ctrl+Z, `:99-102`) calls `table.undo()` directly, so it's gated too: in a mirror it goes through the same route as the menu's Undo (refused in M1, a remote op in M2). | AC-108; the prompt line doubles as the "disconnected" banner (AC-111). |
| `CompanionMatchExtension`: **every acting entry point is gated** on a mirror, a spectator and a read-only phone, not only the menu: `addGameMenuItems` (`:50`; add, Undo, Wrong player, the combat and commander switches, Ready creatures, End game), `tapLife` (`:223`, returns false so the tap does nothing), `addCardMenuItems` (`:232`, no "Move…"), and `runPromptAction` (`:251`, the "Add card" button). In M2 a seated phone's entry points route to a remote op or the pen (4.2). The host gets "Share game…" and "Phones…"; a joined phone gets "Stop following". | AC-101, 108, 109, 117, 121. |
| `CompanionSetupScreen` (`:46`, `:317`): a "Join game" entry. Resuming a game whose `share.json` says *joined* opens it as a mirror and reconnects. One whose `share.json` says *hosting* starts sharing again. | AC-102, 112. |
| `CompanionHistoryScreen`: shows `byName` on rows logged by a joined phone. | AC-119. |
| `IDeviceAdapter` (`forge/interfaces/IDeviceAdapter.java:43-60`): with defaults, next to the capture defaults: `canScanCodes()` and `scanCode(Consumer<String>)`; `bindToLocalNetwork(boolean)` (no-op by default, 3.4); `keepServing(boolean)` for the host's foreground service (no-op by default, 3.4). | AC-102 QR, 3.4. |

The **setup is shared verbatim**, Me included. That keeps the host's player order (`CompanionSetup.gameOrder`, `initialState`, `:128`, `:143`), so its steps apply unchanged. A joined phone's own seat lives in `share.json` and is shown as a highlight. The bottom panel stays the host's Me seat (open decision e). Putting the phone's own seat at the bottom would mean rotating the state's `pN` keys, which is a later, optional change.

### 2.2 New code: `forge-gui`, package `forge.gamemodes.companion.share`
The JSON mapping is written by hand, as `CompanionStore` already does (`CompanionStore.java:41-42`: Gson's record reflection isn't dependable on Android).

| Type | Role |
|---|---|
| `CompanionDevice` (record) | This install's random id and display name, kept in `companion/device.json`. |
| `JoinCode` (record) | Encodes and decodes the typed code and the QR text (section 3.3). |
| `Compat` | The protocol version, the log format version, and the card database fingerprint, which is a hash of the edition codes and card count `StaticData` loaded. Two phones are compatible when all three are equal (B2, AC-110). |
| `ShareMessage` | The message types (3.2) and their JSON. |
| `ShareHost` | Netty WebSocket server. It keeps the **journal** fed by the store hook, one sender per session (in order, chunked catch-up), the roster (device → seat, connected, read-only, address), removing phones, and in M2 the queue of remote ops and pen requests, the pen, and commits. |
| `ShareFollower` | Netty WebSocket client. It sends HELLO with what it has, cuts back what the host lacks, applies entries and steps to the store and then submits `ApplyStep`, reconnects with backoff (0.5 s, doubling to 5 s), and sends seat claims. In M2 it sends remote ops, holds the pen and commits. |
| `ShareState` (record) | `share.json`: role, game token, host device and addresses, port, own seat, last known roster with each phone's addresses and port (the M3 peer list), and the current term (number, time, host). |
| `LocalAddresses` | Usable IPv4 addresses for the code: site-local, up, not loopback, cellular (`rmnet*`) or VPN. It reuses the enumeration in `FServerManager.getAllLocalAddresses` (`forge/gamemodes/net/server/FServerManager.java:645`) and doesn't rely on `getLocalAddress` (`:631`), which routes towards 8.8.8.8 and returns "localhost" on a hotspot with no internet. |
| `RemoteOp` | The prompt-free ops a joined phone can send (4.2), in names rather than card ids, and their mapping to `CompanionTable` ops on the host. |
| M3: `PeerProbe`, `Takeover` | STATUS/FETCH between phones, choosing the freshest phone, the NEW_HOST broadcast, and term comparison (section 4.3). |

### 2.3 Screens: `forge-gui-mobile`, package `forge.screens.companion`
| Screen | Notes |
|---|---|
| `ShareDialog` | The join code as text and as a QR image (ZXing `BitMatrix` → libGDX `Pixmap`), the addresses it holds, and a "Phones" list with seat, connected or not, Remove, and in M2 a read-only switch and "Take the pen back". |
| `JoinGameScreen` | A typed code field (dev build and phones), "Scan" where `canScanCodes()`, then a seat choice from the unclaimed seats or "Watch" (spectator), then `MatchScreen` as a mirror. |
| `MatchScreen` | Unchanged. The follow prompt shows "Following Anna's phone" or "Not connected to Anna's phone: showing the last state, reconnecting…". The own seat is highlighted through the `MatchExtension` hook. |

### 2.4 Android: `forge-gui-android`
- `QrScanActivity` reuses the Camera2 `CameraController` and its YUV frame stream (`forge/app/camera/FrameAnalyser.java`). A `FrameAnalyser` runs ZXing's `QRCodeReader` on the Y plane. It needs no OCR and no new camera code.
- **Wi-Fi binding.** On a Wi-Fi network with no internet (a router alone, or the host's hotspot), Android keeps cellular as the default network, and a plain socket to the host's LAN address goes out over cellular and fails. `bindToLocalNetwork(true)` finds the Wi-Fi `Network` through `ConnectivityManager` (a `NetworkCallback` for `TRANSPORT_WIFI`, which needs only `ACCESS_NETWORK_STATE`) and calls `bindProcessToNetwork` while a game is joined, or while a host connects out to peers (M3); Netty's NIO channels can't take `Network.getSocketFactory()`, so the process binding is the one that applies. It's undone on leaving the game, so image downloads use the default network again. A host that *is* the hotspot only accepts connections, which arrive on the hotspot interface without binding.
- **Foreground service.** Android 12+ freezes cached background apps, so a backgrounded host stops answering. While sharing, the host runs a foreground service of type `specialUse` with a notification (open decision f), through `keepServing(true)`. Joined phones run none: when backgrounded they may drop, and they reconnect and catch up on resume (3.4).
- Permissions: `INTERNET` and `ACCESS_NETWORK_STATE` are already declared (`src/main/AndroidManifest.xml:27-28`), and `FOREGROUND_SERVICE` too (`:25`). New: `FOREGROUND_SERVICE_SPECIAL_USE` and the service entry with its `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (required at `targetSdkVersion` 35, `:7`), and `POST_NOTIFICATIONS` for the notification on Android 13+. Plain `ws://` to a LAN address isn't subject to Android's cleartext policy, which covers HTTP stacks, not raw sockets. The local-network permission announced for later Android versions doesn't apply at SDK 35 yet (Risks).
- Netty is already in the APK: `forge-gui` depends on `netty-all` 4.1.115 (`forge-gui/pom.xml:50-54`), Android doesn't exclude it (`forge-gui-android/pom.xml:140`), and mobile online play uses it (`forge-gui-mobile/src/forge/screens/online/OnlineMenu.java`). ProGuard has `-dontwarn io.netty.**` (`proguard.cfg:24`) and keeps only `io.netty.util.internal.logging.**` (`:99`), so the WebSocket codec classes aren't kept. They're new on Android, so slice M1-3 checks them on a phone and adds a narrow keep rule if shrinking breaks them.

### 2.5 New dependency
**ZXing `com.google.zxing:core`** (Apache-2.0, which is GPLv3-compatible; pure Java; on Maven Central) goes in `forge-gui-mobile`. It encodes the QR code and, on Android, decodes it. The repo has no QR library today: a grep of every pom and Java file for zxing or qrcode finds nothing. iOS takes `forge-gui-mobile` (`forge-gui-ios/pom.xml:93`), and pure Java is safe for `ios-compat-gate.yml`. Sharing isn't offered on iOS, because `canScanCodes` defaults to false and no iOS work is planned.

## 3. Transport and protocol

### 3.1 WebSocket over raw TCP
| | Netty raw TCP, one JSON per line | **Netty WebSocket, one JSON per text frame** (chosen) |
|---|---|---|
| Framing | Our own line splitter. Step text has newlines, so JSON escapes them | Message frames built in |
| Liveness | Our own ping | Ping/pong frames + `IdleStateHandler` |
| Size of a join (a long game is a few hundred steps of 5–15 KB, very repetitive) | Our own gzip | `permessage-deflate` (Netty's `WebSocketServerCompressionHandler`) is available if needed |
| Browser listener later (spec §6.1) | Impossible without a second server | Same port. An HTTP GET can later serve a page |
| Dependency | `netty-all`, present | `netty-all`, present. The codecs are in the same jar |
| Cost | Least code | A handshake handler on each side, about 50 lines |

The WebSocket path is at `/companion`, with a 16 MB frame limit. Each step goes in its own frame. Compression starts **off**: slice 0 measures a join's size and time uncompressed, and `permessage-deflate` is added only if that misses AC-106's 1 s on a phone. Netty runs one `NioEventLoopGroup(1)` for Companion. Messages that touch the store go through one single-thread executor per side, so writes stay in host order. Jetty is a dependency too, but it's excluded on iOS (`forge-gui-ios/pom.xml:86`) and adds nothing that Netty lacks.

**Port:** 36744 by default, so it doesn't clash with Forge net play's 36743 (`ForgeNetPreferences.java:28`), and the next free port up to 36753 if that one is taken. The code carries the port, so nobody has to configure it.

### 3.2 Messages
Each message is a JSON object with a type `t`. **J** marks a joined phone, **H** the host, **P** any peer (M3).

| Dir | Type | Content |
|---|---|---|
| J→H | `hello` | `proto`, `logFormat`, `cardDb`, `app` (for messages only), `device`, `name`, `token`, `game` (null on first join), `have` = {`seq`, `term` (number, time, host) of its last entry} |
| H→J | `refuse` | the reason, plus the host's three versions. The host also shows its operator a notice (AC-110) |
| H→J | `welcome` | game id, host device, term (number, time, host), `setup`, `roster`, the host's latest `seq` and latest `step`, the step index `{n: sha256}`, and (M3) where the phone's log diverged |
| J→H | `want` | the step numbers the phone lacks or has with a different hash |
| H→J | `entries` | a chunk of log lines, verbatim |
| H→J | `step` | `n`, `sha256`, the text |
| H→J | `setup` / `roster` / `end` / `removed` | as named |
| J→H | `claim` | a seat name, or null to watch |
| J→H (M2) | `op` | a remote op (4.2) |
| J→H (M2) | `pen?`, `commit`, `pen-done` | section 4.2 |
| H→J (M2) | `pen`, `queue`, `commit-refused`, `pen-taken` | as named |
| P↔P (M3) | `status?` → `status`; `fetch` → `entries` + `step`s; `new-host` | section 4.3 |

**Catch-up rule.** After `welcome`, the phone cuts back what the host lacks: entries past the host's seq (`truncateLogAfter`, which only happens after a lost pen or a stale host), and step files past the host's latest step (`discardAfter(hostLatest)`). It then compares the index with its own step hashes and sends `want`. The host sends the entries after the phone's `have.seq`, then the wanted steps. A step file only changes after an undo to below it, and that undo is an `UNDO` entry. A phone applying entries in order runs `discardAfter(entry.step)` for each `UNDO` entry, as the host did, so after the entries every step it still has normally matches the host's; the hash comparison catches the rest.

**Live stream.** The host's journal sends each write in the order it was written: an entry when appended (entries come before their step's save, `CompanionLogEntry.java:31-32`), a step when saved, setup on change, `end` last. A joined phone writes each message to its store (`appendVerbatim` fsyncs per chunk, `writeStep` through `writeWhole`), then submits one coalescing `ApplyStep`. Rows show in the history as soon as their entries arrive. The board changes at the host's next settled save (open decision c).

### 3.3 Join code
- **Typed code:** Crockford base32 of the IPv4 address (4 bytes), port (2) and a random share token (4): 16 characters, shown as `XXXX-XXXX-XXXX-XXXX`. It doesn't carry the game id: the host serves one game and returns the id in `welcome`. This differs from the steer, because a UUID makes a typed code too long. A wrong code fails to connect, or the token doesn't match and the phone says so.
- **QR:** `forge-companion:<typed code>?g=<game id>&a=<other addresses>`. Every usable address goes in (Wi-Fi and hotspot interfaces both). A scanning phone tries them in parallel and keeps the first that answers.
- **Hotspot:** when the host is the hotspot, its address is on `ap0`/`swlan0`/`wlan1` and Android may randomise it per session. `LocalAddresses` lists it like any other, and the share dialog names the interface ("Hotspot", "Wi-Fi") so the operator picks the right code. The typed code uses the first address. The dialog can switch it.
- The token stays the same for the game (B3: a removed phone may rejoin). It's stored in `share.json`, so phones can rejoin after a restart without a new code.

### 3.4 Threads and Android lifecycle
- Netty callbacks never touch the game or the UI. Store writes run on the share executor, game changes go through `CompanionTable.submit` (the game loop thread, as for every op), and UI changes go through `FThreads.invokeInEdtLater` (`forge/gui/FThreads.java:26`), which libGDX runs on the render thread.
- While a game is shared or joined, the screen is kept on through `preventSystemSleep(true)` (`IDeviceAdapter.java:27`, Android `Main.java:870`).
- **Host:** a foreground service (`specialUse`, with a notification) runs from "Share game…" until sharing stops or the game ends, planned in M1, not as a fallback: Android 12+ freezes a backgrounded app's process, which would stop the host serving everyone.
- **Joined phone:** no service. When backgrounded (screen locked, app switched) its connection may drop; it shows nothing then, and on resume it reconnects through the normal backoff and sync makes up the gap. The host's Phones list shows it as not connected meanwhile (AC-111). A joined phone holding the pen that is backgrounded loses it like any disconnect (4.2).
- **Network:** a joined phone binds the process to the Wi-Fi network while joined (2.4).

## 4. Behaviour by milestone

### 4.1 M1: follow along
- **Sharing (AC-101, 114):** "Share game…" starts `ShareHost` and the foreground service, writes `share.json` (role host, token, port), installs the store hook and shows `ShareDialog`. With no phone connected, the hook only fills the journal, so logging is exactly v1. The host keeps its journal in memory and rebuilds it from the store on demand, because the store is the source.
- **Joining (AC-102, 103, 104, 105):** `JoinGameScreen` → `hello` → `welcome` → the phone picks a seat or Watch (`claim`). The host refuses a seat that's taken, gives Me's seat to the host itself, and lets any number of spectators watch. Then catch-up, then a mirror `MatchScreen`. The game folder uses the **host's game id**, so the copy is the same game on every phone.
- **One active game per phone:** `CompanionStore.loadActive` resumes the most recent unfinished game (`:352`). A phone with its own active game is asked to end it before joining (open decision h).
- **Viewing (AC-106, 107, 108):** a mirror shows Forge's board, zone tabs and history. The player filter (AC-28) works because the history reads the same log. The own seat is highlighted. Nothing on the mirror can act: every entry point in 2.1 is gated, Ctrl+Z included.
- **Phones list (AC-109):** the roster with seat, connected or not, and Remove. Remove sends `removed`, closes the socket and frees the seat (B5). The removed phone stops reconnecting and keeps its copy as an ended game. It may rejoin with the code (B3).
- **Connection loss (AC-111):** a missed pong (5 s ping, 15 s timeout) or a closed socket → the follow prompt says "not connected", and the phone reconnects with `hello(have)`.
- **Persistence (AC-112):** every message is in the store before it's applied, so a killed phone resumes from its own files and reconnects with what it has. A restarted host resumes sharing on the same port and token, so its phones come back on their own.
- **End (AC-113):** `end` → `store.end(id)` → the game moves to past games, read-only (`CompanionPastGamesScreen.java:47`). A phone that missed the end stays "not connected" until its operator taps "Stop following", which ends it locally. A host that ends its game keeps serving `end` to returning phones until the app leaves Companion.

### 4.2 M2: act together
**Two paths, by whether the action can open an engine prompt.** Checked against v1's code:

| Action | Can it prompt? | Path |
|---|---|---|
| "Wrong player" (`Reassign`, `CompanionTable.java:144`) | No: zone- and control-change triggers are suppressed (`TableEditor.java:233-274`). A state-based choice after it (the legend rule) would open on the host, as in v1 | Remote op |
| Life as a correction (`setLife(p, life, false)`, `TableEditor.java:451`, triggers suppressed) | No | Remote op |
| Life as a real gain or loss (`fire = true`) | Yes: "whenever you gain life, target…" | Pen |
| Ready creatures (`readyCreatures`, `TableEditor.java:564`) | No: it only clears sickness | Remote op |
| Undo, and the undo after an auto-add (`Undo`, `CompanionTable.java:692`) | Practically no; its `applyNow` can, rarely, put a state trigger on the stack, which then asks on the host, as v1's undo does | Remote op |
| Combat this turn / every turn, commander mode | No: table settings | Remote op |
| Any add, by name or photo, cast or put onto the battlefield | Yes: targets, modes, enter effects, other permanents' triggers. "No target" on the card itself isn't enough | Pen |
| Move (`TableEditor.moveTo`, zone-change triggers fire, `:288-298`) | Yes | Pen |
| End Turn, OK (passing priority), a turn skip (`AddCard` with `skipToSeat`) | Yes: draws, upkeep triggers, engine combat | Pen |

v1 has no hand-count op, and "next turn" is End Turn, so neither is a remote op. "Auto-add photos" and "Keep photos" are each phone's own preferences.

**Remote ops.** The acting phone sends `op` with the action in names: seats by setup name, cards by controller, name and ordinal among that controller's same-named cards in the zone, because card ids differ between phones (section 0). The phone asks its own questions first, on its mirror, which is at the host's step: the life dialog's "real gain or loss?" (`LifeDialog.java:90-98`, with `lifeChangeCouldTrigger`) decides whether a life change is a remote op or needs the pen, and the undo confirmation below. The host queues the op with the pen requests and, when it's at the head and the host holds the pen, runs it through `CompanionTable.submit` with `by`/`byName` stamped. A host-side op never calls `table.confirm` for a remote op (a new `SetLife(player, life, fire)` op carries the answer). No hand-over happens, so nothing is lost (decision a).

**The pen.** The host holds the pen whenever no phone does: its operator acts and passes priority as in v1.

- **Hand-over point.** The pen is granted, released or taken back only when the game of the phone giving it up satisfies all of: `settledAsSaved(game)` (`CompanionTable.java:754`: no stack, no simultaneous stack entries, not in combat, equal to the latest save); nothing parked (`isParked`, which covers a turn skip, `:847-893`); and no seat with an active End Turn yield. At any other moment the live game holds what no step does (section 0), and the receiver, which starts from the latest step, would silently drop it. So **a pen spans a whole turn skip or engine combat**: the holder keeps it until its game reaches the next settled main phase, and the queue waits. On the host, a pen request that reaches the head waits the same way, for example until End Turn has run to the next player's Main 1. The "combat this turn" switch (`combatTurn`) isn't in a step, so it travels in `pen` and `commit`.
- **Flow:** the operator confirms an action → `pen?` → the host grants the head of the queue at its next hand-over point (`pen`, with its head seq, step and `combatTurn`) → the phone waits until it has that seq and step, switches its table to `HOST` mode, and runs the op. It **writes locally as a normal host**: its store hook stamps `term`, `host`, `by` and `byName` on its own entries, so the bytes are the ones the host will store. At each settled save its hook collects the entries and the step written since the last commit (or an `UNDO` entry with its `discardAfter`) into `commit` = {base (term, seq, step), entries, step n and text}. The host checks base = head, runs `discardAfter` for an `UNDO`, `appendVerbatim`s the entries, `writeStep`s, submits `ApplyStep` to its own game (which then refreshes its table as 2.1 says) and broadcasts. The holder gets its own entries back through the stream; `appendVerbatim` skips them as already held. At its next hand-over point, idle, it sends `pen-done` and its table goes back to `MIRROR`.
- **Prompts under the pen** open on the holder's phone, for every seat: in an engine combat, the defenders' blockers choice too (open decision b).
- **Taking the pen back:** the host takes it when the holder disconnects (a missed pong, or the holder's app backgrounded), or when its operator uses "Take the pen back" after a confirmation (open decision g). No automatic timeout.
- **Refusal and rollback:** a commit whose base isn't the head can only follow a lost pen. The host sends `commit-refused`. The holder rolls back through the **same truncate-and-reconcile path M3 uses**: `truncateLogAfter(host seq)`, `discardAfter(host latest step)`, catch-up, then `ApplyStep` to the host's latest step, and says "not logged: you lost the connection", with the dropped actions listed, so the operator can redo them. A holder that crashed with uncommitted writes is cut back the same way at its next `welcome`. There's no separate buffered store.
- **The host's own operator** acts freely while it holds the pen. While a phone holds it, the host's seats wait at the follow prompt ("Anna is logging…"), and the operator's actions join the queue (AC-118). Nothing is merged or deduplicated. AC-117: with no connection, a joined phone can't send an op or ask for the pen, and there's no offline queue.
- **Default target (AC-116, B6):** unchanged. The add dialog's default is the active seat on every phone.
- **Undo (AC-120):** if the latest entry it would take back that changed the state has a `by` other than this phone, the phone first shows "Logged by Anna's phone: Cast Lightning Bolt. Undo it?" The undo then runs as a remote op, v1's `Undo` on the host. A pen holder's own undo runs locally and is committed as an `UNDO` entry.
- **Read-only (AC-121):** a roster flag. The host refuses `op` and `pen?` from a read-only phone or a spectator, and those phones gate every entry point as a mirror does.
- **Attribution (AC-119):** `by`/`byName` on every entry written after sharing starts. The history shows "· Anna's phone" for entries not logged by the host.
- **What a hand-over loses (open decision a).** Each pen hand-over puts the receiving game on a snapshot: the holder's mirror at grant, and the host when it applies a commit. A snapshot holds no "until end of turn" effects and no delayed triggers, `applyNow` zeroes the life gained and lost this turn (`CompanionGameState.java:114-119`) and resets commander damage (`:144-146`). So these are lost at **every** hand-over, not only on an undo: a Giant Growth cast on Anna's phone is gone from the board once the host's operator next acts, and 15 commander damage dealt in an engine combat under the pen is gone after the commit. Remote ops cause no hand-over, which is why they come first; a game where only the host's operator uses the pen loses nothing beyond v1.
- **Why not forward prompts from the host instead.** The host's match gives every seat the **same** `IGuiGame` (`CompanionMatch.java:28-33`), so it has no per-seat channel to send a prompt to another phone; building one is Forge net play's `PlayerControllerHuman` forwarding, which spec §10 rejects. Prompts name cards by id, and ids aren't kept across a state load (`GameState.java:1390-1392`), so the asking phone couldn't map them to its own cards. And the host's game loop would block on Wi-Fi.

### 4.3 M3: takeover
Every phone in a shared game also listens, on the port it reported in its roster entry. From M1 on, the roster carries each phone's addresses and port, and `share.json` keeps them.

1. **Start (AC-122):** "Take over as host" on a seated phone, after a confirmation. It doesn't need the host to be unreachable.
2. **Freshness (AC-123):** `status?` to every roster peer, waiting at most 3 s. Each reply holds the game id, its last entry's term (number, time, host) and seq, its latest step, and whether it's a host. Fresher means a higher term in the order of decision 4, or the same term with a higher seq.
3. **Catch-up:** `fetch` from the freshest peer since this phone's last *common* seq. The common seq is the highest seq whose entry has the same term on both, found from the peer's list of term starts. The reply carries the peer's latest step and step index. The phone cuts its log back to that seq if it must (`truncateLogAfter`), runs `discardAfter(peer latest step)`, appends the tail verbatim, and fetches the steps it lacks or has differently. It then shows "Caught up: 4 actions" with their text.
4. **Become host:** append a `HOST` entry with term number = highest seen + 1, host = this phone and the wall-clock time. `CompanionTable` switches from mirror to host in place, so there's no match restart. `ShareHost` starts on its port, and `new-host` goes to every peer (AC-124). A peer that sees a higher term reconnects to the new host with `hello(have)`. The old host, if reachable, does the same and follows.
5. **No peers (AC-127):** steps 2–3 find nobody, and the phone becomes a host with no followers, exactly AC-114.
6. **Stale host returns (AC-125):** a phone that thinks it's host, or follows a host that's gone, probes the roster with `status?` every 10 s. When it finds a higher term, it follows that host. The `welcome` reply says where its log diverged and carries the host's latest seq and step. It keeps its dropped entries in memory, cuts its log back to the common seq (`truncateLogAfter`, a whole rewrite through `writeWhole`), runs `discardAfter(host latest step)`, reconciles steps, and shows its operator the dropped entries that changed the game, to log again. The new host also retries `new-host` to unreachable roster peers every 10 s.
7. **Two takeovers at once (AC-126):** a takeover started after seeing another's `HOST` entry gets a higher term number and wins. Two takeovers with the same term number are ordered by the time on their `HOST` entries, then by device id, as decision 4 says. The loser applies step 6. **Across a network split, "later" means the higher term, not the later clock time** (open decision d): a side that took over twice while the other took over once wins, whatever the clocks say. Clocks matter only for a same-number tie.
8. **Lost in a takeover:** an action a pen holder hadn't committed, and the old host's queue. The pen starts with the new host.

## 5. Files touched outside new code

- **`forge-gui`:** `CompanionLogEntry`, `CompanionStore`, `CompanionTable` (mirror mode, `ApplyStep`, the follow prompt's end path, and in M2 applying commits, `SetLife`, the hand-over check), `CompanionPlayerController` (2.1), `IDeviceAdapter` (scan, network binding and service defaults).
- **`forge-gui-mobile`:** `CompanionSetupScreen`, `CompanionMatchExtension`, `CompanionHistoryScreen`, `LifeDialog`, `MoveDialog`, `WrongPlayerDialog`, `CardSearchDialog` (routing to a remote op or the pen), plus `pom.xml` (ZXing).
- **`forge-gui-android`:** `AndroidManifest.xml` (the `QrScanActivity` and foreground-service entries; `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS`), the service class, `Main.java` (`scanCode`, `bindToLocalNetwork`, `keepServing`), possibly `proguard.cfg` (a Netty WebSocket keep rule, only if the phone check needs it).
- **Not touched:** `forge-game`, `forge/gamemodes/net/**`, desktop Swing. No existing mode changes. Every new path starts from a Companion menu item.

## 6. Traceability and verification

| AC | Delivered by | Verified by |
|---|---|---|
| 101 | `ShareDialog`, `JoinCode`, ZXing | Dev build: the code shows. Phone: the QR scans. |
| 102 | `JoinGameScreen`, `QrScanActivity`, `ShareFollower`, Wi-Fi binding | Two phones on a router with no internet, and on the host's hotspot, with mobile data on. |
| 103 | Roster, `claim` | Dev build with 3 instances. |
| 104 | Roster, `claim` | Dev build with **6 seated instances** (the host and 5 joined, each with its own `userDir`) and one spectator. |
| 105 | Catch-up rule (3.2) | **Test:** a follower joins after undos and gets an identical log and steps. |
| 106 | Journal → `step` → `ApplyStep` | Phone check with a stopwatch log line (B4: up to 8 phones). Board at settled points, history row at once (open decision c). |
| 107 | Mirror `MatchScreen`, highlight, history | Dev build. |
| 108 | Follow prompt, every entry point gated | **Engine test:** a mirror never passes priority, its turn stays put until a step arrives, and `ApplyStep` ends the follow prompt. Dev build: on a mirror, the menu's acting items, a life tap, a card's "Move…", the "Add card" button and Ctrl+Z all do nothing. |
| 109 | Phones list, `removed` | Dev build. |
| 110 | `Compat`, `refuse` | **Test:** a mismatched `logFormat` is refused, and both sides get a reason. |
| 111 | Ping timeout, backoff, follow prompt banner | **Test:** kill the socket, append on the host, reconnect, catch up. Phone: Wi-Fi off and on; joined phone backgrounded and resumed. |
| 112 | Verbatim store, `share.json`, resume | **Test:** restart a follower from its folder and catch up. Phone: kill the app. Phone: host backgrounded 2 minutes with its service, still serving. |
| 113 | `end`, Stop following | Dev build. |
| 114, 127 | The hook only journals | v1 tests unchanged (`forge-gui-desktop/.../TableEditorTest.java`, `CompanionStoreShould`). **Test, shared with no peers:** the same actions with sharing on and no follower write the same log lines and step files as with sharing off. |
| 115, 116 | Remote ops; pen, holder writing locally, commit | **Test:** a remote life correction and a ready-creatures op run on the host and are attributed. Dev build: a target prompt on the acting instance. Phone: capture on a seated phone. |
| 117 | No op or pen without a connection | Dev build. |
| 118 | One FIFO queue for ops and pen requests, base check, hand-over points | **Test:** two `pen?` requests are granted in arrival order, and a stale base is refused and rolled back. **Test (engine):** the pen isn't granted or released during a turn skip, nor during an engine combat, and is at the next settled main phase. |
| 119 | `by`/`byName` | **Test:** a round trip of the new fields, and a v1 line reads as before. |
| 120 | Undo as a remote op, with confirmation | Dev build. |
| 121 | Roster flag, refused `op` and `pen?` | Dev build. |
| 122–124 | `PeerProbe`, `Takeover`, `new-host` | **Test:** three in-process nodes, take over from the freshest and from a stale one. |
| 123 | Catch-up before becoming host | Dev build: "Caught up: n actions" lists the fetched actions. |
| 125 | Divergence → truncate + `discardAfter` + list | **Test:** an isolated old host appends and saves steps, then returns, and gets the exact dropped list, a log equal to the new host's, and no step past the new host's latest. |
| 126 | Term order + tie-break | **Test:** two same-number takeovers converge on one host; a higher term number wins over a later clock. |

**Tests in CI.** `CONTRIBUTING.md` asks agents not to add unnecessary tests, so CI gets only the tests where a regression would be silent:
- sync and catch-up over **real localhost sockets**, with several in-process nodes driving `CompanionStore` directly (no engine, `forge-gui`), including cut-back of steps the host lacks
- the log field round trip, and "shared, no peers" equals unshared
- the queue order, the base check and a refused commit's rollback
- remote ops on the host
- takeover and divergence (M3)
- engine tests, in `forge-gui-desktop` with `TableEditorTest`'s harness: a mirror applies steps and never moves on by itself; the pen's hand-over points across a turn skip and an engine combat

There are no UI tests. Phone checks (QR scan, hotspot with mobile data on, a backgrounded host, screen-off and resume, the 1 s target with several phones) are done by you.

**CI gates:** `test-build.yaml`, `test-android-build.yml`, `ios-compat-gate.yml`, as in v1.

**Several instances on one PC:** the dev build stores games in `USER_DIR/companion` (`CompanionSetupScreen.java:46`), and the host and a joined phone use the same game id. Each extra instance therefore runs with its own `userDir` in `forge.profile.properties` (`ForgeProfileProperties.java:47`). Slice 0 documents the command.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Venue Wi-Fi isolates clients (common in shops and hotels) | Clear error ("can't reach the host: this Wi-Fi may block phones from talking to each other; use the host's hotspot"). The hotspot is the fallback the spec names. |
| On a Wi-Fi network with no internet (a hotspot, a bare router), Android routes sockets over cellular, so joining fails with mobile data on | Bind the process to the Wi-Fi network while joined (2.4). Checked on phones at the start of M1-1, before more is built on it. |
| Android 12+ freezes a backgrounded host | A `specialUse` foreground service while sharing, in M1 (3.4). Joined phones reconnect on resume. |
| Android suspends or kills a backgrounded joined phone's socket (Doze, OEM task killers) | The screen stays on while joined; resume and catch-up are cheap. |
| The hotspot address is randomised, or the host has several interfaces | Every address goes in the QR; the dialog names interfaces; scanning tries all of them. |
| The host phone is the hotspot and dies, so the network goes with it | Takeover can't help, because there's no network left. Told plainly in the takeover message. Venue Wi-Fi survives it. |
| The host's address changes mid-game (DHCP, hotspot restart) | A joined phone retries its stored addresses. Otherwise it scans again (same token, same game, catch-up). LAN discovery (a spec nice-to-have) would fix it later. |
| ProGuard strips Netty's WebSocket classes (only `io.netty.util.internal.logging.**` is kept) | Phone check in M1-3. Add a narrow `-keep` only if needed. |
| A big join is slow (hundreds of 10 KB steps) | Steps reconciled by hash, so a rejoin sends only new steps. Join size and time measured in slice 0; `permessage-deflate` only if that's too slow. |
| A mirror's applied state differs from the host's live view (temporary effects aren't in a step) | Same limit as v1 resume (v1 plan §2.3). Joined phones only display. |
| Every pen hand-over loses "until end of turn" effects, delayed triggers, life-this-turn tallies and commander damage (4.2) | Prompt-free actions are remote ops, with no hand-over. The rest is open decision a. |
| A pen held through a long turn skip or engine combat stalls everyone else | Visible as "Anna is logging…"; the host can take the pen back (open decision g). |
| Followers see the board only at settled points, so a spell waiting on the stack isn't shown | Its log entry shows straight away. Open decision c, a spec note under AC-106. |
| A mirror's own engine does something by itself after a step is applied | Steps are saved settled, so state-based actions have already run on the host. But `applyNow` re-enables triggers and runs `checkStateEffects` (`GameState.java:659`, `:687`), so a state trigger can briefly land on a mirror's stack or ask a choice. `ApplyStep` declines such choices and empties the stack again; the engine test asserts that the mirror's state equals the step after apply. |
| The card database fingerprint differs between builds of the same release (desktop dev vs phone) | Checked in M1-3 with the refusal messages. If it does, fall back to hashing the card names the game uses. |
| Android's announced local-network permission | Not enforced at `targetSdkVersion` 35. Revisit when the SDK is raised. |
| NAT, internet, clock skew | Not relevant: LAN only, and the host sequences. Clocks matter only for AC-126's same-number tie. |
| iOS | Untouched. ZXing and the new code are pure Java and pass the compat gate. Sharing isn't offered there. |
| An attacker on the LAN | Out of scope (B1). The token only prevents wrong joins. |

## 8. Project-rule notes

`/mnt/wsl/git/CLAUDE.md` is a generic workspace file. As in v1 (plan §7), this plan follows **Forge's setup**: Java 17 release level (`pom.xml:27`), Maven, no database, Netty and Gson as Forge already uses them, and threads as Forge uses them (no virtual threads on Android's Java 17 level). Android manifests and poms are XML because the project requires it. `session-insights.md` stays out of commits.

## 9. Build order

The work starts after v1 slice 08 has landed (spec §7). Phase 3 turns each item into a slice file in `docs/companion/multi/slices/`.

0. **Slice 0, walking skeleton (dev build):**
   - log fields (`term`, `host`, `by`, `byName`), `CompanionDevice`, the store hook (after successful writes only), `appendVerbatim`/`writeStep`/`stepHashes`
   - a minimal `ShareHost`/`ShareFollower` over WebSocket with `hello`/`welcome`/`want`/`entries`/`step`, no compression, no version checks yet
   - mirror mode with the follow prompt and its end path, "Share game…" showing the typed code, and "Join game" with a text field
   - **Oracle:** two dev instances. The host adds a card by name, and the joined instance shows it on its board and in its history.
   - Measures latency and join size. Tests: catch-up over localhost, the field round trip, mirror never moves on.
1. **M1-1, connection loss and persistence:** first a phone check of joining over a no-internet hotspot with mobile data on, with the Wi-Fi binding. Then ping/backoff, the "not connected" banner, `share.json`, resume and auto-rejoin, the host resumes sharing, the host's foreground service, catch-up after undo while away (with cut-back of steps the host lacks), `end` → past games, Stop following. AC-111–113.
2. **M1-2, seats and phones:** seat choice and claim, the roster, the Phones list, Remove, own-seat highlight, history filter on the mirror, one active game per phone, gating every acting entry point. AC-103, 104, 107, 108, 109.
3. **M1-3, on real phones:** QR (ZXing) and `QrScanActivity`, `LocalAddresses` with hotspot naming, `Compat` in the handshake with the refusal messages and the card database fingerprint check (AC-110), the ProGuard check, keeping the screen on. Phone checks for AC-102, 106 and 111 on Wi-Fi and hotspot.
4. **M2-1, remote ops:** the shared queue, `op`, `RemoteOp` naming cards without ids, `SetLife`, life correction, wrong player, ready creatures, undo with confirmation, the table switches, attribution in the history, the read-only switch. AC-116, 117, 118 (queue), 119, 120, 121.
5. **M2-2, the pen:** hand-over points, `pen?`/`pen`/`commit`/`pen-done`, the holder writing locally, the host applying commits (table refresh), `truncateLogAfter` and rollback on `commit-refused`, taking the pen back, add by name from a seated phone with its prompts. AC-115 (add), 118.
6. **M2-3, every action:** capture and auto mode on seated phones, the remaining pen actions (move, End Turn, real life gain or loss, turn skip, engine combat). AC-115.
7. **M3-1, takeover:** every phone listens, `status?`/`fetch`, catch-up, the `HOST` entry, mirror → host, `new-host`. AC-122–124, 127.
8. **M3-2, stale host and split brain:** periodic probes, divergence and truncation with the dropped list, the term order and tie-break. AC-125, 126.

Nice-to-haves, unplanned: LAN discovery (a UDP beacon), desktop Forge as a table display (the mirror already works on the dev build), and putting the phone's own seat at the bottom.

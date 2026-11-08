# Slice 01: Skeleton on real phones

**Branch:** `feature/companion-multi-01-phones`, stacked on `feature/companion-multi-00-skeleton`. **Plan item:** section 9, slice 0, Oracles 2 and 3 and the measurement.
**Depends on:** 00 (`CompanionShareHost`, `CompanionPeer`, `PeerGate`, `CompanionClient`, `JoinCode`, `LocalAddresses`, `ShareDialog`, `JoinGameScreen`, `CompanionViewExtension`, `attachMidGame`, `runSystem`, the snapshot events).
**Proves:** the skeleton works where it will be used: Netty listening on an Android phone, a joiner on a Wi-Fi with no internet while mobile data is on, desktop and Android exchanging the view both ways, and a host phone serving 8 viewers, then about 12 to find the safe number for the spectator warning (05).

## Result
The owner hosts a Companion game on one Android phone and joins it from a second Android phone by typing the code, on a router with no internet (or the host's hotspot), with mobile data **on** on both. The joined phone shows the board, follows a card being added, and follows an undo. A desktop dev instance can join a phone's game, and a phone can join a dev instance's game. With 8 viewers attached, the host phone still feels the same to its operator, and the joined phone still updates within a second. A second run with about 12 viewers says how far past 8 the host can go.

## Acceptance criteria
Delivered in part:
- **AC-102** WHEN a user picks "Join game" in Companion and scans the QR code or types the join code THE SYSTEM SHALL connect to that shared game over the local network, without internet. _Here: typed code, two real Android phones, no internet, mobile data on. Scan: 08. Full phone check: 09._
- **AC-106** WHEN the host's game changes THE SYSTEM SHALL show the change on every connected phone within 1 s on the same local network. _Here: measured on a real host phone with 8 consumers (spec B4). Stopwatch check on several phones: 09._

## Design for this slice
Plan 2.1 (`GameEventForwarder`, `IDeviceAdapter`), 2.4 (Wi-Fi binding, local network permission), 3.1 (`TokenGate`, built in 00), Risks ("desktop JVM and Android", "eight peers", "more spectators than measured", "Android routes over cellular", "local network protection").

### `forge-gui`
- **`GameEventForwarder`:** a flush asked for from any thread but the game thread only **marks a flush as due**; the game thread does it at its next flush point (the next priority, input or end of turn). Today an input ending calls `InputQueue.removeInput` → `updateObservers` (`InputQueue.java:56-65`) from whatever thread ended it (`InputSyncronizedBase.java:44-55`), often the render thread on a tap, and the flush then walks the view while the game thread may be changing it, including each `TrackableObject`'s `consumers` `HashMap` (`TrackableObject.java:115-122`, `:179-184`). After this change the view is walked, and each peer's bytes encoded, on the game thread only. Net play keeps working: its flushes off the game thread become a short delay. **Measure that the delay can't be felt** (below).
- **Fallback, only if the measurement says so:** encoding moves to one sender executor working from a per-peer snapshot taken on the game thread (plan 2.1). Decide from the numbers and record the decision here.
- **`IDeviceAdapter`** (`forge/interfaces/IDeviceAdapter.java:27-60`): `bindToLocalNetwork(boolean)`, default no-op.
- **`CompanionClient`:** `bindToLocalNetwork(true)` before connecting, `false` on leaving.
- **`CompanionPeer`** (or the host): timing and size logging for the measurement: per flush, time on the game thread for all peers and bytes per peer; the size of each full state. Behind a debug flag or plain `netLog` lines; no UI.

### `forge-gui-android`
- **`Main.java`** (`forge-gui-android/src/forge/app/Main.java`): `bindToLocalNetwork(true)` finds the Wi-Fi `Network` with a `NetworkCallback` for `TRANSPORT_WIFI` (needs `ACCESS_NETWORK_STATE`, already declared, `src/main/AndroidManifest.xml:27-28`) and calls `bindProcessToNetwork`; `false` undoes it. Netty NIO can't take `Network.getSocketFactory()`, so the process binding is what applies (plan 2.4). A host that is the hotspot only accepts connections and needs no binding.
- No manifest change: `INTERNET` and `ACCESS_NETWORK_STATE` are declared. Netty 4.1.115 (`forge-gui/pom.xml:50-54`) and LZ4 are already in the APK; ProGuard keeps `forge.**` and `net.jpountz.**` (`proguard.cfg:74`, `:100`).

### Measurement harness (not production, not CI)
To reach 8 consumers with two phones: a small `main` in `forge-gui-desktop` test sources (for example `forge.gamemodes.companion.share.ViewerLoad`) that opens N headless joins to a given code, reusing `HeadlessNetworkClient`. **Not a JUnit test**, so CI never runs it (`CONTRIBUTING.md:85`). The real joined phone plus 7 headless viewers = 8 consumers on the host phone; plus 11 = about 12. The headless viewers write the `TokenGate` preamble like `CompanionClient` does (built in 00), or the host closes them at the gate.

## Not in this slice
- The foreground service (the host phone must stay in the foreground with its screen on during these checks): **06**.
- Keeping the screen on automatically: **09**. Set the host phone's screen timeout to 10 minutes for the checks.
- Friendly interface names, QR, address lists: **08, 09**.
- Travelling ops: there are none before M2-1. The cross-runtime check covers the view and the share messages; M2-1 repeats it for `CompanionOps`.
- The size of a big join of steps (plan Risks says "measured in slice 0", but steps don't travel until 03): measured in **03**.

## Check first
1. **Netty listening on Android:** `CompanionShareHost` binds 36744 on a phone on Wi-Fi and on its own hotspot, and a laptop on the same network reaches it (`nc -vz <phone ip> 36744`).
2. **Cellular routing:** with mobile data on and a Wi-Fi with no internet, a joiner **without** the binding fails (or goes slow), and **with** it connects. This shows the binding is what helps.
3. **Cross-runtime:** an Android (ART) peer decodes what a desktop JVM host encodes and the reverse, including `GameView` with placeholders, the share messages, and any lambda or record that travels in the view (`WireClassFilter.java:44-50` allows `SerializedLambda`). Note every class that fails.
4. **Flush delay:** with the game-thread-only flush, the time from the host's tap to the joined board's change.
5. **Local network permission:** Android 17 blocks LAN sockets, in and out, for apps that target API 37 or higher unless `ACCESS_LOCAL_NETWORK` ("Nearby devices") is granted (plan 2.4, Risks). Confirm that the APK still targets 35 (`forge-gui-android/src/main/AndroidManifest.xml:7`), so no permission is needed. On the newest Android phone to hand (note its version), host and join without any permission prompt. Note whether hosting on its own hotspot behaves the same (whether the hotspot counts as local network isn't stated in the docs). If a connect times out only on that phone, suspect this first. The permission request itself is built only when Forge's target moves to 37.

## Tests (CI)
None new. The measurement harness is a `main`, not a test. v1 tests and slice 00's late-attach test stay green; net play's existing tests in `forge-gui-desktop` (`forge/net/`) must stay green with the `GameEventForwarder` change.

## Oracle
**Dev check (before the phones):** two instances as in slice 00:
```
REPO=/path/to/forge; JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
for n in host join; do mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties; done
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) & (cd /tmp/companion-join && java -jar "$JAR" width=450 height=900) &
```
1. Repeat slice 00's oracle steps 3–7. OK: the same results with the game-thread-only flush; tapping OK on the host still updates the joiner without a felt delay.
2. Start a net-play game (desktop or mobile dev, host + one client) and play a turn. OK: works as before.

**[Phone] checks** (the owner; two Android phones with the APK from this branch, a laptop with the dev build, and a router with no internet, or one phone's hotspot):
1. Phone A: Companion → 3 players → add a card. Then "Share game…". OK: a code is shown for the Wi-Fi (or hotspot) address.
2. Phone B, mobile data **on**, on the same no-internet Wi-Fi: Companion → "Join game" → type the code. OK: the board shows within a few seconds. FAIL: a timeout (cellular routing), or an error.
3. Phone A: add Gray Merchant of Asphodel for player 2, cast. OK: phone B shows it, with the life changes, within about 1 s (count it on a stopwatch or a slow-motion video of both screens).
4. Phone A: undo. OK: phone B goes back too.
5. Repeat 1–4 with phone A's **hotspot** as the network (phone B on it, mobile data on).
6. **Cross-runtime, desktop host:** the laptop's dev build hosts; phone B joins by code. OK: steps 3–4 work. **Phone host:** phone A hosts; the laptop's dev build joins. OK: steps 3–4 work. FAIL: a decode error on either side (note the class).
7. **8 consumers:** phone A hosts a **6-player Commander** game (commanders set, a few permanents each). Phone B joins; on the laptop run `ViewerLoad` with 7 viewers against phone A's code. Then on phone A: add a card, cast a spell with a trigger, undo. Record from phone A's log (`adb logcat`): game-thread time per flush for 8 peers, bytes per peer per delta, the full-state size, and the time of the full resend after the undo. OK: phone A's own UI shows no felt lag, and phone B still updates within about 1 s, undo included. Otherwise apply the fallback (sender executor) and measure again.
8. **About 12 consumers:** the same game and steps as 7, with `ViewerLoad` at 11 viewers (phone B + 11 = 12). Record the same numbers. OK or not, write down the **safe number**: the most consumers at which phone A shows no felt lag and phone B updates within about 1 s. Slice 05's warning uses it. A host that struggles at 12 doesn't fail the slice: the warning's number goes down.
9. **Local network permission** (check first 5), on the newest Android phone to hand, ideally Android 17: steps 1–4 with that phone as host, then as joiner. OK: no permission prompt and no timeout. Note the Android version and the APK's target.
10. On phone B, check rendering: a placeholder in hand and library shows as a card back, the command zone shows the commander and the table effect.

Write the numbers (including the safe number from step 8) and the outcome of every "Check first" item into a "Results" section of this file.

## Results
Chunk A (`forge-gui`: the forwarder, the binding hook, the measurement logging). Phone checks and the harness are still to do.

1. **Game-thread-only flush, as built.** `GameEventForwarder.flush()` (and so `update()`, the input-queue observer) runs the flush only on the game thread, which is a `ThreadUtil.isGameThread()` thread ("Game-…") or the thread that delivers the game's events to the forwarder (tests drive the game on their own thread). Anywhere else (the render thread or EDT ending an input with a tap, a Netty thread) it only marks a flush as due. The game thread does it at its next priority (even when only redraw hints came since the last batch, which normally sends nothing), its next input (`setInput` notifies the observers on the game thread) or the end of the turn. `isFlushDue()` is there for tests.
   - **Can a due flush be lost?** Not in the paths found. An input ended off the game thread is what wakes the game thread, and the game thread then reaches priority or a new input, where it flushes. The game thread also flushes before it blocks for an input, so while it's blocked the only events waiting are ones fired off the game thread. They wait until the game thread next runs, which is no later than they would have waited for any other flush. The last flushes, when nothing changes the view any more, use the new `flushNow()` (any thread): `ProtocolGuiGame.shutdownForwarder` (net play's `HostedMatch.endCurrentGame` calls it on the EDT, so `flush()` there would have dropped the batch) and `ReplaySession.finish`. `HostedMatch`'s end-of-game flush is already on the game thread.
   - **Net play timing:** before, a flush asked for when an input ended on the EDT walked the view and encoded on the EDT straight away. Now it goes out when the game thread next reaches priority, an input or the end of the turn, which in practice follows at once, because ending the input is what releases the game thread. Net play's tests are green unchanged: `NetworkPlayIntegrationTest` (its 6 stress tests skip unless `-Drun.stress.tests=true`, as before), `ClientReconnectTest`, `DeltaSyncUnitTest`, `AbuseLimitsTest`, `LobbySlotAuthorizationTest`, `ProtocolGuiGameInProcessTest`, `ProtocolGuiGameSingleThreadTest`, `Wire*`, and `GameEventForwarderTest`.
   - **Not measured yet:** Check first 4 and dev-check step 1 (tap to joined board) need the UI. The fallback (a sender executor) stays undecided until those numbers are in.
2. **`IDeviceAdapter.bindToLocalNetwork(boolean)`**, default no-op. It may be called on any thread (the Netty event loop, net play's reconnect scheduler, the join thread, the UI thread). The client calls it with each value at most once in a row, but an implementation should still treat a repeat call as harmless. **For the Android chunk:** `Main.java`'s adapter only has to implement it. `JoinGameScreen` already passes `Forge.getDeviceAdapter()` to the new `CompanionClient(username, gui, code, IDeviceAdapter)` (the 3-argument constructor means no adapter).
   - **When the client calls it:** `true` at the start of every `connect()`, net play's reconnect attempts included, and again on a manual rejoin. `false`, once, on every way out: `close()` (Leave, leaving `JoinGameScreen`); a first connect that throws; a connection that closes with no reconnect to follow (refused at the gate or the hello, the host gone before the game, the 30 s attach timeout); and the reconnect ending `FAILED` or `SEAT_LOST`, through a new `FGameClient.onReconnectStateChanged(state)` hook (protected, no-op by default, net play unchanged). The client stays bound while it reconnects.
   - The binding applies to new sockets only. Unbinding while a channel is still open (it closes just after) is harmless.
3. **Measurement logging**, off by default. Turn it on with `COMPANION_SHARE_MEASURE=true` in the host's `<userDir>/preferences/server.preferences` (net play's preferences file; on Android it's in the Forge user folder), or with `-Dforge.companion.measure=true` on a desktop JVM. When it's off, the host has no meter, the encoder has no byte tracker, and the code paths are the ones from before. When it's on, `netLog` (logcat on Android) writes these lines:
   - `[CompanionMeasure] flush #n: P peers, X ms on the game thread, bytes [name=b, …]`: for each flush point, the time all peers' sends took on the game thread (the delta walk and each peer's encoding), and the bytes each peer got. Bytes are the encoder's output: LZ4-compressed, with the 4-byte frame length. A flush is written when the next one starts, or when the share stops.
   - `[CompanionMeasure] full state to <name>: B bytes, X ms`: each full state (the attach, an undo, a resync).
   - An undo's flush includes its full state. A heartbeat answered during a flush adds a few bytes.
   - **Example**, from `CompanionShareEndToEndTest` with the measurement forced on for one local run (3 seats, a few cards, 1 peer, desktop JVM, so not a phone number): attach full state 9,138 B; a delta flush 1,861 B at 7.8 ms; the undo's flush 17,385 B at 17.7 ms (its full state was 9,251 B; the delta after it carries the renumbered graph again, as slice 00 noted).
   - The surefire `argLine` in the root pom overrides `-DargLine`, so the system property can't be passed through `mvn test`. Use the preference, or `java -D…` for `ViewerLoad` or the dev jar.
4. **Tests:**
   - `GameEventForwarderThreadTest` (new, 3 tests): a flush asked for on another thread sends nothing there and is due, and the next priority on the game thread sends it there even after only a redraw hint; a due flush goes out at the end of the turn; `flushNow()` sends at once from any thread.
   - `CompanionShareEndToEndTest` now also checks the binding through a recording adapter: bound once before connect, still bound while joined, unbound once on `close()` (a second `close()` makes no call). The wrong-token client is unbound after the refusal without calling `close()`.

Chunk B (`forge-gui-android`, the harness, the APK).

5. **`Main.java`'s `bindToLocalNetwork`.** `true` looks up the Wi-Fi `Network` at once (`getAllNetworks`, the first whose capabilities have `TRANSPORT_WIFI`), so the connect that follows straight away already goes over Wi-Fi, and calls `bindProcessToNetwork(wifi)`. It also registers a `NetworkCallback` for `TRANSPORT_WIFI` **without** `NET_CAPABILITY_INTERNET` (a bare router has none): if Wi-Fi comes later the process is bound then, and if the bound Wi-Fi is lost the process is unbound (a binding to a gone network would fail every new socket) until Wi-Fi is back. `false` unregisters the callback and calls `bindProcessToNetwork(null)`. All under one lock; a repeat call returns at once. A failure (a `SecurityException`, the callback limit) is logged and the join goes on unbound.
   - **`registerNetworkCallback`, not `requestNetwork`:** `requestNetwork` needs `CHANGE_NETWORK_STATE`, which isn't declared, and the bundle says no manifest change. A registered callback only watches; it doesn't ask Android to keep the Wi-Fi up. If a phone drops a no-internet Wi-Fi by itself, answer Android's "Wi-Fi has no internet access. Stay connected?" with yes (or turn off "Switch to mobile data" in the Wi-Fi settings). If this shows up in the checks, `requestNetwork` plus the permission is the fix.
   - **The phone is the hotspot:** its access point isn't a `Network` to `ConnectivityManager`, so no Wi-Fi is found, nothing is bound (logged as `No Wi-Fi network: not binding (this phone may be the hotspot)`), and the default routing applies. A host never binds anyway (only `CompanionClient` calls it). A phone joining **another** phone's hotspot is a Wi-Fi client, so it binds as on a router.
   - APIs: `NetworkRequest.Builder`, `registerNetworkCallback`, `unregisterNetworkCallback`, `getAllNetworks`, `getNetworkCapabilities` (API 21), `bindProcessToNetwork` (API 23). All within minSdk 26; no `androidx`.
   - Log lines (logcat, tag `NETWORK`): `[Companion] Bound the process to Wi-Fi network …`, `Unbound the process from Wi-Fi`, `Wi-Fi network … lost: unbound`.
6. **`ViewerLoad`** (`forge-gui-desktop/src/test/java/forge/gamemodes/companion/share/ViewerLoad.java`): a `main`, not a test (no `@Test`, and the name matches none of surefire's patterns, so `mvn test` never runs it). Each viewer is a real `CompanionClient` with a `HeadlessNetworkGuiGame`, so it writes the `"FCMP"` + token preamble, says hello and follows the view like a phone. A counter at the head of each connection's pipeline counts the bytes off the socket (before LZ4), which compare with the host's `[CompanionMeasure]` bytes per peer. Every 10 s, and at the end, it prints each viewer's bytes, `joined` / `connecting` / `NOT ACTIVE`, and its epoch.
   - **Local smoke run** (a throwaway host on 127.0.0.1, not committed): 3 viewers joined (host peer count 3), each received ~17.8 kB by the first report and ~20.2 kB after 70 s with one card added; all three left on exit (peer count 0).
   - **How to run it** (in WSL, from the repo; the first two commands once per build):
     ```
     cd /mnt/wsl/git/forge--companion-multi
     mvn -o test-compile -pl forge-gui-desktop -am > target/tc.log 2>&1; tail -3 target/tc.log
     mvn -o dependency:build-classpath -pl forge-gui-desktop -am -Dmdep.outputFile=target/viewerload.cp > target/cp.log 2>&1; tail -3 target/cp.log
     R=$PWD; cd forge-gui-desktop
     java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
       -cp "$R/forge-gui-desktop/target/test-classes:$R/forge-gui-desktop/target/classes:$R/forge-gui/target/classes:$R/forge-ai/target/classes:$R/forge-game/target/classes:$R/forge-core/target/classes:$(cat $R/forge-gui-desktop/target/viewerload.cp)" \
       forge.gamemodes.companion.share.ViewerLoad ABCD-EFGH-IJKL-MNOP 7 600 > /tmp/viewerload.log 2>&1 &
     grep -E "^ViewerLoad|^  Viewer" /tmp/viewerload.log | tail -9
     ```
     Arguments: the join code shown on phone A, the number of viewers (7 for step 7, 11 for step 8), the seconds to stay joined. The module `target/classes` go first because `viewerload.cp` points the Forge modules at `~/.m2` jars, which may be another branch's. It loads the card database first (about half a minute). The machine running it must reach phone A's address: WSL's outgoing connections do (NAT), but for step 6's **desktop host** a phone can't reach a dev instance inside WSL unless WSL uses mirrored networking; run the dev jar on Windows for that step.
7. **The APK**, built from this branch (`forge-gui-android`, profile `android-debug`, package renamed to `forge.app.companion` so it installs beside the real Forge, as in earlier Companion slices; the authority patch to the manifest is put back after the build, never committed). android-maven-plugin's own signing fails on Java 21 (`PKCS7.encodeSignedData` NoSuchMethodError), so the APK is re-signed with the debug key (`zipalign` + `apksigner` from build-tools 35.0.0, verified).
   - `forge-gui-android/target/forge-companion-debug.apk`, 76,730,729 bytes (73.2 MiB); minSdk 26, **targetSdk 35** (Check first 5: no local network permission needed).
   - **Windows path:** `\\wsl.localhost\claude-dev\mnt\wsl\git\forge--companion-multi\forge-gui-android\target\forge-companion-debug.apk`. Install: copy it to the phone and open it (allow installs from that app), or `adb install -r <that path>` from Windows. Uninstall an older `forge.app.companion` first if Android says the signature differs.
8. **Turning the measurement on, on phone A.** The Forge user folder of `forge.app.companion` is `/storage/emulated/0/Android/obb/forge.app.companion/Forge/data/` on Android 11 and later (`/storage/emulated/0/Forge/data/` on Android 8–10, shared with the real Forge). Start the app once so the folder exists, then:
   ```
   adb shell "echo COMPANION_SHARE_MEASURE=true >> /storage/emulated/0/Android/obb/forge.app.companion/Forge/data/preferences/server.preferences"
   adb shell am force-stop forge.app.companion
   ```
   Start the app again. The lines: `adb logcat | findstr CompanionMeasure` (Windows) or `| grep CompanionMeasure`. The binding's lines: `findstr "Wi-Fi"`. A later line overrides an earlier one for the same key, so appending works even if the file already has the key, and the app's next save of the file keeps one line. To turn it off, append `COMPANION_SHARE_MEASURE=false` the same way and restart the app.

**[Phone] steps for the owner, in order** (the Oracle's [Phone] checks, with what to record):
1. Install the APK on phones A and B (7). On the newest phone, note the Android version (Check first 9).
2. Phone A: turn the measurement on (8). Set its screen timeout to 10 minutes.
3. Check first 1: Phone A on the no-internet router, Companion, "Share game…"; from a laptop on the same Wi-Fi, `nc -vz <phone A ip> 36744` (or `Test-NetConnection <ip> -Port 36744` in PowerShell). Repeat with phone A as the hotspot and the laptop on it.
4. Check first 2: phone B, mobile data on, on the no-internet Wi-Fi. Join by code. Record whether logcat shows `Bound the process to Wi-Fi network` and whether it connects. (Without the binding there's no switch in the app; the comparison is the old APK, or the slice-00 build, on the same Wi-Fi.)
5. Oracle [Phone] 1–5 (router, then phone A's hotspot). Record the tap-to-change time on phone B (stopwatch or slow-motion video): Check first 4.
6. Oracle [Phone] 6: dev jar on Windows hosts, phone B joins; phone A hosts, the dev jar joins. Note any decode error and its class: Check first 3.
7. Oracle [Phone] 7: 6-player Commander on phone A, phone B joins, ViewerLoad with 7 viewers (6). Add a card, cast a trigger spell, undo. Record from phone A's logcat: the `flush #n` lines (ms on the game thread, bytes per peer), the `full state to` lines and the undo's flush; and from ViewerLoad, the bytes per viewer.
8. Oracle [Phone] 8: the same with ViewerLoad at 11 viewers. Write down the safe number.
9. Oracle [Phone] 9 (newest phone, host then joiner; also on its own hotspot) and 10 (rendering on phone B).
10. Send the logcat captures and numbers back; they go into this section, with the fallback decision.

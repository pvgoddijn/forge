# Slice 09: Real networks

**Branch:** `feature/companion-multi-09-networks`, stacked on `feature/companion-multi-08-qr`. **Plan item:** section 9, M1-3, third part (`LocalAddresses` with interface names, keeping the screen on, the roster's addresses and takeover port, the M1 phone checks, the optional old-tablet check).
**Depends on:** 08 (QR with every address), 05 (roster, Phones list), 04 (`share.json`, reconnect), 01 (Wi-Fi binding).
**Proves:** M1 works at a real table: the host knows which network to tell people about, phones don't sleep mid-game, and every seated phone already stores what M3 will need to find a new host.

## Result
The share dialog names each address by its network: "Hotspot", "Wi-Fi" (with the network name where Android gives it), so the host can say "join my hotspot". Host and joined phones keep their screens on while a shared game is open. If a joined phone can't reach the host on a venue Wi-Fi that blocks devices from each other, it says so and suggests the host's hotspot. If the host's address changes, joined phones try the other addresses they know. Every seated phone's address and takeover port are kept in each phone's copy of the roster, for M3. M1 is then checked end to end on phones.

## Acceptance criteria
This slice completes the M1 phone checks the plan lists under M1-3:
- **AC-102** WHEN a user picks "Join game" in Companion and scans the QR code or types the join code THE SYSTEM SHALL connect to that shared game over the local network, without internet.
- **AC-106** WHEN the host's game changes THE SYSTEM SHALL show the change on every connected phone within 1 s on the same local network.
- **AC-107** WHEN viewing a shared game on a joined phone THE SYSTEM SHALL show the same overview as AC-23, live, with the phone's own seat at the bottom if it has one, and offer the history with the player filter (AC-28). _Phone legibility with 6 seats (plan 6)._
- **AC-111** WHILE a joined phone can't reach the host THE SYSTEM SHALL keep showing the last known state, clearly marked as disconnected, and SHALL reconnect automatically when the host can be reached again. A joined phone restarted while the host is unreachable SHALL show a text summary of the last saved state plus the history, not the board.

Spec assumption **B4**: the 1 s target is for a normal local Wi-Fi or hotspot with up to 8 phones.

## Design for this slice
Plan 2.2 (`LocalAddresses`, `ShareState`), 3.4 (interfaces named, screen kept on), 4.3 intro (roster carries addresses and takeover port), Risks ("Venue Wi-Fi isolates clients", "Hotspot address randomised", "Host address changes mid-game", "old tablet").

### `forge-gui`, `forge.gamemodes.companion.share`
- **`LocalAddresses`:** each usable address gets a kind: hotspot, Wi-Fi, Ethernet, other. On Android, from the interface name (`ap*`, `swlan*`, `wlan1` and similar for hotspot; `wlan0` for Wi-Fi) or, better, from a device-adapter hint if the name is unreliable (check first 1). Still no `FServerManager.getLocalAddress` (`FServerManager.java:631`, `:645-647`).
- **`CompanionShareHost` / messages:** `hello` gains the phone's own addresses and its **takeover port** (36744, or the next free one on that phone when it checked; nothing listens on it until M3). The roster carries them for each seated phone.
- **`ShareState`:** keeps the last roster with addresses and takeover ports, and the host's other addresses.
- **`CompanionClient`:**
  - Keeps the screen on while joined: `preventSystemSleep(true)` (`IDeviceAdapter.java:27`; Android `src/forge/app/Main.java:870`), `false` on leaving.
  - Retries the host's stored addresses, not only the one it joined by (Risks: "Host address changes mid-game").
  - A connect that times out on Wi-Fi where the host is a Wi-Fi address shows "This Wi-Fi may keep phones apart. Ask the host to turn on their hotspot and join that." (Risks: "Venue Wi-Fi isolates clients"). Only after a few failed tries, so a slow join doesn't show it.
- **`CompanionShareHost`:** keeps the screen on while sharing (the same call).

### `forge-gui-mobile`
- **`ShareDialog`:** one row per address with its kind ("Hotspot", "Wi-Fi: <name>"), its typed code, and the QR code for the first (with every address in `a=`).

## Not in this slice
- Anything that uses the takeover port or the roster addresses: **M3-1, M3-2**.
- LAN discovery: unplanned nice-to-have.

## Check first
1. **Interface names on the phones to hand:** with hotspot on, Wi-Fi on, both, and USB tethering, log what `NetworkInterface` reports on each. If names don't tell hotspot from Wi-Fi reliably, add an `IDeviceAdapter` hint with a default (Android: from `ConnectivityManager`/`WifiManager`) and say so here.
2. **Hotspot address per session:** turn the hotspot off and on; note whether the address changed. If it did, joined phones need a scan again (same token); the dialog says so.
3. **Screen on:** the joined phone's screen doesn't time out during a 10-minute shared game, and goes back to normal after leaving.

## Tests (CI)
None new: these are network and device checks (plan 6 lists phone checks for AC-102, 106, 111).

## Oracle
**Dev check** (two instances as in slice 00):
```
REPO=/path/to/forge; JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
for n in host join; do mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties; done
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) & (cd /tmp/companion-join && java -jar "$JAR" width=450 height=900) &
```
1. Host: share. OK: each address is labelled with its kind.
2. Joiner claims a seat. OK: the host's `share.json` (and the joiner's) list the joiner's addresses and takeover port in the roster.

**[Phone] checks** (the owner; at least two Android phones, more where available; a router with **no internet**; mobile data **on** on every phone):
1. **AC-102, router:** phone A hosts on the router's Wi-Fi; phone B joins by **typed** code; phone C (if any) by **scan**. OK: both join.
2. **AC-102, hotspot:** phone A turns on its hotspot and hosts; the dialog labels "Hotspot". Phone B joins the hotspot and scans. OK: joins.
3. **AC-106, timing:** with every available phone joined (aim for up to 8; the laptop's `ViewerLoad` from slice 01 can make up the number), on phone A: add a card; cast a spell that goes on the stack and wait before resolving; start an engine combat and declare attackers. Film the phones together in slow motion. OK: each change shows on every phone within 1 s, the stack and the attack included.
4. **AC-107, legibility:** 6 seats, phone B seated in seat 4. OK: phone B's own seat is at the bottom, highlighted, every board's life and hand count are readable, and the history's player filter works.
5. **AC-111:** phone B: Wi-Fi off for 30 s, then on. OK: the disconnected banner, then it catches up by itself. Phone B: background 2 minutes, then reopen. OK: it reconnects and catches up. Phone A: kill the app, then restart phone B while A is down. OK: phone B shows the text summary and the history; when A is back and shares again, B returns to the board by itself.
6. **Screen on:** leave all phones untouched for 5 minutes during a shared game. OK: no screen turns off.
7. **Venue-style Wi-Fi** (optional, if a network with client isolation is to hand, for example a guest Wi-Fi): OK: phone B shows the "may keep phones apart … hotspot" message.

**[Phone] optional, old tablet as spectator** (plan 9 M1-3, Risks: "An old tablet as a joined phone runs out of memory"):
1. An old tablet (API 26 or later) with this APK joins a **6-player Commander** game as a spectator ("Watch").
2. It follows a few turns, including a spell on the stack and an undo. OK: it follows each change; note how long each takes.
3. Restart the tablet's app. OK: it rejoins by itself.
4. Note: the tablet's RAM, Android version, the app's load time to the Companion menu, and whether the card database fit (no out-of-memory, no long freezes). FAIL here doesn't block the slice; the notes decide whether to recommend old tablets.

Write the results (interface names seen, timings, tablet notes) into a "Results" section of this file. With this slice, M1 is done.

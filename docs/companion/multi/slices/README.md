# Companion Mode, Multiple Phones: Slices

_ADLC phase 3 (Breakdown). Each bundle is self-contained: an implementer reads only that file, plus the code it names. [`../spec.md`](../spec.md) and [`../plan.md`](../plan.md) are the source if a bundle seems wrong. Fix the bundle rather than guessing._

Plan section 9 has eleven items (0–10). Four were too big for one slice (more than about 8 production files) and are split here; see "Splits" below. Bundles exist for slice 0, M1 and the final real-table test (15). M2 and M3 bundles are written when M1 has landed, and may be split again then.

| # | Plan item | Slice | User-visible result | Depends on | ACs |
|---|---|---|---|---|---|
| 00 | 0 (part 1) | [Walking skeleton, dev build](00-walking-skeleton.md) | A second dev instance types the host's code mid-game and watches the board live, through an undo | v1 slice 08 landed | 108; part of 101, 102, 105, 106, 107, 114 |
| 01 | 0 (part 2) | [Skeleton on real phones](01-skeleton-on-phones.md) | One Android phone hosts, another joins on a no-internet Wi-Fi with mobile data on; desktop and phone join each other; 8 and about 12 viewers measured (the safe number for 05's warning); the Android 17 local network check | 00 | part of 102, 106 |
| 02 | M1-1a | [The log follows](02-log-follows.md) | The joined phone has the history with its player filter, and an ended game lands in its past games | 01 | 113; part of 105, 107, 112, 114 |
| 03 | M1-1b (part 1) | [Steps follow](03-steps-follow.md) | The joined phone's copy holds every step, so its past game shows the real final board | 02 | part of 105, 112 |
| 04 | M1-1b (part 2) + the reconnect of M1-2 | [Reconnect and restart](04-reconnect-and-restart.md) | Joined phones mark "disconnected", reconnect by themselves for ever, and rejoin after a kill; a restarted host shares again | 03 | 111, 112 |
| 05 | M1-2 (part 1) | [Seats and the phones list](05-seats-and-phones.md) | Claim a seat or watch, own seat at the bottom, the host's Phones list with Remove, and a warning (no cap) when more phones join than slice 01 measured as safe | 04 | 103, 104, 109; rest of 107 |
| 06 | M1-2 (part 2) | [Host keeps serving](06-host-keeps-serving.md) | A host phone in the background keeps serving, with a notification | 05 | Q27; host part of 112 |
| 07 | M1-3 (part 1) | [Compatibility check](07-compatibility-check.md) | A phone whose build can't exchange games is refused, with the reason on both sides | 06 | 110 |
| 08 | M1-3 (part 2) | [QR code](08-qr-code.md) | The share dialog shows a QR code; Android phones scan it to join | 07 | QR part of 101, 102 |
| 09 | M1-3 (part 3) | [Real networks](09-real-networks.md) | Interfaces named in the share dialog, screens kept on, the roster carries addresses for M3; the M1 phone checks, and the optional old tablet | 08 | phone checks for 102, 106, 107, 111 |
| 10 | M2-1 | M2-1, ops without prompts (bundle later) | Seated phones log actions that need no prompt, with attribution, undo with confirmation, read-only | 09 | 117–121 |
| 11 | M2-2 | M2-2, the floor (bundle later) | Add by name from a phone with its target prompt on that phone; OK, End Turn; Paid on a mana payment prompt from the phone with the floor (routed to the host, which runs `InputPayMana.acceptManaAsPaid`); Take back the floor | 10 | 115, 116 |
| 12 | M2-3 | M2-3, every action (bundle later) | Capture, move, real life change, turn skip, engine combat with blocks on the defender's phone; Paid keeps working from the floor for every cost these raise | 11 | 115 |
| 13 | M3-1 | M3-1, manual takeover (bundle later) | "Take over as host", fresh engine from the latest step, new join code; a reachable old host follows | 12 | 122, 123, 127; 124 with the old host up |
| 14 | M3-2 | M3-2, finding the new host, and the stale host (bundle later) | Phones find the new host on roster addresses, else by code; a returning old host follows and lists its dropped actions | 13 | 124, 125 |
| 15 | 10 | [Real table](15-real-table.md) | A full 2-hour Commander game with 4–6 players on real phones, M2 and M3 included, and the players' feedback list; only then are the maintainers contacted | 14 | re-checks 102, 104, 106, 111–113, 115, 119, 120, 122–124 and Q27 at a real table |

Every M1 AC is covered by 00–09. The spectator warning (spec: no fixed limit on spectators, but the host is measured only up to about 12) is in **05**, next to the Phones list it decorates; its number comes from 01's Results. Bundle 05 doesn't list it yet: add it there before 05 is built. AC-108 is listed under M1-2 in the plan but its parts (`PeerGate` dropping everything, no acting items) are built in slice 0, so it's delivered in 00.

## Splits

- **Plan slice 0 → 00 + 01.** As one slice it touched about 20 production files. 00 is the dev-build path (Oracle 1); 01 adds what only real phones need (Wi-Fi binding, flushes on the game thread, the cross-runtime check and the 8-consumer measurement: Oracles 2 and 3). 00 is still about 16 files, most of them small. It isn't cut further: any thinner cut either shows nothing a user can see (engine plumbing alone) or ships a joined board that goes wrong on the first undo. 00 lists four build chunks that each compile.
- **Plan M1-1b → 03 + 04, and M1-2's unbounded reconnect moves to 04.** Steps (catch-up by hash) and restarts/reconnects are separate risks, and together they were about 11 files. AC-111 ("reconnect automatically") is claimed by M1-1b but its unbounded backoff was in M1-2; both now sit in 04, so AC-111 is delivered by one slice.
- **Plan M1-2 → 05 + 06.** The foreground service is Android-only (manifest, service class, API branches) and testable on its own.
- **Plan M1-3 → 07 + 08 + 09.** It was about 15 files across three unrelated risks: the fingerprint, QR, and real-network polish.
- **Close to the limit, not split:** 02 (about 9 files) and 05 (about 10, most a few lines each: messages, two screens, two match hooks). Each is one user-visible result; a cut would leave half a feature.
- **Pulled forward into 02:** a minimal `share.json` (role only) and "Stop following". Without it, a joined phone restarted between 02 and 04 would resume the followed copy as its own v1 game, which runs `loadState` and writes a settle `UNDO` into the copy (plan 0.1, decision 4).

## Stacking

- Each slice is a branch stacked on the previous one, starting from `feature/companion-multi`: slice 00 branches from `feature/companion-multi`, slice NN+1 branches from slice NN's branch.
- Branch names: `feature/companion-multi-<NN>-<short>`, `<NN>` the number above and `<short>` the name in the bundle's header. For example `feature/companion-multi-00-skeleton`, `feature/companion-multi-01-phones`.
- One merge request per slice, targeting the branch below it. When a lower one changes, rebase the ones above.

## Rules for every slice

- **Done locally:** it compiles, the slice's tests are green, v1's tests are still green (`TableEditorTest`, `CompanionStoreShould`), and its oracle was observed running, not inferred.
- **Gate A:** CI green on the fork (`test-build.yaml`, `test-android-build.yml`, `ios-compat-gate.yml`). Halt if red.
- **Gate B:** an independent reviewer, not the author, checks it against the spec.
- **No peers = v1 (AC-114):** every slice leaves an unshared game exactly as in v1.
- **Tests:** only the ones a bundle names, which are the ones the plan lists (plan 6). `CONTRIBUTING.md:85` asks agents not to add tests that aren't needed. No UI or wiring tests. Engine tests go in `forge-gui-desktop` next to `TableEditorTest`, reusing `forge-gui-desktop/src/test/java/forge/net/HeadlessNetworkClient.java` and `HeadlessNetworkGuiGame.java`.
- **Setup:** Forge's, not the workspace defaults (plan 8): Java 17 release level, Maven, no database, Netty and Java serialization as net play uses them, Forge's threads (no virtual threads; Android). Follow Forge's code style.
- **Not touched in any M1 slice:** `forge-game`, desktop Swing, `FServerManager`, `ServerGameLobby`, the lobby screens. In `forge/gamemodes/net/**` only the visibility, hook and threading changes a bundle names.
- **PRs:** note AI authorship. Nothing goes to Card-Forge/forge until the owner and the maintainers have agreed. The maintainers are contacted only after the real-table beta test (15), the owner's call. `session-insights.md` stays out of commits.
- **Phone checks:** marked **[Phone]**. The owner installs the APK and reports the result. A slice isn't done until they pass.
- **Two dev instances:** each with its own `userDir`, because host and joiner use the same game id (plan 6). Slice 00 confirms the recipe; every bundle repeats it.

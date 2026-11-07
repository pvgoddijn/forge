# Companion Mode, Multiple Phones: Spec

_ADLC phase 1 (Specify). This document is the **what**, not the how: no technology choices belong here. Those go in `plan.md`._

Status: **approved** · 2026-11-07 (assumptions B1–B6 accepted as defaults)

Follow-up to [Companion Mode v1](../spec.md). It delivers v1 spec §6 item 8, "Sync between several devices". `AC-n` (n < 100) refers to the v1 criteria.

## 1. Problem

In v1, one operator on one phone logs a whole paper game. The other players can't see the tracked state without looking at the operator's phone, and only the operator can log, so they become a bottleneck at a busy Commander table.

**Goal:** the other players at the table follow the same tracked game on their own phones. In the end, they can also log actions themselves.

## 2. Users and context

- **Host operator:** the person who starts the game, as in v1. Their phone holds the authoritative game.
- **Seated players:** up to 5 other players, each on their own phone and tied to their seat.
- **Spectators:** any number of extra phones that watch without a seat.
- **Setting:** the same as v1. There is often no internet, but the phones can share a local network: the venue's Wi-Fi or the host phone's hotspot.

## 3. Glossary

v1 terms still apply. New terms:

| Term | Meaning |
|---|---|
| **Shared game** | A Companion game that other phones have joined. |
| **Host** | The phone that holds the authoritative game. Every action goes through it. |
| **Joined phone** | Any phone in a shared game other than the host. |
| **Seated phone** | A joined phone that has claimed a seat (Player). That seat is its **Me**. |
| **Spectator** | A joined phone without a seat. Always read-only. |
| **Join code** | A short code, also shown as a QR code, that lets a phone join a shared game. |
| **Read-only phone** | A seated phone that the host has stopped from logging actions. |
| **Takeover** | A seated phone making itself the host. |
| **Attribution** | Which phone logged an action. |

## 4. Scope

Delivered in three milestones. Each ships on its own.

- **M1, follow along (read-only):** hosting, joining by code, a live view of the full game and history on every joined phone, every phone keeping its own saved copy, reconnecting, and kicking a phone.
- **M2, act together:** seated phones log actions (including capture and add-by-name), with attribution, the shared undo rule, and the host making a phone read-only.
- **M3, takeover:** a seated phone takes over the host role.

Design constraint across all milestones: from M1 on, every joined phone keeps the **complete** game, history included, so M3 needs no change to what M1 stores.

## 5. Acceptance criteria (EARS)

### 5.1 M1: hosting and joining
- **AC-101** WHILE a game is active THE SYSTEM SHALL let the operator share it, which shows a join code as text and as a QR code.
- **AC-102** WHEN a user picks "Join game" in Companion and scans the QR code or types the join code THE SYSTEM SHALL connect to that shared game over the local network, without internet.
- **AC-103** WHEN a phone joins THE SYSTEM SHALL let it claim one unclaimed seat or join as a spectator. A seat can be held by only one phone, and the host's phone holds the host operator's own seat.
- **AC-104** THE SYSTEM SHALL allow up to 6 seated phones (the host included) and SHALL NOT set a fixed limit on spectators.
- **AC-105** WHEN a phone joins or reconnects THE SYSTEM SHALL give it the full current game and the full history, undone entries included.
- **AC-106** WHEN the host's game changes THE SYSTEM SHALL show the change on every connected phone within 1 s on the same local network.
- **AC-107** WHEN viewing a shared game on a joined phone THE SYSTEM SHALL show the same overview as AC-23, highlight the phone's own seat if it has one, and offer the history with the player filter (AC-28).
- **AC-108** WHILE in M1 THE SYSTEM SHALL keep every joined phone read-only.
- **AC-109** THE SYSTEM SHALL let the host see which phones are connected, and which seat each holds, and SHALL let the host remove a phone from the game.
- **AC-110** WHEN a phone runs a different, incompatible version of the app THE SYSTEM SHALL refuse the join and tell both sides why.

### 5.2 M1: connection loss and persistence
- **AC-111** WHILE a joined phone can't reach the host THE SYSTEM SHALL keep showing the last known state, clearly marked as disconnected, and SHALL reconnect automatically when the host can be reached again.
- **AC-112** THE SYSTEM SHALL save the complete shared game on every joined phone as it changes, so that a joined phone that is closed, killed or restarted resumes the game where it left off and rejoins automatically.
- **AC-113** WHEN the host ends the game THE SYSTEM SHALL end it on every joined phone and keep it in their past games, read-only (AC-30).
- **AC-114** WHILE no other phone is connected THE SYSTEM SHALL let the host keep logging exactly as in v1, without a network.

### 5.3 M2: acting together
- **AC-115** WHILE connected to the host THE SYSTEM SHALL let a seated phone that isn't read-only perform every v1 action (AC-14 to AC-25, AC-27), on any player, including capture and identification on that phone itself.
- **AC-116** WHEN a seated phone adds a card THE SYSTEM SHALL target the active player by default, as in AC-17 (see B6).
- **AC-117** WHILE a joined phone can't reach the host THE SYSTEM SHALL disable its actions, with no offline queue.
- **AC-118** WHEN actions from several phones arrive THE SYSTEM SHALL apply them in the order the host receives them, and SHALL NOT merge or reject actions as duplicates.
- **AC-119** WHEN any action happens THE SYSTEM SHALL record which phone logged it, and show that attribution in the history (extending AC-26).
- **AC-120** WHEN any phone taps undo THE SYSTEM SHALL revert the most recent action of the whole game that hasn't been undone (AC-27). WHEN that action was logged by another phone THE SYSTEM SHALL first show who logged it and what it was, and ask for confirmation.
- **AC-121** THE SYSTEM SHALL let the host switch any seated phone between read-only and acting. Spectators are always read-only.

### 5.4 M3: takeover
- **AC-122** THE SYSTEM SHALL offer a "Take over as host" option on every seated phone, at any time.
- **AC-123** WHEN a phone starts a takeover THE SYSTEM SHALL check which reachable phones have newer actions than it does, fetch any missing actions from them first, show what was caught up, and only then become host.
- **AC-124** WHEN a takeover completes THE SYSTEM SHALL make every reachable phone, the old host included, follow the new host without needing anyone's approval.
- **AC-125** WHEN a former host reconnects after a takeover with actions the new host doesn't have THE SYSTEM SHALL drop those actions from the game, follow the new host, and show its operator the list of dropped actions so they can be logged again.
- **AC-126** WHEN two phones take over at about the same time THE SYSTEM SHALL let the later takeover win, and the other phone SHALL follow it, applying AC-125.
- **AC-127** WHEN a phone that has become host has no peers THE SYSTEM SHALL behave as AC-114.

## 6. Out of scope (later)

1. Joining from a browser without the Forge app.
2. Device-to-device connections without a shared network (no router or hotspot).
3. Internet play or cloud relay.
4. Private per-player information (notes, hidden views). Every phone sees the same information.
5. Merging actions logged offline by joined phones (AC-117 disables them).
6. Spectators taking over as host.
7. Automatic duplicate detection.

Nice-to-have, if cheap: desktop Forge as a joined phone ("table display"), and automatic discovery of games on the local network.

## 7. Constraints

All v1 constraints still apply: upstream contribution, open-source GPLv3-compatible dependencies, and AI disclosure. In addition:

- **No internet or vendor service** is used for anything in this feature.
- Build starts after the last v1 slice (currently slice 08) has landed. Spec and plan can be written now. Breakdown waits until the v1 action types are stable.

## 8. Assumptions to confirm

- **B1** Anyone on the local network who has the join code can join. There are no accounts or passwords, and the host can remove a phone (AC-109).
- **B2** "Incompatible version" (AC-110) means a different game-log format or card database version, not any difference in app version.
- **B3** A removed phone may rejoin with the code. Blocking it permanently is out of scope.
- **B4** The 1 s update target (AC-106) is for a normal local Wi-Fi or hotspot with up to 8 phones.
- **B5** A seated phone that leaves frees its seat for another phone. The seat's player stays in the game.
- **B6** The default target player stays the active player (AC-17) on every phone, not the phone's own seat. Most cards are played by the active player, so that default holds for everyone at the table.

## 9. Decision log

| # | Decision |
|---|---|
| M-Q1 | Same information on every phone. A phone may claim a seat, which highlights it. Spectators are allowed. |
| M-Q2 | Local network only: venue Wi-Fi or the host's hotspot. Device-to-device and internet later. |
| M-Q3 | The Forge app is required on joined phones. A browser listener comes later. Desktop Forge as a display is a nice-to-have. |
| M-Q4 | Join by QR code or short code, with no approval step. The host can remove phones. Discovery is a nice-to-have. |
| M-Q5 | The host is the authority. Joined phones show the last state when disconnected and reconnect automatically. Explicit takeover with a freshness check comes as M3. Every phone keeps the full game. |
| M-Q6 | Joiners get the full history and current state. |
| M-Q7 | Joined phones save the game: the live game, and the ended game in past games. |
| M-Q8 | 6 seated phones, any number of spectators. |
| M-Q9 | One spec, three milestones. Interaction and takeover are decided now because they shape M1's design. |
| M-Q10 | Build after v1 slice 08. Docs in `docs/companion/multi/`. |
| M-Q11 | In M2, seated phones can act on any player. The host can make a phone read-only. Spectators are read-only. |
| M-Q12 | One phone per seat. |
| M-Q13 | Actions apply in the order the host receives them. No duplicate detection, and attribution plus undo fix mistakes. |
| M-Q14 | Undo reverts the last action of the whole game, with a confirmation when another phone logged it. |
| M-Q15 | History shows who logged each action. |
| M-Q16 | Seated phones capture and identify cards themselves. |
| M-Q17 | No actions while disconnected from the host. |
| M-Q18 | Any seated phone may take over at any time, and the old host follows. |
| M-Q19 | Takeover first fetches missing actions from fresher peers. |
| M-Q20 | A returning old host's unshared actions are dropped and listed for its operator. The later takeover wins. |
| M-Q21 | A host with no peers works exactly as in v1. |

## 10. Notes for Plan (not part of the spec)

The existing Forge network play was surveyed on 2026-11-05. It is **not** reusable as a protocol:
- It is tied to the rules engine (`HostedMatch` / `GameView`).
- It allows one connection per seat, with no remote spectators.
- Its wire format is Java serialization of Forge classes.
- It blocks on remote prompts.

What is reusable is its libraries (Netty, which has HTTP and WebSocket codecs, and Gson are already dependencies) and the idea of "full state on join, then changes". Companion's action log in JSON lines is a natural wire format for this.

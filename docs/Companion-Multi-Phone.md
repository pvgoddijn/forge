# Chapters
- [What It Is](#what-it-is)
- [What You Need](#what-you-need)
- [Hosting a Game](#hosting-a-game)
  - [Share the game](#share-the-game)
  - [The join code and QR code](#the-join-code-and-qr-code)
  - [Connected phones](#connected-phones)
  - [The notification while sharing](#the-notification-while-sharing)
  - [Stop sharing](#stop-sharing)
  - [Remove a phone](#remove-a-phone)
  - [Make a phone read-only](#make-a-phone-read-only)
- [Joining a Game](#joining-a-game)
  - [Scan or type the code](#scan-or-type-the-code)
  - [Claim a seat or watch](#claim-a-seat-or-watch)
  - [What you see](#what-you-see)
  - [Leaving](#leaving)
  - [When the game ends](#when-the-game-ends)
- [Acting Together](#acting-together)
  - [What a seated phone can do](#what-a-seated-phone-can-do)
  - [Whose phone the questions appear on](#whose-phone-the-questions-appear-on)
  - [Paying costs: Paid](#paying-costs-paid)
  - [Undo](#undo)
  - [Take back the floor (host)](#take-back-the-floor-host)
  - [If your phone disconnects or you switch apps](#if-your-phone-disconnects-or-you-switch-apps)
  - [Two people logging the same thing](#two-people-logging-the-same-thing)
- [When Things Go Wrong](#when-things-go-wrong)
  - [Disconnected and reconnecting](#disconnected-and-reconnecting)
  - [Your phone restarted while the host was away](#your-phone-restarted-while-the-host-was-away)
  - [The host phone restarts](#the-host-phone-restarts)
  - [The host phone dies: Take over as host](#the-host-phone-dies-take-over-as-host)
  - [The old host comes back](#the-old-host-comes-back)
  - [Another phone is also hosting this game](#another-phone-is-also-hosting-this-game)
  - [The host was also the hotspot](#the-host-was-also-the-hotspot)
- [Troubleshooting and FAQ](#troubleshooting-and-faq)
- [Limits](#limits)
---

# What It Is

Companion Mode lets one phone track a paper Magic game. With multi-phone Companion, the other players at the table follow that same game live on their own phones, and can log actions themselves instead of waiting for the person holding the tracking phone.

One phone is the **host**. It holds the real game, and every action goes through it. Every other phone is a **joined phone**: either **seated** (tied to one player at the table) or a **spectator** (watching, never acting).

This page assumes you already know how to start and log a Companion game on one phone.

<!-- TODO verify: link to the v1 Companion user page once one exists; none is in docs/ yet. -->

---

# What You Need

| Requirement | Details |
|---|---|
| **App** | Forge on every device, Android 8.0 or newer. Nothing is joined from a web browser. |
| **Same Forge build** | Every device must run a Forge build that can exchange games with the host's. In practice: install the same Forge release on every device. See [The host refused my phone: "Forge build is different"](#the-host-refused-my-phone-forge-build-is-different). |
| **A shared local network** | All devices on the same Wi-Fi, or connected to the host phone's hotspot. |
| **Internet** | Not needed. Nothing goes over the internet or through any online service. |
| **Players** | Up to 6 seated phones (the host included), one per player. Any number of spectators. |

**Old tablets** make fine spectators, as long as they run Android 8.0 or newer. A joined device runs the full Forge app with its card database, so memory, not the Android version, is what may stop a very old tablet.

<!-- TODO verify: a minimum amount of RAM for an old tablet spectator (plan M1-3's optional old-tablet check has no recorded result yet). -->

**A laptop as a big-screen spectator.** A laptop can join as a spectator or seat using Forge's mobile interface on desktop: run the Adventure Mode program in your Forge folder and pick the Classic Mobile UI (see [Play Adventure Mode on Desktop](User-Guide.md#play-adventure-mode-on-desktop)). It can't scan QR codes, so type the code instead.

<!-- TODO verify: that a desktop release build of the mobile UI passes the compatibility check against Android phones of the same release (plan Risks: "Card database fingerprint differs between desktop dev and phone builds of one release"). -->

---

# Hosting a Game

The host is usually whoever started the Companion game. While no other phone is connected, the host logs exactly as in single-phone Companion, with or without a network.

## Share the game

1. Start or resume a Companion game as usual.
2. Open the game menu and pick **Share game…**.
3. The **Share game** dialog opens with the join code.

If the phone isn't on any local network, there's no code to give out. Forge says:

> This device isn't on a local network, so there's no code to join with. Join a Wi-Fi network or turn on a hotspot, then open Share game… again.

If you want to host on your own hotspot, turn the hotspot on first, then pick **Share game…**.

<!-- screenshot: the Share game dialog with QR code, typed codes per network, and the Phones list -->

## The join code and QR code

The dialog shows a **QR code** and a **typed code** of 16 letters and digits, like `ABCD-EFGH-JKMN-PQRS`.

- The others scan the QR code, or type the code if they can't scan.
- If the host phone is on more than one network (for example its own hotspot and a Wi-Fi), the dialog shows one row per network, named **Hotspot** or **Wi-Fi: \<network name\>**, each with its own code. Tell the others which network to join, for example "join my hotspot". The QR code covers every network at once.
- The code lasts for the whole game. It stays the same if the host restarts, unless the host phone's address changes (for example a new hotspot session) or Forge had to pick a different port; then the dialog shows the new code.

## Connected phones

The dialog shows **Devices connected:** with a count, and a **Phones** list. Each row has:

- the phone's name (the joining player can set it when joining; it defaults to the phone's model),
- its seat, or **watching** for a spectator,
- whether it's connected right now,
- **Remove**, and (for seated phones) the read-only switch.

The host's own seat always belongs to the host. It's never offered to other phones.

## The notification while sharing

While a game is shared, the host phone shows a notification: **Sharing a Companion game**. Tapping it brings you back to Forge.

The notification is there so Android keeps Forge running while you check another app or lock the screen. Without it, Android freezes apps in the background, and every joined phone would stop updating. With it, you can switch away for a few minutes: the other phones stay live, and whatever you log when you come back reaches them at once.

On Android 13 and newer, Forge asks for permission to show notifications the first time you share. If you say no, sharing still works; you just don't see the notification.

Joined phones never show this notification. Only the host (or a phone that takes over as host) does.

While a game is shared or joined, Forge keeps the screen on.

## Stop sharing

Pick **Stop sharing** in the Share game dialog. Sharing also stops when you leave the match or end the game. Joined phones then show that they've lost the host.

<!-- TODO verify: what joined phones show after Stop sharing mid-game: the disconnected banner (they keep retrying) or a clear "the host stopped sharing" notice. Sources only define "The host stopped sharing before the game arrived." for a join in progress. -->

## Remove a phone

In the **Phones** list, tap **Remove** next to the phone and confirm.

- The removed phone leaves the game with **The host removed this phone**. It keeps its copy of the game in its past games, read-only.
- Its seat becomes free. The player in that seat stays in the game; only the phone is gone.
- A removed phone can join again with the same code. There's no way to block a phone for good.

## Make a phone read-only

In the **Phones** list, switch a seated phone to read-only. A read-only phone keeps following the game but can't log anything. Switch it back to let it act again.

Spectators are always read-only.

<!-- TODO verify: exact label of the read-only switch in the Phones list (M2-1 bundle not written yet). -->

---

# Joining a Game

You can only follow one Companion game at a time. If your phone has its own unfinished Companion game, Forge asks you to end it before you join.

## Scan or type the code

1. Open Forge, choose **New Game**, then **Companion**.
2. Tap **Join game**.
3. Either tap **Scan** and point the camera at the host's QR code, or type the code and tap **Join**.
   - Capitals and dashes don't matter.
   - **Scan** is only on devices with a camera. On a laptop, type the code.
4. Forge shows **Connecting to …** and then **Connected. Waiting for the game: the host sends it at its next prompt.**

If the host is in the middle of a choice (picking a target, answering a "may"), your phone waits until the host has answered. That usually takes a few seconds.

<!-- screenshot: Join game screen with code field, Scan and Join -->

## Claim a seat or watch

After connecting, pick one of the free seats, or **Watch** to be a spectator.

- Each seat can be held by one phone only. If someone else claims it first, the list shows again.
- When every seat is taken, only **Watch** is offered.
- If you leave and come back, claim your seat again.

## What you see

- **The board, live.** The same overview as on the host: every player's life, hand count, known cards in hand, battlefield, and one tap to graveyard, exile and command zone. Changes show up within about a second.
- **Your own seat at the bottom**, highlighted, with the other seats following in turn order. Spectators see the host's seat at the bottom.
- **The stack and combat as they happen**: spells waiting to resolve, triggers, attackers and blockers.
- **History**, from the game menu: every action of the game from the start, even if you joined late. Undone actions are marked **undone**. Filter by player with **Everyone** or a player's name. Each entry also shows which phone logged it, for example "· Anna's phone".

Everyone sees the same information. Cards known to be in a player's hand show on every phone, just as on the host.

## Leaving

Pick **Leave** in the game menu. You get **You left the shared game.** If you held a seat, it becomes free for another phone; the player stays in the game.

## When the game ends

When the host ends the game, your phone leaves the match screen with **The host ended the game**. The game stays in **Past games** on your phone, read-only, with its final board and full history, just like a game you tracked yourself.

Your phone keeps a complete copy of the game the whole time, saved as it changes. If Forge is closed or the phone restarts mid-game, opening Companion takes you straight back into the game.

If your phone missed the end of the game (it was off when the host ended it, and the host has since left Companion), Companion offers **Stop following**. That moves the game to your past games.

---

# Acting Together

## What a seated phone can do

A seated phone that isn't read-only can do everything the host can, for any player at the table, not just its own seat:

- add a card by name, or photograph it with its own camera,
- move a card, fix a card logged for the wrong player,
- change life (including "real gain or loss?"), ready creatures,
- the combat and commander switches,
- OK, End Turn, and skipping a turn,
- undo.

When you add a card, the target player starts as the **active player**, as on the host, not as your own seat. Change it before confirming if needed.

Your photo settings (such as auto-add) are your phone's own; they don't change anyone else's.

Spectators and read-only phones can't do any of this. And no phone can act while it's disconnected from the host: the actions are greyed out, and nothing is saved up to send later.

## Whose phone the questions appear on

The game still asks questions while a spell or ability resolves: targets, modes, "may" choices, OK. These questions open on **the phone that logged the action**. If Anna logs Lightning Bolt on her phone, Anna picks the target on her phone.

- While Anna's phone is handling its action, the host's prompt bar says **Anna's phone is acting…**. The host's board stays live, but the host's own taps wait.
- Later questions from the stack (for example a trigger that resolves after Anna's spell) also go to the last phone that acted, until the stack is empty and the game is back to a quiet main phase.
- **Blocks:** when an attack is declared, the question "which creatures block?" opens on the **defending player's phone**, if that phone is connected and allowed to act. Otherwise it opens on the attacking phone.
- Messages about an action (such as "card added") go to the phone that logged it.

<!-- TODO verify: exact host prompt-bar text while a phone acts; plan 4.2 gives "Anna's phone is acting…" as an example. -->

## Paying costs: Paid

At the table, the players already paid with their real lands, so tapping lands again in Forge is busywork. When a mana payment prompt is open, the prompt bar's middle button (normally **Add card**) reads **Paid**, beside **Auto**. Tap **Paid** to take the mana as paid without tapping anything.

Only the mana is skipped. Other parts of the cost still happen: {T}, sacrificing, paying life. The spell or ability then goes on the stack and resolves as usual.

On a seated phone, **Paid** appears when the payment prompt is on your phone, that is, for an action you logged.

<!-- TODO verify: that Paid is available on a seated (joined) phone. The Paid button (branch feature/companion-skip-payment) postdates the multi-phone plan, and plan 4.2 lists no op or remote path for it. -->

## Undo

**Undo** in the game menu takes back the **last action of the whole game**, whoever logged it, not just your own last action.

- If someone else logged that action, Forge first shows who and what, for example **Logged by Anna's phone: Cast Lightning Bolt. Undo it?**, and asks you to confirm.
- If something else was logged while you were deciding, the undo is refused: **Something else happened first: check the history.** Look at the history and try again if it's still what you want.
- Undone actions stay in the history, marked **undone**.

Undo can make other phones' boards redraw. If you tried to move a card at that exact moment, you may see **the board changed: try again**.

## Take back the floor (host)

If a phone is stuck on a question (the player went to get a drink, or the phone is frozen), the host can tap **Take back the floor** on its prompt bar. The question moves to the host's screen and the host answers it. The other phone is told **The host took back the floor**.

There's no automatic time limit. This button is how the host gets the table going again.

<!-- TODO verify: final label of the host's button ("Take back the floor" in plan N8; the task brief calls it "Take back"). -->

## If your phone disconnects or you switch apps

- **Disconnect in the middle of a question:** the question moves to the host's screen, and the host answers it. Nothing is answered for you with a blank.
- **Switch to another app** while a question is on your phone: you have about 10 seconds to come back. After that, the question moves to the host as if you'd disconnected. You keep following the game either way.

## Two people logging the same thing

Forge applies actions in the order the host receives them. It doesn't spot duplicates: if two players both log the same Sol Ring, there will be two Sol Rings.

To fix it, open the **History**, see which phone logged what, and undo the extra one. Agreeing at the table who logs what (for example "everyone logs their own spells") avoids most of this.

---

# When Things Go Wrong

## Disconnected and reconnecting

If your phone loses the host (Wi-Fi drops, the host walks away, the host phone restarts), your board stays on screen with a **reconnecting** banner, usually within about 15 seconds. Your phone keeps trying for as long as you're in the game. When the host is back, the board catches up by itself.

While disconnected you can still look at the board and the history, but you can't act.

## Your phone restarted while the host was away

If you open Companion and the host can't be reached, you see a **text summary** of the last saved board and the history, not the full board. Forge keeps trying in the background and opens the board as soon as it connects. **Stop following** ends the game on your phone and moves it to past games.

## The host phone restarts

If the host phone restarts or Forge is closed on it, reopen Companion on the host. The game resumes and is shared again with the same code, and the other phones reconnect by themselves.

If Forge had to use a different port, the code changes; the host is told, and the others need the new code.

<!-- TODO verify: exact host notice when the code changes after a restart (slice 04: "say so on the host"). -->

## The host phone dies: Take over as host

If the host phone runs out of battery or breaks, any **seated** phone can become the new host. Spectators can't.

1. Open the game menu and pick **Take over as host**.
2. Read the confirmation and confirm.
3. Your phone continues the game from its own copy and shows a new join code. It shows the **Sharing a Companion game** notification, like any host.

You don't need to wait for the old host to be gone. You can also use this to hand over hosting, for example when the host's battery is low: if the old host is still connected, it follows the new host by itself.

<!-- TODO verify: whether a read-only seated phone may take over (spec AC-122 says "every seated phone"). -->

**What's lost in a takeover.** The new host restarts from the last **settled** moment its copy has: a main phase with an empty stack. Anything not yet settled is lost:

- a spell or ability still on the stack,
- a combat in progress,
- skipped turns and passes that were waiting,
- this turn's tallies, such as life gained or lost this turn, and the commander damage counts.

Actions logged after that settled moment show in the history as **undone**. Also, if your phone was disconnected before taking over, actions the old host logged after your phone dropped are missing; the confirmation warns you: **Phones that saw later actions will lose them**.

Re-log whatever was lost.

<!-- TODO verify: "commander damage" in the lost list. Plan 4.3 step 8 says the load resets "the life-this-turn tallies and commander damage"; plan N5 says only "the tallies a load resets". Confirm whether commander damage totals really reset on a takeover. -->

**The other phones** follow the new host by themselves, without anyone approving. If a phone can't find the new host (its address or port changed), it keeps the reconnecting banner and offers **Join by code**: type or scan the new host's code.

<!-- screenshot: the Take over as host confirmation -->

## The old host comes back

If the old host phone comes back (recharged, restarted), it notices that another phone has taken over, stops hosting, and follows the new host. If it had logged actions that the new host never got, those are dropped from the game, and the old host's operator sees a **list of the dropped actions** so they can be logged again.

## Another phone is also hosting this game

If two phones take over at about the same time, both become host. Forge doesn't settle this by itself. Phones that see both show **Another phone is also hosting this game**.

Agree at the table which phone stays host. On the other phone, join the remaining host with its code. Actions only the other phone had are listed for its operator to log again.

## The host was also the hotspot

If everyone was connected to the host phone's hotspot and that phone dies, the network dies with it. No phone can reach any other, so no takeover is possible. The take-over confirmation says so.

To avoid this at a long game, plug the host phone in, or use a network that doesn't depend on the host: the venue's Wi-Fi, a travel router, or a hotspot from a spare phone that isn't the host.

---

# Troubleshooting and FAQ

## My phone can't reach the host

Forge says **Couldn't reach the host at …. Check that both devices are on the same network and that the game is still shared.** Check:

- both devices are on the **same** network (the same Wi-Fi name, or the host's hotspot),
- the host still has the Share game dialog open or sharing turned on,
- you used the code for the network you're on, if the host shows more than one.

**Store or public Wi-Fi often keeps devices from seeing each other.** If Forge says **This Wi-Fi may keep phones apart. Ask the host to turn on their hotspot and join that.**, do exactly that: the host turns on their phone's hotspot, everyone joins it, and the host shares again.

**Mobile data on a hotspot with no internet.** On a Wi-Fi or hotspot without internet, Android prefers mobile data. Forge sends the game over the Wi-Fi anyway, so you can leave mobile data on.

<!-- TODO verify: whether turning mobile data off is worth suggesting as a last resort if a join still fails on a no-internet network (not in the sources; the plan relies on Forge binding to the Wi-Fi). -->

## "That isn't a join code"

A code has 16 letters and digits, like `ABCD-EFGH-JKMN-PQRS`. Check for a missing or extra character.

## "The host refused the code"

The code is from an earlier share, or a character is wrong. Ask the host for the current code, or scan the QR code.

## "The host's game didn't arrive"

The host is probably in the middle of a choice. Wait until the host has answered it, then try again.

If you see **The host stopped sharing before the game arrived.**, ask the host to share again.

## The host refused my phone: "Forge build is different"

Joined phones and the host exchange the game in a format that depends on the exact Forge build. If two builds can't read each other's games, the join is refused, with the reason on both phones: the joining phone shows **The host's Forge build is different** (its game view, or its card database), with the host's build; the host shows **A phone was refused** and why.

Install the same Forge release on every device. Two builds that look the same (for example two development builds) can still be refused if they differ inside.

## My battery drains fast

Sharing keeps the screen on, and the host also serves every phone (and maybe a hotspot). Tips:

- plug the host phone in, especially if it's also the hotspot,
- turn the screen brightness down on joined phones,
- spectators can **Leave** when not watching and join again later; their phone catches up on what they missed.

## Who can see the game? Is it private?

- **Everyone sees the same information**, including known cards in hand. There are no private notes or hidden views.
- **Anyone on the same network who has the code can join**, with no password or approval. A QR code on the table can be scanned by anyone nearby.
- The host can see every connected phone in the **Phones** list and **Remove** any of them. A removed phone can rejoin with the code.
- Nothing is sent over the internet.

## Can I use my phone for my own Companion game while following one?

No. A phone has one active Companion game at a time. End your own game before joining, and leave or finish the shared one before starting your own.

---

# Limits

These are not supported:

- **Joining from a web browser.** Every device needs the Forge app.
- **Internet play.** The phones must share a local network (Wi-Fi or a hotspot). There's no online relay.
- **Phones talking directly without a network.** Someone has to provide a Wi-Fi or hotspot.
- **Acting while disconnected.** A joined phone can't log anything while it can't reach the host, and nothing is queued for later.
- **Private information per player.** Every phone sees the same game.
- **Automatic duplicate detection.** Use the history and undo.
- **Spectators taking over as host.** Only seated phones can.
- **Automatic takeover safety.** Taking over doesn't check other phones for newer actions first, and two takeovers at once aren't settled automatically.
- **iOS.** Multi-phone Companion is built and tested for Android. <!-- TODO verify: whether iOS builds can join by typed code (plan: iOS untouched, typed code only, not tested). -->
- **Desktop Forge's classic window** as a table display. Use the Classic Mobile UI on desktop instead (see [What You Need](#what-you-need)).
- **More than 6 seated phones.** Extra devices can watch.

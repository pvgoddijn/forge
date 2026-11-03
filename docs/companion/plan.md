# Companion Mode — Plan

_ADLC phase 2 (Plan). This is the **how** for the approved [`spec.md`](spec.md). `AC-n` refers to its acceptance criteria._

Status: **approved** · 2026-10-31 · **revised 2026-11-03 for spec Q20 (rules engine), awaiting owner review**

## 0. What changed on 2026-11-03

The owner asked for Forge's rules engine behind the tracker, so life totals and ETB effects happen by themselves (spec Q20). The first plan kept the engine out and replayed our own action log into synthetic views. This revision runs a **real `Game`**. The operator's actions are fed into it as engine operations, and the screen is Forge's own match screen.

A throwaway headless proof (2026-11-03, `AITest` harness, AI controllers, not committed) showed that the engine already does the hard part:

| Logged by the operator | Engine result |
|---|---|
| A hidden card leaves the hand, then Gray Merchant of Asphodel is cast for free | Both opponents 20 → 18, caster 20 → 24 |
| Elvish Visionary cast for free | ETB draws an unknown card from the placeholder library, so the hand count is right |
| Lightning Bolt cast for free | The target took 3 |
| Pass to the next turn | The next player's draw step raised their hand 7 → 8 |

The proof didn't exercise human prompts, the priority loop or the GUI. It also showed a gap: Forge's `GameState` save format can't recreate a placeholder card (section 2.3).

An independent Opus review of the first draft of this revision (same day, verdict "sound with changes") corrected four of its claims: undo, the operator-action thread, `MatchScreen`'s layout, and where `PlaySpellAbility` lives. Its findings are folded in below.

What survives from slices 0 and 2: `CardNameMatcher`, `CardSearchDialog`, the setup screen, the menu entry, and what we learned about `VPlayerPanel`. What goes: `CompanionAction`, `CompanionState`, `CompanionGame`, the JSONL replay in `CompanionStore`, and the synthetic views in `CompanionBoard`/`CompanionController`. The engine now holds the state.

## 1. Approach

```
 Companion screens ──► operator-op queue ──► CompanionPlayerController (forge-gui, extends PlayerControllerHuman)
   (setup, search,                              │ runs ops on the game loop thread, auto-pass policy,
    MatchScreen)                                │ keep hand, no attackers, unlimited lands, name hidden cards
                                                ▼
                                   TableEditor ──► real Game ──► after each settled op: GameState snapshot + log line
```

Key decisions:

1. **One real `Game` per tracked game, hosted by `HostedMatch` and shown on `MatchScreen`.** Every seat is a local `CompanionPlayerController` on the one mobile GUI. `HostedMatch` already opens a single view for them (`HostedMatch:219-264`). Mobile panels don't move when the acting player changes (`MatchController:124-130`), and every local hand is visible (`AbstractGuiGame:270`), which is what the operator needs. The seat list is rotated to start at Me, so Me is the bottom panel and turn order is unchanged.
2. **Companion behaviour lives in a controller subclass and in effect cards, not in a rules flag.** The precedents are `WatchLocalGame extends PlayerControllerHuman` and `LobbyPlayerHuman.createIngamePlayer`. Existing hooks cover nearly everything:
   - `canPlayUnlimitedLands` (`PlayerController:402`), `mulliganKeepHand` (`:300`), `chooseStartingPlayer` (`:261`)
   - `declareAttackers`: no attack unless the operator has turned combat on for the game (Q21, AC-41). When it's on, Forge's own attack and block prompts are used for every seat. The setting lives in `setup.json`, and a game-menu item switches it.
   - `chooseCardsToDiscardToMaximumHandSize`
   - costs and timing: a free cast from a play effect already waives them (`PlaySpellAbility:683`, `SpellAbility:2610`)

   A Companion effect card per player gives "no maximum hand size" (`SetMaxHandSize$ Unlimited`) and "can't lose the game" (a `GameLoss` replacement, as on Platinum Angel). An accidental 0 life or empty library therefore can't end the game irreversibly. The game loop exits on game over, and the operator ends the game explicitly (AC-30).
3. **Unknown cards are one Companion-owned rules-less `PaperCard`, never in `CardDb`.**
   - Hands and libraries are built from a placeholder deck through the normal `Match.prepareAllZones` and opening draw.
   - Hidden hand count = placeholders in hand, and known cards = real cards in hand, so draws, discards and bounces follow the rules.
   - There's no core `isHiddenPlaceholder` flag: a placeholder is recognised by being that `PaperCard`.
   - When an effect would make a placeholder public (a discard, mill, cleanup, or a search such as Cultivate), the controller asks the operator to name the card.
4. **Operator actions are queued and run on the game loop thread.**
   - `GameAction.invoke` isn't used, because it would start a parallel pool thread (`ThreadUtil:43-52`) and skip state-based actions and priority bookkeeping.
   - Instead an op ends the current prompt with `InputPassPriority.restartPrompt()`, the same way undo does (`PlayerControllerHuman:1779-1782`).
   - The controller's `chooseSpellAbilityToPlay` then runs the queue and returns no ability, so `mainLoopStep` prompts again.
   - The point after the queue runs is the **settled point** for snapshots.
5. **Priority policy** (in the controller, not the yield controller or phase stops, which are user preferences):
   - non-active seats pass, unless the operator has armed "respond as …" for a seat
   - the active seat stops in main phase with an empty stack, **and** whenever the stack isn't empty, so the operator can add a response (AC-38) or tap OK to resolve
   - "Next turn" sets a flag that passes until the next turn's first main phase, so end-step, untap, upkeep and draw effects resolve on the way (AC-37)
6. **Persistence and undo share one mechanism: a `GameState` snapshot after every settled op.**
   - Files: `companion/<id>/<n>.state`, plus `companion/<id>.log` holding the op (card names, not IDs, which change on load).
   - Resume loads the latest snapshot through `HostedMatch.setStartGameHook`, as `PuzzleScreen:63` does; the hook runs before the main loop.
   - Undo re-applies snapshot n−1 to the running game in place, as dev mode's "set up game state" does, and marks op n undone in the log (AC-27).
   - The engine's `UndoManager` is set to off for Companion. It's in memory only, and its barriers on every draw, phase start, reveal and random event (`Player:1151`, `PhaseHandler:244`, `UndoManager:92`) make it far too shallow.
7. **`TableEditor` starts in the Companion package and moves into `forge-game` once it settles.** `PlaySpellAbility` is already in forge-game. The dev-mode `addCardToZone` mixes GUI prompts into the engine work (`PlayerControllerHuman:3502,3534,3553`), so making dev mode delegate to it is a separate, optional PR.
8. **Recognition is unchanged:** OCR on the name band, then `CardNameMatcher`. Section 4 still applies.

## 2. Components

### 2.1 Changes to existing code
Each change keeps today's behaviour for every other mode:

| Change | Why |
|---|---|
| `GameState`: a resolver hook for card names it doesn't know (the placeholder). Also save commander damage, commander tax, monarch/initiative and day/night. Cards without a `PaperCard` (effects, emblems) are skipped today (`GameState:289-291`). Companion recreates its own effect cards on load, and emblems get saved by name. | Resume and undo (AC-27, AC-29). The extra fields help puzzles upstream as well. |
| `MatchScreen:134`: a single-operator flag that suppresses the rotated second prompt, instead of reading the global hot-seat preference. | With several local humans it would otherwise flip the top panel. |
| `HostedMatch:231`: subscribe one `FControlGameEventHandler` per GUI, not one per human. | Six humans on one GUI would otherwise get every UI update 6 times. |
| `VGameMenu`: a small hook for extra menu items. The menu is hard-coded today (`VGameMenu:23-95`). | Companion's Add card, Next turn, Set life, Hand ±1, Undo, End game. |
| Prompt titles name the deciding seat when one GUI serves several humans (`getGui().one`/`many` in `PlayerControllerHuman`). | AC-35. Design it in slice 2 or 3; the first slices can live with the existing titles. |

### 2.2 New code: `forge-gui`, package `forge.gamemodes.companion`
| Type | Role |
|---|---|
| `CompanionSetup` (record) | Players, seat order, Me, life, hand size, library size, deck and commander. |
| `CompanionLobbyPlayer`, `CompanionPlayerController` | Decisions 2, 4 and 5. |
| `CompanionCards` | The placeholder `PaperCard` and the per-player Companion effect card. |
| `TableEditor` | Cast for free, put onto the battlefield (with or without cast triggers, AC-40), move to any zone of any player, set life, hand ±1. Each call runs only from the op queue. |
| `CompanionStore` | Snapshots and log as in decision 6. Snapshots are written to a temp file then renamed. Torn last log line ignored. No `ended` marker means active (AC-30). |
| `CardNameMatcher` | Unchanged; must exclude the placeholder. |

### 2.3 Persistence limits
Even with the added fields, a snapshot loses three things:
- effects that last "until end of turn"
- delayed triggers
- per-turn counts, such as spells cast this turn

That affects both a resume and an undo within the turn. Combat is off by default (Q21), so lost P/T pumps rarely matter; with it on, a resume between a pump and combat damage can change the outcome. **Spec AC-27 and AC-29 are amended to say so.** They promise the board, hands, life, counters and turn exactly, and temporary effects only as far as the engine can save them.

Slice 0 lists what a typical Commander turn loses. If that is more than we expect, extend `GameState` further rather than replaying the log. Replay would need every prompt answer recorded, and the engine's determinism is unproven.

### 2.4 Screens: `forge-gui-mobile`
| Screen | Notes |
|---|---|
| `NewGameMenu` Companion entry | As now. Resumes the active game. |
| `CompanionSetupScreen` | As now, plus life and hand size defaults (AC-5). |
| **`MatchScreen`** | The game view, with board, zone tabs, stack, prompts, card zoom and log. Companion adds its items through the `VGameMenu` hook. |
| `CardSearchDialog` | As now: name, target player, from/to zone, plus "cast" or "put onto the battlefield" (AC-34, AC-40). |
| `CardSuggestionDialog`, `CompanionHistoryScreen` | As before. History reads our log plus the engine's `GameLog`. |

**AC-23:** mobile `MatchScreen` already stacks **every** player's panel vertically (`MatchScreen:1312-1350`). Avatars are halved above 2 players, and the hand is a tab on each panel. So every player is reachable on one screen. The risk is legibility with 6, which slice 0 measures with a screenshot. Slice 2's compact-panel work is the fallback.

### 2.5 Capture and recognition
Unchanged from the first plan:
- `IDeviceAdapter.captureCardPhoto`, with a default
- a Camera2 `CardCaptureActivity` on Android, and a file chooser on the dev build
- a `CardNameReader` interface
- a new `forge-card-ocr` module, which `forge-gui-mobile` doesn't depend on

See section 4 for the engine choice.

## 3. Files touched outside new code

- **`forge-game`:** `GameState` (resolver hook, extra fields).
- **`forge-gui`:** `HostedMatch` (one event handler per GUI), and later `PlayerControllerHuman` (seat name in prompts).
- **`forge-gui-mobile`:** `MatchScreen` (single-operator flag), `VGameMenu` (menu hook), `NewGameMenu`.
- **Also:** `ForgePreferences` (auto mode), and the capture files as before.

Existing game modes must behave exactly as before. Each change is either a hook that other modes don't use, or a fix that's correct for them too (one event handler per GUI).

## 4. Spike first: choose the OCR engine

The largest recognition risk is whether OCR is accurate and fast enough on real phone photos. Before slice 1, run a **throwaway** benchmark on the dev machine with a first batch of about 30 photos.

| Candidate | For | Against |
|---|---|---|
| **ONNX Runtime + PP-OCR model** (lead candidate) | ONNX Runtime has a Java API on desktop and Android, and an AAR on Maven Central under MIT. PP-OCR is built for natural photos. | Largest native library. Model licence and conversion still to confirm. |
| **Tesseract** (`Tesseract4Android`) | Mature, small language data. | Built for scanned pages, so it may struggle with photos. Distributed through JitPack, not Central. Licence to confirm from its repo. |

Measure: top-3 accuracy through `CardNameMatcher` (AC-32), time per photo, and APK size increase per ABI.

**Pass:** at least 90 % top-3 on the batch, and a clear path to 2 s on a phone. **If neither passes:** stop and revisit the spec with you, rather than quietly weakening AC-32.

## 5. Traceability and verification

| AC | Delivered by | Verified by |
|---|---|---|
| 1–5 | Setup screen, controller, placeholder deck | Dev build. Engine test for the starting state. |
| 6 | `CardCaptureActivity` | Real phone. |
| 7, 13 | Reader + matcher | Benchmark photo set, airplane mode. |
| 8–11 | `CardSuggestionDialog`, `FPref` | Dev build with image files. |
| 12, 14–16 | `CardNameMatcher`, `CardSearchDialog` | Matcher tests. |
| 17–22, 22a | `TableEditor`, placeholders | Engine tests through the **real main loop** with a scripted `CompanionPlayerController` (not the AI): hand counts through cast, bounce, draw and discard. |
| 23 | `MatchScreen` | 6 players on a phone. |
| 24, 25 | Priority policy, `TableEditor` | Engine test: next turn runs upkeep and draw. |
| 26, 28 | Log + `GameLog`, history screen | Run on the dev build. |
| 27 | Snapshot n−1 re-applied | Engine test: undo across a draw and a seat change. |
| 29, 30 | `CompanionStore`, `GameState` + resolver | Round-trip test with placeholders and the new fields. Kill the app on a phone. |
| 31–33 | Whole pipeline | As before. |
| 34–40 | `TableEditor`, controller policy, prompts | Engine tests with an ETB, a cast trigger, a targeted spell, an opponent's choice, "put onto the battlefield", and a response on the stack. Dev build for the prompts. |

**Tests:** `CONTRIBUTING.md` asks agents not to add unnecessary tests to CI. CI gets these, and no UI tests:
- `TableEditor` and the placeholder hand rules, through the main loop
- a `GameState` round trip with placeholders and the new fields
- undo across a draw
- the matcher

**CI gates:** `test-build.yaml`, `test-android-build.yml` and `ios-compat-gate.yml`, run on the fork.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Several local humans on one GUI misbehave (rotated prompt, duplicate events, wrong bottom panel) | The fixes in 2.1, then a run in slice 0 with 3 and 6 seats. |
| Ops through `restartPrompt` interact badly with prompts other than priority (a target choice open when the operator taps Add) | Ops are offered only at priority prompts. The menu disables them otherwise. |
| Placeholders made public, or searched for, show "Unknown Card" | The controller asks the operator to name the card (decision 3). Slice 2 covers discard, mill and search. |
| Snapshots lose temporary effects on resume or undo | Spec amended (2.3). Slice 0 measures what's lost. |
| A snapshot per op costs time or storage on a phone | A snapshot is a few KB of text. Measure in slice 0. Keep the last 500 per game and prune older ones. |
| An effect the engine resolves differs from what happened at the table | Override (AC-22, AC-25) and undo (AC-27) are always there. The engine assists the operator and never has the last word. |
| Core changes worry upstream maintainers | Only hooks and a fix (2.1), with no rules flag. Ask the maintainers with the proof in hand, and keep it on the fork if they decline. |
| OCR, APK size, ProGuard, `android-maven-plugin` | As before: the spike, keep rules, slice 1. |
| No phone reachable from this environment | APKs are installed by you, so **phone checks (AC-6, 23, 29, 31, 33) depend on you**. |

## 7. Project-rule notes

`/mnt/wsl/git/CLAUDE.md` asks for Java 26, Gradle and JOOQ by default. This plan follows **Forge's existing setup** instead: Java 17 release level, Maven, no database. Android manifests and Maven poms are XML because the project requires it. `session-insights.md` stays out of commits.

## 8. Build order

Slice files in [`slices/`](slices/) have the detail.

- **Spike:** OCR engine choice, then contact maintainers. Slice 1 waits on it; slice 0 doesn't.
- **Slice 0 (redone), engine walking skeleton:**
  - builds `CompanionPlayerController` (policy, op queue), placeholders, the effect card, and the `MatchScreen`/`HostedMatch` fixes
  - Companion starts a real 3–6 seat game on `MatchScreen`
  - the operator adds a card by name, casts it for free or puts it onto the battlefield, and its effects resolve
  - snapshot, kill, then resume through the start hook
  - a headless test drives the real main loop
  - it measures the hot-seat, AC-23 and `GameState` risks
- **Slice 1:** capture → OCR → suggestions on Android.
- **Slice 2 (redone), zones and hand:** move any card anywhere, hand ±1, naming placeholders that become public, known cards visible to the operator.
- **Then:**
  - turns and life (next-turn flag, set life)
  - undo and correction (snapshot n−1)
  - history
  - decks and commanders (Commander rules come from the engine)
  - auto mode
  - quality pass

## 9. Sources

- [ONNX Runtime Android on Maven Central](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android)
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android)

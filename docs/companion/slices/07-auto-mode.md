# Slice 7 — Auto mode

**Depends on:**
- **Slice 1:** the photo flow and its confidence. `CardSearchDialog.addByPhoto` and `CardNameReader.Reading`, whose `confident` flag is the reader's own cut-off (a score of 0.10, slice 1 "known gaps").
- **Slice 4:** undo, which reloads the save made before the last action. The undo offered after an auto-add is that undo, not a special one.
- **Not slice 3:** the default target is the active seat (AC-17), which `CardSearchDialog` already picks (slice 3 found nothing left to do for it). Slice 3's parked ops do apply: an auto-added sorcery outside a main phase waits for one like any other add.

**Works with slice 6 when built:** with Commander mode on, an auto-add first passes the commander check. A card that fails it is never added on its own: the operator gets the confirm dialog with slice 6's skip/Retake/Cancel choice.

**Proves:** a confident photo can go straight into the game without a tap, and is easy to take back.

## Result
Experienced operators can turn on a setting that adds confident matches at once, with an undo prompt.

## Acceptance criteria
- **AC-9** WHEN identification finishes in auto mode and the best suggestion's confidence passes the threshold THE SYSTEM SHALL add the card at once and offer an undo prompt. Below the threshold it SHALL behave as in confirm mode.
- **AC-10** THE SYSTEM SHALL default to confirm mode and SHALL offer a setting to switch to auto mode.

## What "add at once" means on the engine
The auto-add is the same op as tapping Add with the dialog's defaults: the active seat, From hand, Cast it (AC-17, AC-18), submitted through `CompanionTable.submit`. Casting may still ask for a target or a mode; those prompts come as usual, since the engine can't guess them. The undo prompt shows once the op has run, from `CompanionTable`'s result callback.

## Build
Small chunks, in order. Each is one or two files.

1. **`CardNameReader.Reading` (forge-gui) + `OcrCardNameReader` (forge-card-ocr):** add the best match's score, so a threshold can be set apart from `confident`. `NOTHING` has 0. The dev build's and Android's readers both go through `OcrCardNameReader`, so no backend changes.
2. **`AutoAddPolicy` (new, forge-gui, `forge.gamemodes.companion`):** `boolean shouldAutoAdd(Reading reading, double threshold)`: confident, at least one card, score at or above the threshold, and the best card clearly ahead of the second (if the reader's scores show it). Pure, so it can be tested. The threshold constant and the evidence for it go in a comment here (see chunk 5).
3. **`CompanionPreferences` (new, forge-gui, `forge.gamemodes.companion`):** `CPref.AUTO_ADD` in its own `companion.preferences`, default false (confirm mode), so Forge's `ForgePreferences` stays as it is.
4. **`CardSearchDialog` (forge-gui-mobile):** in `showReading`, when the preference is on and `AutoAddPolicy` says yes, don't show the dialog: submit the add with the defaults and close. Otherwise, as now. With slice 6 built, the commander check runs first, and a failure shows the dialog.
5. **Threshold:** set it from the spike's photo set (`S-ocr-spike.md`, the reader's real-model tests). Pick the value where a wrong auto-add is rare (at most 1 in 100 photos), and record the value and the counts in `AutoAddPolicy`'s comment.
6. **Companion's `MatchExtension` (forge-gui-mobile):** "Auto-add photos: on/off" in the game menu, writing the preference. After an auto-add, a short message with Undo (`FOptionPane` with "OK" and "Undo", or a toast-like bar if `MatchScreen` has one), whose Undo submits slice 4's undo op.

## Check early
- **Undo prompt during a target prompt:** an auto-added Lightning Bolt asks for its target before the undo prompt can be answered. The undo prompt should come only after the op settled; if the operator cancels the target, the add didn't happen (`TableEditor` puts the hand back) and there's nothing to undo.
- **Undo reach:** the Undo on the prompt must undo the auto-add, not something else. If another op ran between the two, say so instead of undoing it.

## Tests (CI)
`AutoAddPolicy`: below the threshold no; not confident no; no cards no; at or above yes. Nothing else: the rest is UI.

## Oracle
On the dev build with image files (built with `-Pcompanion-photos`, run with `-Dforge.companion.photos=true`):
1. Default setting: a clear photo shows the dialog with the best card chosen.
2. Auto on: a clear photo adds the card at once, and a prompt offers Undo. Undo removes it, and the hand count is back.
3. Auto on: a poor photo still shows the dialog, with the text read.
4. Auto on, a clear photo of Lightning Bolt: the target prompt comes, then the undo prompt.
5. The setting survives a restart.
6. With slice 6 built: auto on, Commander mode on, a clear photo of a card illegal for the active seat shows the dialog, not an add.

## Out of scope
Changing the default.

# Slice 7 — Auto mode

**Depends on:** slice 1 (suggestions) and slice 4 (undo).

## Result
Experienced operators can turn on a setting that adds confident matches immediately.

## Acceptance criteria
- **AC-9** WHEN identification finishes in auto mode and the best suggestion's confidence passes the threshold THE SYSTEM SHALL add the card at once and offer an undo prompt. Below the threshold it SHALL behave as in confirm mode.
- **AC-10** THE SYSTEM SHALL default to confirm mode and SHALL offer a setting to switch to auto mode.

## Build
- A new `FPref` in `ForgePreferences`, default confirm, with a toggle on the game screen or in settings.
- In auto mode, skip `CardSuggestionDialog` when confidence passes the threshold, using the AC-17 and AC-18 defaults. Show a brief prompt with undo.
- Set the threshold from benchmark data: pick the value where a wrong auto-add is rare, and record it and the evidence in a comment.

## Tests (CI)
None needed beyond a small threshold-decision test, if that logic is pure.

## Oracle
On the dev build with image files:
1. Default setting: a clear photo shows the suggestion dialog.
2. Auto on: a clear photo adds the card at once, and a prompt offers undo. Undo removes it.
3. Auto on: a poor photo still shows the dialog.
4. The setting survives a restart.

## Out of scope
Changing the default.

# Camera PoC — Capture only the card, on a real phone

**Throwaway app.** A small standalone Android app outside the Forge modules (`/mnt/wsl/git/forge-camera-poc`), not merged. Its outputs are results added to `plan.md` and working capture code and settings that slice 1 can port.

## Why
The OCR spike uses photos taken with no help from the app, and only ever runs on a desktop. Two questions need a real phone before Forge code is built:

1. Does a camera that knows about the card take better photos than a plain shutter?
2. Can the whole pipeline, from capture to suggestions, reach 2 s on your phone?

## Input needed
- Your Android phone, and your help installing the APK from `\\wsl.localhost\claude-dev\...` and running the tests.
- The phone's model and Android version, recorded with the results.
- About 30 real English cards, mixed sleeved, unsleeved and foil.

## Do
Use the platform Camera2 API and no camera library. Reuse the spike's recognition code: find the card, fit four edges, rectify, read the title, match against card names.

Build one screen with two modes, switchable so both can run on the same cards:

- **Plain:** a preview and a shutter button. This is the baseline.
- **Assisted:**
  - Run the 63×88 rounded-rectangle finder live on a low-resolution preview stream, and draw the detected card outline.
  - Set focus and exposure on the detected card area.
  - Capture automatically once the card is found, steady and sharp. A shutter button stays available.
  - Take a short burst and keep the sharpest frame.
  - Warn when glare covers the title.
  - Use the gyroscope, when the phone has one, to judge steadiness and to shift the outline between finder results. Without one, fall back to comparing outlines.
  - Find the card by its rectangle. If rounded-corner detection proves useful, it adds to this but doesn't replace it, because black sleeves and sloppy proxies may have no visible rounded corners.
  - Don't require a fixed guide frame. Sideways cards and neighbouring cards are normal during a game.

Both modes show a debug result: the flattened card, the text read, the top 3 matches, and the time spent in each step. Each run is saved with its photo so results can be scored afterwards. The card-name list ships as an app asset.

## Confirm and record
- Top-1 and top-3 accuracy per mode, on the same cards in the same lighting. Label by eye, not with the OCR under test.
- Time per step on the phone: finder, capture, rectify, OCR, match, total.
- Whether the finder keeps up with the preview, and at what resolution and frame rate.
- How long a capture takes for the user, from pointing the phone to getting suggestions.
- Whether photos of the same cards look sharper in assisted mode.
- APK size increase.
- Which photos to add to the OCR benchmark.

## Oracle
- 📱 WHEN a card is in view in assisted mode THE APP SHALL outline it on the preview, including a sideways card with neighbours in view.
- 📱 WHEN the card is steady and sharp THE APP SHALL capture without the shutter being pressed.
- 📱 WHEN a capture finishes THE APP SHALL show the flattened card, the top 3 matches and step timings, with airplane mode on.
- WHEN the saved runs are scored THE RESULT SHALL be a table comparing both modes, reproducible from a stated command.

**Pass:** assisted top-3 is at least as good as plain and at least 90 %, and capture to suggestions takes 2 s or less.

**If assisted isn't better:** slice 1 ships the plain capture with the on-screen guide, and the result is recorded.

**If 2 s or 90 % is missed in both modes:** stop and revisit the spec with you. Do not weaken the target.

## After
- Record the results and the chosen capture design in `plan.md`.
- Update slice 1 with what to port.
- The numbers go to the Forge maintainers along with the spike's.

## Out of scope
Any change to Forge modules. Game tracking. A polished UI. iOS. Other phones, which are left to the slice 8 quality pass.

# Slice 1 — Photo capture

**Depends on:** slice 0, and the engine chosen in the spike (see `plan.md` section 4). **Proves:** the native OCR build works on Android, which is the riskiest integration.

## Result
The operator taps capture, frames the card in the guide, takes a photo, and picks the card from suggestions.

## Acceptance criteria
- **AC-6** WHEN the operator starts a capture THE SYSTEM SHALL show a camera view and take a photo of one card.
- **AC-7** WHEN a photo has been taken THE SYSTEM SHALL identify the card by reading its printed name and matching it against Forge's card database, without any network connection.
- **AC-8** WHEN identification finishes in confirm mode THE SYSTEM SHALL show the best suggestion plus up to 3 alternatives, a "search by name" option, and a cancel option.
- **AC-11** WHEN the name cannot be read confidently THE SYSTEM SHALL open the name search with any partially read text already filled in.
- **AC-13** WHEN the card is double-faced THE SYSTEM SHALL recognize it from either face's name.

## Build
- **`IDeviceAdapter`:** add `default void captureCardPhoto(Consumer<File> onResult)`. The default reports "unsupported", so iOS needs no changes.
- **`forge-gui`:** `CardNameReader` interface: `OcrResult read(File photo)`, returning text and confidence.
- **New module `forge-card-ocr`:** the implementation. Add it to the root `pom.xml`. Only `forge-gui-android` and `forge-gui-mobile-dev` depend on it. **`forge-gui-mobile` must not**, or native code reaches the iOS build. The backend passes the reader in.
- **`forge-gui-android`:**
  - `CardCaptureActivity`: platform Camera2, preview, guide overlay, returns a JPEG file.
  - `AndroidManifest.xml`: the `CAMERA` permission and the activity.
  - `Main.java`: start the activity, handle `onActivityResult`.
  - `proguard.cfg`: keep rules for OCR classes used through JNI.
  - The model file goes in `assets`.
- **`forge-gui-mobile-dev`:** a file chooser instead of the camera.
- **`forge-gui-mobile`:** `CardSuggestionDialog`, and a capture button on `CompanionGameScreen`.
- Run OCR off the UI thread. Load the model on first capture. Delete photos after reading.
- If camera permission is refused, go to the name search.

## Tests (CI)
- Matcher: noisy OCR-style text ("So1 Rinq") finds the right card; low confidence is reported as low.
- No OCR engine tests in CI, because they'd need native code and photos.

## Oracle
1. On the dev build: choose a photo of a real card → suggestions appear with the right card in them → confirm → it's on the target player's battlefield.
2. Choose a photo with an unreadable name → the search opens with the partial text filled in.
3. Choose a photo of the back face of a double-faced card → that card is suggested.
4. 📱 On a phone, in airplane mode: capture a real card → the right card appears in the suggestions.
5. 📱 Refuse camera permission → the name search opens, no crash.
6. 📱 The app still starts from a release-style APK, so ProGuard hasn't stripped the OCR classes.
7. `ios-compat-gate.yml` stays green.

## Out of scope
Auto mode (slice 7), deck ranking (slice 6), accuracy targets (slice 8).

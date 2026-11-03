# Slice 1 — Photo capture

**Depends on:** slice 0 (redone on the engine), and the OCR engine chosen in the spike (see `plan.md` section 4). **Proves:** the native OCR build works on Android, which is the riskiest integration.

**Built from the camera PoC (2026-11-03).** The PoC is a standalone Android app at `/mnt/wsl/git/forge-camera-poc`, outside Forge. It was tried on the owner's phone with PP-OCR models through ONNX Runtime. Its plain-Java code (card finder, Assisted capture logic, reader) is ported as-is, with its tests, into the new `forge-card-ocr` module. The Android capture screen is cut down from its Assisted mode.

## Result
The operator taps Photo in the Add card dialog. On a phone, the camera finds the card, focuses on it and takes the photo on its own (a shutter button is still there). The operator then picks the card from suggestions.

## Acceptance criteria
- **AC-6** WHEN the operator starts a capture THE SYSTEM SHALL show a camera view and take a photo of one card.
- **AC-7** WHEN a photo has been taken THE SYSTEM SHALL identify the card by reading its printed name and matching it against Forge's card database, without any network connection.
- **AC-8** WHEN identification finishes in confirm mode THE SYSTEM SHALL show the best suggestion plus up to 3 alternatives, a "search by name" option, and a cancel option.
- **AC-11** WHEN the name cannot be read confidently THE SYSTEM SHALL open the name search with any partially read text already filled in.
- **AC-13** WHEN the card is double-faced THE SYSTEM SHALL recognize it from either face's name.

## Build
- **`forge-gui`:**
  - `CardNameReader`: `Reading read(CardPhoto)`, which returns the text read, up to 4 card face names (the OCR reader keeps 3, so the best and 2 alternatives), and whether the best one is confident.
  - `CardPhoto`: a JPEG, plus the card outline the camera tracked, if any.
  - `IDeviceAdapter`: `canPhotographCards()`, `photographCard(Consumer<CardPhoto>)` and `getCardNameReader()`. The defaults say "unsupported", so iOS needs no changes.
- **New module `forge-card-ocr`** (`forge.cardocr`):
  - The PoC's `finder`, `assist` and `reader` packages, with 359 of its unit tests on JUnit 5. The PoC's photo-based evaluations are left out.
  - `OcrCardNameReader` uses the PoC's default budget. Models and names load on the first read. Names come from Forge's card faces (`CardNameMatcher.cardFaceNames()`), so a back face is matched too.
  - `PhotoDecoder` is the backend's JPEG decoder.
  - Models in `src/main/resources/forge/cardocr/ocr/`: PP-OCR v3 detection plus English recognition, Apache-2.0, 10.3 MB.
  - ONNX Runtime 1.31.1 (MIT) is `provided`: each backend brings its own build.
  - Only `forge-gui-android` and `forge-gui-mobile-dev` depend on it. **`forge-gui-mobile` must not**, or native code reaches the iOS build.
- **`forge-gui-android`:**
  - `forge.app.camera.CardCaptureActivity` is the PoC's Assisted capture: Camera2, live card outline, focus and exposure on the card, auto capture, shutter, torch.
  - It returns the photo path and the tracked outline.
  - It depends on `onnxruntime-android` (AAR).
  - `AndroidCardPhotos` starts the activity, decodes photos with `BitmapFactory` and holds the reader. `Main`'s adapter calls it.
  - Manifest: `CAMERA` permission, and the activity as `singleTask` in its own task, kept out of Recents (`Main` is `singleInstance`). The result comes back through a static callback, not `onActivityResult`.
  - ProGuard keeps `ai.onnxruntime`.
  - Photos nobody will read are deleted, and photos older than 10 minutes are cleared when the capture screen opens.
- **`forge-gui-mobile-dev`:**
  - A file chooser instead of the camera. It's off unless `-Dforge.companion.photos=true` is set, because this jar is also the shipped PC build.
  - The system property `forge.companion.cardPhoto=<file>` skips the chooser, for scripted checks.
  - The decoder uses ImageIO. It depends on the desktop `onnxruntime` jar.
- **`forge-gui-mobile`:** `CardSearchDialog` gets a Photo button where the device can photograph cards.
  - A confident read lists the best card and its alternatives, with the best one chosen, so one tap adds it.
  - Otherwise the text read is left in the search box.
  - No photo, for example when the camera is refused, leaves the search as it was.
- Reading runs off the UI thread. Photos from the camera are deleted after reading. Photos the operator picked on the dev build are kept.

## Tests (CI)
- `forge-card-ocr`: the PoC's unit tests (finder, tracking, gyroscope, capture logic, reader scoring). Also `OcrCardNameReaderTest`:
  - a blank photo isn't confident;
  - a temporary photo is deleted;
  - a photo that can't be decoded reads as nothing.
- The real-model photo tests run only where the spike's photos are, so not in CI.

## Oracle
1. ✅ 2026-11-03, dev build. A real photo of Westfold Rider gave "Westfold Rider, Fault Riders, Hellrider", with the first one chosen. Add, then OK: it was cast and is on the target player's battlefield, and their hand went from 8 to 7.
2. ✅ 2026-11-03, dev build. A blurred phone capture of Curious Colossus opened the search with "Cnas Cotes" filled in and nothing chosen.
3. ✅ 2026-11-03, dev build. The back face of Delver of Secrets (Insectile Aberration, a Scryfall image) suggested Insectile Aberration. Put onto battlefield: a transformed 3/2.
4. 📱 On a phone, in airplane mode: capture a real card → the right card appears in the suggestions. ✅ 2026-11-03, Motorola edge 60 neo, Android 16, debug APK installed beside release Forge, with Wi-Fi on, not airplane mode. Daxos, Blessed by the Sun was found, focused (216 ms), auto-captured (249 ms, tracked outline) and read; it was suggested first with two alternatives, then cast for Player 1, whose hand went 7 to 6. ONNX Runtime's native library loaded. Still to do: the same in airplane mode.
   - Building the APK on Java 21: android-maven-plugin 4.6.2 fails at signing (`PKCS7.encodeSignedData` was removed after Java 17; CI uses 17). The unsigned APK it leaves is complete and can be signed with `apksigner`.
5. 📱 Refuse camera permission → the name search stays open, no crash.
6. 📱 The app still starts from a release-style APK, so ProGuard hasn't stripped the OCR classes. Check with `unzip -l` that the APK has `forge/cardocr/ocr/*.onnx` and `lib/*/libonnxruntime*.so`.
8. 📱 Press Home during a capture, go back to Forge, tap Photo again → one capture screen, and the photo reaches the open dialog.
7. `ios-compat-gate.yml` stays green.

## Known gaps
- If a read fails, the PoC asked for the card again and retook the photo once. Forge drops that: the operator taps Photo again.
- The reader always returns its best names, even with no card in view. A score below 0.10 counts as not confident.
- The PC build (the `forge-gui-mobile-dev` jar, which the installer ships) grows by about 61 MB: 51 MB of ONNX Runtime plus the models. The Photo button is off there unless asked for.
- APK size: the PoC measured +44 MB for arm64 (ONNX Runtime 34 MB uncompressed, models 10 MB). Forge's APK carries 4 ABIs, so the native part grows about fourfold unless ABIs are split.

## Out of scope
Auto mode (slice 7), deck ranking (slice 6), accuracy targets (slice 8).

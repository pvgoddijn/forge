# Slice 08: QR code

**Branch:** `feature/companion-multi-08-qr`, stacked on `feature/companion-multi-07-compat`. **Plan item:** section 9, M1-3, second part ("QR and `QrScanActivity`").
**Depends on:** 00 (`JoinCode`, `ShareDialog`, `JoinGameScreen`, `CompanionClient`), v1 slice 01 (the Camera2 controller and `FrameAnalyser` in `forge-gui-android/src/forge/app/camera/`).
**Proves:** joining at the table takes one scan, and a host with several addresses is still found.

## Result
The share dialog shows a QR code next to the typed code. On an Android phone, "Join game" has a "Scan" button: point the camera at the host's QR code and the phone joins, trying every address the host has at once. iOS and the desktop dev build keep the typed code only.

## Acceptance criteria
Delivered in part:
- **AC-101** WHILE a game is active THE SYSTEM SHALL let the operator share it, which shows a join code as text and as a QR code. _Here: the QR code (the text code is from 00). With this slice AC-101 is complete._
- **AC-102** WHEN a user picks "Join game" in Companion and scans the QR code or types the join code THE SYSTEM SHALL connect to that shared game over the local network, without internet. _Here: the scan. Phone checks on real networks: 09._

## Design for this slice
Plan 2.1 (`IDeviceAdapter`), 2.3 (`ShareDialog`, `JoinGameScreen`), 2.4 (`QrScanActivity`), 2.5 (ZXing), 3.4 (QR text).

### New dependency
- **ZXing `com.google.zxing:core`** (Apache-2.0, pure Java) in **`forge-gui-mobile/pom.xml`**, to draw and read the QR code. No QR library is in the repo today. iOS takes `forge-gui-mobile` (`forge-gui-ios/pom.xml:93`), and pure Java passes `ios-compat-gate.yml`. Use the latest stable `core` release; check it at Forge's Java 17 release level and through the iOS gate (check first 1).

### `forge-gui`
- **`JoinCode`:** the QR text `forge-companion:<code>?g=<game id>&a=<other addresses>` (plan 3.4), parsed back to a code plus extra addresses; a typed code still parses alone.
- **`IDeviceAdapter`** (`forge/interfaces/IDeviceAdapter.java:27-60`): `canScanCodes()` default false, `scanCode(Consumer<String>)` default `accept(null)`. So iOS and desktop offer typed codes only.
- **`CompanionClient`:** with several addresses, **tries every address in parallel**; the first that answers `welcome` for this game id wins, the others are closed.

### `forge-gui-mobile`, `forge.screens.companion`
- **`ShareDialog`:** a QR code for the share's QR text: ZXing `QRCodeWriter` → `BitMatrix` → libGDX `Pixmap` → texture, sized to the dialog, with a quiet zone. The typed code(s) stay below it.
- **`JoinGameScreen`:** "Scan" where `canScanCodes()`; the result fills the code and joins.

### `forge-gui-android`
- **`QrScanActivity`** (new, in `forge.app.camera` or beside it): reuses the Camera2 controller and its YUV frames (`forge/app/camera/FrameAnalyser.java`, `CameraController.java`), with ZXing's `QRCodeReader` on the Y plane (a `PlanarYUVLuminanceSource`). Returns the text, or null on back.
- **`AndroidManifest.xml`:** the activity entry. `CAMERA` is already declared for v1's capture (`src/main/AndroidManifest.xml:31`).
- **`Main.java`:** `canScanCodes()` true when a camera exists; `scanCode` starts `QrScanActivity` and delivers its result.

## Not in this slice
- Interface names ("Hotspot", "Wi-Fi") in the dialog, keeping the screen on, roster addresses: **09**.
- Scanning on iOS or desktop: not planned (`canScanCodes` false).
- LAN discovery: unplanned nice-to-have.

## Check first
1. **iOS gate:** `ios-compat-gate.yml` passes with ZXing in `forge-gui-mobile`. Run it on the fork before building the rest.
2. **Camera reuse:** `QrScanActivity` and v1's `CardCaptureActivity` don't fight over the camera; the frame size used for card capture is enough for a QR code at arm's length.
3. **Pixmap size:** the QR code is crisp on a small phone and on the 450×900 dev window (whole modules per pixel, no scaling blur).
4. **Parallel tries:** the losing connections close cleanly on both sides (no ghost peer on the host's Phones list).

## Tests (CI)
None: the plan lists no automated test for AC-101/102 (plan 6: "Dev: code shows. Phone: QR scans"). If `JoinCode`'s QR text needs a guard against regressions, fold it into an existing test rather than adding a class.

## Oracle
**Dev check** (two instances as in slice 00):
```
REPO=/path/to/forge; JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
for n in host join; do mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties; done
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) & (cd /tmp/companion-join && java -jar "$JAR" width=450 height=900) &
```
1. Host: share. OK: a QR code shows above the typed code. Decode a screenshot of it with any QR reader: OK, it reads `forge-companion:<the code>?g=<game id>&a=…`.
2. Joiner: OK: no Scan button (desktop); the typed code still joins.

**[Phone] checks** (two Android phones as slice 01, on a router with no internet, mobile data on):
1. Phone A: share. OK: a QR code that fits the screen.
2. Phone B: Companion → Join game → Scan → point at phone A's code. OK: joins within a few seconds, without typing.
3. Phone A on its own hotspot **and** on a Wi-Fi at once (if the phone allows both): phone B scans. OK: joins, whichever network phone B is on.
4. Phone B: Scan, then back without scanning. OK: back on the join screen, nothing joined.
5. If an iPhone or iPad build is to hand: OK, no Scan button, typed code works. (Optional.)

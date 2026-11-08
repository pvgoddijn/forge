# Slice 06: Host keeps serving

**Branch:** `feature/companion-multi-06-service`, stacked on `feature/companion-multi-05-seats`. **Plan item:** section 9, M1-2, second part ("the host's foreground service").
**Depends on:** 04 (host re-shares after a restart; joined phones reconnect for ever), 00 (`CompanionShareHost` start/stop).
**Proves:** an Android host keeps serving while its operator uses another app or locks the phone (Android 12+ freezes a backgrounded app).

## Result
While a game is shared, the host phone shows a notification "Sharing a Companion game". The operator can switch to another app or lock the phone for minutes, and the joined phones stay live: they keep following, and actions the host makes on returning reach them at once. When sharing stops (leaving the match, ending the game), the notification goes. Joined phones show no notification.

## Acceptance criteria
- Owner decision **Q27**: "The host runs a foreground service with a notification while sharing."
- Host part of **AC-112** THE SYSTEM SHALL save the complete shared game on every joined phone as it changes, so that a joined phone that is closed, killed or restarted resumes the game where it left off and rejoins automatically. _Plan 6 verifies it with "Host backgrounded 2 min with its service"._

## Design for this slice
Plan 2.1 (`IDeviceAdapter`), 2.4 (Foreground service, Service by API level, Permissions), N11 (joined phones run none).

### `forge-gui`
- **`IDeviceAdapter`** (`forge/interfaces/IDeviceAdapter.java:27-60`): `keepServing(boolean)`, default no-op (desktop, iOS).
- **`CompanionShareHost`:** `keepServing(true)` when sharing starts (on the "Share game…" tap, or the resume of a hosting game), `keepServing(false)` when it stops. Never on a joined phone, in any milestone (N11).

### `forge-gui-android`
- **Service class** (new, for example `forge.app.CompanionShareService`): a foreground service with a notification "Sharing a Companion game", tap → back to the app. No work of its own: it keeps the process unfrozen while Netty serves.
- **API branches** (min API 26, target 35, `src/main/AndroidManifest.xml:5-7`): started with `startForegroundService`, and `startForeground` within about 5 s. On `SDK_INT >= 34`: `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`; below: `startForeground(id, notification)`. `androidx.core` is excluded from the build (`forge-gui-android/pom.xml`, Sentry exclusions), so there is no `ServiceCompat`: two `SDK_INT` branches. From API 31 a foreground service can't be started from the background, so it starts on the "Share game…" tap, in the foreground; a resumed hosting game starts it when Companion opens (also in the foreground).
- **`POST_NOTIFICATIONS`:** asked at runtime on API 33+ only, when sharing starts. Refused: sharing still works; the service still runs, its notification is hidden.
- **`AndroidManifest.xml`:** the service entry with `android:foregroundServiceType="specialUse"` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property (needed at target 35), permissions `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS`. `FOREGROUND_SERVICE` is already declared (`:25`).
- **`Main.java`** (`forge-gui-android/src/forge/app/Main.java`): `keepServing` starts and stops the service.

## Not in this slice
- Keeping the screen on while sharing or joined: **09**.
- A service on joined phones: never (N11). A phone that takes over in M3 starts this service then (**M3-1**).

## Check first
1. **The oldest device to hand** (API 26–28): the service starts without the three-argument `startForeground` and without a type, and the notification shows without asking.
2. **API 34+:** no `MissingForegroundServiceTypeException` or `ForegroundServiceStartNotAllowedException` when sharing starts, and on the resume of a hosting game.
3. **Play-store type review:** `specialUse` needs the subtype property's text to explain the use ("serves a shared board game to phones on the local network"). Note it in the PR.

## Tests (CI)
None: Android wiring (plan 6 verifies it by hand). `test-android-build.yml` must stay green with the manifest change.

## Oracle
**Dev check:** two instances as in slice 00; share and join. OK: works as before; `keepServing` is a no-op on desktop.
```
REPO=/path/to/forge; JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
for n in host join; do mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties; done
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) & (cd /tmp/companion-join && java -jar "$JAR" width=450 height=900) &
```

**[Phone] checks** (two phones as slice 01; one of them Android 12+; note each phone's Android version):
1. Phone A: share a game. OK: on Android 13+ it asks for notifications once; the notification "Sharing a Companion game" shows.
2. Phone B joins. Phone A: home button, open another app, wait **2 minutes**. OK: phone B never shows the reconnect banner (or shows it and recovers within 15 s). FAIL: phone B stays disconnected until phone A is reopened.
3. Phone A: lock the screen for 2 minutes, unlock, return to Forge, add a card. OK: phone B shows it within about 1 s.
4. Phone A: end the game. OK: the notification goes away.
5. Phone B (joined): OK: it never showed a notification.
6. On the **oldest Android phone to hand** (ideally API 26–28): repeat 1–4 with it as the host. OK: the same results.

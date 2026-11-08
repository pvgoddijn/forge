# Slice 07: Compatibility check

**Branch:** `feature/companion-multi-07-compat`, stacked on `feature/companion-multi-06-service`. **Plan item:** section 9, M1-3, first part (`Compat` with the protocol fingerprint and refusals, N1).
**Depends on:** 00 (`hello`/`welcome`, the join screen), 05 (the host's share dialog, for the host-side notice).
**Proves:** Java serialization never breaks silently: two builds that can't exchange games refuse each other with a reason, even when both report the same version string.

## Result
A phone whose Forge build can't exchange games with the host's is refused at join. The joining phone says why ("The host's Forge build is different: its game view differs" or "…its card database differs"), and names the host's build. The host shows a short notice that a phone was refused and why. Two builds that can exchange games join as before, whatever their version strings.

## Acceptance criteria
- **AC-110** WHEN a phone's app can't exchange games with the host's (a different network format or card database) THE SYSTEM SHALL refuse the join and tell both sides why.

Spec assumption **B2**: "Can't exchange games" is decided by a fingerprint of the network format and the card database, not by the app version string (dev builds all report the same one).

## Design for this slice
Plan N1, 2.2 (`Compat`), 3.3 (`hello.fingerprint`, `refuse`), Risks ("Builds differ", "Card database fingerprint differs between desktop dev and phone builds").

### `forge-gui`, `forge.gamemodes.companion.share`
- **`Compat`** (new): the **protocol fingerprint**, one hash per part, so a refusal can say which part differs:
  - `TrackableProperty` names in declaration order (`forge-game/.../trackable/TrackableProperty.java`);
  - `ProtocolMethod` names in order;
  - the `serialVersionUID`s of the classes that travel: the view classes, `DeltaPacket`, Companion's messages (and, from M2-1, its ops); read them with `ObjectStreamClass.lookup(c).getSerialVersionUID()`, which also gives a computed one for a class that declares none;
  - the Companion protocol version (a constant in this package, bumped by hand when a message changes);
  - the card database: edition codes + card count.
  - The build string (`BuildInfo.getVersionString`) is sent **for the refusal message only**: every dev build reports `GIT` (`BuildInfo.java:45-51`).
- **Messages:** `hello` gains `fingerprint` (the per-part hashes) and the build string; new `refuse` {reason, the host's build string, which parts differ}.
- **`CompanionShareHost`:** compares on `hello`, before `welcome`. Not equal: send `refuse`, close, show the host a notice ("A phone was refused: its card database differs").
- **`CompanionClient`:** on `refuse`, stop (no reconnect), and pass the reason to the screen.

### `forge-gui-mobile`
- **`JoinGameScreen`:** shows the refusal: which part differs and the host's build string. For a phone that rejoins by itself (04), the same message replaces the reconnect banner.

## Not in this slice
- No "same build" check of any kind (N1).
- QR, interface names: **08, 09**.
- The ops' `serialVersionUID`s: added to the list when `CompanionOps` exists (**M2-1**).

## Check first
1. **Card database across platforms** (plan Risks): compute the card-database part on a desktop dev build and on an APK **of the same commit**. If they differ (editions or cards filtered on one platform), fall back to hashing the names of the cards the game uses (plan Risks) and record it here.
2. **Stable order:** the `TrackableProperty` and `ProtocolMethod` parts are the same on desktop and Android for the same commit (`Enum.values()` order; ProGuard keeps `forge.**`, `proguard.cfg:74`, so names survive).
3. **Classes without a declared `serialVersionUID`** among the view classes: their computed UID can differ between javac and Android's toolchain for the same source. If so, the fingerprint refuses two copies of the same commit; record which classes, and decide between declaring UIDs on them (a `forge-gui`/`forge-game` change, outside "not touched") or leaving them out. Ask the owner before touching `forge-game`.

## Tests (CI)
Named in plan 6 (AC-110 test); no others. In `forge-gui` (or `forge-gui-desktop` if it needs the card database loaded):
- **A different fingerprint is refused with a reason on both sides:** one `TrackableProperty` added (simulate by feeding `Compat` a changed list), or a different card database.
- **Two dev builds that both say `GIT` but differ are refused too.**

## Oracle
**Two instances** (as in slice 00; for the mismatch, the joiner runs a jar built from a branch with one extra `TrackableProperty`):
```
REPO=/path/to/forge; JAR=$(ls "$REPO"/forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar)
for n in host join; do mkdir -p /tmp/companion-$n && ln -sfn "$REPO/forge-gui/res" /tmp/companion-$n/res
  echo "userDir=/tmp/companion-$n/user/" > /tmp/companion-$n/forge.profile.properties; done
(cd /tmp/companion-host && java -jar "$JAR" width=450 height=900) & (cd /tmp/companion-join && java -jar "$OTHER_JAR" width=450 height=900) &
```
1. Same jar on both: share and join. OK: joins as before.
2. Joiner on a jar with one extra `TrackableProperty` (a throwaway local commit, copy the jar to `$OTHER_JAR` before rebuilding): join. OK: the joiner says the game view differs and shows the host's build string (`GIT`); the host shows "A phone was refused"; no board appears. FAIL: the join succeeds, or fails with a decode error.
3. Joiner with a different card database (for example, a `res` folder with one edition removed: point `/tmp/companion-join/res` at a copy). OK: refused, "card database differs".

**[Phone]**
1. A phone and a desktop dev build of the **same commit**: the phone joins the desktop's game. OK: accepted. FAIL: refused (then check-first item 1 or 3 applies).
2. A phone with an older APK (a previous slice) joins a phone with this slice's APK. OK: refused with a reason on both, or (if the older build has no `Compat`) the older one gets a clear failure and the newer host shows the notice.

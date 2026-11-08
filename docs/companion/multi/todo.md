# Companion multi-phone — open items

_Last updated 2026-11-08._

## Later (planned, not sliced yet)
- [ ] **JSON on the wire instead of Java serialization.** Replace the view stream's Java-serialized `GameView`/`TrackableObject` deltas, and the share messages, with a JSON encoding.
  - Why: it removes the same-build requirement (N1, AC-110). Phones on different Forge versions can then share a game as long as the JSON format matches, and dev builds stop looking compatible when they aren't. It also takes Java deserialization of data from the network off the host, and keeps a browser listener possible (spec §6.1).
  - This is a core change to Forge's network play (`CompatibleObjectEncoder`/`Decoder`, `TrackableSerializer`, `DeltaSyncManager`), so build it on its **own worktree and branch**, separate from the Companion slice stack. Then the network-play change can be reviewed and proposed on its own.
  - Until then, the protocol fingerprint (slice 07) stays the compatibility check.

## Before asking the maintainers
- [ ] Beta-test the finished feature at a real table first (owner's call, 2026-11-08). Only then contact Card-Forge maintainers, with the changes to network play listed (FGameClient hooks, `ProtocolGuiGame.attachMidGame`, flush timing, `RemoteClient.getReplyPool`, the `MatchController.getExtension` change) and the two `forge-game` null checks from the Paid button.

## Manual checks not yet done
- [ ] Slice 00 UI: share and join between two mobile-dev instances (recipe and steps in `slices/00-walking-skeleton.md`, Results). Not run: this machine can't drive the UI.

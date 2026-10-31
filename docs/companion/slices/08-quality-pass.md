# Slice 8 — Quality pass

**Depends on:** all other slices.

## Result
The numbers in the spec are measured on real hardware and met, or the gap is reported.

## Acceptance criteria
- **AC-31** WHEN a capture is taken on a mid-range Android phone THE SYSTEM SHALL show suggestions within 2 s.
- **AC-32** WHEN tested against the reference photo set (normal indoor light, English cards, sleeved and unsleeved) THE SYSTEM SHALL include the correct card in the top 3 suggestions for ≥ 90 % of photos with no loaded deck, and ≥ 98 % when the card's deck is loaded.
- **AC-33** WHILE the device has no network connection THE SYSTEM SHALL support every v1 feature. At most, card artwork may fall back to a text rendering.

## Build
- A benchmark outside CI, run by hand, using the real pipeline: guide crop, reader, matcher.
- Reference set of about 200 photos with correct names. **Taken by you**, using the app's guide. Photos stay out of the repo unless their licensing is clear.
- Tune cropping, preprocessing and matching against the results. Fix failures rather than removing hard cards from the set.

## Oracle
1. Benchmark prints top-3 accuracy with and without loaded decks, from a stated command. At least 90 % and 98 %.
2. 📱 Timed on a named mid-range phone: 2 s or less from photo to suggestions, measured with the model already loaded, and the first-capture time reported separately.
3. 📱 In airplane mode with the card image cache emptied: play a whole game using every feature. Nothing fails, and card art falls back to text at worst.
4. 📱 Report the final APK size increase.

**If a target is missed:** report it with the data, and let you decide. Do not lower a target or adjust the photo set to pass.

## After this slice
ADLC phase 6 (Learn): capture what was learned in `session-insights.md` or a project rule.

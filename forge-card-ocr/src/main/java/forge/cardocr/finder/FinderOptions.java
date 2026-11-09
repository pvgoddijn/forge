package forge.cardocr.finder;

/**
 * Finder settings. Input larger than a limit is box-downsampled to it; smaller input is used as it
 * is, never upscaled.
 *
 * @param searchLongSide long side of the image searched for rough rectangles
 * @param refineLongSide long side of the image used for fitting edges
 * @param maxAngles how many rotations to search, strongest edge directions first. Search time is
 *     roughly proportional to this
 * @param roughCandidates how many rough rectangles to refine. More than maxCandidates, because
 *     several usually turn out to be the same outline
 * @param maxCandidates how many candidates to return
 */
public record FinderOptions(
        int searchLongSide, int refineLongSide, int maxAngles, int roughCandidates, int maxCandidates) {

    /** Chosen by timing and accuracy on the test photos: see the README. */
    public static final FinderOptions DEFAULTS = new FinderOptions(480, 1600, 7, 20, 6);
}

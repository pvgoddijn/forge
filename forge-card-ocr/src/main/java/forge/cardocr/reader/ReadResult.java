package forge.cardocr.reader;

import forge.cardocr.finder.*;
import java.util.*;

/**
 * What one read produced.
 *
 * @param matches best first, at most 3
 * @param titleOnly the same before any whole-image scores were merged
 * @param textRead the plain reading behind the best match, or the surest title reading if none
 * @param card the outline the best match was read from, turned so its title is at c0-c1. If that
 *     came from the whole image, the outline with the surest title reading. Null if there was none
 * @param steps wall-clock time per step
 * @param candidates how many outlines were read
 * @param bandsRead title bands recognised
 * @param boxesFound text boxes detected
 * @param boxesRead text boxes recognised
 * @param wholeUsed whether the whole-image pass ran
 * @param nodesVisited trie nodes scored
 * @param usedTracked whether titles were read from the outline the camera tracked
 * @param finderRan whether the finder ran on this image
 * @param strength how far to believe the best title match
 */
public record ReadResult(List<Match> matches, List<Match> titleOnly, String textRead, CardQuad card, Steps steps,
        int candidates, int bandsRead, int boxesFound, int boxesRead, boolean wholeUsed, long nodesVisited, boolean usedTracked,
        boolean finderRan, TitleStrength strength) {

    /** @param fromTitle false if the score came from the whole-image pass */
    public record Match(String name, float score, boolean fromTitle) {
    }

    /** Milliseconds. Whole-image steps are 0 if that pass didn't run. */
    public record Steps(double finder, double rectify, double recognise, double match, double detect, double wholeRectify,
            double wholeRecognise, double wholeMatch) {

        public double total() {
            return finder + rectify + recognise + match + detect + wholeRectify + wholeRecognise + wholeMatch;
        }
    }
}

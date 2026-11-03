package forge.cardocr.reader;

import java.util.*;

/**
 * How much work one read may do. {@link #A_SIX} is the OCR spike's frozen best, and the rest trade
 * accuracy for time.
 *
 * @param id how runs and tables name this budget
 * @param candidates how many of the finder's outlines to read titles from
 * @param windows the title windows per outline and way up
 * @param whole when to detect and read all text in the image
 * @param workers how many threads run recognitions side by side, on single-threaded model sessions
 * @param recogniser threading inside each recogniser call
 * @param detector threading inside the detector call
 * @param detectorLongSide the image's long side for detection
 * @param trustTracked read the tracked outline first when there is one, and run the finder only if
 *     that title comes out weak
 */
public record ReadBudget(String id, int candidates, List<TitleBands.Window> windows, Whole whole, int workers,
        TextModels.Threads recogniser, TextModels.Threads detector, int detectorLongSide, boolean trustTracked) {

    /** When to run the whole-image pass. */
    public enum Whole { ALWAYS, IF_WEAK, NEVER }

    private static final List<TitleBands.Window> FOUR_WINDOWS = List.of(TitleBands.NARROW_HIGH, TitleBands.NARROW_LOW,
            TitleBands.WIDE_HIGH, TitleBands.WIDE_LOW);
    private static final List<TitleBands.Window> ONE_WINDOW = List.of(TitleBands.WIDE_TALL);
    private static final TextModels.Threads FOUR = new TextModels.Threads(4, false);

    static ReadBudget of(String id, int candidates, List<TitleBands.Window> windows, Whole whole) {
        return new ReadBudget(id, candidates, windows, whole, 1, TextModels.Threads.ONE, TextModels.Threads.ONE, 1600, false);
    }

    /** Four workers, and four threads for the detector. */
    public ReadBudget withWorkers(String newId) {
        return new ReadBudget(newId, candidates, windows, whole, 4, recogniser, FOUR, detectorLongSide, trustTracked);
    }

    /** One worker running the recogniser with four threads inside it. */
    public ReadBudget withInnerThreads(String newId) {
        return new ReadBudget(newId, candidates, windows, whole, 1, FOUR, FOUR, detectorLongSide, trustTracked);
    }

    /** XNNPACK is in the Android package but not the desktop one, so this runs on the phone only. */
    public ReadBudget withXnnpack(String newId) {
        return new ReadBudget(newId, candidates, windows, whole, 4, new TextModels.Threads(1, true),
                new TextModels.Threads(4, true), detectorLongSide, trustTracked);
    }

    public ReadBudget trustingTracked(String newId) {
        return new ReadBudget(newId, candidates, windows, whole, workers, recogniser, detector, detectorLongSide, true);
    }

    public static final ReadBudget A_SIX = of("a-6cand-whole", 6, FOUR_WINDOWS, Whole.ALWAYS);
    public static final ReadBudget B_TWO = of("b-2cand-whole", 2, FOUR_WINDOWS, Whole.ALWAYS);
    public static final ReadBudget C_TWO_FOUR = of("c-2cand-4win", 2, FOUR_WINDOWS, Whole.IF_WEAK);
    public static final ReadBudget C_ONE_FOUR = of("c-1cand-4win", 1, FOUR_WINDOWS, Whole.IF_WEAK);
    public static final ReadBudget C_TWO_ONE = of("c-2cand-1win", 2, ONE_WINDOW, Whole.IF_WEAK);
    public static final ReadBudget C_ONE_ONE = of("c-1cand-1win", 1, ONE_WINDOW, Whole.IF_WEAK);

    /** What the app offers. The first is the default. */
    public static final List<ReadBudget> ON_PHONE = List.of(
            C_TWO_ONE.trustingTracked("c-tracked").withWorkers("c-tracked-w4"),
            C_TWO_ONE.withWorkers("c-2cand-1win-w4"),
            C_ONE_ONE.withWorkers("c-1cand-1win-w4"),
            C_TWO_ONE.trustingTracked("c-tracked-w1"),
            C_TWO_ONE.trustingTracked("c-tracked").withInnerThreads("c-tracked-i4"),
            C_TWO_ONE.trustingTracked("c-tracked").withXnnpack("c-tracked-xnn4"),
            C_TWO_FOUR.withWorkers("c-2cand-4win-w4"),
            B_TWO.withWorkers("b-2cand-whole-w4"),
            A_SIX.withWorkers("a-6cand-whole-w4"));

    public static ReadBudget byId(String id) {
        for (ReadBudget budget : ON_PHONE) {
            if (budget.id().equals(id)) {
                return budget;
            }
        }
        return ON_PHONE.get(0);
    }
}

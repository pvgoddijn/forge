package forge.cardocr.assist;

/**
 * Finder time split by whether a card was tracked, comparing wall clock with the thread's own CPU
 * time. Equal times mean the finder is just doing that much work. Wall clearly above CPU means the
 * thread was waiting for a core, through contention. Slower CPU time for the same scene over a
 * session means throttling.
 */
public final class FinderLoad {

    /**
     * Means in ms per frame, 0 with no frames.
     *
     * @param pipelineMs the tracker, signature and glare measuring that follow the finder
     */
    public record Part(int frames, double wallMs, double cpuMs, double searchMs, double refineMs, double pipelineMs) {
    }

    /** One accumulating half of the split. */
    private static final class Totals {
        int frames;
        double wall;
        double cpu;
        double search;
        double refine;
        double pipeline;

        Part part() {
            return frames == 0 ? new Part(0, 0, 0, 0, 0, 0)
                    : new Part(frames, wall / frames, cpu / frames, search / frames, refine / frames, pipeline / frames);
        }
    }

    /** @param local frames that only refitted known outlines, which card and noCard leave out */
    public record Summary(Part card, Part noCard, Part local) {
        public Summary(Part card, Part noCard) {
            this(card, noCard, new Part(0, 0, 0, 0, 0, 0));
        }
    }

    private final Totals card = new Totals();
    private final Totals noCard = new Totals();
    private final Totals local = new Totals();

    public synchronized void add(boolean cardTracked, double wallMs, double cpuMs, double searchMs, double refineMs, double pipelineMs) {
        add(cardTracked ? card : noCard, wallMs, cpuMs, searchMs, refineMs, pipelineMs);
    }

    public synchronized void addLocal(double wallMs, double cpuMs, double refineMs, double pipelineMs) {
        add(local, wallMs, cpuMs, 0, refineMs, pipelineMs);
    }

    private static void add(Totals totals, double wallMs, double cpuMs, double searchMs, double refineMs, double pipelineMs) {
        totals.frames++;
        totals.wall += wallMs;
        totals.cpu += cpuMs;
        totals.search += searchMs;
        totals.refine += refineMs;
        totals.pipeline += pipelineMs;
    }

    public synchronized Summary summary() {
        return new Summary(card.part(), noCard.part(), local.part());
    }
}

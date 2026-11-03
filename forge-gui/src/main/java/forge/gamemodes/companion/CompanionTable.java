package forge.gamemodes.companion;

import forge.game.Game;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputPassPriority;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One tracked game while it runs: the operator's pending actions, and saving after each one.
 * <p>
 * The operator acts from the UI thread, but the game may only be changed on its loop thread. So an action is queued,
 * the priority prompt the loop is waiting on is ended, and the seat's controller runs the queue before prompting again
 * (see {@link CompanionPlayerController#chooseSpellAbilityToPlay}). That keeps state-based actions, triggers and
 * priority working as for anything else a player does.
 */
public class CompanionTable {
    /** An operator action, run on the game loop thread. */
    public interface Op {
        TableEditor.Result run(Game game);
    }

    private final String id;
    private final CompanionSetup setup;
    private final CompanionStore store;
    private final Queue<Op> pending = new ConcurrentLinkedQueue<>();
    private final List<CompanionPlayerController> seats = new CopyOnWriteArrayList<>();
    private volatile Consumer<TableEditor.Result> onResult = r -> { };
    private String lastSaved;

    public CompanionTable(String id, CompanionSetup setup, CompanionStore store) {
        this.id = id;
        this.setup = setup;
        this.store = store;
    }

    public String id() {
        return id;
    }

    public CompanionSetup setup() {
        return setup;
    }

    /** Where results go, for example to tell the operator a card couldn't be cast. Called on the game thread. */
    public void setOnResult(Consumer<TableEditor.Result> onResult0) {
        onResult = onResult0;
    }

    void register(CompanionPlayerController seat) {
        seats.add(seat);
        // An action can arrive just before a prompt opens. End that prompt as it opens, or the action would wait
        // for the operator's next tap.
        seat.getInputQueue().addObserver((queue, arg) -> {
            if (!pending.isEmpty()) {
                endPriorityPrompt(seat);
            }
        });
    }

    /** Queues {@code op}, and ends the priority prompt so the game runs it. Any thread. */
    public void submit(Op op) {
        pending.add(op);
        for (CompanionPlayerController seat : seats) {
            endPriorityPrompt(seat);
        }
    }

    private static void endPriorityPrompt(CompanionPlayerController seat) {
        Input input = seat.getInputQueue().getInput();
        if (input instanceof InputPassPriority priority) {
            priority.restartPrompt();
        }
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    /** The state to start the match from: the latest save, or the setup's start if there is none yet. */
    public List<String> latestState() {
        return store.loadState(id).orElseGet(setup::initialState);
    }

    /** Runs the queued ops. Game loop thread. Returns whether there were any. */
    boolean runPending(Game game) {
        boolean any = false;
        Op op;
        while ((op = pending.poll()) != null) {
            any = true;
            TableEditor.Result result;
            try {
                result = op.run(game);
            } catch (RuntimeException e) {
                // one bad action mustn't stop the table
                e.printStackTrace();
                result = new TableEditor.Result(false, "That didn't work: " + e);
            }
            store.appendLog(id, result.message());
            onResult.accept(result);
        }
        return any;
    }

    /** Saves the game if it changed. Game loop thread, at a settled point: the stack empty, priority with the table. */
    void save(Game game) {
        try {
            String state = CompanionGameState.capture(game);
            if (state.equals(lastSaved)) {
                return;
            }
            store.saveState(id, state);
            lastSaved = state;
        } catch (RuntimeException e) {
            // the game goes on unsaved rather than stopping; the operator is told
            e.printStackTrace();
            onResult.accept(new TableEditor.Result(false, "The game couldn't be saved: " + e.getMessage()));
        }
    }
}

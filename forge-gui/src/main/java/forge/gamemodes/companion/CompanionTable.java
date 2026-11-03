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
    }

    /** Queues {@code op}, and ends the priority prompt so the game runs it. Any thread. */
    public void submit(Op op) {
        pending.add(op);
        for (CompanionPlayerController seat : seats) {
            Input input = seat.getInputQueue().getInput();
            if (input instanceof InputPassPriority priority) {
                priority.restartPrompt();
            }
        }
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
        String state = CompanionGameState.capture(game);
        if (state.equals(lastSaved)) {
            return;
        }
        try {
            store.saveState(id, state);
            lastSaved = state;
        } catch (java.io.UncheckedIOException e) {
            onResult.accept(new TableEditor.Result(false, "The game couldn't be saved: " + e.getMessage()));
        }
    }
}

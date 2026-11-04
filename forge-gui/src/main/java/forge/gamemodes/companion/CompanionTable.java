package forge.gamemodes.companion;

import forge.game.Game;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gui.interfaces.IGuiGame;

import java.util.ArrayList;
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
 * <p>
 * An op that has to wait for a main phase (AC-42, D5: the active player's sorcery outside a main phase with an empty
 * stack) is <em>parked</em> instead of run. While it's parked the seats pass at every stop, so the loop moves on by
 * the rules, until {@link #parkedReady} says the target is reached and {@link #runParked} runs it. Only card-adding
 * ops ({@link Op#waitsBehindParked}) queue behind a parked op, in the order they were submitted, so cards still reach
 * the game in the table's order. Every other op (a life edit, readying creatures) runs at once even while something
 * is parked: it doesn't depend on the waiting card, and the operator expects to see it straight away.
 */
public class CompanionTable {
    /** An operator action, run on the game loop thread. */
    public interface Op {
        TableEditor.Result run(Game game);

        /**
         * The main phase the game has to reach before this op runs, or {@code null} to run it now. Asked on the game
         * loop thread just before the op would run, and again before a parked op runs.
         */
        default PhaseType mainPhaseNeeded(Game game) {
            return null;
        }

        /**
         * Whether this op queues behind a parked op rather than running at once. Only ops that add a card do, so the
         * cards reach the game in the order the table logged them.
         */
        default boolean waitsBehindParked() {
            return false;
        }
    }

    /**
     * Adds the card {@code name} for the player named {@code player} through {@link TableEditor#addCard}. By name,
     * which setup keeps unique: a player's place in the game's list changes if anyone leaves the game. An active
     * player's cast sorcery waits for its main phase ({@link TableEditor#mainPhaseFor}).
     */
    public record AddCard(String player, String name, boolean fromHand, TableEditor.Mode mode) implements Op {
        @Override
        public TableEditor.Result run(Game game) {
            Player target = find(game);
            if (target == null) {
                return new TableEditor.Result(false, player + " isn't in the game: " + name + " wasn't added");
            }
            return TableEditor.addCard(target, name, fromHand, mode);
        }

        @Override
        public PhaseType mainPhaseNeeded(Game game) {
            Player target = find(game);
            return target == null ? null : TableEditor.mainPhaseFor(game, target, name, mode);
        }

        @Override
        public boolean waitsBehindParked() {
            return true;
        }

        private Player find(Game game) {
            return game.getPlayers().stream().filter(p -> p.getName().equals(player)).findFirst().orElse(null);
        }
    }

    private final String id;
    private volatile CompanionSetup setup; // the options in it can change during the game
    private final CompanionStore store;
    private final Queue<Op> pending = new ConcurrentLinkedQueue<>();
    private final List<CompanionPlayerController> seats = new CopyOnWriteArrayList<>();
    private volatile Consumer<TableEditor.Result> onResult = r -> { };
    private volatile Consumer<String> onNotice = n -> { };
    private String lastSaved;
    private boolean saveFailing;
    /** Ops waiting for {@link #parkedFor}, the first being the one that waits. Game loop thread only. */
    private final List<Op> parked = new ArrayList<>();
    private PhaseType parkedFor;
    private int parkedTurn = -1;
    /** No turn: no game is running yet, or combat isn't wanted for a single turn. Turns count from 1. */
    private static final int NO_TURN = -1;
    /** The turn the operator wants combat through the engine for, though it's off for the game. */
    private volatile int combatTurn = NO_TURN;

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

    /** Whether combat is declared through the engine every turn. A change counts from the next combat. */
    public boolean engineCombat() {
        return setup.engineCombat();
    }

    /** Whether combat in {@code turn} is declared through the engine: every turn, or just this one. */
    public boolean engineCombat(int turn) {
        return setup.engineCombat() || (turn != NO_TURN && combatTurn == turn);
    }

    /**
     * Turns combat through the engine on or off for {@code turn} only, for a fight the table wants resolved by the
     * rules. It ends with the turn. It isn't written to disk, so it lasts through a restart from the menu but not a
     * resume after the app was closed; a resume mid-turn is rare enough to just turn it on again. Turning it on for
     * {@link #NO_TURN} stores nothing, which only matters before a game runs, when there's no menu to do it from.
     */
    public void setEngineCombatForTurn(int turn, boolean on) {
        combatTurn = on ? turn : NO_TURN;
    }

    /** Whether combat goes through the engine in the turn being played now. Any thread. */
    public boolean engineCombatThisTurn() {
        return engineCombat(currentTurn());
    }

    /** Turns combat through the engine on or off for the turn being played now. Any thread. */
    public void setEngineCombatThisTurn(boolean on) {
        setEngineCombatForTurn(currentTurn(), on);
    }

    /** The game's own turn number, from the game rather than its view, which a load updates only afterwards. */
    private int currentTurn() {
        return seats.stream().findFirst().map(seat -> seat.getGame().getPhaseHandler().getTurn()).orElse(NO_TURN);
    }

    /** Turns combat through the engine on or off for this game, and saves the choice with it. Any thread. */
    public void setEngineCombat(boolean on) {
        CompanionSetup changed = setup.withEngineCombat(on);
        setup = changed;
        try {
            store.saveSetup(id, changed);
        } catch (java.io.UncheckedIOException e) {
            onResult.accept(new TableEditor.Result(false, "The combat setting couldn't be saved: " + e.getMessage()));
        }
    }

    /** Where results go, for example to tell the operator a card couldn't be cast. Called on the game thread. */
    public void setOnResult(Consumer<TableEditor.Result> onResult0) {
        onResult = onResult0;
    }

    /** Where notices go: things the game decided that the operator should know about. Called on the game thread. */
    public void setOnNotice(Consumer<String> onNotice0) {
        onNotice = onNotice0;
    }

    /** Tells the operator something the game decided, and keeps it in the game's log. */
    void tell(String notice) {
        tell(notice, List.of());
    }

    /** {@link #tell(String)}, about the seats {@code affected}. */
    private void tell(String notice, List<String> affected) {
        store.appendLog(id, entry(CompanionLogEntry.Kind.NOTICE, affected, notice));
        onNotice.accept(notice);
    }

    /**
     * A log entry under the step the next save writes, with the running game's turn and active seat. Before a
     * game runs, the turn is 0 and the active seat unknown.
     */
    private CompanionLogEntry entry(CompanionLogEntry.Kind kind, List<String> affected, String text) {
        Game game = seats.stream().findFirst().map(CompanionPlayerController::getGame).orElse(null);
        int turn = game == null ? 0 : Math.max(game.getPhaseHandler().getTurn(), 0);
        Player active = game == null ? null : game.getPhaseHandler().getPlayerTurn();
        return CompanionLogEntry.of(kind, store.latestStep(id) + 1, turn, active == null ? null : active.getName(),
                affected, text);
    }

    void register(CompanionPlayerController seat) {
        // A restart builds new seats: the old game's are done. This runs on the thread building the new game, not the
        // game loop, yet touching the loop-only parked list is safe: the table is reused only by a restart or
        // continue from the win/lose screen (HostedMatch.restartMatch/continueMatch, on the UI thread). That screen
        // opens on GameEventGameFinished, which Match.startGame fires on the old loop thread only after the main loop
        // has returned, so that loop has made its last parkedReady/runParked call. The event's hand-off to the UI
        // thread, and the new loop's start through game.getAction().invoke, order the writes. A game opened from the
        // setup screen gets a new table.
        boolean restarted = seats.removeIf(old -> old.getGame() != seat.getGame());
        seats.add(seat); // first, so the notice below is logged with the new game's turn
        if (restarted && !parked.isEmpty()) {
            // they were for the old game; the restart is from a save made before they were submitted
            tell("The game was restarted: " + nameOf(parked.get(0)) + " and anything after it wasn't added",
                    affectedBy(parked.get(0)));
            parked.clear();
        }
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
        InputPassPriority priority = priorityPrompt(seat);
        if (priority != null) {
            priority.restartPrompt();
        }
    }

    /** The priority prompt {@code seat} is waiting at, or null when it's at another prompt (a target, a choice) or none. */
    private static InputPassPriority priorityPrompt(CompanionPlayerController seat) {
        Input input = seat.getInputQueue().getInput();
        return input instanceof InputPassPriority priority ? priority : null;
    }

    /**
     * Whether a seat is waiting at a priority prompt, so the table is free for an op. False during a target or choice
     * prompt, where a tap on a player answers that prompt instead. Any thread.
     */
    public boolean atPriorityPrompt() {
        return seats.stream().anyMatch(seat -> priorityPrompt(seat) != null);
    }

    /**
     * Asks the operator a yes/no question from inside an op, through the GUI of the seat the op runs from: ops run
     * from the priority player's {@link CompanionPlayerController#chooseSpellAbilityToPlay}. Game loop thread; blocks
     * until answered. Returns {@code defaultIsYes} when there's no seat with a GUI to ask, as in a headless test.
     */
    public boolean confirm(Game game, String question, boolean defaultIsYes) {
        Player asking = game.getPhaseHandler().getPriorityPlayer();
        IGuiGame gui = seats.stream().filter(seat -> seat.getPlayer() == asking).findFirst()
                .or(() -> seats.stream().findFirst()).map(CompanionPlayerController::getGui).orElse(null);
        if (gui == null) {
            return defaultIsYes;
        }
        return gui.confirm(null, question, defaultIsYes, List.of("Yes", "No"));
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    /** The state to start the match from: the latest save, or the setup's start if there is none yet. */
    public List<String> latestState() {
        return store.loadState(id).orElseGet(setup::initialState);
    }

    /**
     * Starts {@code game} from {@link #latestState}: the match's start hook. When that is a save, the game as loaded
     * counts as that step, so the first prompt doesn't save it again as a new step, which the next undo would only
     * take back to the same state. Game loop thread.
     */
    public void startFromLatest(Game game) {
        CompanionGameState.of(latestState()).applyNow(game);
        lastSaved = store.latestStep(id) == CompanionStore.NO_STEP ? null : CompanionGameState.capture(game);
    }

    /**
     * Takes back the last action (AC-27): queued like any op, so it runs on the game loop thread in its turn. See
     * {@link Undo}. Any thread.
     */
    public void undo() {
        submit(new Undo());
    }

    /**
     * Undo puts the save before the last action back in place: the latest save when something happened since it (a
     * spell on the stack, a combat, an op not yet saved, the turn moved on), as that is the action being taken back,
     * and otherwise the one before it. The undone step's file goes before the log says so, so a restart never loads
     * it; the log only gains an entry naming the step, so the undone entries stay, marked
     * ({@link CompanionLogEntry#undone}). What a save doesn't hold goes too: combat for one turn unless the turn is
     * the same, and parked ops, which were all submitted after the latest save (nothing is saved while one waits).
     */
    private final class Undo implements Op {
        @Override
        public TableEditor.Result run(Game game) {
            int latest = store.latestStep(id);
            if (latest == CompanionStore.NO_STEP) {
                return new TableEditor.Result(false, "Nothing to undo yet");
            }
            boolean settled = settled(game);
            int back = settled ? latest - 1 : latest;
            if (back < 0) {
                return new TableEditor.Result(false, "Nothing to undo: this is the start of the game");
            }
            List<String> state = store.loadStep(id, back).orElse(null);
            if (state == null) {
                return new TableEditor.Result(false, "Step " + back + " of the game wasn't found: nothing was undone");
            }
            CompanionGameState.of(state).applyNow(game);
            store.discardAfter(id, back); // files first, then the log
            lastSaved = CompanionGameState.capture(game);
            PhaseHandler ph = game.getPhaseHandler();
            if (combatTurn != ph.getTurn()) {
                combatTurn = NO_TURN;
            }
            Player active = ph.getPlayerTurn();
            // the entries of the action taken back carry the step its save wrote, or would have written
            String text = "Undone: back to turn " + ph.getTurn() + (active == null ? "" : ", " + active.getName());
            store.appendLog(id, CompanionLogEntry.undo(back, List.of(back + 1), Math.max(ph.getTurn(), 0),
                    active == null ? null : active.getName(), text));
            if (!parked.isEmpty()) {
                List<String> affected = affectedBy(parked.get(0));
                String first = nameOf(parked.get(0));
                parked.clear();
                parkedFor = null;
                parkedTurn = -1;
                tell("Undone: " + first + " and anything after it wasn't added", affected);
            }
            return new TableEditor.Result(true, text);
        }

        /** Whether the game is as the latest save left it: nothing on the stack, no combat, nothing changed. */
        private boolean settled(Game game) {
            if (!game.getStack().isEmpty() || game.getStack().hasSimultaneousStackEntries()
                    || game.getPhaseHandler().inCombat() || lastSaved == null) {
                return false;
            }
            try {
                return CompanionGameState.capture(game).equals(lastSaved);
            } catch (RuntimeException e) {
                e.printStackTrace();
                return false; // it can't be compared, so it isn't as saved
            }
        }
    }

    /**
     * Runs the queued ops in order, until one needs a main phase that isn't reached: that one and everything after it
     * are parked (see {@link #isParked}). While ops are parked, newly queued card-adding ones join them at the back
     * instead of running ({@link Op#waitsBehindParked}); the others run at once. Game loop thread. Returns whether it took any ops, run or parked, so the caller asks the loop again.
     */
    boolean runPending(Game game) {
        boolean any = false;
        Op op;
        while ((op = pending.poll()) != null) {
            any = true;
            if (!parked.isEmpty() && op.waitsBehindParked()) {
                // the order stays the table's, but the operator should know why nothing happened yet
                tell(nameOf(op) + " waits behind " + nameOf(parked.get(0)), affectedBy(op));
                parked.add(op);
                continue;
            }
            PhaseType target = mainPhaseNeeded(game, op);
            if (target != null) {
                park(game, op, target);
                continue;
            }
            runOne(game, op);
        }
        return any;
    }

    /** Whether ops are waiting for a main phase. Until {@link #parkedReady} is true, every seat should pass. */
    boolean isParked() {
        return !parked.isEmpty();
    }

    /**
     * Whether {@link #runParked} should be called now: the turn the ops were parked in is at their main phase with an
     * empty stack, or that main phase was missed (a later step of that turn, or another turn). False when nothing is
     * parked. Game loop thread; changes nothing.
     */
    boolean parkedReady(Game game) {
        if (parked.isEmpty()) {
            return false;
        }
        PhaseHandler ph = game.getPhaseHandler();
        if (ph.getTurn() != parkedTurn) {
            return true;
        }
        PhaseType phase = ph.getPhase();
        if (phase == null) {
            return false;
        }
        return phase == parkedFor ? game.getStack().isEmpty() : phase.isAfter(parkedFor);
    }

    /**
     * Runs the parked ops in order, through the same result and log path as {@link #runPending}. Each is asked
     * {@link Op#mainPhaseNeeded} again first, so a second sorcery that now meets the first on the stack is parked
     * again, for when it has resolved, and one whose Main 1 was skipped waits for Main 2. When the turn has ended
     * since they were parked, there's no going back (D5): they all run now, with a notice. Game loop thread.
     */
    void runParked(Game game) {
        if (parked.isEmpty()) {
            return;
        }
        List<Op> ops = new ArrayList<>(parked);
        PhaseType waitedFor = parkedFor;
        boolean turnEnded = game.getPhaseHandler().getTurn() != parkedTurn;
        parked.clear();
        parkedFor = null;
        parkedTurn = -1;
        if (turnEnded) {
            tell("The turn ended before " + mainName(waitedFor) + ": " + nameOf(ops.get(0)) + " is cast now");
            ops.forEach(op -> runOne(game, op));
            return;
        }
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            PhaseType target = mainPhaseNeeded(game, op);
            if (target != null) {
                park(game, op, target);
                parked.addAll(ops.subList(i + 1, ops.size()));
                return;
            }
            PhaseType phase = game.getPhaseHandler().getPhase();
            if (i == 0 && phase != null && phase.isAfter(PhaseType.MAIN2)) {
                // past it, and no main phase left in this turn: the end step or cleanup
                tell("The main phases of this turn are over: " + nameOf(op) + " is cast now");
            }
            runOne(game, op);
        }
    }

    private void park(Game game, Op op, PhaseType target) {
        parked.add(op);
        parkedFor = target;
        parkedTurn = game.getPhaseHandler().getTurn();
        tell(nameOf(op) + " waits for " + mainName(target), affectedBy(op));
    }

    private static PhaseType mainPhaseNeeded(Game game, Op op) {
        try {
            return op.mainPhaseNeeded(game);
        } catch (RuntimeException e) {
            // run it now: its own run reports what's wrong
            e.printStackTrace();
            return null;
        }
    }

    private void runOne(Game game, Op op) {
        TableEditor.Result result;
        try {
            result = op.run(game);
        } catch (RuntimeException e) {
            // one bad action mustn't stop the table
            e.printStackTrace();
            result = new TableEditor.Result(false, "That didn't work: " + e);
        }
        // the result says whom it was about and what left the hand; an op's own failure (a seat not in the game, an
        // exception) has none, so the op says what it can
        if (op instanceof Undo && result.done()) {
            onResult.accept(result); // it wrote its own entry
            return;
        }
        List<String> affected = result.affected().isEmpty() ? affectedBy(op) : result.affected();
        String card = result.card() != null ? result.card() : op instanceof AddCard add ? add.name() : null;
        CompanionLogEntry entry = entry(!result.done() ? CompanionLogEntry.Kind.NOTICE
                : op instanceof AddCard ? CompanionLogEntry.Kind.ADD : CompanionLogEntry.Kind.ACTION,
                affected, result.message());
        if (card != null) {
            entry = entry.withCard(card, result.fromHand());
        }
        store.appendLog(id, entry);
        onResult.accept(result);
    }

    /** The seats an op is about, as far as the op says: the player an add is for. */
    private static List<String> affectedBy(Op op) {
        return op instanceof AddCard add ? List.of(add.player()) : List.of();
    }

    private static String nameOf(Op op) {
        return op instanceof AddCard add ? add.name() : "The action";
    }

    private static String mainName(PhaseType main) {
        return main == PhaseType.MAIN1 ? "Main 1" : "Main 2";
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
            saveFailing = false;
        } catch (RuntimeException e) {
            // the game goes on unsaved rather than stopping; the operator is told once, not at every prompt
            e.printStackTrace();
            if (!saveFailing) {
                saveFailing = true;
                onResult.accept(new TableEditor.Result(false, "The game couldn't be saved: " + e.getMessage()));
            }
        }
    }
}

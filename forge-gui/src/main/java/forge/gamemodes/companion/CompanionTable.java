package forge.gamemodes.companion;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.*;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gui.interfaces.IGuiGame;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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
            return seat(game, player);
        }
    }

    /**
     * The "wrong player" correction (AC-22a): the card {@code card} logged for {@code from} goes to {@code to},
     * through {@link TableEditor#reassign}, found wherever it is now ({@link TableEditor#findToReassign}). By names,
     * as the log has them: card ids don't survive a load, so not an undo either. {@code fromHand} is the add entry's,
     * what left {@code from}'s hand. Written to the log as a {@code correction}.
     */
    public record Reassign(String from, String card, String to, CompanionLogEntry.FromHand fromHand) implements Op {
        @Override
        public TableEditor.Result run(Game game) {
            List<String> seats = List.of(from, to);
            if (from.equals(to)) {
                return new TableEditor.Result(false, card + " is already " + to + "'s: nothing was changed", seats,
                        card, null);
            }
            Player original = seat(game, from);
            Player target = seat(game, to);
            if (original == null || target == null) {
                return new TableEditor.Result(false, (original == null ? from : to) + " isn't in the game: " + card
                        + " wasn't given to " + to, seats, card, null);
            }
            Card found = TableEditor.findToReassign(original, card);
            if (found == null) {
                return new TableEditor.Result(false, from + "'s " + card + " wasn't found: nothing was changed", seats,
                        card, null);
            }
            TableEditor.Result result = TableEditor.reassign(found, target, fromHand);
            // the log's name for it, which slice 5 matches against the add entry
            return new TableEditor.Result(result.done(), result.message(), result.affected(), card, result.fromHand());
        }
    }

    /**
     * The operator's "Move…" (AC-22): the card {@code card} with id {@code cardId} goes to {@code player}'s
     * {@code zone}, through {@link TableEditor#moveTo}. The card is picked on the screen, so by id, as
     * {@link TableEditor#readyCreatures} does; the name guards against an id that a load or undo since gave to
     * another card. Written to the log as a {@code move}.
     */
    public record Move(int cardId, String card, String player, ZoneType zone) implements Op {
        @Override
        public TableEditor.Result run(Game game) {
            Player target = seat(game, player);
            if (target == null) {
                return new TableEditor.Result(false, player + " isn't in the game: " + card + " wasn't moved",
                        List.of(player), card, null);
            }
            Card found = game.findById(cardId);
            if (found == null || !card.equals(found.getName())) {
                return new TableEditor.Result(false, card + " wasn't found: nothing was moved", List.of(player), card,
                        null);
            }
            return TableEditor.moveTo(found, target, zone);
        }
    }

    /** The player whose setup name is {@code name}, or null when they aren't in the game. */
    private static Player seat(Game game, String name) {
        return game.getPlayers().stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
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
    /** Whether an op other than undo has run since the latest save. See {@link #ranSinceSave}. Game loop thread only. */
    private boolean ranSinceSave;
    /** Ops waiting for {@link #parkedFor}, the first being the one that waits. Game loop thread only. */
    private final List<Op> parked = new ArrayList<>();
    private PhaseType parkedFor;
    private int parkedTurn = -1;
    /** No turn: no game is running yet, or combat isn't wanted for a single turn. Turns count from 1. */
    private static final int NO_TURN = -1;
    /** The turn the operator wants combat through the engine for, though it's off for the game. */
    private volatile int combatTurn = NO_TURN;
    /** Whether {@link #undo} had something to take back at the latest priority prompt. See {@link #canUndo}. */
    private volatile boolean canUndo;
    /** The cards "wrong player" could correct at the latest priority prompt. See {@link #addedThisTurn}. */
    private volatile List<CompanionLogEntry.Added> addedThisTurn = List.of();
    /** The active seat's summoning-sick creatures at the latest prompt, if it was before combat. See {@link #sickBeforeCombat}. */
    private volatile List<Integer> sickBeforeCombat = List.of();
    /** The game's log entries as of the latest priority prompt. See {@link #logAtPrompt}. */
    private volatile List<CompanionLogEntry> logAtPrompt = List.of();
    /** Every seat's mana sources at the latest prompt, for {@link #couldHaveCast}. */
    private volatile List<TableEditor.ManaSources> manaSources = List.of();
    /** The turn and phase of the latest priority prompt, for {@link #promptNote}. */
    private volatile int promptTurn = NO_TURN;
    private volatile PhaseType promptPhase;
    /**
     * The game's log as written, and its latest step, kept in memory so a prompt or a log entry doesn't read the log
     * file or list the steps folder (L3). Null until first needed; read again from the store after a load, which may
     * add an entry ({@link CompanionStore#loadState}), and after a write that failed part way. This table is the only
     * writer while its game runs. Guarded by {@code this}.
     */
    private List<CompanionLogEntry> log;
    private int latestStep;
    /** The Forge game log entry types copied into the table's log as {@code engine} entries. Not phases or mana. */
    private static final Set<GameLogEntryType> ENGINE_TYPES = EnumSet.of(GameLogEntryType.TURN,
            GameLogEntryType.LIFE, GameLogEntryType.DAMAGE, GameLogEntryType.ZONE_CHANGE, GameLogEntryType.DISCARD,
            GameLogEntryType.STACK_RESOLVE, GameLogEntryType.COMBAT, GameLogEntryType.PLAYER_CONTROL);
    /** The running game's engine log copier; a restart replaces it, which stops the old game's. */
    private volatile EngineLog engineLog;
    /**
     * Whether the table itself is changing the game: an op runs, or a save is applied. What the engine logs then isn't
     * copied: an op writes its own entry, and a save applied (a start, an undo) sets every life total again, which
     * isn't something that happened. Game loop thread only.
     */
    private boolean tableActing;

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
        append(entry(CompanionLogEntry.Kind.NOTICE, affected, notice));
        onNotice.accept(notice);
    }

    /** Writes {@code entry} through the store, which never throws for it, and keeps it in {@link #log}. */
    private synchronized void append(CompanionLogEntry entry) {
        List<CompanionLogEntry> entries = cachedLog();
        entries.add(store.appendLog(id, entry));
    }

    private synchronized List<CompanionLogEntry> cachedLog() {
        if (log == null) {
            log = new ArrayList<>(store.loadLog(id));
            latestStep = store.latestStep(id);
        }
        return log;
    }

    /** The game's log entries as written, oldest first: what {@link CompanionStore#loadLog} reads, from memory. */
    synchronized List<CompanionLogEntry> log() {
        return List.copyOf(cachedLog());
    }

    /** The game's current step, as {@link CompanionStore#latestStep} says, from memory. */
    synchronized int latestStep() {
        cachedLog();
        return latestStep;
    }

    private synchronized void setLatestStep(int step) {
        cachedLog();
        latestStep = step;
    }

    /** Reads the log and step from the store again at next use: after a load, or a write that may have half happened. */
    private synchronized void forgetCache() {
        log = null;
    }

    /**
     * A log entry under the step the next save writes, with the running game's turn and active seat. Before a
     * game runs, the turn is 0 and the active seat unknown.
     */
    private CompanionLogEntry entry(CompanionLogEntry.Kind kind, List<String> affected, String text) {
        Game game = seats.stream().findFirst().map(CompanionPlayerController::getGame).orElse(null);
        int turn = game == null ? 0 : Math.max(game.getPhaseHandler().getTurn(), 0);
        Player active = game == null ? null : game.getPhaseHandler().getPlayerTurn();
        return CompanionLogEntry.of(kind, latestStep() + 1, turn, active == null ? null : active.getName(),
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
        EngineLog current = engineLog;
        if (current == null || current.game != seat.getGame()) {
            // the first seat of this game: copy what its engine logs. Game has no unsubscribe, so the old game's copier
            // stays on the old game's event bus, which nothing posts to after its loop ended, and is stopped by this
            EngineLog copier = new EngineLog(seat.getGame());
            engineLog = copier;
            seat.getGame().subscribeToEvents(copier);
        }
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

    /**
     * Copies what the engine logs into the table's log as {@code engine} entries, under the step the next save writes,
     * like any entry. It watches the game's events and formats each with the game log's own formatter, so the text and
     * type are those of Forge's {@code GameLog}. Not the {@code GameLog} itself: its entries don't say which player
     * they are about, and an event does. Called on the game loop thread, where the game posts its events.
     */
    private final class EngineLog {
        final Game game;

        EngineLog(Game game) {
            this.game = game;
        }

        @Subscribe
        public void receive(GameEvent event) {
            // replaced by a restart, or the table is acting; the outcome's formatting adds to Forge's log itself
            if (engineLog != this || tableActing || event instanceof GameEventGameOutcome) {
                return;
            }
            try {
                GameLogEntry logged = event.visit(game.getGameLog().getEventVisitor());
                if (logged != null && ENGINE_TYPES.contains(logged.type())) {
                    append(entry(CompanionLogEntry.Kind.ENGINE, affectedBy(game, event, logged), logged.message()));
                }
            } catch (RuntimeException e) {
                // the history misses a line rather than the game stopping
                e.printStackTrace();
            }
        }
    }

    /**
     * The seats an engine log entry is about, by name: the players its event names, as a life change names whose life
     * changed. For an event that names none, such as a line a card's effect logs, the seats whose names its text
     * mentions as a whole word; a seat named like a card ("Angel" in "Serra Angel") can then be matched wrongly, but
     * only there.
     */
    static List<String> affectedBy(Game game, GameEvent event, GameLogEntry logged) {
        Set<PlayerView> players = new LinkedHashSet<>();
        if (event instanceof GameEventPlayerLivesChanged e) {
            players.add(e.player());
        } else if (event instanceof GameEventPlayerDamaged e) {
            players.add(e.target());
            addController(players, e.source()); // "Bo's Bears deals 2 to Ann" is about Bo too
        } else if (event instanceof GameEventCardDamaged e) {
            addController(players, e.card());
            addController(players, e.source());
        } else if (event instanceof GameEventPlayerPoisoned e) {
            players.add(e.receiver());
        } else if (event instanceof GameEventPlayerRadiation e) {
            players.add(e.receiver());
        } else if (event instanceof GameEventTurnBegan e) {
            players.add(e.turnOwner());
        } else if (event instanceof GameEventCardChangeZone e) {
            addController(players, e.card());
            if (e.card() != null) {
                players.add(e.card().getOwner()); // a stolen creature dies into its owner's graveyard
            }
            // the zones' players: a stolen creature leaves its thief's battlefield for its owner's graveyard
            addZone(players, e.from());
            addZone(players, e.to());
        } else if (event instanceof GameEventPlayerControl e) {
            players.add(e.player());
        } else if (event instanceof GameEventAttackersDeclared e) {
            players.add(e.player());
            e.attackersMap().keySet().forEach(defender -> addEntity(players, defender));
        } else if (event instanceof GameEventBlockersDeclared e) {
            players.add(e.defendingPlayer());
            e.blockers().keySet().forEach(defender -> addEntity(players, defender));
        } else if (event instanceof GameEventSpellResolved e) {
            if (e.spell() != null) {
                addController(players, e.spell().getHostCard());
            }
        } else if (event instanceof GameEventScry e) {
            players.add(e.player());
        } else if (event instanceof GameEventSurveil e) {
            players.add(e.player());
        } else if (event instanceof GameEventCardModeChosen e) {
            players.add(e.player());
        } else if (logged.sourceCard() != null) {
            addController(players, logged.sourceCard()); // a discard names the card discarded
        }
        List<String> names = new ArrayList<>();
        for (PlayerView player : players) {
            if (player != null && !names.contains(player.getName())) {
                names.add(player.getName());
            }
        }
        if (names.isEmpty()) {
            for (Player player : game.getPlayers()) {
                if (mentions(logged.message(), player.getName())) {
                    names.add(player.getName());
                }
            }
        }
        return names;
    }

    private static void addController(Set<PlayerView> players, CardView card) {
        if (card != null) {
            players.add(card.getController() != null ? card.getController() : card.getOwner());
        }
    }

    private static void addZone(Set<PlayerView> players, ZoneView zone) {
        if (zone != null && zone.player() != null) {
            players.add(zone.player());
        }
    }

    private static void addEntity(Set<PlayerView> players, GameEntityView entity) {
        if (entity instanceof PlayerView player) {
            players.add(player);
        } else if (entity instanceof CardView card) {
            addController(players, card); // a planeswalker or battle attacked
        }
    }

    /** Whether {@code text} has {@code name} in it as a whole word, not as part of a longer one. */
    static boolean mentions(String text, String name) {
        return text != null && !name.isEmpty() && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name)
                + "(?![\\p{L}\\p{N}])").matcher(text).find();
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
        List<String> state = store.loadState(id).orElseGet(setup::initialState);
        forgetCache(); // the load may have added an entry
        return state;
    }

    /**
     * Starts {@code game} from {@link #latestState}: the match's start hook. When that is a save, the game as loaded
     * counts as that step, so the first prompt doesn't save it again as a new step, which the next undo would only
     * take back to the same state. Game loop thread.
     */
    public void startFromLatest(Game game) {
        List<String> state = latestState();
        tableActing = true;
        try {
            CompanionGameState.of(state).applyNow(game);
        } finally {
            tableActing = false;
        }
        lastSaved = latestStep() == CompanionStore.NO_STEP ? null : CompanionGameState.capture(game);
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
     * the same, parked ops, which were all submitted after the latest save (nothing is saved while one waits), and
     * every seat's yield (End Turn).
     */
    private final class Undo implements Op {
        @Override
        public TableEditor.Result run(Game game) {
            int latest = latestStep();
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
            // Ops run at every priority, so an undo tapped just after End Turn can run while that yield passes Main 2
            // or the end step. A save doesn't hold the yield, which is the controller's: left on, it would pass the
            // main phase put back too, and end the turn again. So it ends here, and the screen is told.
            for (CompanionPlayerController seat : seats) {
                seat.getYieldController().clearActiveYieldAndDispatch();
            }
            // files first, then the log: a crash in between leaves entries for a step that isn't on disk, which the
            // resume marks undone (CompanionStore.loadState), so a reused step number can't hide them
            store.discardAfter(id, back);
            setLatestStep(back);
            lastSaved = CompanionGameState.capture(game);
            ranSinceSave = false; // what ran is taken back
            PhaseHandler ph = game.getPhaseHandler();
            if (combatTurn != ph.getTurn()) {
                combatTurn = NO_TURN;
            }
            Player active = ph.getPlayerTurn();
            // the entries of the action taken back carry the step its save wrote, or would have written
            String text = "Undone: back to turn " + ph.getTurn() + (active == null ? "" : ", " + active.getName());
            append(CompanionLogEntry.undo(back, List.of(back + 1), Math.max(ph.getTurn(), 0),
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
        tableActing = true;
        try {
            result = op.run(game);
        } catch (RuntimeException e) {
            // one bad action mustn't stop the table
            e.printStackTrace();
            forgetCache(); // an undo may have stopped between its files and the log
            result = new TableEditor.Result(false, "That didn't work: " + e);
        } finally {
            tableActing = false;
        }
        if (!(op instanceof Undo)) {
            ranSinceSave = true; // even one that failed may have changed something; a save of no change writes nothing
        }
        // the result says whom it was about and what left the hand; an op's own failure (a seat not in the game, an
        // exception) has none, so the op says what it can
        if (op instanceof Undo && result.done()) {
            onResult.accept(result); // it wrote its own entry
            return;
        }
        List<String> affected = result.affected().isEmpty() ? affectedBy(op) : result.affected();
        String card = result.card() != null ? result.card() : op instanceof AddCard add ? add.name()
                : op instanceof Reassign fix ? fix.card() : op instanceof Move move ? move.card() : null;
        CompanionLogEntry entry = entry(!result.done() ? CompanionLogEntry.Kind.NOTICE
                : op instanceof AddCard ? CompanionLogEntry.Kind.ADD
                : op instanceof Reassign ? CompanionLogEntry.Kind.CORRECTION
                : op instanceof Move ? CompanionLogEntry.Kind.MOVE : CompanionLogEntry.Kind.ACTION,
                affected, result.message());
        if (card != null) {
            entry = entry.withCard(card, result.fromHand());
        }
        append(entry);
        onResult.accept(result);
    }

    /** The seats an op is about, as far as the op says: the player an add is for, both seats of a correction. */
    private static List<String> affectedBy(Op op) {
        return op instanceof AddCard add ? List.of(add.player())
                : op instanceof Reassign fix ? List.of(fix.from(), fix.to())
                : op instanceof Move move ? List.of(move.player()) : List.of();
    }

    private static String nameOf(Op op) {
        return op instanceof AddCard add ? add.name() : op instanceof Reassign fix ? fix.card()
                : op instanceof Move move ? move.card() : "The action";
    }

    private static String mainName(PhaseType main) {
        return main == PhaseType.MAIN1 ? "Main 1" : "Main 2";
    }

    /**
     * Before each priority prompt the operator sees: saves the game in a main phase with an empty stack (a game state
     * can't hold a combat in progress), then works out {@link #canUndo} for that prompt. Game loop thread.
     */
    void atPrompt(Game game) {
        PhaseType phase = game.getPhaseHandler().getPhase();
        boolean settledPoint = game.getStack().isEmpty() && phase != null && phase.isMain();
        if (settledPoint) {
            save(game);
        }
        int latest = latestStep();
        // as Undo decides: past step 0 there's always a save to go back to; at step 0 only when the game has moved
        // on from it (something on the stack, a combat stop, a save that failed), as then step 0 itself is the target
        boolean asSaved = settledPoint && !saveFailing && !game.getStack().hasSimultaneousStackEntries();
        canUndo = latest > 0 || (latest == 0 && !asSaved);
        addedThisTurn = findAddedThisTurn(game);
        sickBeforeCombat = findSickBeforeCombat(game);
        manaSources = TableEditor.manaSources(game);
        promptTurn = game.getPhaseHandler().getTurn();
        promptPhase = phase;
        try {
            logAtPrompt = log();
        } catch (RuntimeException e) {
            // an unreadable log mustn't stop the prompt; the history keeps what it showed last
            e.printStackTrace();
        }
    }

    /**
     * The active seat's creatures that can't attack only because they came in this turn, when the game is still
     * before this turn's combat; after it, readying them changes nothing until the next turn.
     */
    private static List<Integer> findSickBeforeCombat(Game game) {
        PhaseHandler ph = game.getPhaseHandler();
        PhaseType phase = ph.getPhase();
        Player active = ph.getPlayerTurn();
        if (phase == null || active == null || !phase.isBefore(PhaseType.COMBAT_BEGIN)) {
            return List.of();
        }
        return TableEditor.sickCreatures(active).stream().map(Card::getId).toList();
    }

    /**
     * The ids of the active seat's summoning-sick creatures as of the latest priority prompt, when it was before this
     * turn's combat, and otherwise none. With engine combat on, the engine asks for attackers only if a creature can
     * attack, so a turn whose creatures were all logged this turn skips combat without a word: the game menu offers
     * to ready these when combat is switched on. Safe from any thread; out of date only between prompts, like
     * {@link #canUndo}, and the ready op checks each card again.
     */
    public List<Integer> sickBeforeCombat() {
        return sickBeforeCombat;
    }

    /**
     * The seats, by setup name, whose mana sources at the latest priority prompt could have paid for {@code card}
     * when it's an instant or has flash, for the add dialog to offer (item 11); every seat for any other card. Empty
     * before the first prompt, or when nobody could have: the dialog then offers every seat (AC-39). See
     * {@link TableEditor#couldHaveCast(Game, String)}. Safe from any thread: it reads only what the prompt published
     * and card data, and like {@link #canUndo} it can be out of date only between prompts.
     */
    public List<String> couldHaveCast(String card) {
        return TableEditor.couldHaveCast(manaSources, card);
    }

    /**
     * A line for the active seat's Main 1 prompt saying whether combat goes through the engine this turn, and how to
     * change that, or null at any other prompt. Read live, so a prompt shown again after the operator switches combat
     * says so. Safe from any thread.
     */
    public String promptNote() {
        if (promptPhase != PhaseType.MAIN1) {
            return null;
        }
        if (!engineCombat(promptTurn)) {
            return "Combat: off (game menu → Combat this turn)";
        }
        int sick = sickBeforeCombat.size();
        return sick == 0 ? "Combat: on"
                : "Combat: on, but " + sick + (sick == 1 ? " creature is" : " creatures are")
                        + " summoning sick (game menu → Ready creatures…)";
    }

    /**
     * Shows the priority prompt again without running anything, so its message is redrawn after a setting it shows
     * changed. Any thread.
     */
    public void refreshPrompt() {
        for (CompanionPlayerController seat : seats) {
            endPriorityPrompt(seat);
        }
    }

    /** This turn's adds from the log that are still in the game, where {@link Reassign} would find them. */
    private List<CompanionLogEntry.Added> findAddedThisTurn(Game game) {
        try {
            List<CompanionLogEntry.Added> found = new ArrayList<>();
            int turn = game.getPhaseHandler().getTurn();
            for (CompanionLogEntry.Added added : CompanionLogEntry.addedInTurn(log(), turn)) {
                Player owner = seat(game, added.player());
                if (owner != null && TableEditor.findToReassign(owner, added.card()) != null) {
                    found.add(added);
                }
            }
            return List.copyOf(found);
        } catch (RuntimeException e) {
            // an unreadable log mustn't stop the prompt; the menu just has nothing to correct
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * The cards added this turn that "wrong player" can give to another seat, oldest first, as of the latest priority
     * prompt: not undone, and still in the game (a token that ceased to exist isn't). Safe from any thread (the UI's
     * game menu reads it). Like {@link #canUndo}, it can be out of date only while the game runs between prompts;
     * the {@link Reassign} op looks the card up again and refuses with a message if it's gone.
     */
    public List<CompanionLogEntry.Added> addedThisTurn() {
        return addedThisTurn;
    }

    /**
     * The game's log entries as written, oldest first, as of the latest priority prompt: the live game's history for
     * the UI to show ({@link CompanionHistory#of}). Empty before the first prompt. Safe from any thread, and never
     * waits for the game thread or reads the store: like {@link #addedThisTurn}, it's published by the prompt, so it
     * can be out of date only while the game runs between prompts.
     */
    public List<CompanionLogEntry> logAtPrompt() {
        return logAtPrompt;
    }

    /**
     * Whether an undo would take something back, as of the latest priority prompt: false before the first save and
     * at the start of the game (AC-27's step 0). Cheap and safe from any thread (the UI's game menu reads it). It can
     * be out of date only while the game runs between prompts, when undo waits for the next prompt anyway; an undo
     * submitted then is refused with a message if it has nothing to take back.
     */
    public boolean canUndo() {
        return canUndo;
    }

    /**
     * Whether an operator action (anything but an undo) has run since the latest save, or since an undo put a save
     * back. While End Turn passes Main 2 nothing is saved, so that one undo goes back to where it was tapped; an
     * action made during that pass (a card added just after the tap, a parked sorcery cast) is saved anyway, as its
     * own step. Game loop thread.
     */
    boolean ranSinceSave() {
        return ranSinceSave;
    }

    /** Saves the game if it changed. Game loop thread, at a settled point: the stack empty, priority with the table. */
    void save(Game game) {
        try {
            String state = CompanionGameState.capture(game);
            if (state.equals(lastSaved)) {
                ranSinceSave = false;
                return;
            }
            setLatestStep(store.saveState(id, state));
            lastSaved = state;
            saveFailing = false;
            ranSinceSave = false;
        } catch (RuntimeException e) {
            // the game goes on unsaved rather than stopping; the operator is told once, not at every prompt
            e.printStackTrace();
            forgetCache();
            if (!saveFailing) {
                saveFailing = true;
                onResult.accept(new TableEditor.Result(false, "The game couldn't be saved: " + e.getMessage()));
            }
        }
    }
}

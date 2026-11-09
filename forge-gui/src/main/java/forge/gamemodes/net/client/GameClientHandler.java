package forge.gamemodes.net.client;

import forge.game.*;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.GameProtocolHandler;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.TrackableSerializer;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.interfaces.IGuiGame;
import forge.util.BuildInfo;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import io.netty.channel.ChannelHandlerContext;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.FutureTask;

final class GameClientHandler extends GameProtocolHandler<IGuiGame> implements IHasForgeLog {

    private final FGameClient client;
    private final IGuiGame gui;
    private Tracker tracker;

    public GameClientHandler(final FGameClient client) {
        super(true);
        this.client = client;
        this.gui = client.getGui();
        this.tracker = null;
    }

    @Override
    protected ReplyPool getReplyPool(final ChannelHandlerContext ctx) {
        return client.getReplyPool();
    }

    @Override
    protected IRemote getRemote(final ChannelHandlerContext ctx) {
        return client;
    }

    @Override
    protected IGuiGame getToInvoke(final ChannelHandlerContext ctx) {
        return gui;
    }

    @Override
    protected Object[] decodeArgs(final ProtocolMethod protocolMethod, final byte[] encodedArgs) {
        try {
            // Deltas are applied on the GUI thread in arrival order, so a prompt handled on another thread waits for the ones before it
            final FutureTask<Void> deltasApplied = new FutureTask<>(() -> {}, null);
            FThreads.invokeInEdtNowOrLater(deltasApplied);
            deltasApplied.get();
            final Object[] args = TrackableSerializer.decodeArgs(encodedArgs, tracker);
            protocolMethod.checkArgs(args);
            updateTrackers(args);
            return args;
        } catch (final Exception e) {
            // Handled like a failed call, so the host still gets its reply
            netLog.error(e, "Failed to decode args for {}", protocolMethod);
            return null;
        }
    }

    @Override
    protected boolean shouldDispatchToGuiThread(final ProtocolMethod protocolMethod) {
        // Libgdx modal prompts block via WaitCallback and deadlock on the GL thread. Return-value
        // methods always block; message/showErrorDialog return void but still open a blocking modal.
        if (GuiBase.getInterface().isLibgdxPort()
                && (!protocolMethod.getReturnType().equals(Void.TYPE)
                    || protocolMethod == ProtocolMethod.message
                    || protocolMethod == ProtocolMethod.showErrorDialog)) {
            return false;
        }
        return super.shouldDispatchToGuiThread(protocolMethod);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void beforeCall(final ChannelHandlerContext ctx, final ProtocolMethod protocolMethod, final Object[] args) {
        switch (protocolMethod) {
            case setGameView:
                if (args.length > 0 && args[0] instanceof GameView gameView) {
                    if (this.tracker == null) {
                        // a mode whose reconnect sends the board afresh into the view still shown keeps that view's
                        // tracker, so the new board's card views replace the old ones in it, as after an undo
                        final GameView shown = client.keepsTrackerAcrossConnections() ? gui.getGameView() : null;
                        this.tracker = shown != null && shown.getTracker() != null ? shown.getTracker() : new Tracker();
                        // Encoder uses the tracker to emit IdRef for client→server CardView args
                        // (presence check only — stale detection is server-only).
                        // Ephemerals absent from the tracker serialize as full objects in both directions.
                        CompatibleObjectEncoder encoder = ctx.pipeline().get(CompatibleObjectEncoder.class);
                        if (encoder != null) {
                            encoder.setTracker(this.tracker);
                        }
                        CompatibleObjectDecoder decoder = ctx.pipeline().get(CompatibleObjectDecoder.class);
                        if (decoder != null) {
                            decoder.setTracker(this.tracker);
                        }
                        if (gameView.getGameLog() == null) {
                            gameView.initGameLog();
                        }
                    }
                    if (gameView.getTracker() == null) {
                        updateTrackers(new Object[]{gameView});
                    }
                }
                if (client.isActiveChannel(ctx.channel())
                        && client.getReconnectState() == FGameClient.ReconnectState.RECONNECTING) {
                    client.onResumeResponseReceived();
                }
                break;
            case openView:
                gui.setNetGame();
                final TrackableCollection<PlayerView> myPlayers = (TrackableCollection<PlayerView>) args[0];
                for (PlayerView myPlayer : myPlayers) {
                    if (myPlayer.getTracker() == null) {
                        myPlayer.setTracker(this.tracker);
                    }
                }
                client.setGameControllers(myPlayers);
                break;
            default:
                break;
        }
        if (this.tracker != null) {
            updateTrackers(args);
            // Register all objects from the incoming GameView in the tracker's ID
            // lookup table synchronously on the IO thread. copyChangedProps() also
            // does this, but it runs on EDT asynchronously — too late for the
            // subsequent handleGameEvents.beforeCall which needs to resolve IdRefs.
            if (protocolMethod == ProtocolMethod.setGameView && args.length > 0 && args[0] instanceof GameView gv) {
                gv.updateObjLookup();
                // updateObjLookup skips objects already in the tracker, so CardViews
                // registered on the first setGameView become stale (wrong zone, etc.).
                // Replace tracker entries with incoming server CardViews so that
                // IdRef resolution in handleGameEvents gets current state.
                refreshTrackerCardViews(gv);
            }
            replicateProps(args);
        }
    }

    /**
     * Drops the stale card views the tracker holds. The tracker keeps every {@link CardView} it has been sent, and once
     * the host renumbers the board (an undo or resume sends a full state with new card ids) the old ones are stale.
     * Call it on the channel's thread right after a full state ({@code setGameView}) was read from that channel.
     * <p>
     * The work is queued on the GUI thread, which applies each call in the order it was read: so it runs after that
     * full state was applied, and before any later call is (a later delta's args are decoded only once the GUI thread
     * has applied everything before it, {@link #decodeArgs}). In one step it drops every card view and registers again
     * those of the board the GUI now shows, which are the full state's, so the tracker is never empty in between and
     * the next delta resolves its ids against the current board. Nothing to do before the first game view.
     * <p>
     * Known race, not fixed: the tracker isn't thread-safe, and the IO thread still touches it while the reset runs on
     * the GUI thread: the frame decoder resolves ids against it, and {@link #beforeCall} registers each incoming call's
     * objects before it is queued. A message read during the reset can so meet a half-cleared tracker, or change it
     * under the reset's iteration. The window is one GUI-thread task right after a full state, as net play's own
     * IO-thread registration already has with the GUI thread's {@code copyChangedProps}.
     */
    protected void resetTracker() {
        final Tracker current = tracker;
        if (current == null) {
            return;
        }
        FThreads.invokeInEdtNowOrLater(() -> {
            final GameView shown = gui.getGameView();
            if (shown != null) {
                keepOnlyCardsOf(current, shown);
            }
        });
    }

    /** Drops every card view {@code tracker} holds, then registers those {@code shown} reaches. */
    static void keepOnlyCardsOf(final Tracker tracker, final GameView shown) {
        tracker.removeAll(TrackableTypes.CardViewType);
        shown.updateObjLookup();
        // the players and stack items stay registered, so updateObjLookup doesn't walk into them: walk their cards
        if (shown.getPlayers() != null) {
            for (PlayerView player : shown.getPlayers()) {
                player.updateObjLookup();
            }
        }
        if (shown.getStack() != null) {
            for (TrackableObject item : shown.getStack()) {
                item.updateObjLookup();
            }
        }
    }

    /**
     * This method is used to recursively update the <b>tracker</b>
     * references on all objects and their props.
     *
     * <p>Inline-serialized CardViews are intentionally NOT registered in the
     * tracker's id lookup: a tracker miss is the symmetric signal that a
     * CardView is ephemeral, mirroring the host's encoder check.
     *
     * @param objs
     */
    private void updateTrackers(final Object[] objs) {
        for (Object obj: objs) {
            if (obj instanceof TrackableObject trackableObject) {
                if (trackableObject.getTracker() == null) {
                    trackableObject.setTracker(this.tracker);
                    // walk the props
                    Map props = trackableObject.getProps();
                    if (props != null) {
                        for (Object propObj : props.values()) {
                            updateTrackers(new Object[]{propObj});
                        }
                    }
                }
            } else if (obj instanceof TrackableCollection collection) {
                Iterator itrCollection = collection.iterator();
                while (itrCollection.hasNext()) {
                    Object objCollection = itrCollection.next();
                    updateTrackers(new Object[]{objCollection});
                }
            }
        }
    }

    private void replicateProps(final Object[] objs) {
        for (Object obj: objs) {
            if (obj instanceof PlayerView pv) {
                replicatePlayerView(pv);
            }
        }
    }

    private void replicatePlayerView(final PlayerView newPlayerView) {
        if (RemoteClientGuiGame.useDeltaSync) {
            return; // Delta sync manages state — don't overwrite from protocol snapshots
        }
        PlayerView existingPlayerView = tracker.getObj(TrackableTypes.PlayerViewType, newPlayerView.getId());
        existingPlayerView.copyChangedProps(newPlayerView);
        netLog.trace("replicated PlayerView properties - {}", existingPlayerView.toString());
    }

    /**
     * Replace tracker CardView entries with incoming server CardViews.
     * <p>
     * {@code updateObjLookup()} skips objects already in the tracker, so CardViews
     * registered on the first {@code setGameView} become stale — their zone, state,
     * and other properties no longer reflect the server's current game state.
     * When the decoder resolves IdRefs from the tracker, it gets these
     * stale CardViews, causing issues like card-back images in the game log
     * (the stale zone is Library, so {@code canBeShownTo} returns false).
     */
    private void refreshTrackerCardViews(final GameView gv) {
        if (gv.getPlayers() == null) { return; }
        for (final PlayerView pv : gv.getPlayers()) {
            final Map props = pv.getProps();
            if (props == null) { continue; }
            for (final Object value : props.values()) {
                if (value instanceof TrackableCollection<?> collection) {
                    for (final Object item : collection) {
                        if (item instanceof CardView cv) {
                            tracker.putObj(TrackableTypes.CardViewType, cv.getId(), cv);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        String loginName = client.getUsername();
        if (loginName == null || loginName.isEmpty()) {
            loginName = FModel.getPreferences().getPref(FPref.PLAYER_NAME);
        }
        // Don't use send() here, as this.channel is not yet set!
        ctx.channel().writeAndFlush(new LoginEvent(
                loginName,
                Integer.parseInt(FModel.getPreferences().getPref(FPref.UI_AVATARS).split(",")[0]),
                Integer.parseInt(FModel.getPreferences().getPref(FPref.UI_SLEEVES).split(",")[0]),
                BuildInfo.getVersionString(),
                GuiBase.getInterface().isLibgdxPort()
        ));
    }

}
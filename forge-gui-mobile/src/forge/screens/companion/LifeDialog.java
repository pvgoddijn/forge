package forge.screens.companion;

import com.badlogic.gdx.utils.Align;
import forge.assets.FSkinFont;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.companion.TableEditor;
import forge.toolbox.FButton;
import forge.toolbox.FDialog;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.util.regex.Pattern;

/**
 * Sets one player's life (AC-25): the ± buttons or typing change the value shown, and Set applies it. Zero and below
 * are allowed, as the table decides when someone is out. When the change could set off a life gain or loss trigger,
 * the operator is asked whether it's a real gain or loss or only a correction (decision D2).
 */
public class LifeDialog extends FDialog {
    private static final float PADDING = Utils.scale(5);
    private static final FSkinFont VALUE_FONT = FSkinFont.get(24);
    /** What the field accepts while typing: an optional minus, then up to six digits, so parsing never overflows. */
    private static final Pattern PARTIAL = Pattern.compile("-?\\d{0,6}");
    private static final Pattern COMPLETE = Pattern.compile("-?\\d{1,6}");
    private static final String TRIGGER_QUESTION = "Trigger life gain/loss effects?";

    private final CompanionTable table;
    private final String playerName;
    private final FTextField txtValue = add(new FTextField());
    private final FButton[] steps = {
            add(new FButton("-5")), add(new FButton("-1")), add(new FButton("+/-")),
            add(new FButton("+1")), add(new FButton("+5"))
    };

    public LifeDialog(CompanionTable table0, PlayerView player) {
        super("Life: " + player.getName(), 2);
        table = table0;
        playerName = player.getName();

        txtValue.setFont(VALUE_FONT);
        txtValue.setAlignment(Align.center);
        txtValue.setIsNumeric(true);
        txtValue.setText(String.valueOf(player.getLife()));
        txtValue.setLiveChangeEvents(true);
        txtValue.setChangedHandler(e -> {
            // a keyboard can type anything: keep the last text that could still become a number
            if (!PARTIAL.matcher(txtValue.getText()).matches()) {
                txtValue.setText(e.getArgs() instanceof String before ? before : "");
            }
        });

        steps[0].setCommand(e -> step(-5));
        steps[1].setCommand(e -> step(-1));
        // the numeric keyboard may have no minus key, so the sign has a button of its own
        steps[2].setCommand(e -> setValue(-value(0)));
        steps[3].setCommand(e -> step(1));
        steps[4].setCommand(e -> step(5));

        initButton(0, "Set", e -> set());
        initButton(1, "Cancel", e -> hide());
    }

    /** The typed value, or {@code fallback} while the field holds no number (empty, or only a minus). */
    private int value(int fallback) {
        String text = txtValue.getText().trim();
        return COMPLETE.matcher(text).matches() ? Integer.parseInt(text) : fallback;
    }

    private void step(int delta) {
        setValue(value(0) + delta);
    }

    /** Shows {@code value}, kept within what the field accepts, so the buttons can't build a number it would refuse. */
    private void setValue(int value) {
        txtValue.setText(String.valueOf(Math.max(-999_999, Math.min(999_999, value))));
    }

    private void set() {
        String text = txtValue.getText().trim();
        if (!COMPLETE.matcher(text).matches()) {
            return; // nothing to set yet: an empty field or a lone minus
        }
        int life = Integer.parseInt(text);
        String name = playerName;
        hide();
        // by name, which setup keeps unique, as CardSearchDialog does; the question and the change both run on the
        // game thread, so they see the same table
        table.submit(game -> {
            Player p = game.getPlayers().stream().filter(pl -> pl.getName().equals(name)).findFirst().orElse(null);
            if (p == null) {
                return new TableEditor.Result(false, name + " isn't in the game");
            }
            // it's a correction unless the operator says it's a real gain or loss, and only asked when it matters
            boolean fire = TableEditor.lifeChangeCouldTrigger(p, life) && table.confirm(game, TRIGGER_QUESTION, false);
            return TableEditor.setLife(p, life, fire);
        });
    }

    @Override
    protected float layoutAndGetHeight(float width, float maxHeight) {
        float w = width - 2 * PADDING;
        float y = PADDING;
        float valueHeight = FTextField.getDefaultHeight(VALUE_FONT);
        txtValue.setBounds(PADDING, y, w, valueHeight);
        y += valueHeight + PADDING;
        float rowHeight = FTextField.getDefaultHeight();
        float stepWidth = (w - (steps.length - 1) * PADDING) / steps.length;
        float x = PADDING;
        for (FButton b : steps) {
            b.setBounds(x, y, stepWidth, rowHeight);
            x += stepWidth + PADDING;
        }
        return y + rowHeight + PADDING;
    }
}

package forge.gamemodes.companion;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The placeholders in a game state's text. Forge's loader only makes cards it finds in the card database, and the
 * placeholder isn't in it, so {@link #extract} takes them out of the text before a load, noting each one's player,
 * zone and place in that zone, and {@link #putBack} puts new placeholders in those places after it. A placeholder saved
 * with an {@code Id:} is also taken out of the other cards' references to it, which the loader would look up and not
 * find: what a card remembered, imprinted or chose of the unknown cards is lost on a load.
 */
final class PlaceholderText {
    /** A zone line's key, as Forge's loader reads it: the player, then the zone. */
    private static final Pattern ZONE_KEY = Pattern.compile("(human|ai|p\\d)(hand|library|graveyard|exile|battlefield|play|command|sideboard)");
    /** The modifiers that name other cards by id: a list of ids, or one. */
    private static final List<String> ID_LISTS = List.of("RememberedCards:", "Imprinting:", "ChosenCards:");
    private static final List<String> SINGLE_IDS = List.of("ExiledWith:", "AttachedTo:", "Attaching:", "Attacking:");
    private static final Map<String, ZoneType> ZONES = Map.of(
            "hand", ZoneType.Hand, "library", ZoneType.Library, "graveyard", ZoneType.Graveyard,
            "exile", ZoneType.Exile, "battlefield", ZoneType.Battlefield, "play", ZoneType.Battlefield,
            "command", ZoneType.Command, "sideboard", ZoneType.Sideboard);

    /**
     * A placeholder taken out of the text: the index of its player in the game's players, its zone, its place among
     * that zone's saved cards (0 is the top of a library) and the modifiers it was saved with.
     */
    record Spot(int player, ZoneType zone, int position, List<String> modifiers) { }

    private final List<String> lines;
    private final List<Spot> spots;

    private PlaceholderText(List<String> lines, List<Spot> spots) {
        this.lines = lines;
        this.spots = spots;
    }

    /** The text with the placeholders out, for Forge's loader. */
    List<String> lines() {
        return lines;
    }

    /** The placeholders taken out, in the order of the text. */
    List<Spot> spots() {
        return spots;
    }

    static PlaceholderText extract(List<String> lines) {
        Set<String> placeholderIds = new HashSet<>();
        for (String line : lines) {
            String[] zoneLine = zoneLine(line);
            if (zoneLine == null) {
                continue;
            }
            for (String entry : entries(zoneLine[1])) {
                String[] modifiers = entry.trim().split("\\|", -1);
                if (isPlaceholder(modifiers)) {
                    for (String modifier : modifiers) {
                        if (modifier.startsWith("Id:")) {
                            placeholderIds.add(modifier.substring(3).trim());
                        }
                    }
                }
            }
        }

        List<String> kept = new ArrayList<>();
        List<Spot> spots = new ArrayList<>();
        for (String line : lines) {
            String[] zoneLine = zoneLine(line);
            if (zoneLine == null) {
                kept.add(line);
                continue;
            }
            Matcher key = ZONE_KEY.matcher(zoneLine[0].toLowerCase());
            key.matches();
            int player = playerIndex(key.group(1));
            ZoneType zone = ZONES.get(key.group(2));
            List<String> cards = new ArrayList<>();
            String[] entries = entries(zoneLine[1]);
            for (int i = 0; i < entries.length; i++) {
                String[] modifiers = entries[i].trim().split("\\|", -1);
                if (isPlaceholder(modifiers)) {
                    spots.add(new Spot(player, zone, i, List.of(modifiers).subList(1, modifiers.length)));
                } else {
                    cards.add(placeholderIds.isEmpty() ? entries[i] : withoutReferences(entries[i], placeholderIds));
                }
            }
            kept.add(zoneLine[0] + "=" + String.join(";", cards));
        }
        return new PlaceholderText(kept, spots);
    }

    /** The key and the value of a line that lists a player's cards in a zone, or null for any other line. */
    private static String[] zoneLine(String line) {
        if (line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        String[] keyValue = line.split("=", 2);
        if (keyValue.length < 2 || !ZONE_KEY.matcher(keyValue[0].toLowerCase()).matches()) {
            return null;
        }
        return keyValue;
    }

    /** A zone's cards, split as Forge's loader splits them. */
    private static String[] entries(String value) {
        return value.isEmpty() ? new String[0] : value.split(";");
    }

    private static boolean isPlaceholder(String[] modifiers) {
        return CompanionCards.PLACEHOLDER_NAME.equals(modifiers[0]);
    }

    /** The player key's index, as Forge's loader reads it: human is the first player, ai the second. */
    private static int playerIndex(String key) {
        return switch (key) {
            case "human" -> 0;
            case "ai" -> 1;
            default -> key.charAt(1) - '0';
        };
    }

    /** {@code entry} without its references to {@code ids}, a modifier that only named them going altogether. */
    private static String withoutReferences(String entry, Set<String> ids) {
        List<String> modifiers = new ArrayList<>(Arrays.asList(entry.split("\\|", -1)));
        for (int i = modifiers.size() - 1; i > 0; i--) {
            String modifier = modifiers.get(i);
            String name = modifier.substring(0, modifier.indexOf(':') + 1);
            if (ID_LISTS.contains(name)) {
                List<String> left = new ArrayList<>();
                for (String id : modifier.substring(name.length()).split(",")) {
                    if (!ids.contains(id.trim())) {
                        left.add(id);
                    }
                }
                if (left.isEmpty()) {
                    modifiers.remove(i);
                } else {
                    modifiers.set(i, name + String.join(",", left));
                }
            } else if (SINGLE_IDS.contains(name) && ids.contains(modifier.substring(name.length()).trim())) {
                modifiers.remove(i);
            }
        }
        return String.join("|", modifiers);
    }

    /**
     * Puts a new placeholder in each spot of {@code game}, after Forge's loader has set up the zones. A placeholder
     * keeps whether it was tapped, summoning sick, face down (a manifested or cloaked card is) and its counters; the
     * rest of what it was saved with is lost. Call on the game thread.
     */
    void putBack(Game game) {
        if (spots.isEmpty()) {
            return;
        }
        List<Player> players = game.getPlayers();
        boolean battlefield = false;
        for (int player = 0; player < players.size(); player++) {
            for (ZoneType zoneType : ZoneType.values()) {
                List<Spot> inZone = new ArrayList<>();
                for (Spot spot : spots) {
                    if (spot.player() == player && spot.zone() == zoneType) {
                        inZone.add(spot);
                    }
                }
                if (inZone.isEmpty()) {
                    continue;
                }
                Player owner = players.get(player);
                PlayerZone zone = owner.getZone(zoneType);
                List<Card> loaded = new ArrayList<>(zone.getCards());
                List<Card> merged = new ArrayList<>();
                int next = 0;
                for (Spot spot : inZone) {
                    // a card the loader couldn't make leaves a gap, so a later placeholder moves up by one
                    while (merged.size() < spot.position() && next < loaded.size()) {
                        merged.add(loaded.get(next++));
                    }
                    merged.add(newPlaceholder(owner, spot.modifiers()));
                }
                merged.addAll(loaded.subList(next, loaded.size()));
                zone.setCards(merged);
                battlefield |= zoneType == ZoneType.Battlefield;
            }
        }
        if (battlefield) {
            game.getAction().checkStateEffects(true);
        }
        // as Forge's loader does, so nothing looks back at the state from before the placeholders were in
        game.copyLastState();
    }

    private static Card newPlaceholder(Player owner, List<String> modifiers) {
        Card card = CompanionCards.newPlaceholder(owner);
        card.setSickness(false);
        for (String modifier : modifiers) {
            if (modifier.startsWith("Tapped")) {
                card.setTapped(true);
            } else if (modifier.startsWith("SummonSick")) {
                card.setSickness(true);
            } else if (modifier.startsWith("FaceDown")) {
                if (modifier.endsWith("Manifested")) {
                    card.setManifested(faceDownBy(ApiType.Manifest, card));
                }
                if (modifier.endsWith("Cloaked")) {
                    card.setCloaked(faceDownBy(ApiType.Cloak, card));
                }
                card.turnFaceDown(true);
            } else if (modifier.startsWith("Counters:")) {
                for (String counter : modifier.substring("Counters:".length()).split(",")) {
                    String[] pair = counter.split("=", 2);
                    card.addCounterInternal(CounterType.getType(pair[0]), Integer.parseInt(pair[1]), null, false, null, null);
                }
            }
        }
        return card;
    }

    /** What turned {@code card} face down. The view takes the face-down image's set from it, so it needs a state. */
    private static SpellAbility faceDownBy(ApiType api, Card card) {
        SpellAbility sa = new SpellAbility.EmptySa(api, card);
        sa.setCardState(card.getState(CardStateName.Original));
        return sa;
    }
}

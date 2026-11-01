package forge.gamemodes.companion;

import forge.StaticData;
import forge.card.CardType;
import forge.card.ICardFace;

/** A zone each player has. The Hand holds only known cards; the rest of a hand is a hidden count. */
public enum CompanionZone {
    HAND, BATTLEFIELD, GRAVEYARD, EXILE, COMMAND;

    /** Where a newly added card comes from and goes to, until the operator changes it. {@code from} may be null. */
    public record AddDefaults(CompanionZone from, CompanionZone to) { }

    /** Defaults for a card of this type. No type means a token, emblem or free text, which never was in a hand. */
    public static AddDefaults addDefaults(CardType type) {
        if (type == null) {
            return new AddDefaults(null, BATTLEFIELD);
        }
        return new AddDefaults(HAND, type.isPermanent() ? BATTLEFIELD : GRAVEYARD);
    }

    /** Defaults for a name, looked up in Forge's card database. Either face of a card counts as that card's name. */
    public static AddDefaults addDefaults(String cardName) {
        ICardFace face = StaticData.instance().getCommonCards().getFaceByName(cardName);
        return addDefaults(face == null ? null : face.getType());
    }
}

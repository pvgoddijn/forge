package forge.cardocr.reader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class ReadVerdictTest {

    private static List<ReadResult.Match> top(float score) {
        return List.of(new ReadResult.Match("Card", score, true));
    }

    @Test
    void scoresFromRealCapturesFallOnTheRightSide() {
        assertTrue(ReadVerdict.unreadable(top(-0.1378f)), "the blurred Ashnod's Altar, read as Pain");
        assertTrue(ReadVerdict.unreadable(top(0.08f)), "Ashnod's Altar misread as Iron Myr");
        assertTrue(ReadVerdict.unreadable(top(0.04f)), "a printed label with no card");
        assertFalse(ReadVerdict.unreadable(top(0.20f)), "Iron Myr, the lowest right answer");
        assertFalse(ReadVerdict.unreadable(top(0.49f)), "Daxos, found from the rules text");
    }

    @Test
    void noMatchIsUnreadable() {
        assertTrue(ReadVerdict.unreadable(List.of()));
    }
}

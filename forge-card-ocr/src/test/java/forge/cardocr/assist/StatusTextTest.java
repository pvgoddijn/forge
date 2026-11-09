package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class StatusTextTest {

    @Test
    void theMainLineSaysWhatTheAppIsDoing() {
        assertEquals("Looking for a card", StatusText.main(false, false, false, true));
        assertEquals("Hold steady", StatusText.main(true, false, false, true));
        assertEquals("Capturing", StatusText.main(true, true, true, true));
        assertEquals("Remove or change the card", StatusText.main(true, true, false, true));
        assertEquals("Looking for a card", StatusText.main(false, true, false, true));
        assertEquals("Card found. Press the shutter", StatusText.main(true, false, false, false));
    }

    @Test
    void theGyroCheckCannotReadAsARejection() {
        assertEquals("gyro direction check: needs movement to judge", StatusText.gyroCheck(MotionAudit.NOT_ENOUGH_MOTION));
        assertEquals("gyro direction check: fine", StatusText.gyroCheck(MotionAudit.OK));
    }

    @Test
    void debugNumbersCallSkippedFramesNormal() {
        String text = StatusText.debug(80, 83, 8.6, 700, 540, MotionAudit.NOT_ENOUGH_MOTION, 0.03, 900, 400, true, 2);
        assertTrue(text.contains("frames analysed 700"));
        assertTrue(text.contains("frames skipped (normal) 540"));
        assertTrue(text.contains("needs movement to judge"));
        assertTrue(text.contains("card steady 400 ms"));
        assertFalse(text.contains("dropped"));
        assertFalse(text.contains("not enough"));
        assertTrue(StatusText.debug(80, 83, 8.6, 1, 0, MotionAudit.OK, null, 0, -1, false, 0).contains("no card tracked"));
    }

    @Test
    void loadIsSplitByWhetherACardWasTracked() {
        FinderLoad load = new FinderLoad();
        load.add(true, 100, 90, 20, 80, 3);
        load.add(true, 120, 110, 20, 100, 5);
        load.add(false, 40, 39, 30, 10, 1);
        assertEquals(2, load.summary().card().frames());
        assertEquals(110, load.summary().card().wallMs());
        assertEquals(100, load.summary().card().cpuMs());
        assertEquals(1, load.summary().noCard().frames());
        assertEquals(0, new FinderLoad().summary().card().wallMs());
    }
}

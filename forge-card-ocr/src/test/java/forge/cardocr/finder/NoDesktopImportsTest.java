package forge.cardocr.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

/** The finder must compile for Android and run in a plain JVM, so it can use neither platform. */
class NoDesktopImportsTest {

    @Test
    void finderSourcesImportNoAwtOrAndroid() throws IOException {
        File folder = new File("src/main/java/forge/cardocr/finder");
        File[] sources = folder.listFiles((dir, name) -> name.endsWith(".java"));
        assertNotNull(sources, "Missing " + folder.getAbsolutePath());
        assertTrue(sources.length > 0);
        for (File source : sources) {
            String text = Files.readString(source.toPath());
            for (String banned : new String[] {"java.awt", "javax.imageio", "android."}) {
                assertFalse(text.contains(banned), source.getName() + " uses " + banned);
            }
        }
    }
}

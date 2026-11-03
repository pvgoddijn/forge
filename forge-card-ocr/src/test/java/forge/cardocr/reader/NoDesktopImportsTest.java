package forge.cardocr.reader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

/** Recognition must compile for Android and run in a plain JVM. */
class NoDesktopImportsTest {

    @Test
    void readerSourcesImportNoAwtOrAndroid() throws IOException {
        File[] sources = new File("src/main/java/forge/cardocr/reader").listFiles((dir, name) -> name.endsWith(".java"));
        assertNotNull(sources);
        assertTrue(sources.length > 0);
        for (File source : sources) {
            String text = Files.readString(source.toPath());
            for (String banned : new String[] {"java.awt", "javax.imageio", "android."}) {
                assertFalse(text.contains(banned), source.getName() + " uses " + banned);
            }
        }
    }
}

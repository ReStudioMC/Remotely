package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FocusedJsonResourceDesignerScreenTest {
    @Test
    void resourceLinesHandleMinecraftTextAndUnicodeLineBreaks() {
        String value = "First\r\nSecond\nThird\u2028Fourth\r";

        assertArrayEquals(new String[]{"First", "Second", "Third", "Fourth", ""},
            FocusedJsonResourceDesignerScreen.splitResourceLines(value));
        assertEquals(5, FocusedJsonResourceDesignerScreen.resourceLineCount(value));
        assertEquals("First", FocusedJsonResourceDesignerScreen.firstResourceLine(value));
        assertArrayEquals(new String[]{""}, FocusedJsonResourceDesignerScreen.splitResourceLines(null));
    }
}

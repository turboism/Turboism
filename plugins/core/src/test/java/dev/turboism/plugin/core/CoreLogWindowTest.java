package dev.turboism.plugin.core;

import org.junit.jupiter.api.Test;

import javax.swing.JTextPane;
import javax.swing.text.StyleConstants;
import java.awt.Color;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreLogWindowTest {

    private static final List<String> LINES = List.of(
        "2026-01-01T00:00:00Z [INFO] [runtime] startup",
        "java.lang.IllegalStateException: disk fixture",
        "2026-01-01T00:00:01Z [WARN] [storage] disk nearly full",
        "2026-01-01T00:00:02Z [ERROR] [network] disconnected"
    );

    @Test
    void filtersCompleteLogEntriesByKeywordAndMinimumLevel() {
        assertEquals(
            "2026-01-01T00:00:01Z [WARN] [storage] disk nearly full",
            CoreLogWindow.filter(LINES, "disk", "WARN")
        );
        assertEquals(
            "2026-01-01T00:00:00Z [INFO] [runtime] startup\n"
                + "java.lang.IllegalStateException: disk fixture",
            CoreLogWindow.filter(LINES, "fixture", "INFO")
        );
    }

    @Test
    void colorsEachEntryByLevelAndCarriesColorIntoContinuationLines() {
        final JTextPane output = new JTextPane();
        output.setBackground(Color.WHITE);
        output.setForeground(Color.BLACK);
        final String content = String.join("\n",
            "2026-01-01T00:00:00Z [TRACE] [probe] trace message",
            "2026-01-01T00:00:01Z [DEBUG] [probe] debug message",
            "2026-01-01T00:00:02Z [INFO] [probe] info message",
            "2026-01-01T00:00:03Z [WARN] [probe] warn message",
            "2026-01-01T00:00:04Z [ERROR] [probe] error message",
            "caused by fixture",
            "2026-01-01T00:00:05Z [FATAL] [probe] fatal message"
        );

        CoreLogWindow.render(output, content);

        assertEquals(new Color(0x5F6368), colorAt(output, "trace message"));
        assertEquals(new Color(0x0057B8), colorAt(output, "debug message"));
        assertEquals(Color.BLACK, colorAt(output, "info message"));
        assertEquals(new Color(0x8A5A00), colorAt(output, "warn message"));
        assertEquals(new Color(0xB00020), colorAt(output, "error message"));
        assertEquals(new Color(0xB00020), colorAt(output, "caused by fixture"));
        assertEquals(new Color(0xB00020), colorAt(output, "fatal message"));


        output.setBackground(new Color(0x202124));
        output.setForeground(Color.WHITE);
        CoreLogWindow.render(output, content);
        assertEquals(new Color(0xFFD166), colorAt(output, "warn message"));
        assertEquals(new Color(0xFF6B6B), colorAt(output, "error message"));
    }

    private static Color colorAt(final JTextPane output, final String marker) {
        final int offset = output.getText().indexOf(marker);
        return StyleConstants.getForeground(
            output.getStyledDocument().getCharacterElement(offset).getAttributes()
        );
    }
}

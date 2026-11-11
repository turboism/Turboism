package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * Contract of the startup motto catalog: the bundled resource parses, every line is displayable,
 * and the pick stays inside the catalog so a launch never shows a stray or empty subtitle.
 */
class StartupMottoCatalogTest {

    private static final String SAMPLE = "# comment\n\nWake up, samurai.\n海内存知己，天涯若比邻。\n";

    @Test
    void bundledCatalogParsesIntoTheQuotesTheWindowCycles() throws IOException {
        try (InputStream stream = StartupMottoCatalog.class.getClassLoader()
                .getResourceAsStream(StartupMottoCatalog.RESOURCE)) {
            final List<String> quotes = StartupMottoCatalog.parse(stream);
            assertTrue(quotes.size() >= 20, "The bundled catalog must ship the full motto list");
            assertEquals(quotes.size(), new HashSet<>(quotes).size(), "A duplicated line wastes a draw");
            for (final String quote : quotes) {
                assertFalse(quote.isBlank(), "A blank line must not reach the window");
                assertFalse(quote.startsWith("#"), "A comment must not reach the window");
                assertTrue(
                        quote.length() <= StartupMottoCatalog.MAX_QUOTE_LENGTH,
                        "A long line widens the undecorated window: " + quote);
            }
        }
    }

    @Test
    void parseSkipsCommentsAndBlankLinesAndKeepsFileOrder() {
        assertEquals(
                List.of("Wake up, samurai.", "海内存知己，天涯若比邻。"),
                StartupMottoCatalog.parse(stream(SAMPLE)));
    }

    @Test
    void parseRejectsAnOverlongLineInsteadOfStretchingTheWindow() {
        final String line = "x".repeat(StartupMottoCatalog.MAX_QUOTE_LENGTH + 1);
        assertTrue(StartupMottoCatalog.parse(stream(line + "\n")).isEmpty());
    }

    @Test
    void missingOrUnreadableCatalogFallsBackToTheBuiltInQuote() {
        assertEquals(StartupMottoCatalog.FALLBACK_QUOTE, StartupMottoCatalog.choose(List.of(), random(0)));
        assertEquals(List.of(), StartupMottoCatalog.parse(null));
        assertEquals(List.of(), StartupMottoCatalog.parse(failing()));
    }

    @Test
    void chooseReturnsACatalogEntryForEveryDraw() {
        final List<String> quotes = StartupMottoCatalog.parse(stream(SAMPLE));
        final Set<String> drawn = new HashSet<>();
        for (int index = 0; index < quotes.size(); index++) {
            drawn.add(StartupMottoCatalog.choose(quotes, random(index)));
        }
        assertEquals(new HashSet<>(quotes), drawn, "Each index must select its own catalog line");
        assertNotEquals(StartupMottoCatalog.FALLBACK_QUOTE, StartupMottoCatalog.choose(quotes, random(0)));
    }

    @Test
    void everyLaunchDrawsFromTheBundledCatalog() {
        assertFalse(StartupMottoCatalog.choose(ThreadLocalRandom.current()).isBlank());
    }

    private static InputStream stream(final String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream failing() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable catalog");
            }
        };
    }

    private static RandomGenerator random(final int value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return value;
            }

            @Override
            public int nextInt(final int bound) {
                return Math.floorMod(value, bound);
            }
        };
    }
}

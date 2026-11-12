package dev.turboism.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Picks the motto shown under the startup loading title.
 *
 * <p>The bundled catalog owns the wording, so a reviewer revises a quote without a code change,
 * and the injected random source keeps the pick reproducible under test. A missing, unreadable or
 * empty catalog yields {@link #FALLBACK_QUOTE}: optional startup feedback must never fail the
 * bootstrap because a resource is absent.
 */
final class StartupMottoCatalog {

    static final String RESOURCE = "META-INF/turboism/startup-quotes.txt";

    /** Shown when the catalog is absent or holds no usable line. */
    static final String FALLBACK_QUOTE = "Switch on the power line";

    /** Longest accepted line; a wider one would stretch the undecorated loading window. */
    static final int MAX_QUOTE_LENGTH = 120;

    private StartupMottoCatalog() {}

    /**
     * @param random source of the pick
     * @return one catalog line, or {@link #FALLBACK_QUOTE} when the catalog holds none
     */
    static String choose(final RandomGenerator random) {
        return choose(Catalog.QUOTES, random);
    }

    /**
     * @param quotes candidate lines; an empty list means the catalog is unusable
     * @param random source of the pick
     * @return one candidate, or {@link #FALLBACK_QUOTE} when no candidate exists
     */
    static String choose(final List<String> quotes, final RandomGenerator random) {
        return quotes.isEmpty() ? FALLBACK_QUOTE : quotes.get(random.nextInt(quotes.size()));
    }

    /**
     * Reads a catalog stream: one quote per line, with {@code #} comments and blank lines ignored.
     *
     * @param stream catalog bytes in UTF-8, or {@code null} when the resource is absent
     * @return the usable quotes in file order; unreadable input yields an empty list
     */
    static List<String> parse(final InputStream stream) {
        if (stream == null) return List.of();
        final List<String> quotes = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String quote = line.trim();
                if (quote.isEmpty() || quote.startsWith("#") || quote.length() > MAX_QUOTE_LENGTH) continue;
                quotes.add(quote);
            }
        } catch (IOException unreadable) {
            return List.of();
        }
        return List.copyOf(quotes);
    }

    // The distributed agent declares Boot-Class-Path, so its classes can have no defining
    // loader; the -javaagent jar is also on the system class path with the same resource.
    private static InputStream openCatalog() {
        final ClassLoader loader = StartupMottoCatalog.class.getClassLoader();
        return loader != null ? loader.getResourceAsStream(RESOURCE) : ClassLoader.getSystemResourceAsStream(RESOURCE);
    }

    /** Holds the parsed catalog for the process lifetime: the file ships inside the agent jar. */
    private static final class Catalog {

        private static final List<String> QUOTES = parse(openCatalog());

        private Catalog() {}
    }
}

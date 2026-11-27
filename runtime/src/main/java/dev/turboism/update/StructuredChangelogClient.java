package dev.turboism.update;

import dev.turboism.internal.TurboismEndpoints;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Fixed-origin HTTPS client that queries structured changelog ranges across stable and beta channels. */
public final class StructuredChangelogClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final int HTTP_OK = 200;
    private static final String DEFAULT_LOCALE = "en";
    private static final String GROUP_BY_CATEGORY = "category";
    private static final String FORMAT_MARKDOWN = "markdown";
    private static final Set<String> SUPPORTED_LOCALES = Set.of("en", "zh", "ja", "ko");

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI endpoint) throws IOException;
    }

    private static final ConnectionFactory DEFAULT_CONNECTION_FACTORY = endpoint -> {
        final URLConnection opened = endpoint.toURL().openConnection();
        if (!(opened instanceof HttpURLConnection http)) {
            throw new IOException("changelog endpoint did not create an HTTP connection");
        }
        return http;
    };

    private final URI baseEndpoint;
    private final ConnectionFactory connectionFactory;

    /** Creates a client targeting the canonical first-party changelog endpoint. */
    public StructuredChangelogClient() {
        this(URI.create(TurboismEndpoints.CHANGELOG_URL), DEFAULT_CONNECTION_FACTORY);
    }

    StructuredChangelogClient(final URI baseEndpoint, final ConnectionFactory connectionFactory) {
        this.baseEndpoint = Objects.requireNonNull(baseEndpoint, "baseEndpoint");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    /**
     * Fetches localized Markdown changes strictly after {@code fromVersion} up to {@code toVersion}.
     *
     * <p>Returns empty when versions share the same core semver, are invalid, or the network is unavailable.</p>
     */
    public Optional<String> fetchRange(final String fromVersion, final String toVersion, final Locale locale) {
        final Optional<URI> uri = buildRangeUri(fromVersion, toVersion, locale);
        if (uri.isEmpty()) {
            return Optional.empty();
        }
        HttpURLConnection connection = null;
        try {
            connection = connectionFactory.open(uri.orElseThrow());
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "text/markdown, text/plain");
            if (connection.getResponseCode() != HTTP_OK) {
                return Optional.empty();
            }
            try (InputStream body = connection.getInputStream()) {
                final byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
                    return Optional.empty();
                }
                final String markdown = new String(bytes, StandardCharsets.UTF_8).strip();
                return markdown.isEmpty() ? Optional.empty() : Optional.of(markdown);
            }
        } catch (IOException | RuntimeException unavailable) {
            return Optional.empty();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Builds the structured changelog range URI when both versions are valid SemVer and {@code from < to}. */
    Optional<URI> buildRangeUri(final String fromVersion, final String toVersion, final Locale locale) {
        final Optional<String> normalizedFrom = normalizeSemver(fromVersion);
        final Optional<String> normalizedTo = normalizeSemver(toVersion);
        if (normalizedFrom.isEmpty() || normalizedTo.isEmpty()) {
            return Optional.empty();
        }
        final String start = normalizedFrom.orElseThrow();
        final String end = normalizedTo.orElseThrow();
        if (UpdateVersionOrdering.compare(start, end) >= 0) {
            return Optional.empty();
        }
        final String lang = resolveLocale(locale);
        final String query = "from="
                + encode(start)
                + "&to="
                + encode(end)
                + "&lang="
                + encode(lang)
                + "&groupBy="
                + GROUP_BY_CATEGORY
                + "&format="
                + FORMAT_MARKDOWN;
        return Optional.of(URI.create(baseEndpoint + "?" + query));
    }

    private static String resolveLocale(final Locale locale) {
        if (locale == null) {
            return DEFAULT_LOCALE;
        }
        final String language = locale.getLanguage();
        return SUPPORTED_LOCALES.contains(language) ? language : DEFAULT_LOCALE;
    }

    private static Optional<String> normalizeSemver(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = raw.strip();
        if ((cleaned.startsWith("v") || cleaned.startsWith("V")) && cleaned.length() > 1) {
            cleaned = cleaned.substring(1);
        }
        return UpdateVersionOrdering.isValid(cleaned) ? Optional.of(cleaned) : Optional.empty();
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

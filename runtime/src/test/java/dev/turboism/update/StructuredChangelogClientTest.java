package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StructuredChangelogClientTest {

    @Test
    void fetchesLocalizedRangeMarkdownAcrossChannelsAndRejectsDowngrades() {
        final AtomicReference<URI> requested = new AtomicReference<>();
        final StructuredChangelogClient client =
                new StructuredChangelogClient(URI.create("https://api.turboism.dev/v1/changelog"), endpoint -> {
                    requested.set(endpoint);
                    return stubConnection(endpoint, 200, "### 新增\n\n- 区间变更内容。");
                });

        final Optional<String> result = client.fetchRange("0.70.3-beta.4", "0.70.3-beta.5", Locale.SIMPLIFIED_CHINESE);

        assertEquals(Optional.of("### 新增\n\n- 区间变更内容。"), result);
        assertEquals(
                "https://api.turboism.dev/v1/changelog?from=0.70.3-beta.4&to=0.70.3-beta.5&lang=zh&groupBy=category&format=markdown",
                requested.get().toString());
        assertTrue(client.fetchRange("0.70.3-beta.5", "0.70.3", Locale.ENGLISH).isPresent());
        assertTrue(client.fetchRange("0.70.2", "0.70.3-beta.4", Locale.ENGLISH).isPresent());
        assertTrue(client.fetchRange("0.70.3", "0.70.3-beta.5", Locale.ENGLISH).isEmpty());
        assertTrue(client.fetchRange("0.70.3-beta.5", "0.70.3-beta.5", Locale.ENGLISH)
                .isEmpty());
    }

    @Test
    void degradesToEmptyOnHttpFailure() {
        final StructuredChangelogClient client = new StructuredChangelogClient(
                URI.create("https://api.turboism.dev/v1/changelog"), endpoint -> stubConnection(endpoint, 400, ""));

        assertTrue(client.fetchRange("0.70.3", "0.70.1", Locale.ENGLISH).isEmpty());
    }

    private static HttpURLConnection stubConnection(final URI endpoint, final int status, final String body)
            throws IOException {
        return new HttpURLConnection(endpoint.toURL()) {
            @Override
            public void disconnect() {}

            @Override
            public boolean usingProxy() {
                return false;
            }

            @Override
            public void connect() {}

            @Override
            public int getResponseCode() {
                return status;
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}

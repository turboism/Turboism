package dev.turboism.plugin.turboismwithfx;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.turboism.protocol.json.StrictJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loopback Gateway-to-OpenAI adapter behaviour against a controlled upstream. */
final class FxOpenAiAdapterTest {

    private static final String STREAM =
        "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n"
            + "\n"
            + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
            + "\"function\":{\"name\":\"rename\",\"arguments\":\"{\\\"id\\\":\\\"a\\\"}\"}}]}}]}\n"
            + "\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
            + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}\n"
            + "\n"
            + "data: [DONE]\n"
            + "\n";

    private HttpServer upstream;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> chatRequest = new AtomicReference<>();
    private final List<String> paths = new ArrayList<>();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0
        );
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                paths.add(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                chatRequest.set(object(StrictJson.parse(
                    exchange.getRequestBody().readAllBytes()
                )));
                final byte[] body = STREAM.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
        });
        upstream.createContext("/v1/models", exchange -> {
            try (exchange) {
                paths.add(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                final byte[] body = StrictJson.bytes(Map.of(
                    "object", "list",
                    "data", List.of(
                        Map.of("id", "local/one"),
                        Map.of("id", "local/two")
                    )
                ));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    @Test
    void requestedModelHeaderWinsAndReasoningNoLongerFailsClosed() throws Exception {
        try (FxOpenAiAdapter adapter = FxOpenAiAdapter.start(settings("sk-upstream"))) {
            final String events = generate(adapter, "high", "provider/requested-model");

            final Map<String, Object> request = chatRequest.get();
            assertEquals("provider/requested-model", request.get("model"));
            assertEquals(Boolean.TRUE, request.get("stream"));
            assertFalse(request.containsKey("reasoning"));
            assertEquals("Bearer sk-upstream", authorization.get());

            assertTrue(events.contains(
                "data: {\"type\":\"text-delta\",\"id\":\"text-1\",\"delta\":\"Hello\"}"
            ));
            assertTrue(events.contains("\"type\":\"tool-call\""));
            assertTrue(events.contains("\"toolCallId\":\"call-1\""));
            assertTrue(events.contains("\"toolName\":\"rename\""));
            assertTrue(events.contains("\"unified\":\"tool-calls\""));
            assertTrue(events.contains("\"inputTokens\""));
            assertTrue(events.trim().endsWith("data: [DONE]"));
        }
    }

    @Test
    void blankHeaderFallsBackToTheProfileDefaultModel() throws Exception {
        try (FxOpenAiAdapter adapter = FxOpenAiAdapter.start(settings("sk-upstream"))) {
            generate(adapter, null, null);

            assertEquals("profile/default-model", chatRequest.get().get("model"));
        }
    }

    @Test
    void providerWithoutDefaultModelPublishesOnlyDiscoveredModels() throws Exception {
        try (FxOpenAiAdapter adapter = FxOpenAiAdapter.start(settings("", ""))) {
            final HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                    URI.create(adapter.endpoint() + "/coding-agent/v1/models")
                ).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );

            assertEquals(200, response.statusCode());
            final Map<String, Object> catalog = object(StrictJson.parse(response.body()));
            assertEquals(
                List.of("local/one", "local/two"),
                ((List<?>) catalog.get("data")).stream()
                    .map(FxOpenAiAdapterTest::object)
                    .map(model -> (String) model.get("id"))
                    .toList()
            );
        }
    }

    @Test
    void manuallyAddedModelsArePublishedAndRecognizedForPrompting() throws Exception {
        try (FxOpenAiAdapter adapter = FxOpenAiAdapter.start(
            settings("", ""),
            List.of("manual/model")
        )) {
            final HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                    URI.create(adapter.endpoint() + "/coding-agent/v1/models")
                ).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );

            assertEquals(200, response.statusCode());
            assertTrue(adapter.hasModel("manual/model"));
            assertFalse(adapter.hasModel("unlisted/model"));
        }
    }

    @Test
    void keylessSelfHostedEndpointSendsNoAuthorizationAndNeverLeaksTheAdapterBearer()
        throws Exception {
        try (FxOpenAiAdapter adapter = FxOpenAiAdapter.start(settings(""))) {
            final Map<String, String> environment = adapter.fxEnvironment();
            assertEquals(
                adapter.endpoint() + "/v3/ai/language-model",
                environment.get("FX_GATEWAY_CHAT_URL")
            );

            generate(adapter, "high", null);

            assertNull(authorization.get());
            assertFalse(environment.get("AI_GATEWAY_API_KEY").isBlank());
        }
    }

    @Test
    void modelDiscoveryReturnsUpstreamIdentifiersWithoutStartingAnAdapter() throws Exception {
        final List<String> models = FxOpenAiAdapter.discoverModels(settings("sk-upstream"));

        assertEquals(List.of("local/one", "local/two"), models);
        assertEquals(List.of("/v1/models"), paths);
        assertEquals("Bearer sk-upstream", authorization.get());
    }

    @Test
    void keylessModelDiscoverySendsNoAuthorization() throws Exception {
        final List<String> models = FxOpenAiAdapter.discoverModels(settings(""));

        assertEquals(List.of("local/one", "local/two"), models);
        assertNull(authorization.get());
    }

    private String generate(
        final FxOpenAiAdapter adapter,
        final String reasoning,
        final String requestedModel
    ) throws Exception {
        final java.util.LinkedHashMap<String, Object> gateway = new java.util.LinkedHashMap<>();
        gateway.put("prompt", List.of(
            Map.of("role", "system", "content", "boundary"),
            Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "rename the object")
            ))
        ));
        if (reasoning != null) gateway.put("reasoning", reasoning);
        final HttpRequest.Builder request = HttpRequest.newBuilder(
            URI.create(adapter.endpoint() + "/v3/ai/language-model")
        ).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
        if (requestedModel != null) request.header("ai-language-model-id", requestedModel);
        final HttpResponse<String> response = HttpClient.newHttpClient().send(
            request.POST(HttpRequest.BodyPublishers.ofByteArray(StrictJson.bytes(gateway))).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private FxCustomEndpointSettings settings(final String apiKey) {
        return settings(apiKey, "profile/default-model");
    }

    private FxCustomEndpointSettings settings(final String apiKey, final String model) {
        return new FxCustomEndpointSettings(
            true,
            "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1",
            model,
            "",
            apiKey
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }
}

package dev.turboism.plugin.turboismwithfx;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.turboism.protocol.json.StrictJson;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Empty loopback Gateway endpoint that lets ACP connect before a provider is selected. */
final class FxDeferredGatewayAdapter implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final URI endpoint;
    private final AtomicBoolean closed = new AtomicBoolean();

    private FxDeferredGatewayAdapter(
        final HttpServer server,
        final ExecutorService executor,
        final URI endpoint
    ) {
        this.server = server;
        this.executor = executor;
        this.endpoint = endpoint;
    }

    static FxDeferredGatewayAdapter start() throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress(
            InetAddress.getByName("127.0.0.1"), 0
        ), 0);
        final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "turboism-fx-deferred-adapter");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        final URI endpoint = URI.create(
            "http://127.0.0.1:" + server.getAddress().getPort()
        );
        final FxDeferredGatewayAdapter adapter = new FxDeferredGatewayAdapter(
            server, executor, endpoint
        );
        server.createContext("/coding-agent/v1/models", adapter::models);
        server.createContext("/v3/ai/language-model", adapter::generate);
        server.start();
        return adapter;
    }

    Map<String, String> fxEnvironment() {
        return Map.of(
            "AI_GATEWAY_API_KEY", "turboism-deferred-provider",
            "FX_GATEWAY_CHAT_URL", endpoint + "/v3/ai/language-model",
            "FX_GATEWAY_BASE_URL", endpoint.toString()
        );
    }

    private void models(final HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1L);
                return;
            }
            final byte[] body = StrictJson.bytes(Map.of(
                "object", "list",
                "data", List.of()
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private void generate(final HttpExchange exchange) throws IOException {
        try (exchange) {
            final byte[] body = StrictJson.bytes(Map.of(
                "error", Map.of(
                    "type", "provider_required",
                    "message", "Select a provider and model before sending a prompt"
                )
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        executor.shutdownNow();
    }
}

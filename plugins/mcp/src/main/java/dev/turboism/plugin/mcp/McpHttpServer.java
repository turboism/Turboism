package dev.turboism.plugin.mcp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.UiScheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only Streamable HTTP transport for the embedded MCP server. */
final class McpHttpServer implements AutoCloseable {

    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Set<String> ACCEPTED_PROTOCOL_VERSIONS = Set.of(
        McpProtocol.VERSION,
        "2025-06-18",
        "2025-03-26"
    );

    private final HttpServer server;
    private final ExecutorService executor;
    private final PluginLogger logger;
    private final McpProtocol protocol;
    private final String token;
    private final Path connectionFile;
    private final URI endpoint;
    private final WindowRateLimiter rateLimiter;
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpHttpServer(
        final HttpServer server,
        final ExecutorService executor,
        final PluginLogger logger,
        final McpProtocol protocol,
        final String token,
        final Path connectionFile,
        final URI endpoint,
        final WindowRateLimiter rateLimiter
    ) {
        this.server = server;
        this.executor = executor;
        this.logger = logger;
        this.protocol = protocol;
        this.token = token;
        this.connectionFile = connectionFile;
        this.endpoint = endpoint;
        this.rateLimiter = rateLimiter;
    }

    static McpHttpServer start(final PluginContext context) throws IOException {
        Objects.requireNonNull(context, "context");
        final StartupStage stage = new StartupStage();
        try {
            stage.enter("context.logger()");
            final PluginLogger logger = context.logger();
            stage.enter("context.modelObjects()");
            final ModelObjectService modelObjects = context.modelObjects();
            stage.enter("context.parameterQuery()");
            final ParameterQueryService parameterQuery = context.parameterQuery();
            stage.enter("context.modelHierarchyQuery()");
            final ModelHierarchyQueryService hierarchyQuery = context.modelHierarchyQuery();
            stage.enter("context.selectionQuery()");
            final SelectionQueryService selectionQuery = context.selectionQuery();
            stage.enter("context.cubismRead()");
            final CubismReadCapabilityService read = context.cubismRead();
            stage.enter("context.cubismClipMasks()");
            final CubismClipMaskService clipMasks = context.cubismClipMasks();
            stage.enter("context.uiScheduler()");
            final UiScheduler uiScheduler = context.uiScheduler();
            stage.enter("context.paths().stateDir()");
            final Path stateDir = context.paths().stateDir();
            stage.enter("port property");
            final int port = integerProperty("turboism.mcp.port", 0, 0, 65535);
            stage.enter("configured token");
            final String token = configuredToken();
            stage.enter("requests-per-minute property");
            final int requestsPerMinute =
                integerProperty("turboism.mcp.requestsPerMinute", 120, 10, 6000);
            stage.enter("context dependency extraction");
            return start(new Dependencies(
                logger,
                modelObjects,
                parameterQuery,
                hierarchyQuery,
                selectionQuery,
                read,
                clipMasks,
                uiScheduler,
                stateDir,
                port,
                token,
                requestsPerMinute
            ), stage);
        } catch (McpStartupFailure failure) {
            throw failure;
        } catch (Throwable failure) {
            throw stage.failure(failure);
        }
    }

    static McpHttpServer start(final Dependencies dependencies) throws IOException {
        return start(dependencies, new StartupStage());
    }

    private static McpHttpServer start(
        final Dependencies dependencies,
        final StartupStage stage
    ) throws IOException {
        final Dependencies checked = Objects.requireNonNull(dependencies, "dependencies");
        final PluginLogger logger = checked.logger();
        HttpServer server = null;
        ExecutorService executor = null;
        McpHttpServer transport = null;
        try {
            stage.enter("numeric loopback resolution");
            final InetAddress loopback = InetAddress.getByName("127.0.0.1");
            stage.enter("socket-address construction");
            final InetSocketAddress address = new InetSocketAddress(
                loopback,
                checked.port()
            );
            stage.enter("HTTP server create/bind");
            server = HttpServer.create(address, 0);

            stage.enter("executor/transport construction");
            executor = Executors.newFixedThreadPool(
                4,
                new DaemonThreadFactory("turboism-mcp-http-")
            );
            server.setExecutor(executor);
            final int actualPort = server.getAddress().getPort();
            final URI endpoint = URI.create("http://127.0.0.1:" + actualPort + "/mcp");
            final Path connectionFile = checked.stateDir().resolve("mcp-connection.json");
            transport = new McpHttpServer(
                server,
                executor,
                logger,
                new McpProtocol(new McpTools(
                    checked.modelObjects(),
                    checked.parameterQuery(),
                    checked.hierarchyQuery(),
                    checked.selectionQuery(),
                    checked.read(),
                    checked.clipMasks(),
                    logger,
                    checked.uiScheduler()
                )),
                checked.token(),
                connectionFile,
                endpoint,
                new WindowRateLimiter(checked.requestsPerMinute())
            );
            server.createContext("/mcp", transport::handle);

            stage.enter("server start");
            server.start();

            stage.enter("connection-file publication");
            transport.writeConnectionFile();
            logger.info("Turboism MCP server listening at " + endpoint);
            return transport;
        } catch (Throwable failure) {
            closeAfterStartupFailure(transport, server, executor, failure);
            throw stage.failure(failure);
        }
    }

    /**
     * Best-effort cleanup of every allocated resource after a failed startup.
     * Every independent cleanup Throwable is added as suppressed to the exact
     * original startup Throwable; the wrapper cause is never replaced. A
     * cleanup that rethrows the original object itself is not self-suppressed
     * (addSuppressed rejects self-suppression).
     */
    static void closeAfterStartupFailure(
        final McpHttpServer transport,
        final HttpServer server,
        final ExecutorService executor,
        final Throwable original
    ) {
        if (transport != null) {
            try {
                transport.close();
            } catch (Throwable cleanup) {
                if (cleanup != original) {
                    original.addSuppressed(cleanup);
                }
            }
        }
        if (server != null) {
            try {
                server.stop(0);
            } catch (Throwable cleanup) {
                if (cleanup != original) {
                    original.addSuppressed(cleanup);
                }
            }
        }
        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (Throwable cleanup) {
                if (cleanup != original) {
                    original.addSuppressed(cleanup);
                }
            }
        }
    }

    URI endpoint() {
        return endpoint;
    }

    Path connectionFile() {
        return connectionFile;
    }

    private void handle(final HttpExchange exchange) throws IOException {
        try (exchange) {
            final Headers responseHeaders = exchange.getResponseHeaders();
            responseHeaders.set("Cache-Control", "no-store");
            responseHeaders.set("X-Content-Type-Options", "nosniff");

            if (!originAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
                sendEmpty(exchange, 403);
                return;
            }
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                responseHeaders.set("WWW-Authenticate", "Bearer");
                sendEmpty(exchange, 401);
                return;
            }
            if (!rateLimiter.acquire()) {
                responseHeaders.set("Retry-After", "60");
                sendEmpty(exchange, 429);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                responseHeaders.set("Allow", "POST");
                sendEmpty(exchange, 405);
                return;
            }
            if (!acceptsMcp(exchange.getRequestHeaders().getFirst("Accept"))) {
                sendEmpty(exchange, 406);
                return;
            }
            if (!isJson(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                sendEmpty(exchange, 415);
                return;
            }
            final String protocolVersion = exchange.getRequestHeaders()
                .getFirst("MCP-Protocol-Version");
            if (protocolVersion != null && !protocolVersion.isBlank()
                && !ACCEPTED_PROTOCOL_VERSIONS.contains(protocolVersion)) {
                sendEmpty(exchange, 400);
                return;
            }

            final byte[] body;
            try {
                body = readBounded(exchange);
            } catch (BodyTooLarge failure) {
                sendEmpty(exchange, 413);
                return;
            } catch (IllegalArgumentException failure) {
                sendEmpty(exchange, 400);
                return;
            }
            final Object request;
            try {
                request = Json.parse(body);
            } catch (IllegalArgumentException failure) {
                sendJson(exchange, 200, McpProtocol.parseError(failure.getMessage()));
                return;
            }
            if ((protocolVersion == null || protocolVersion.isBlank())
                && !isInitializeRequest(request)) {
                sendEmpty(exchange, 400);
                return;
            }
            final McpProtocol.Outcome outcome = protocol.handle(request);
            if (outcome.body() == null) {
                sendEmpty(exchange, outcome.status());
            } else {
                sendJson(exchange, outcome.status(), outcome.body());
            }
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            logger.error("Unhandled Turboism MCP transport failure", failure);
            if (exchange.getResponseCode() < 0) {
                try {
                    sendEmpty(exchange, 500);
                } catch (IOException ignored) {
                    // The client may already have disconnected.
                }
            }
        }
    }

    private byte[] readBounded(final HttpExchange exchange) throws IOException {
        final String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                final long length = Long.parseLong(contentLength);
                if (length < 0 || length > MAX_BODY_BYTES) throw new BodyTooLarge();
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Invalid Content-Length", failure);
            }
        }
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_BODY_BYTES) throw new BodyTooLarge();
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void writeConnectionFile() throws IOException {
        final Path directory = connectionFile.getParent();
        Files.createDirectories(directory);
        final LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("transport", "streamable-http");
        content.put("endpoint", endpoint.toString());
        content.put("protocolVersion", McpProtocol.VERSION);
        content.put("authorization", "Bearer " + token);
        content.put("pid", ProcessHandle.current().pid());
        content.put("startedAt", Instant.now().toString());
        final byte[] bytes = Json.bytes(content);
        final Path temporary = Files.createTempFile(directory, ".mcp-connection-", ".tmp");
        try {
            Files.write(temporary, bytes);
            restrictToOwner(temporary);
            try {
                Files.move(
                    temporary,
                    connectionFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, connectionFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(connectionFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictToOwner(final Path file) {
        try {
            Files.setPosixFilePermissions(
                file,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            );
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACL inheritance is supplied by the plugin state directory.
        }
    }

    private boolean authorized(final String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        final byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        final byte[] supplied = authorization.substring("Bearer ".length())
            .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }

    private static boolean originAllowed(final String origin) {
        if (origin == null || origin.isBlank()) return true;
        try {
            final URI value = URI.create(origin);
            final String scheme = value.getScheme();
            final String host = value.getHost();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && host != null
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host))
                && value.getRawUserInfo() == null
                && value.getRawQuery() == null
                && value.getRawFragment() == null
                && (value.getRawPath() == null || value.getRawPath().isEmpty()
                    || "/".equals(value.getRawPath()));
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean acceptsMcp(final String accept) {
        if (accept == null) return false;
        final String normalized = accept.toLowerCase(Locale.ROOT);
        return normalized.contains("application/json")
            && normalized.contains("text/event-stream");
    }

    private static boolean isJson(final String contentType) {
        if (contentType == null) return false;
        final String normalized = contentType.toLowerCase(Locale.ROOT).strip();
        return normalized.equals("application/json")
            || normalized.startsWith("application/json;");
    }

    private static boolean isInitializeRequest(final Object request) {
        if (!(request instanceof Map<?, ?> values)) return false;
        return "initialize".equals(values.get("method"));
    }

    private static void sendJson(
        final HttpExchange exchange,
        final int status,
        final Object body
    ) throws IOException {
        final byte[] bytes = Json.bytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendEmpty(final HttpExchange exchange, final int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static int integerProperty(
        final String name,
        final int defaultValue,
        final int minimum,
        final int maximum
    ) {
        final String configured = System.getProperty(name);
        if (configured == null || configured.isBlank()) return defaultValue;
        try {
            final int value = Integer.parseInt(configured.strip());
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum
                );
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }

    private static String configuredToken() {
        final String configured = System.getProperty("turboism.mcp.token");
        if (configured != null && !configured.isBlank()) {
            final String value = configured.strip();
            if (value.length() < 24 || value.length() > 512) {
                throw new IllegalArgumentException(
                    "turboism.mcp.token must contain between 24 and 512 characters"
                );
            }
            return value;
        }
        final byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        executor.shutdownNow();
        try {
            Files.deleteIfExists(connectionFile);
        } catch (IOException failure) {
            logger.warn("Could not remove MCP connection file: " + connectionFile);
        }
        logger.info("Turboism MCP server stopped");
    }

    /**
     * Last-entered deterministic startup stage, advanced before every step.
     */
    private static final class StartupStage {
        private String current = "context dependency extraction";

        private void enter(final String name) {
            this.current = name;
        }

        private McpStartupFailure failure(final Throwable original) {
            return new McpStartupFailure(current, frameChain(original), original);
        }

        private static String frameChain(final Throwable original) {
            // Bounded diagnostic frame chain: at most the first six frames in
            // original order, one line, class.method[:line] only, no file paths.
            final StackTraceElement[] trace = original.getStackTrace();
            if (trace.length == 0) {
                return "<empty stack>";
            }
            final StringBuilder chain = new StringBuilder();
            final int limit = Math.min(trace.length, 6);
            for (int index = 0; index < limit; index++) {
                if (index > 0) {
                    chain.append(", ");
                }
                final StackTraceElement frame = trace[index];
                chain.append(frame.getClassName()).append('.').append(frame.getMethodName());
                if (frame.getLineNumber() >= 0) {
                    chain.append(':').append(frame.getLineNumber());
                }
            }
            return chain.toString();
        }
    }

    /**
     * Diagnostic-only wrapper: reports the startup stage where the original
     * failure surfaced and the original Throwable's top stack frame, keeping
     * the exact original Throwable as cause.
     */
    static final class McpStartupFailure extends IOException {
        private final String stage;

        private McpStartupFailure(
            final String stage,
            final String frameChain,
            final Throwable cause
        ) {
            super(
                "Turboism MCP startup failed at stage '" + stage + "'"
                    + " (original failure at " + frameChain + ")",
                cause
            );
            this.stage = stage;

        }
        String stage() {
            return stage;
        }
    }
    record Dependencies(
        PluginLogger logger,
        ModelObjectService modelObjects,
        ParameterQueryService parameterQuery,
        ModelHierarchyQueryService hierarchyQuery,
        SelectionQueryService selectionQuery,
        CubismReadCapabilityService read,
        CubismClipMaskService clipMasks,
        UiScheduler uiScheduler,
        Path stateDir,
        int port,
        String token,
        int requestsPerMinute
    ) {
        Dependencies {
            logger = Objects.requireNonNull(logger, "logger");
            modelObjects = Objects.requireNonNull(modelObjects, "modelObjects");
            parameterQuery = Objects.requireNonNull(parameterQuery, "parameterQuery");
            hierarchyQuery = Objects.requireNonNull(hierarchyQuery, "hierarchyQuery");
            selectionQuery = Objects.requireNonNull(selectionQuery, "selectionQuery");
            read = Objects.requireNonNull(read, "read");
            clipMasks = Objects.requireNonNull(clipMasks, "clipMasks");
            uiScheduler = Objects.requireNonNull(uiScheduler, "uiScheduler");
            stateDir = Objects.requireNonNull(stateDir, "stateDir").toAbsolutePath().normalize();
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            token = Objects.requireNonNull(token, "token").strip();
            if (token.length() < 24 || token.length() > 512) {
                throw new IllegalArgumentException("token must contain between 24 and 512 characters");
            }
            if (requestsPerMinute < 1 || requestsPerMinute > 6000) {
                throw new IllegalArgumentException(
                    "requestsPerMinute must be between 1 and 6000"
                );
            }
        }
    }

    private static final class BodyTooLarge extends IOException {
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private DaemonThreadFactory(final String prefix) {
            this.prefix = prefix;
        }

        @Override public Thread newThread(final Runnable task) {
            final Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class WindowRateLimiter {
        private final int limit;
        private long windowStart = System.nanoTime();
        private int count;

        private WindowRateLimiter(final int limit) {
            this.limit = limit;
        }

        private synchronized boolean acquire() {
            final long now = System.nanoTime();
            if (now - windowStart >= 60_000_000_000L) {
                windowStart = now;
                count = 0;
            }
            if (count >= limit) return false;
            count++;
            return true;
        }
    }
}

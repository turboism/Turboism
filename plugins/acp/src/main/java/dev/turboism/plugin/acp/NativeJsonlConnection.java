package dev.turboism.plugin.acp;

import dev.turboism.sdk.io.BoundedLineReader;
import dev.turboism.sdk.json.Json;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Supervised, bounded native JSONL I/O shared by the three built-in providers. */
final class NativeJsonlConnection implements AutoCloseable {
    private final AcpTransport transport;
    private final BufferedWriter writer;
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    NativeJsonlConnection(
            final AcpTransport transport,
            final Consumer<Map<String, Object>> events,
            final Consumer<byte[]> stderr,
            final Consumer<Throwable> failure) {
        this.transport = transport;
        writer = new BufferedWriter(new OutputStreamWriter(transport.stdin(), StandardCharsets.UTF_8));
        final Thread output = new Thread(
                () -> {
                    try (var lines = new BoundedLineReader(
                            new InputStreamReader(
                                    transport.stdout(),
                                    StandardCharsets.UTF_8
                                            .newDecoder()
                                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)),
                            AcpClient.MAX_ACP_LINE_CHARS)) {
                        for (String line; (line = lines.readLine()) != null; ) {
                            if (line.isBlank()) continue;
                            final Map<String, Object> message = NativeAgentTransport.object(
                                    Json.parseObject(line.getBytes(StandardCharsets.UTF_8)));
                            final Object id = responseId(message);
                            final var request = id == null ? null : pending.remove(id.toString());
                            if (request != null) request.complete(message);
                            else events.accept(message);
                        }
                        if (!closed.get()) throw new IOException("Native agent closed its output");
                    } catch (IOException | RuntimeException exception) {
                        if (!closed.get()) {
                            failPending(exception);
                            failure.accept(exception);
                            close();
                        }
                    }
                },
                "turboism-native-agent-stdout");
        output.setDaemon(true);
        output.start();
        final Thread errors = new Thread(
                () -> {
                    try (var input = transport.stderr()) {
                        final byte[] buffer = new byte[4096];
                        for (int size; (size = input.read(buffer)) >= 0; ) {
                            if (size > 0) stderr.accept(java.util.Arrays.copyOf(buffer, size));
                        }
                    } catch (IOException ignored) {
                        // The owning transport controls shutdown; stdout reports unexpected termination.
                    }
                },
                "turboism-native-agent-stderr");
        errors.setDaemon(true);
        errors.start();
    }

    private static Object responseId(final Map<String, Object> message) {
        if ("control_response".equals(message.get("type"))) {
            return NativeAgentTransport.object(message.get("response")).get("request_id");
        }
        if ("response".equals(message.get("type")) || !message.containsKey("method")) return message.get("id");
        return null;
    }

    CompletableFuture<Map<String, Object>> request(final Object id, final Map<String, Object> message) {
        if (pending.size() >= 64) return CompletableFuture.failedFuture(new IOException("Too many native requests"));
        final var future = new CompletableFuture<Map<String, Object>>();
        pending.put(id.toString(), future);
        future.orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((result, error) -> pending.remove(id.toString(), future));
        try {
            send(message);
        } catch (IOException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    synchronized void send(final Map<String, Object> message) throws IOException {
        if (closed.get()) throw new IOException("Native agent is closed");
        final String line = Json.stringify(message);
        if (line.length() > AcpClient.MAX_ACP_LINE_CHARS) throw new IOException("Native message exceeds size limit");
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private void failPending(final Throwable failure) {
        pending.values().forEach(future -> future.completeExceptionally(failure));
        pending.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        failPending(new IOException("Native agent closed"));
        transport.terminate(Duration.ofMillis(200));
        transport.close();
    }
}

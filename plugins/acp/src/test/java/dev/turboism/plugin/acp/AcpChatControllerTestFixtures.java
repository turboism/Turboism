package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class AcpChatControllerTestFixtures {

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(final Object value) {
        return (List<Object>) value;
    }

    @FunctionalInterface
    interface CheckedCondition {
        boolean test() throws Exception;
    }

    static final class RecordingView implements AcpChatController.View {
        final List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> userMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> agentMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> thinkingMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<List<String>> toolCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> configUpdatingIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<String> configFailureId =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<List<AcpConfigOption>> configFailureOptions =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean prompting = new java.util.concurrent.atomic.AtomicBoolean();
        final List<String> timeline = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger permissionRequests =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile java.util.concurrent.CountDownLatch permissionEntered;
        volatile java.util.concurrent.CountDownLatch releasePermission;
        volatile AcpListener.PermissionDecision permissionDecision = AcpListener.PermissionDecision.CANCELLED;
        volatile AcpClient.McpAttachment lastMcpAttachment;
        final java.util.concurrent.atomic.AtomicReference<List<AcpSessionSummary>> listedSessions =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());

        void record(final String event) {
            timeline.add(event);
        }

        @Override
        public void showConnecting(final String agentLabel) {}

        @Override
        public void showConnected(
                final AcpClient.AcpAgentInfo agentInfo,
                final List<AcpConfigOption> options,
                final boolean durableSessionsAvailable,
                final AcpClient.McpAttachment mcpAttachment) {
            lastMcpAttachment = mcpAttachment;
            record("connected");
        }

        @Override
        public void showAuthRequired(final List<AcpAuthMethod> methods) {
            record("auth-required");
        }

        @Override
        public void showConfigOptions(final List<AcpConfigOption> options) {
            record("config");
        }

        @Override
        public void showConfigUpdating(final String optionId) {
            configUpdatingIds.add(optionId);
        }

        @Override
        public void showConfigFailure(final String optionId, final List<AcpConfigOption> confirmedOptions) {
            configFailureId.set(optionId);
            configFailureOptions.set(List.copyOf(confirmedOptions));
        }

        @Override
        public void showSessions(
                final List<AcpSessionSummary> sessions,
                final String activeSessionId,
                final boolean durableSessionsAvailable) {
            listedSessions.set(List.copyOf(sessions));
        }

        @Override
        public void clearTranscript() {
            record("clear");
        }

        @Override
        public void showPrompting() {
            prompting.set(true);
        }

        @Override
        public void showPromptComplete(final String stopReason) {
            record("complete:" + stopReason);
        }

        @Override
        public void showFailure(final String localizationKey) {
            failures.add(localizationKey);
        }

        @Override
        public void showSessionFailure(final String localizationKey) {
            failures.add(localizationKey);
        }

        @Override
        public void showSettingsSaved() {}

        @Override
        public void appendUser(final String text) {
            userMessages.add(text);
        }

        @Override
        public void appendAgent(final String text) {
            agentMessages.add(text);
            record("agent:" + text);
        }

        @Override
        public void appendThinking(final String text) {
            thinkingMessages.add(text);
            record("thought:" + text);
        }

        @Override
        public void appendTool(final String toolCallId, final String title, final String kind, final String status) {
            toolCalls.add(List.of(toolCallId, title, kind, status));
            record("tool:" + toolCallId + ":" + title + ":" + kind + ":" + status);
        }

        @Override
        public void updateToolInput(final String toolCallId, final String input) {
            record("input:" + toolCallId + ":" + input);
        }

        @Override
        public void updateTool(final String toolCallId, final String status, final String content) {
            record("update:" + toolCallId + ":" + status + ":" + content);
        }

        @Override
        public AcpListener.PermissionDecision requestPermission(final AcpListener.PermissionRequest request) {
            permissionRequests.incrementAndGet();
            final java.util.concurrent.CountDownLatch entered = permissionEntered;
            if (entered != null) entered.countDown();
            final java.util.concurrent.CountDownLatch release = releasePermission;
            if (release != null) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AcpListener.PermissionDecision.CANCELLED;
                }
            }
            return permissionDecision;
        }

        void awaitFailure(final String expected) throws InterruptedException {
            for (int attempt = 0; !failures.contains(expected) && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertTrue(failures.contains(expected), "missing controller failure " + expected + " in " + failures);
        }

        void awaitPrompting() throws InterruptedException {
            for (int attempt = 0; !prompting.get() && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertTrue(prompting.get(), "controller did not enter prompting state");
        }

        void awaitAgentMessages() throws InterruptedException {
            for (int attempt = 0; agentMessages.isEmpty() && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertFalse(agentMessages.isEmpty(), "controller did not drain ACP UI updates");
        }

        void awaitThinking() throws InterruptedException {
            for (int attempt = 0; thinkingMessages.isEmpty() && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertFalse(thinkingMessages.isEmpty(), "controller did not forward ACP thinking");
        }

        void awaitToolCall() throws InterruptedException {
            for (int attempt = 0; toolCalls.isEmpty() && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertFalse(toolCalls.isEmpty(), "controller did not forward ACP tool identity");
        }

        void awaitConfigFailure() throws InterruptedException {
            for (int attempt = 0; configFailureId.get() == null && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertTrue(configFailureId.get() != null, "controller did not restore config state");
        }

        void awaitTimeline(final String event) throws InterruptedException {
            for (int attempt = 0; !timeline.contains(event) && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertTrue(timeline.contains(event), "controller did not deliver " + event);
        }
    }

    static final class CapturingLogger implements PluginLogger {
        final List<String> infos = new java.util.concurrent.CopyOnWriteArrayList<>();

        void awaitInfo(final String message) throws InterruptedException {
            final long deadline =
                    System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (!infos.contains(message) && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            assertTrue(infos.contains(message), "controller did not log " + message + "; infos=" + infos);
        }

        final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {
            infos.add(message);
        }

        @Override
        public void warn(final String message) {
            warnings.add(message);
        }

        @Override
        public void error(final String message) {
            errors.add(new AssertionError(message));
        }

        @Override
        public void error(final String message, final Throwable throwable) {
            errors.add(throwable);
        }

        boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    static final class MemoryConfig implements PluginConfigRegistry {
        final Map<String, String> values = new LinkedHashMap<>();
        boolean failWrites;
        String failKey;
        String mutateThenFailKey;

        String value(final String key) {
            return values.get(key);
        }

        @Override
        public Registration readScope(final String relativePath) {
            return () -> {};
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return () -> {};
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value)
                throws dev.turboism.sdk.config.PluginConfigException {
            if (failWrites || key.equals(failKey)) {
                failKey = null;
                throw new dev.turboism.sdk.config.PluginConfigException("test write failure");
            }
            values.put(key, value);
            if (key.equals(mutateThenFailKey)) {
                mutateThenFailKey = null;
                throw new dev.turboism.sdk.config.PluginConfigException("test post-mutation write failure");
            }
        }

        @Override
        public CompletionStage<Void> registerSchema(final ConfigSchema schema, final List<ConfigMigration> migrations) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(
                final ConfigKey<T> key, final T value, final long expectedRevision) {
            throw new UnsupportedOperationException("not used");
        }
    }

    static final class BlockingLoadTransport implements AcpTransport {
        final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        final java.io.PipedOutputStream serverStdout;
        final java.io.PipedInputStream stderr = new java.io.PipedInputStream();
        final java.io.PipedOutputStream serverStderr;
        final java.util.concurrent.CountDownLatch loadStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseLoad = new java.util.concurrent.CountDownLatch(1);
        volatile boolean alive = true;

        BlockingLoadTransport() throws java.io.IOException {
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            serverStderr = new java.io.PipedOutputStream(stderr);
        }

        @Override
        public java.io.InputStream stdout() {
            return clientStdout;
        }

        @Override
        public java.io.InputStream stderr() {
            return stderr;
        }

        @Override
        public java.io.OutputStream stdin() {
            return new java.io.OutputStream() {
                final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

                @Override
                public void write(final int value) throws java.io.IOException {
                    if (value == '\n') {
                        handle(line.toString(java.nio.charset.StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            };
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void terminate(final Duration grace) {
            close();
        }

        @Override
        public void close() {
            alive = false;
            releaseLoad.countDown();
            try {
                serverStdout.close();
            } catch (java.io.IOException ignored) {
            }
            try {
                serverStderr.close();
            } catch (java.io.IOException ignored) {
            }
        }

        void handle(final String json) throws java.io.IOException {
            final Map<String, Object> request = object(
                    dev.turboism.sdk.json.Json.parseObject(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!"session/load".equals(request.get("method"))) return;
            loadStarted.countDown();
            try {
                releaseLoad.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class ReplayLoadTransport implements AcpTransport {
        final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        final java.io.PipedOutputStream serverStdout;
        final java.io.PipedInputStream stderr = new java.io.PipedInputStream();
        final java.io.PipedOutputStream serverStderr;
        final String sessionId;
        volatile boolean alive = true;

        ReplayLoadTransport(final String sessionId) throws java.io.IOException {
            this.sessionId = sessionId;
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            serverStderr = new java.io.PipedOutputStream(stderr);
        }

        @Override
        public java.io.InputStream stdout() {
            return clientStdout;
        }

        @Override
        public java.io.InputStream stderr() {
            return stderr;
        }

        @Override
        public java.io.OutputStream stdin() {
            return new java.io.OutputStream() {
                final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

                @Override
                public void write(final int value) throws java.io.IOException {
                    if (value == '\n') {
                        handle(line.toString(java.nio.charset.StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            };
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void terminate(final Duration grace) {
            close();
        }

        @Override
        public void close() {
            alive = false;
            try {
                serverStdout.close();
            } catch (java.io.IOException ignored) {
            }
            try {
                serverStderr.close();
            } catch (java.io.IOException ignored) {
            }
        }

        void handle(final String json) throws java.io.IOException {
            final Map<String, Object> request = object(
                    dev.turboism.sdk.json.Json.parseObject(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!"session/load".equals(request.get("method"))) return;
            final Map<String, Object> update = new LinkedHashMap<>();
            update.put("jsonrpc", "2.0");
            update.put("method", "session/update");
            update.put(
                    "params",
                    Map.of(
                            "sessionId",
                            sessionId,
                            "update",
                            Map.of(
                                    "sessionUpdate",
                                    "agent_message_chunk",
                                    "content",
                                    Map.of("type", "text", "text", "restored"))));
            serverStdout.write((dev.turboism.sdk.json.Json.stringify(update) + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", request.get("id"));
            response.put("result", Map.of("configOptions", List.of()));
            serverStdout.write((dev.turboism.sdk.json.Json.stringify(response) + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            serverStdout.flush();
        }
    }

    static final class PermissionFailingLoadTransport implements AcpTransport {
        final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        final java.io.PipedOutputStream serverStdout;
        final java.io.PipedInputStream stderr = new java.io.PipedInputStream();
        final java.io.PipedOutputStream serverStderr;
        final java.util.concurrent.atomic.AtomicReference<AcpListener.PermissionDecision> decision =
                new java.util.concurrent.atomic.AtomicReference<>();
        volatile AcpClient source;
        volatile AcpListener listener;
        volatile boolean alive = true;

        PermissionFailingLoadTransport() throws java.io.IOException {
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            serverStderr = new java.io.PipedOutputStream(stderr);
        }

        @Override
        public java.io.InputStream stdout() {
            return clientStdout;
        }

        @Override
        public java.io.InputStream stderr() {
            return stderr;
        }

        @Override
        public java.io.OutputStream stdin() {
            return new java.io.OutputStream() {
                final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

                @Override
                public void write(final int value) throws java.io.IOException {
                    if (value == '\n') {
                        handle(line.toString(java.nio.charset.StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            };
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void terminate(final Duration grace) {
            close();
        }

        @Override
        public void close() {
            alive = false;
            try {
                serverStdout.close();
            } catch (java.io.IOException ignored) {
            }
            try {
                serverStderr.close();
            } catch (java.io.IOException ignored) {
            }
        }

        void handle(final String json) throws java.io.IOException {
            final Map<String, Object> request = object(
                    dev.turboism.sdk.json.Json.parseObject(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!"session/load".equals(request.get("method"))) return;
            final Map<String, Object> params = object(request.get("params"));
            final AcpListener.PermissionDecision permission = listener.permission(
                    source,
                    (String) params.get("sessionId"),
                    new AcpListener.PermissionRequest(
                            "Resume",
                            "edit",
                            "call-1",
                            "{}",
                            new AcpClient.PermissionOptionSet("allow_once", "allow_always", "reject_once")));
            decision.set(permission);
            final Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", request.get("id"));
            response.put("error", Map.of("code", -32000L, "message", "permission cancelled"));
            serverStdout.write((dev.turboism.sdk.json.Json.stringify(response) + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            serverStdout.flush();
        }
    }

    static final class CapturingTransport implements AcpTransport {
        final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        final java.io.PipedOutputStream serverStdout;
        final java.io.ByteArrayOutputStream stdin = new java.io.ByteArrayOutputStream();
        final java.io.PipedInputStream stderr = new java.io.PipedInputStream();
        final java.io.PipedOutputStream serverStderr;

        CapturingTransport() throws java.io.IOException {
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            serverStderr = new java.io.PipedOutputStream(stderr);
        }

        boolean hasRequest() {
            return stdin.size() > 0;
        }

        Map<String, Object> request() throws Exception {
            for (int attempt = 0; stdin.size() == 0 && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            final String line =
                    stdin.toString(java.nio.charset.StandardCharsets.UTF_8).strip();
            assertFalse(line.isEmpty(), "ACP request was not written");
            return object(
                    dev.turboism.sdk.json.Json.parseObject(line.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Override
        public java.io.InputStream stdout() {
            return clientStdout;
        }

        @Override
        public java.io.InputStream stderr() {
            return stderr;
        }

        @Override
        public java.io.OutputStream stdin() {
            return stdin;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void terminate(final Duration grace) {
            try {
                serverStdout.close();
            } catch (java.io.IOException ignored) {
            }
            try {
                serverStderr.close();
            } catch (java.io.IOException ignored) {
            }
        }

        @Override
        public void close() {
            terminate(Duration.ZERO);
        }
    }
}

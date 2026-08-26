package dev.turboism.graal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedGraalRuntimeServiceTest {

    @TempDir
    Path home;

    @Test
    void installsPinnedArchiveIntoManagedRuntimeAndPreservesLegalFiles() throws Exception {
        final byte[] archive = archive(Map.of(
            "graalvm-test/bin/java.exe", new byte[] {1, 2, 3},
            "graalvm-test/release", release().getBytes(StandardCharsets.UTF_8),
            "graalvm-test/legal/graalvm/LICENSE", "GPLv2+CPE".getBytes(StandardCharsets.UTF_8)
        ));
        final ManagedGraalRuntimeService.Platform platform = testPlatform(archive);
        final RecordingClient client = new RecordingClient(new ResponseSpec(200, Map.of(), archive));
        final List<Path> probed = new java.util.ArrayList<>();
        try (ManagedGraalRuntimeService service = service(client, platform, probed::add)) {
            final ManagedGraalRuntimeService.Status result = service.install().completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.READY, result.state());
            assertEquals(
                home.resolve("graal/runtime/bin/java.exe").toAbsolutePath().normalize(),
                result.javaExecutable().orElseThrow()
            );
            assertTrue(Files.isRegularFile(home.resolve("graal/runtime/legal/graalvm/LICENSE")));
            assertEquals(1, probed.size());
            assertTrue(probed.get(0).endsWith(Path.of("graalvm-test/bin/java.exe")));
            assertFalse(Files.exists(home.resolve("graalvm")));
        }
    }

    @Test
    void rejectsHashMismatchWithoutReplacingExistingRuntime() throws Exception {
        final Path existing = home.resolve("graal/runtime");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("marker.txt"), "existing");
        final byte[] archive = archive(Map.of(
            "graalvm-test/bin/java.exe", new byte[] {1},
            "graalvm-test/release", release().getBytes(StandardCharsets.UTF_8)
        ));
        final ManagedGraalRuntimeService.Platform platform = new ManagedGraalRuntimeService.Platform(
            "test-windows-x64", "bin/java.exe", "test.zip", archive.length,
            "0".repeat(64)
        );
        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(new ResponseSpec(200, Map.of(), archive)), platform, ignored -> { }
        )) {
            final ManagedGraalRuntimeService.Status result = service.install().completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.FAILED, result.state());
            assertEquals("GRAAL_RUNTIME_HASH_MISMATCH", result.code());
            assertEquals("existing", Files.readString(existing.resolve("marker.txt")));
        }
    }

    @Test
    void rejectsReleaseMetadataThatOnlyMatchesPinnedPrefixes() throws Exception {
        final byte[] archive = archive(Map.of(
            "graalvm-test/bin/java.exe", new byte[] {1},
            "graalvm-test/release", """
                IMPLEMENTOR="GraalVM Community Modified"
                GRAALVM_VERSION="25.2.4"
                JAVA_VERSION="25.0.40"
                """.getBytes(StandardCharsets.UTF_8)
        ));
        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(new ResponseSpec(200, Map.of(), archive)),
            testPlatform(archive),
            ignored -> { }
        )) {
            final ManagedGraalRuntimeService.Status result = service.install().completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.FAILED, result.state());
            assertEquals("GRAAL_RUNTIME_INVALID", result.code());
            assertFalse(Files.exists(home.resolve("graal/runtime")));
        }
    }

    @Test
    void rejectsRedirectOutsideApprovedOfficialHosts() throws Exception {
        final byte[] archive = new byte[] {1};
        final RecordingClient client = new RecordingClient(new ResponseSpec(
            302, Map.of("Location", List.of("https://example.invalid/runtime.zip")), archive
        ));
        try (ManagedGraalRuntimeService service = service(
            client,
            new ManagedGraalRuntimeService.Platform(
                "test-windows-x64", "bin/java.exe", "test.zip", 1L,
                sha256(archive)
            ),
            ignored -> { }
        )) {
            final ManagedGraalRuntimeService.Status result = service.install().completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.FAILED, result.state());
            assertEquals("GRAAL_RUNTIME_DOWNLOAD_URI_REJECTED", result.code());
            assertEquals(1, client.requests.size());
        }
    }

    @Test
    void rejectsZipSlipBeforeActivation() throws Exception {
        final byte[] archive = archive(Map.of(
            "graalvm-test/../escaped.txt", "escaped".getBytes(StandardCharsets.UTF_8)
        ));
        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(new ResponseSpec(200, Map.of(), archive)),
            testPlatform(archive),
            ignored -> { }
        )) {
            final ManagedGraalRuntimeService.Status result = service.install().completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.FAILED, result.state());
            assertEquals("GRAAL_RUNTIME_ARCHIVE_REJECTED", result.code());
            assertFalse(Files.exists(home.resolve("escaped.txt")));
            assertFalse(Files.exists(home.resolve("graal/runtime")));
        }
    }

    @Test
    void rejectsWin32AmbiguousArchiveSegments() throws Exception {
        final byte[] archive = archive(Map.of(
            "graalvm-test/bin/java.exe", new byte[] {1},
            "graalvm-test/release", release().getBytes(StandardCharsets.UTF_8),
            "graalvm-test/release.", "alias".getBytes(StandardCharsets.UTF_8)
        ));
        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(new ResponseSpec(200, Map.of(), archive)),
            testPlatform(archive),
            ignored -> { }
        )) {
            final ManagedGraalRuntimeService.Status result = service.install().completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.FAILED, result.state());
            assertEquals("GRAAL_RUNTIME_ARCHIVE_REJECTED", result.code());
            assertFalse(Files.exists(home.resolve("graal/runtime")));
        }
    }

    @Test
    void wholeDownloadDeadlineFailsAStalledBody() throws Exception {
        final DeadlineInputStream body = new DeadlineInputStream();
        final ManagedGraalRuntimeService.Platform platform = new ManagedGraalRuntimeService.Platform(
            "test-windows-x64", "bin/java.exe", "test.zip", 1L,
            sha256(new byte[] {1})
        );
        try (ManagedGraalRuntimeService service = new ManagedGraalRuntimeService(
            home,
            new RecordingClient(new ResponseSpec(200, Map.of(), body)),
            platform,
            ignored -> { },
            ignored -> { },
            Duration.ofMillis(100)
        )) {
            final ManagedGraalRuntimeService.Operation operation = service.install();
            assertTrue(body.awaitStarted());

            final ManagedGraalRuntimeService.Status result = operation.completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.FAILED, result.state());
            assertEquals("GRAAL_RUNTIME_DOWNLOAD_TIMEOUT", result.code());
        }
    }

    @Test
    void closeCompletesAnActiveOperationAsCancelled() throws Exception {
        final BlockingInputStream body = new BlockingInputStream();
        final ManagedGraalRuntimeService service = service(
            new RecordingClient(new ResponseSpec(200, Map.of(), body)),
            new ManagedGraalRuntimeService.Platform(
                "test-windows-x64", "bin/java.exe", "test.zip", 1L,
                sha256(new byte[] {1})
            ),
            ignored -> { }
        );
        final ManagedGraalRuntimeService.Operation operation = service.install();
        assertTrue(body.awaitStarted());

        service.close();
        body.release();
        final ManagedGraalRuntimeService.Status result = operation.completion()
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(ManagedGraalRuntimeService.State.CANCELLED, result.state());
    }

    @Test
    void cancellationDuringDownloadKeepsExistingRuntimeAndSkipsProbe() throws Exception {
        final Path existing = home.resolve("graal/runtime");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("marker.txt"), "existing");
        final BlockingInputStream body = new BlockingInputStream();
        final ManagedGraalRuntimeService.Platform platform = new ManagedGraalRuntimeService.Platform(
            "test-windows-x64", "bin/java.exe", "test.zip", 1L,
            sha256(new byte[] {1})
        );
        final List<Path> probed = new java.util.ArrayList<>();
        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(new ResponseSpec(200, Map.of(), body)), platform, probed::add
        )) {
            final ManagedGraalRuntimeService.Operation operation = service.install();
            assertTrue(body.awaitStarted());

            assertTrue(operation.cancel());
            body.release();
            final ManagedGraalRuntimeService.Status result = operation.completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.CANCELLED, result.state());
            assertEquals("existing", Files.readString(existing.resolve("marker.txt")));
            assertTrue(probed.isEmpty());
        }
    }

    @Test
    void probeFailureDoesNotReplaceExistingRuntime() throws Exception {
        final Path existing = home.resolve("graal/runtime");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("marker.txt"), "existing");
        final byte[] archive = archive(Map.of(
            "graalvm-test/bin/java.exe", new byte[] {1},
            "graalvm-test/release", release().getBytes(StandardCharsets.UTF_8)
        ));
        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(new ResponseSpec(200, Map.of(), archive)),
            testPlatform(archive),
            ignored -> { throw new IOException("probe failed"); }
        )) {
            final ManagedGraalRuntimeService.Status result = service.install().completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.FAILED, result.state());
            assertEquals("GRAAL_RUNTIME_HOST_PROBE_FAILED", result.code());
            assertEquals("existing", Files.readString(existing.resolve("marker.txt")));
        }
    }

    @Test
    void hostReadyMessageRequiresThePinnedJavaVersion() {
        assertFalse(ManagedGraalRuntimeService.isCompatibleReadyMessage(
            "{\"type\":\"READY\",\"protocolVersion\":1,"
                + "\"graalAvailable\":true,\"javaVersion\":\"25.0.40\"}"
        ));
        assertTrue(ManagedGraalRuntimeService.isCompatibleReadyMessage(
            "{\"type\":\"READY\",\"protocolVersion\":1,"
                + "\"graalAvailable\":true,\"javaVersion\":\"25.0.4\"}"
        ));
    }

    @Test
    void cancellationInterruptsAnActiveProbeAndKeepsExistingRuntime() throws Exception {
        final Path existing = home.resolve("graal/runtime");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("marker.txt"), "existing");
        final byte[] archive = archive(Map.of(
            "graalvm-test/bin/java.exe", new byte[] {1},
            "graalvm-test/release", release().getBytes(StandardCharsets.UTF_8)
        ));
        final CountDownLatch probeStarted = new CountDownLatch(1);
        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(new ResponseSpec(200, Map.of(), archive)),
            testPlatform(archive),
            ignored -> {
                probeStarted.countDown();
                new CountDownLatch(1).await();
            }
        )) {
            final ManagedGraalRuntimeService.Operation operation = service.install();
            assertTrue(probeStarted.await(5, TimeUnit.SECONDS));

            assertTrue(operation.cancel());
            final ManagedGraalRuntimeService.Status result = operation.completion()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ManagedGraalRuntimeService.State.CANCELLED, result.state());
            assertEquals("existing", Files.readString(existing.resolve("marker.txt")));
        }
    }

    @Test
    void startupRestoresPreviousRuntimeAfterInterruptedActivation() throws Exception {
        final Path previous = home.resolve("graal/.runtime-previous");
        Files.createDirectories(previous.resolve("bin"));
        Files.write(previous.resolve("bin/java.exe"), new byte[] {1});
        Files.writeString(previous.resolve("release"), release());
        Files.writeString(home.resolve("graal/.runtime-activation"), "25.2.4\n");

        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(),
            ManagedGraalRuntimeService.Platform.WINDOWS_X64,
            ignored -> { }
        )) {
            final ManagedGraalRuntimeService.Status status = service.status();

            assertEquals(ManagedGraalRuntimeService.State.READY, status.state());
            assertTrue(Files.isRegularFile(home.resolve("graal/runtime/bin/java.exe")));
            assertFalse(Files.exists(previous, LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(
                home.resolve("graal/.runtime-activation"), LinkOption.NOFOLLOW_LINKS
            ));
        }
    }

    @Test
    void removalRejectsLinkedDescendantsAndKeepsOutsideFiles() throws Exception {
        final Path outside = home.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("marker.txt"), "outside");
        Files.createDirectories(home.resolve("graal/runtime/bin"));
        try {
            Files.createSymbolicLink(home.resolve("graal/runtime/linked"), outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + unavailable);
        }
        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(),
            ManagedGraalRuntimeService.Platform.WINDOWS_X64,
            ignored -> { }
        )) {
            final ManagedGraalRuntimeService.Status result = service.remove();

            assertEquals(ManagedGraalRuntimeService.State.FAILED, result.state());
            assertEquals("outside", Files.readString(outside.resolve("marker.txt")));
            assertTrue(Files.exists(home.resolve("graal/runtime"), LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test
    void removalDeletesOnlyManagedRuntimeAndKeepsPackagedLibraries() throws Exception {
        Files.createDirectories(home.resolve("graal/runtime/bin"));
        Files.write(home.resolve("graal/runtime/bin/java.exe"), new byte[] {1});
        Files.writeString(home.resolve("graal/runtime/release"), release());
        Files.createDirectories(home.resolve("graal/lib"));
        Files.write(home.resolve("graal/lib/polyglot.jar"), new byte[] {2});
        try (ManagedGraalRuntimeService service = service(
            new RecordingClient(),
            ManagedGraalRuntimeService.Platform.WINDOWS_X64,
            ignored -> { }
        )) {
            final ManagedGraalRuntimeService.Status result = service.remove();

            assertEquals(ManagedGraalRuntimeService.State.ABSENT, result.state());
            assertFalse(Files.exists(home.resolve("graal/runtime")));
            assertTrue(Files.isRegularFile(home.resolve("graal/lib/polyglot.jar")));
        }
    }

    private ManagedGraalRuntimeService service(
        final HttpClient client,
        final ManagedGraalRuntimeService.Platform platform,
        final ManagedGraalRuntimeService.Probe probe
    ) {
        return new ManagedGraalRuntimeService(home, client, platform, ignored -> { }, probe);
    }

    private static ManagedGraalRuntimeService.Platform testPlatform(final byte[] archive) {
        return new ManagedGraalRuntimeService.Platform(
            "test-windows-x64", "bin/java.exe", "test.zip", archive.length,
            sha256(archive)
        );
    }

    private static String release() {
        return """
            IMPLEMENTOR="GraalVM Community"
            GRAALVM_VERSION="25.2.4"
            JAVA_VERSION="25.0.4"
            """;
    }

    private static byte[] archive(final Map<String, byte[]> files) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record ResponseSpec(
        int status,
        Map<String, List<String>> headers,
        java.util.function.Supplier<java.io.InputStream> body
    ) {
        private ResponseSpec(
            final int status,
            final Map<String, List<String>> headers,
            final byte[] body
        ) {
            this(status, headers, () -> new ByteArrayInputStream(body));
        }

        private ResponseSpec(
            final int status,
            final Map<String, List<String>> headers,
            final java.io.InputStream body
        ) {
            this(status, headers, () -> body);
        }
    }

    private static final class DeadlineInputStream extends java.io.InputStream {
        private final CountDownLatch started = new CountDownLatch(1);

        boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        @Override
        public int read() throws IOException {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("unreachable");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("download deadline interrupted stalled input", interrupted);
            }
        }
    }

    private static final class BlockingInputStream extends java.io.InputStream {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private boolean delivered;

        boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override
        public int read() throws IOException {
            started.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("download test input was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("download test input was interrupted", interrupted);
            }
            if (delivered) return -1;
            delivered = true;
            return 1;
        }
    }

    private static final class RecordingClient extends HttpClient {
        private final java.util.ArrayDeque<ResponseSpec> responses = new java.util.ArrayDeque<>();
        private final List<HttpRequest> requests = new java.util.ArrayList<>();

        private RecordingClient(final ResponseSpec... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return defaultSslContext(); }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> HttpResponse<T> send(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler
        ) {
            requests.add(request);
            final ResponseSpec spec = responses.removeFirst();
            @SuppressWarnings("unchecked")
            final T body = (T) spec.body().get();
            return new FakeResponse<>(request, spec.status(), spec.headers(), body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler
        ) {
            try {
                return CompletableFuture.completedFuture(send(request, handler));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler,
            final PushPromiseHandler<T> pushPromiseHandler
        ) {
            return sendAsync(request, handler);
        }

        private static SSLContext defaultSslContext() {
            try {
                return SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    private record FakeResponse<T>(
        HttpRequest request,
        int statusCode,
        Map<String, List<String>> rawHeaders,
        T body
    ) implements HttpResponse<T> {
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            final BiPredicate<String, String> acceptAll = (name, value) -> true;
            return HttpHeaders.of(rawHeaders, acceptAll);
        }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}

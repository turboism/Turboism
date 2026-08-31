package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.plugin.PluginPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxManagedRuntimeServiceTest {

    @TempDir
    Path home;

    @Test
    void installsExactArchiveAndLeavesNoTransactionFiles() throws Exception {
        final byte[] archive = archiveFixture();
        final FxRuntimeManifest.Entry entry = fixtureEntry(archive);
        final List<FxManagedRuntimeService.Code> diagnostics = new ArrayList<>();
        final FxManagedRuntimeService service = service(
            entry, new StaticHttpClient(archive), diagnostics, ignored -> { }
        );

        assertEquals(FxManagedRuntimeService.Result.INSTALLED, service.installOrRepair());

        assertTrue(Files.isRegularFile(target().resolve("fx")));
        assertArrayEquals(Files.readAllBytes(packaged("LICENSE")),
            Files.readAllBytes(target().resolve("LICENSE")));
        assertArrayEquals(Files.readAllBytes(packaged("THIRD_PARTY_NOTICES.md")),
            Files.readAllBytes(target().resolve("THIRD_PARTY_NOTICES.md")));
        assertTrue(Files.isRegularFile(target().resolve("manifest.properties")));
        assertEquals(List.of(FxManagedRuntimeService.Code.INSTALLED), diagnostics);
        assertFalse(Files.exists(staging()));
        assertFalse(Files.exists(previous()));
    }

    @Test
    void windowsProductPayloadRequiresReinstallWithoutSendingARequestOrMutating() {
        final CountingHttpClient client = new CountingHttpClient();
        final FxManagedRuntimeService service = new FxManagedRuntimeService(
            paths(),
            () -> FxRuntimePlatform.detect("Windows 11", "amd64"),
            FxRuntimeManifest::entry,
            client,
            ignored -> { },
            bytes("LICENSE"),
            bytes("THIRD_PARTY_NOTICES.md"),
            bytes("TURBOISM-DISTRIBUTION-NOTICE.txt"),
            bytes("manifest.properties"),
            ignored -> { }
        );

        assertEquals(
            FxManagedRuntimeService.Result.PRODUCT_PAYLOAD_ONLY,
            service.installOrRepair()
        );
        assertEquals(0, client.requests);
        assertFalse(Files.exists(home.resolve("runtimes")));
        assertFalse(Files.exists(home.resolve("cache")));
    }

    @Test
    void rejectsSymlinkedRuntimeAncestorBeforeDownload() throws Exception {
        final byte[] archive = archiveFixture();
        final CountingHttpClient client = new CountingHttpClient();
        final Path outside = home.resolve("outside-runtime");
        Files.createDirectory(outside);
        Files.createSymbolicLink(home.resolve("runtimes"), outside);

        assertEquals(
            FxManagedRuntimeService.Result.FAILED,
            service(fixtureEntry(archive), client, new ArrayList<>(), ignored -> { })
                .installOrRepair()
        );
        assertEquals(0, client.requests);
        assertDirectoryEmpty(outside);
    }

    @Test
    void rejectsSymlinkedCacheAncestorBeforeDownload() throws Exception {
        final byte[] archive = archiveFixture();
        final CountingHttpClient client = new CountingHttpClient();
        final Path outside = home.resolve("outside-cache");
        Files.createDirectory(outside);
        Files.createSymbolicLink(home.resolve("cache"), outside);

        assertEquals(
            FxManagedRuntimeService.Result.FAILED,
            service(fixtureEntry(archive), client, new ArrayList<>(), ignored -> { })
                .installOrRepair()
        );
        assertEquals(0, client.requests);
        assertDirectoryEmpty(outside);
    }

    @Test
    void rejectsSymlinkedSelectedTargetBeforeDownload() throws Exception {
        final byte[] archive = archiveFixture();
        final CountingHttpClient client = new CountingHttpClient();
        final Path outside = home.resolve("outside-target");
        Files.createDirectories(versionRoot());
        Files.createDirectory(outside);
        Files.createSymbolicLink(target(), outside);

        assertEquals(
            FxManagedRuntimeService.Result.FAILED,
            service(fixtureEntry(archive), client, new ArrayList<>(), ignored -> { })
                .installOrRepair()
        );
        assertEquals(0, client.requests);
        assertDirectoryEmpty(outside);
    }

    @Test
    void retainedValidPreviousRuntimeIsReconciledAndRepaired() throws Exception {
        final byte[] archive = archiveFixture();
        writeVerifiedRuntime(previous(), "recovered-runtime");
        final StaticHttpClient client = new StaticHttpClient(archive);

        assertEquals(
            FxManagedRuntimeService.Result.INSTALLED,
            service(fixtureEntry(archive), client, new ArrayList<>(), ignored -> { })
                .installOrRepair()
        );
        assertEquals(1, client.requests);
        assertTrue(Files.isRegularFile(target().resolve("fx")));
        assertFalse(Files.exists(target().resolve("recovered-runtime")));
        assertFalse(Files.exists(previous()));
    }

    @Test
    void invalidTargetIsReplacedByValidPreviousBeforeDownloadFailure() throws Exception {
        final byte[] archive = archiveFixture();
        Files.createDirectories(target());
        Files.writeString(target().resolve("fx"), "invalid");
        writeVerifiedRuntime(previous(), "recovered-runtime");
        final FailingHttpClient client = new FailingHttpClient();

        assertEquals(
            FxManagedRuntimeService.Result.FAILED,
            service(fixtureEntry(archive), client, new ArrayList<>(), ignored -> { })
                .installOrRepair()
        );
        assertEquals(1, client.requests);
        assertTrue(Files.isRegularFile(target().resolve("recovered-runtime")));
        assertArrayEquals(executableFixture(), Files.readAllBytes(target().resolve("fx")));
        assertFalse(Files.exists(previous()));
    }

    @Test
    void unsafeInstallingStateFailsClosedBeforeDownload() throws Exception {
        final byte[] archive = archiveFixture();
        final CountingHttpClient client = new CountingHttpClient();
        final Path outside = home.resolve("outside-staging");
        Files.createDirectories(versionRoot());
        Files.createDirectory(outside);
        Files.createSymbolicLink(staging(), outside);

        assertEquals(
            FxManagedRuntimeService.Result.FAILED,
            service(fixtureEntry(archive), client, new ArrayList<>(), ignored -> { })
                .installOrRepair()
        );
        assertEquals(0, client.requests);
        assertTrue(Files.isSymbolicLink(staging()));
        assertDirectoryEmpty(outside);
    }

    @Test
    void failedPostActivationVerificationRestoresThePriorRuntime() throws Exception {
        final byte[] archive = archiveFixture();
        final FxRuntimeManifest.Entry entry = fixtureEntry(archive);
        Files.createDirectories(target());
        Files.writeString(target().resolve("old-runtime"), "preserve me");
        final FxManagedRuntimeService service = service(
            entry,
            new StaticHttpClient(archive),
            new ArrayList<>(),
            installed -> Files.writeString(installed.resolve("fx"), "corrupt")
        );

        assertEquals(FxManagedRuntimeService.Result.FAILED, service.installOrRepair());

        assertEquals("preserve me", Files.readString(target().resolve("old-runtime")));
        assertFalse(Files.exists(target().resolve("fx")));
        assertFalse(Files.exists(previous()));
    }

    @Test
    void rejectsUnreviewedRedirectHostAndPath() throws Exception {
        final byte[] archive = archiveFixture();
        final FxRuntimeManifest.Entry entry = fixtureEntry(archive);

        assertRejectedRedirect(entry, "https://example.invalid" + entry.releaseAssetPath());
        assertRejectedRedirect(
            entry,
            "https://release-assets.githubusercontent.com/github-production-release-asset/"
                + "1330702515/not-the-reviewed-asset"
        );
    }

    @Test
    void rejectsTruncatedOversizedAndHashMismatchedStreams() throws Exception {
        final byte[] archive = archiveFixture();
        final FxRuntimeManifest.Entry entry = fixtureEntry(archive);
        final byte[] truncated = Arrays.copyOf(archive, archive.length - 1);
        final byte[] oversized = Arrays.copyOf(archive, archive.length + 1);
        final byte[] hashMismatch = archive.clone();
        hashMismatch[hashMismatch.length - 1] ^= 0x01;

        assertDownloadRejected(entry, new BodyHttpClient(truncated, null));
        assertDownloadRejected(entry, new BodyHttpClient(oversized, null));
        assertDownloadRejected(entry, new BodyHttpClient(hashMismatch, (long) archive.length));
    }

    @Test
    void rejectsInvalidTarTypesInventoryAndEscapingPaths() throws Exception {
        final byte[] license = bytes("LICENSE");
        final byte[] notices = bytes("THIRD_PARTY_NOTICES.md");
        final List<byte[]> invalidArchives = List.of(
            archive(
                tar("fx", executableFixture(), 0755, '2'),
                tar("LICENSE", license, 0644, '0'),
                tar("THIRD_PARTY_NOTICES.md", notices, 0644, '0')
            ),
            archive(
                tar("fx", executableFixture(), 0755, '0'),
                tar("fx", executableFixture(), 0755, '0'),
                tar("LICENSE", license, 0644, '0'),
                tar("THIRD_PARTY_NOTICES.md", notices, 0644, '0')
            ),
            archive(
                tar("fx", executableFixture(), 0755, '0'),
                tar("LICENSE", license, 0644, '0'),
                tar("THIRD_PARTY_NOTICES.md", notices, 0644, '0'),
                tar("README", new byte[0], 0644, '0')
            ),
            archive(
                tar("../fx", executableFixture(), 0755, '0'),
                tar("LICENSE", license, 0644, '0'),
                tar("THIRD_PARTY_NOTICES.md", notices, 0644, '0')
            )
        );

        for (byte[] invalidArchive : invalidArchives) {
            final BodyHttpClient client = new BodyHttpClient(
                invalidArchive, (long) invalidArchive.length
            );
            assertEquals(
                FxManagedRuntimeService.Result.FAILED,
                service(
                    fixtureEntry(invalidArchive), client, new ArrayList<>(), ignored -> { }
                ).installOrRepair()
            );
            assertEquals(1, client.requests);
            assertFalse(Files.exists(target()));
            assertFalse(Files.exists(staging()));
        }
    }

    @Test
    void manifestPinsVersionedReleaseAssetsAndNoLatestEndpoint() {
        for (FxRuntimeManifest.Entry entry : FxRuntimeManifest.allEntries().values()) {
            if (entry.delivery() == FxRuntimeManifest.Delivery.PRODUCT_PAYLOAD) {
                assertTrue(entry.sourceUri().isEmpty());
                continue;
            }
            final URI source = entry.sourceUri().orElseThrow();
            assertEquals("https", source.getScheme());
            assertEquals("github.com", source.getHost());
            assertTrue(source.getPath().contains("/download/v0.0.5/"));
            assertFalse(source.toString().contains("latest"));
            assertTrue(entry.archiveSize() > 0L);
            assertTrue(entry.releaseAssetPath().startsWith(
                "/github-production-release-asset/1330702515/"
            ));
        }
    }

    private void assertRejectedRedirect(
        final FxRuntimeManifest.Entry entry,
        final String location
    ) {
        final RedirectHttpClient client = new RedirectHttpClient(location);
        assertEquals(
            FxManagedRuntimeService.Result.FAILED,
            service(entry, client, new ArrayList<>(), ignored -> { }).installOrRepair()
        );
        assertEquals(1, client.requests);
        assertFalse(Files.exists(target()));
    }

    private void assertDownloadRejected(
        final FxRuntimeManifest.Entry entry,
        final CountingHttpClient client
    ) {
        assertEquals(
            FxManagedRuntimeService.Result.FAILED,
            service(entry, client, new ArrayList<>(), ignored -> { }).installOrRepair()
        );
        assertEquals(1, client.requests);
        assertFalse(Files.exists(target()));
        assertFalse(Files.exists(staging()));
    }

    private void assertDirectoryEmpty(final Path directory) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            assertEquals(0L, entries.count());
        }
    }

    private FxManagedRuntimeService service(
        final FxRuntimeManifest.Entry entry,
        final HttpClient client,
        final List<FxManagedRuntimeService.Code> diagnostics,
        final FxManagedRuntimeService.FailureInjector failureInjector
    ) {
        return new FxManagedRuntimeService(
            paths(),
            () -> FxRuntimePlatform.detect("Linux", "amd64"),
            ignored -> Optional.of(entry),
            client,
            diagnostics::add,
            bytes("LICENSE"),
            bytes("THIRD_PARTY_NOTICES.md"),
            bytes("TURBOISM-DISTRIBUTION-NOTICE.txt"),
            bytes("manifest.properties"),
            failureInjector
        );
    }

    private FxRuntimeManifest.Entry fixtureEntry(final byte[] archive) throws Exception {
        final byte[] executable = executableFixture();
        return FxRuntimeManifest.Entry.upstreamArchive(
            "linux-x86_64",
            sha256(executable),
            executable.length,
            "fx-linux-x86_64.tar.gz",
            sha256(archive),
            archive.length,
            "/github-production-release-asset/1330702515/268f7872-098f-462c-a154-76f644e6ec3d",
            "test fixture"
        );
    }

    private byte[] archiveFixture() throws IOException {
        return archive(
            tar("fx", executableFixture(), 0755, '0'),
            tar("LICENSE", bytes("LICENSE"), 0644, '0'),
            tar("THIRD_PARTY_NOTICES.md", bytes("THIRD_PARTY_NOTICES.md"), 0644, '0')
        );
    }

    private static TarEntry tar(
        final String name,
        final byte[] content,
        final int mode,
        final int type
    ) {
        return new TarEntry(name, content, mode, type);
    }

    private static byte[] archive(final TarEntry... entries) throws IOException {
        final ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(archive)) {
            for (TarEntry entry : entries) writeTarEntry(gzip, entry);
            gzip.write(new byte[1024]);
        }
        return archive.toByteArray();
    }

    private static byte[] executableFixture() {
        return "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.US_ASCII);
    }

    private void writeVerifiedRuntime(final Path directory, final String marker) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve("fx"), executableFixture());
        Files.write(directory.resolve("LICENSE"), bytes("LICENSE"));
        Files.write(
            directory.resolve("THIRD_PARTY_NOTICES.md"),
            bytes("THIRD_PARTY_NOTICES.md")
        );
        Files.writeString(directory.resolve(marker), marker);
    }

    private static void writeTarEntry(
        final GZIPOutputStream output,
        final TarEntry entry
    ) throws IOException {
        final byte[] header = new byte[512];
        putAscii(header, 0, 100, entry.name());
        putOctal(header, 100, 8, entry.mode());
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, entry.content().length);
        putOctal(header, 136, 12, 0);
        for (int index = 148; index < 156; index++) header[index] = 0x20;
        header[156] = (byte) entry.type();
        putAscii(header, 257, 6, "ustar");
        header[262] = 0;
        putAscii(header, 263, 2, "00");
        long checksum = 0L;
        for (byte value : header) checksum += value & 0xff;
        putOctal(header, 148, 8, checksum);
        output.write(header);
        output.write(entry.content());
        final int padding = (512 - entry.content().length % 512) % 512;
        output.write(new byte[padding]);
    }

    private static void putAscii(
        final byte[] target,
        final int offset,
        final int length,
        final String value
    ) {
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, Math.min(length, bytes.length));
    }

    private static void putOctal(
        final byte[] target,
        final int offset,
        final int length,
        final long value
    ) {
        final String octal = Long.toOctalString(value);
        final int start = offset + length - octal.length() - 1;
        for (int index = offset; index < start; index++) target[index] = (byte) '0';
        putAscii(target, start, octal.length(), octal);
        target[offset + length - 1] = 0;
    }

    private byte[] bytes(final String name) {
        try {
            return Files.readAllBytes(packaged(name));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Path packaged(final String name) {
        return Path.of(
            System.getProperty("turboism.fxRuntimeFixtureDir"),
            name
        );
    }

    private Path versionRoot() {
        return home.resolve("runtimes/fx/0.0.5");
    }

    private Path target() {
        return versionRoot().resolve("linux-x86_64");
    }

    private Path staging() {
        return versionRoot().resolve(".linux-x86_64.installing");
    }

    private Path previous() {
        return versionRoot().resolve(".linux-x86_64.previous");
    }

    private PluginPaths paths() {
        final String plugin = "dev.turboism.plugin.turboism-with-fx";
        return new PluginPaths() {
            @Override public Path configDir() { return home.resolve("config").resolve(plugin); }
            @Override public Path dataDir() { return home.resolve("data").resolve(plugin); }
            @Override public Path logsDir() { return home.resolve("logs").resolve(plugin); }
            @Override public Path stateDir() { return home.resolve("state").resolve(plugin); }
            @Override public Path cacheDir() { return home.resolve("cache").resolve(plugin); }
        };
    }

    private static String sha256(final byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(content)
        );
    }

    private static void assertPublicRequest(final HttpRequest request) {
        assertFalse(request.headers().firstValue("Authorization").isPresent());
        assertFalse(request.headers().firstValue("Cookie").isPresent());
    }

    private static final class StaticHttpClient extends BodyHttpClient {
        private StaticHttpClient(final byte[] archive) {
            super(archive, (long) archive.length);
        }
    }

    private static class BodyHttpClient extends CountingHttpClient {
        private final byte[] body;
        private final Long declaredLength;

        private BodyHttpClient(final byte[] body, final Long declaredLength) {
            this.body = body.clone();
            this.declaredLength = declaredLength;
        }

        @Override public <T> HttpResponse<T> send(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            requests++;
            assertPublicRequest(request);
            @SuppressWarnings("unchecked")
            final T responseBody = (T) new ByteArrayInputStream(body);
            final Map<String, List<String>> headers = declaredLength == null
                ? Map.of()
                : Map.of("Content-Length", List.of(Long.toString(declaredLength)));
            return new StaticResponse<>(request, responseBody, 200, headers);
        }
    }

    private static final class RedirectHttpClient extends CountingHttpClient {
        private final String location;

        private RedirectHttpClient(final String location) {
            this.location = location;
        }

        @Override public <T> HttpResponse<T> send(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            requests++;
            assertPublicRequest(request);
            @SuppressWarnings("unchecked")
            final T responseBody = (T) new ByteArrayInputStream(new byte[0]);
            return new StaticResponse<>(
                request,
                responseBody,
                302,
                Map.of("Location", List.of(location))
            );
        }
    }

    private static final class FailingHttpClient extends CountingHttpClient {
        @Override public <T> HttpResponse<T> send(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException {
            requests++;
            assertPublicRequest(request);
            throw new IOException("synthetic download failure");
        }
    }

    private static final class StaticResponse<T> implements HttpResponse<T> {
        private final HttpRequest request;
        private final T body;
        private final int status;
        private final HttpHeaders headers;

        private StaticResponse(
            final HttpRequest request,
            final T body,
            final int status,
            final Map<String, List<String>> headers
        ) {
            this.request = request;
            this.body = body;
            this.status = status;
            this.headers = HttpHeaders.of(headers, (name, value) -> true);
        }

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return headers; }
        @Override public T body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private static class CountingHttpClient extends HttpClient {
        int requests;

        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }
        @Override public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
        @Override public <T> HttpResponse<T> send(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException, InterruptedException {
            requests++;
            throw new AssertionError("HTTP request must not be sent");
        }
        @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> responseBodyHandler,
            final HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private record TarEntry(String name, byte[] content, int mode, int type) {
        private TarEntry {
            content = content.clone();
        }

        @Override public byte[] content() {
            return content.clone();
        }
    }
}

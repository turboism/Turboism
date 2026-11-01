package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.pluginmanagement.PluginCatalog.CatalogArtifact;
import dev.turboism.pluginmanagement.PluginCatalogClient.DownloadControl;
import dev.turboism.pluginmanagement.PluginCatalogClient.DownloadFailure;
import dev.turboism.pluginmanagement.PluginCatalogClient.DownloadProgress;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginCatalogDownloadTest {
    private static final URI ARTIFACT = URI.create("https://github.com/turboism/test/releases/download/v1/test.jar");
    private static final byte[] BYTES = new byte[] {1, 2, 3, 4};

    @TempDir
    Path home;

    @Test
    void verifiesBytesAndReportsProgress() throws Exception {
        final List<DownloadProgress> progress = new ArrayList<>();
        final List<URI> requests = new ArrayList<>();
        final Path target = home.resolve("download.jar");
        client(uri -> {
                    requests.add(uri);
                    return new Response();
                })
                .downloadArtifact(ARTIFACT, BYTES.length, hash(), target, new DownloadControl(), progress::add);
        assertEquals(List.of(ARTIFACT), requests);
        assertArrayEquals(BYTES, Files.readAllBytes(target));
        assertEquals(new DownloadProgress(0, 4), progress.get(0));
        assertEquals(new DownloadProgress(4, 4), progress.get(progress.size() - 1));
    }

    @Test
    void retriesTransientResponseTimeoutOnlyOnce() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        final PluginCatalogClient client = client(uri -> attempts.incrementAndGet() == 1
                ? new Response() {
                    @Override
                    public int getResponseCode() throws IOException {
                        throw new SocketTimeoutException("test timeout");
                    }
                }
                : new Response());
        client.downloadArtifact(ARTIFACT, 4, hash(), home.resolve("download.jar"));
        assertEquals(2, attempts.get());
    }

    @Test
    void repeatedReadTimeoutFallsBackThenStopsAfterTwoOriginAttempts() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        final PluginCatalogClient client = client(uri -> {
            attempts.incrementAndGet();
            return new Response() {
                @Override
                public InputStream getInputStream() {
                    return new InputStream() {
                        @Override
                        public int read() throws IOException {
                            throw new SocketTimeoutException("idle");
                        }
                    };
                }
            };
        });
        final Path target = home.resolve("download.jar");
        final var failure = assertThrows(DownloadFailure.class, () -> client.downloadArtifact(artifact(), target));
        assertEquals("PLUGIN_DOWNLOAD_READ_TIMEOUT", failure.code());
        assertEquals(3, attempts.get());
        assertFalse(Files.exists(target));
    }

    @Test
    void neverRetriesOrPublishesHashMismatch() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        final var client = client(uri -> {
            attempts.incrementAndGet();
            return new Response();
        });
        final Path target = home.resolve("download.jar");
        final var failure =
                assertThrows(DownloadFailure.class, () -> client.downloadArtifact(ARTIFACT, 4, "0".repeat(64), target));
        assertEquals("PLUGIN_ARTIFACT_HASH_MISMATCH", failure.code());
        assertEquals(1, attempts.get());
        assertFalse(Files.exists(target));
    }

    @Test
    void reportsDiskFailureWithoutDeletingAnExistingDirectory() throws Exception {
        final Path target = Files.createDirectory(home.resolve("directory"));
        final List<URI> requests = new ArrayList<>();
        final var failure = assertThrows(
                DownloadFailure.class,
                () -> client(uri -> {
                            requests.add(uri);
                            return uri.equals(apiUri()) ? redirect(cdnUri().toString()) : new Response();
                        })
                        .downloadArtifact(artifact(), target));
        assertEquals("PLUGIN_DOWNLOAD_WRITE_FAILED", failure.code());
        assertEquals(List.of(apiUri(), cdnUri()), requests);
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void cancellationDisconnectsBlockedRequestWithoutBlockingEdt() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicBoolean disconnectOnEdt = new AtomicBoolean();
        final var control = new DownloadControl();
        final var client = client(uri -> new Response() {
            @Override
            public int getResponseCode() throws IOException {
                entered.countDown();
                try {
                    if (!disconnected.await(5, TimeUnit.SECONDS)) throw new IOException("test stalled");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("disconnected");
            }

            @Override
            public void disconnect() {
                if (SwingUtilities.isEventDispatchThread()) disconnectOnEdt.set(true);
                disconnected.countDown();
            }
        });
        final var work = CompletableFuture.supplyAsync(() -> assertThrows(
                DownloadFailure.class,
                () -> client.downloadArtifact(
                        ARTIFACT, 4, hash(), home.resolve("download.jar"), control, ignored -> {})));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> assertTrue(control.cancel()));
        assertEquals("PLUGIN_DOWNLOAD_CANCELLED", work.get(5, TimeUnit.SECONDS).code());
        assertFalse(disconnectOnEdt.get());
        assertFalse(Files.exists(home.resolve("download.jar")));
    }

    @Test
    void cancellationFromProgressDeletesPartialDownload() throws Exception {
        final var control = new DownloadControl();
        final Path target = home.resolve("download.jar");
        final var failure = assertThrows(
                DownloadFailure.class,
                () -> client(uri -> new Response()).downloadArtifact(ARTIFACT, 4, hash(), target, control, progress -> {
                    if (progress.receivedBytes() > 0) control.cancel();
                }));
        assertEquals("PLUGIN_DOWNLOAD_CANCELLED", failure.code());
        assertFalse(Files.exists(target));
        assertFalse(control.beginCommit());
    }

    @Test
    void commitBoundaryRejectsLateCancellation() {
        final var control = new DownloadControl();
        assertTrue(control.beginCommit());
        assertFalse(control.cancel());
        assertFalse(control.isCancelled());
        assertFalse(control.canCancel());
    }

    @Test
    void totalBudgetRejectsLateReadAndCleansOutput() throws Exception {
        final var client = new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI,
                PluginCatalogClient.SIGNATURE_URI,
                Duration.ofMillis(50),
                uri -> new Response() {
                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(BYTES) {
                            @Override
                            public synchronized int read(byte[] bytes, int offset, int length) {
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException failure) {
                                    Thread.currentThread().interrupt();
                                }
                                return super.read(bytes, offset, length);
                            }
                        };
                    }
                });
        final Path target = home.resolve("download.jar");
        final var failure = assertThrows(DownloadFailure.class, () -> client.downloadArtifact(artifact(), target));
        assertEquals("PLUGIN_DOWNLOAD_DEADLINE_EXCEEDED", failure.code());
        assertFalse(Files.exists(target));
    }

    @Test
    void refusesRedirectOutsideApprovedHosts() throws Exception {
        final var client = client(uri -> new Response() {
            @Override
            public int getResponseCode() {
                return 302;
            }

            @Override
            public String getHeaderField(String name) {
                return "Location".equals(name) ? "https://unapproved.example/plugin.jar" : null;
            }
        });
        final IOException failure = assertThrows(
                IOException.class, () -> client.downloadArtifact(artifact(), home.resolve("download.jar")));
        assertTrue(failure.getMessage().contains("unapproved plugin CDN redirect"));
    }

    @Test
    void downloadsThroughExactApiAndCdnContractWithoutContactingGithub() throws Exception {
        final List<URI> requests = new ArrayList<>();
        final Path target = home.resolve("accelerated.jar");
        client(uri -> {
                    requests.add(uri);
                    return uri.getHost().equals("api.turboism.dev") ? redirect(cdnUri().toString()) : new Response();
                })
                .downloadArtifact(artifact(), target);
        assertEquals(List.of(apiUri(), cdnUri()), requests);
        assertArrayEquals(BYTES, Files.readAllBytes(target));
    }

    @Test
    void missingOrUnavailableApiAutomaticallyUsesSignedGithubUrl() throws Exception {
        for (int status : List.of(403, 404, 408, 410, 429, 500, 501, 503, 507, 599)) {
            final List<URI> requests = new ArrayList<>();
            client(uri -> {
                        requests.add(uri);
                        return uri.getHost().equals("api.turboism.dev") ? response(status) : new Response();
                    })
                    .downloadArtifact(artifact(), home.resolve("fallback-" + status + ".jar"));
            assertEquals(List.of(apiUri(), ARTIFACT), requests);
        }
    }

    @Test
    void unavailableCdnFallsBackAndKeepsGithubReleaseRedirectSupport() throws Exception {
        for (boolean timeout : List.of(false, true)) {
            final List<URI> requests = new ArrayList<>();
            final URI githubCdn = URI.create("https://release-assets.githubusercontent.com/assets/test.jar?token=test");
            client(uri -> {
                        requests.add(uri);
                        if (uri.getHost().equals("api.turboism.dev")) return redirect(cdnUri().toString());
                        if (uri.getHost().equals("dl.turboism.dev")) {
                            if (!timeout) return response(503);
                            return new Response() {
                                @Override
                                public int getResponseCode() throws IOException {
                                    throw new SocketTimeoutException("CDN offline");
                                }
                            };
                        }
                        return uri.equals(ARTIFACT) ? redirect(githubCdn.toString()) : new Response();
                    })
                    .downloadArtifact(artifact(), home.resolve("cdn-fallback-" + timeout + ".jar"));
            assertEquals(List.of(apiUri(), cdnUri(), ARTIFACT, githubCdn), requests);
        }
    }

    @Test
    void partialCdnNetworkFailureRestartsOriginalDownloadFromZero() throws Exception {
        final List<URI> requests = new ArrayList<>();
        final List<DownloadProgress> progress = new ArrayList<>();
        final Path target = home.resolve("partial-fallback.jar");
        client(uri -> {
                    requests.add(uri);
                    if (uri.getHost().equals("api.turboism.dev")) return redirect(cdnUri().toString());
                    if (!uri.getHost().equals("dl.turboism.dev")) return new Response();
                    return new Response() {
                        @Override
                        public InputStream getInputStream() {
                            return new InputStream() {
                                boolean sent;

                                @Override
                                public int read() throws IOException {
                                    if (sent) throw new SocketTimeoutException("interrupted CDN body");
                                    sent = true;
                                    return BYTES[0];
                                }
                            };
                        }
                    };
                })
                .downloadArtifact(artifact(), target, new DownloadControl(), progress::add);
        assertEquals(List.of(apiUri(), cdnUri(), ARTIFACT), requests);
        assertArrayEquals(BYTES, Files.readAllBytes(target));
        final int partial = progress.indexOf(new DownloadProgress(1, 4));
        assertTrue(partial >= 0);
        assertTrue(progress.subList(partial + 1, progress.size()).contains(new DownloadProgress(0, 4)));
    }

    @Test
    void mirrorRedirectCannotChangeHashFilenameHostPortOrQuery() throws Exception {
        final String expected = cdnUri().toString();
        for (String location : List.of(
                expected.replace(hash(), "0".repeat(64)), expected.replace("test.jar", "other.jar"),
                expected.replace("dl.turboism.dev", "unapproved.example"), expected + "?url=other",
                expected.replace("https:", "http:"), expected.replace("dl.turboism.dev", "dl.turboism.dev:444"))) {
            final AtomicInteger requests = new AtomicInteger();
            assertThrows(
                    IOException.class,
                    () -> client(uri -> {
                                requests.incrementAndGet();
                                return redirect(location);
                            })
                            .downloadArtifact(artifact(), home.resolve("redirect.jar")));
            assertEquals(1, requests.get());
        }
    }

    @Test
    void invalidCdnBytesAreDiscardedBeforeVerifiedGithubFallback() throws Exception {
        for (String defect : List.of("hash", "short", "long", "empty", "null", "length")) {
            final List<URI> requests = new ArrayList<>();
            final Path target = home.resolve("invalid-cdn-" + defect + ".jar");
            client(uri -> {
                        requests.add(uri);
                        if (uri.equals(apiUri())) return redirect(cdnUri().toString());
                        if (uri.equals(ARTIFACT)) {
                            assertFalse(Files.exists(target), "Rejected CDN file must be discarded: " + defect);
                            return new Response();
                        }
                        return new Response() {
                            @Override
                            public long getContentLengthLong() {
                                return defect.equals("length") ? 3 : BYTES.length;
                            }

                            @Override
                            public InputStream getInputStream() {
                                return switch (defect) {
                                    case "hash" -> new ByteArrayInputStream(new byte[] {4, 3, 2, 1});
                                    case "short" -> new ByteArrayInputStream(new byte[] {1, 2});
                                    case "long" -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5});
                                    case "empty" -> new ByteArrayInputStream(new byte[0]);
                                    case "null" -> null;
                                    default -> new ByteArrayInputStream(BYTES);
                                };
                            }
                        };
                    })
                    .downloadArtifact(artifact(), target);
            assertEquals(List.of(apiUri(), cdnUri(), ARTIFACT), requests);
            assertArrayEquals(BYTES, Files.readAllBytes(target));
        }
    }

    @Test
    void corruptGithubFallbackIsStillRejectedWithoutRetry() throws Exception {
        final List<URI> requests = new ArrayList<>();
        final Path target = home.resolve("corrupt-cdn.jar");
        final var failure = assertThrows(
                DownloadFailure.class,
                () -> client(uri -> {
                            requests.add(uri);
                            if (uri.getHost().equals("api.turboism.dev")) return redirect(cdnUri().toString());
                            return new Response() {
                                @Override
                                public InputStream getInputStream() {
                                    return new ByteArrayInputStream(new byte[] {4, 3, 2, 1});
                                }
                            };
                        })
                        .downloadArtifact(artifact(), target));
        assertEquals("PLUGIN_ARTIFACT_HASH_MISMATCH", failure.code());
        assertEquals(List.of(apiUri(), cdnUri(), ARTIFACT), requests);
        assertFalse(Files.exists(target));
    }

    @Test
    void cancellationAtCdnDoesNotStartGithubFallback() throws Exception {
        final List<URI> requests = new ArrayList<>();
        final DownloadControl control = new DownloadControl();
        final var failure = assertThrows(
                DownloadFailure.class,
                () -> client(uri -> {
                            requests.add(uri);
                            if (uri.getHost().equals("api.turboism.dev")) return redirect(cdnUri().toString());
                            control.cancel();
                            return new Response();
                        })
                        .downloadArtifact(artifact(), home.resolve("cancelled.jar"), control, ignored -> {}));
        assertEquals("PLUGIN_DOWNLOAD_CANCELLED", failure.code());
        assertEquals(List.of(apiUri(), cdnUri()), requests);
    }

    @Test
    void accelerationUsesCatalogFilenameAndFallbackRetainsOriginalUrl() throws Exception {
        final List<URI> requests = new ArrayList<>();
        final CatalogArtifact entry = new CatalogArtifact(
                CatalogArtifact.MEDIA_TYPE,
                "signed-name.jar",
                ARTIFACT.toString(),
                hash(),
                "0".repeat(64),
                BYTES.length);
        client(uri -> {
                    requests.add(uri);
                    return uri.getHost().equals("api.turboism.dev") ? response(404) : new Response();
                })
                .downloadArtifact(entry, home.resolve("named.jar"));
        assertEquals(
                List.of(
                        URI.create("https://api.turboism.dev/v1/plugins/artifacts/" + hash() + "/signed-name.jar"),
                        ARTIFACT),
                requests);
    }

    @Test
    void unsafeCatalogFilenameIsRejectedBeforeNetworkAccess() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        for (String fileName : List.of("../test.jar", "test.jar?x=1", "test%2f.jar", "test.jar#x", "test.zip")) {
            final CatalogArtifact entry = new CatalogArtifact(
                    CatalogArtifact.MEDIA_TYPE, fileName, ARTIFACT.toString(), hash(), "0".repeat(64), BYTES.length);
            assertThrows(
                    IOException.class,
                    () -> client(uri -> {
                                requests.incrementAndGet();
                                return new Response();
                            })
                            .downloadArtifact(entry, home.resolve("unsafe.jar")));
        }
        assertEquals(0, requests.get());
    }

    @Test
    void cdnTlsFailureFallsBackToSignedGithubUrl() throws Exception {
        final List<URI> requests = new ArrayList<>();
        final Path target = home.resolve("tls-fallback.jar");
        client(uri -> {
                    requests.add(uri);
                    if (uri.equals(apiUri())) return redirect(cdnUri().toString());
                    if (uri.equals(cdnUri())) throw new SSLHandshakeException("CDN handshake failed");
                    return new Response();
                })
                .downloadArtifact(artifact(), target);
        assertEquals(List.of(apiUri(), cdnUri(), ARTIFACT), requests);
        assertArrayEquals(BYTES, Files.readAllBytes(target));
    }

    private static CatalogArtifact artifact() throws IOException {
        return new CatalogArtifact(
                CatalogArtifact.MEDIA_TYPE, "test.jar", ARTIFACT.toString(), hash(), "0".repeat(64), BYTES.length);
    }

    private static URI apiUri() throws IOException {
        return URI.create("https://api.turboism.dev/v1/plugins/artifacts/" + hash() + "/test.jar");
    }

    private static URI cdnUri() throws IOException {
        return URI.create("https://dl.turboism.dev/plugins/" + hash() + "/test.jar");
    }

    private static Response response(final int status) throws IOException {
        return new Response() {
            @Override
            public int getResponseCode() {
                return status;
            }
        };
    }

    private static Response redirect(final String location) throws IOException {
        return new Response() {
            @Override
            public int getResponseCode() {
                return 302;
            }

            @Override
            public String getHeaderField(String name) {
                return "Location".equals(name) ? location : null;
            }
        };
    }

    private static PluginCatalogClient client(PluginCatalogClient.ConnectionFactory factory) {
        return new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI,
                PluginCatalogClient.SIGNATURE_URI,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                factory);
    }

    private static String hash() throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IOException(failure);
        }
    }

    private static class Response extends HttpURLConnection {
        Response() throws IOException {
            super(ARTIFACT.toURL());
        }

        @Override
        public void connect() {}

        @Override
        public void disconnect() {}

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public int getResponseCode() throws IOException {
            return 200;
        }

        @Override
        public long getContentLengthLong() {
            return BYTES.length;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(BYTES);
        }
    }
}

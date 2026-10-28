package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.*;

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
        final Path target = home.resolve("download.jar");
        client(uri -> new Response())
                .downloadArtifact(ARTIFACT, BYTES.length, hash(), target, new DownloadControl(), progress::add);
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
    void repeatedReadTimeoutStopsAfterTwoAttemptsAndCleansPartialFile() throws Exception {
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
        final var failure =
                assertThrows(DownloadFailure.class, () -> client.downloadArtifact(ARTIFACT, 4, hash(), target));
        assertEquals("PLUGIN_DOWNLOAD_READ_TIMEOUT", failure.code());
        assertEquals(2, attempts.get());
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
        final var failure = assertThrows(
                DownloadFailure.class,
                () -> client(uri -> new Response()).downloadArtifact(ARTIFACT, 4, hash(), target));
        assertEquals("PLUGIN_DOWNLOAD_WRITE_FAILED", failure.code());
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
        final var failure =
                assertThrows(DownloadFailure.class, () -> client.downloadArtifact(ARTIFACT, 4, hash(), target));
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
                IOException.class, () -> client.downloadArtifact(ARTIFACT, 4, hash(), home.resolve("download.jar")));
        assertTrue(failure.getMessage().contains("unapproved redirect host"));
    }

    private static PluginCatalogClient client(PluginCatalogClient.ConnectionFactory factory) {
        return new PluginCatalogClient(
                PluginCatalogClient.CATALOG_URI,
                PluginCatalogClient.SIGNATURE_URI,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                factory);
    }

    private static String hash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
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

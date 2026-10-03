package dev.turboism.plugin.webdavbackup.webdav;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Controlled heap measurement for the upload body publisher, run manually —
 * never during the test suite. Compares the JDK {@code ofInputStream} baseline
 * against {@link BoundedInputStreamBodyPublisher} on a large upload to a
 * loopback {@link HttpServer}, reporting the peak managed-heap delta of a
 * sampler thread, mirroring the real-host BACKUP_HEAP_OBSERVATION phase.
 *
 * <pre>
 *   ./gradlew :plugins:webdav-backup:testClasses
 *   java -cp build/worktree/&lt;id&gt;/webdav-backup/classes/java/test:\
 *            build/worktree/&lt;id&gt;/webdav-backup/classes/java/main \
 *        dev.turboism.plugin.webdavbackup.webdav.UploadHeapMeasurement [bytes]
 * </pre>
 */
public final class UploadHeapMeasurement {

    private UploadHeapMeasurement() {}

    public static void main(final String[] args) throws Exception {
        final long bytes = args.length > 0 ? Long.parseLong(args[0]) : 128L * 1024 * 1024;
        final Path artifact = Files.createTempFile("webdav-upload-measure-", ".cmo3");
        try {
            writeDeterministicFile(artifact, bytes);
            final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                server.createContext("/upload", exchange -> {
                    try (InputStream body = exchange.getRequestBody()) {
                        body.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                    exchange.sendResponseHeaders(201, -1);
                    exchange.close();
                });
                server.start();
                final URI target =
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/upload");
                measure(
                        "ofInputStream",
                        target,
                        artifact,
                        bytes,
                        () -> HttpRequest.BodyPublishers.fromPublisher(
                                HttpRequest.BodyPublishers.ofInputStream(() -> openUnchecked(artifact)), bytes));
                measure(
                        "bounded",
                        target,
                        artifact,
                        bytes,
                        () -> new BoundedInputStreamBodyPublisher(() -> openUnchecked(artifact), bytes));
            } finally {
                server.stop(0);
            }
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    private static void measure(
            final String label,
            final URI target,
            final Path artifact,
            final long bytes,
            final Supplier<HttpRequest.BodyPublisher> publisher)
            throws IOException, InterruptedException {
        settleHeap();
        final long baseline = heapUsed();
        final AtomicLong peak = new AtomicLong(baseline);
        final AtomicBoolean sampling = new AtomicBoolean(true);
        final Thread sampler = new Thread(
                () -> {
                    while (sampling.get()) {
                        peak.accumulateAndGet(heapUsed(), Math::max);
                        try {
                            Thread.sleep(5L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                },
                "upload-heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
        int status = -1;
        try {
            final HttpClient client = HttpClient.newHttpClient();
            status = client.send(
                            HttpRequest.newBuilder(target).PUT(publisher.get()).build(),
                            HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } finally {
            sampling.set(false);
            sampler.join(10_000L);
        }
        settleHeap();
        final long postGc = heapUsed();
        System.out.println("RESULT impl=" + label + " status=" + status + " bytes=" + bytes + " baselineHeap="
                + baseline + " peakHeap=" + peak.get() + " peakDelta=" + (peak.get() - baseline) + " postGcHeap="
                + postGc);
    }

    private static InputStream openUnchecked(final Path file) {
        try {
            return Files.newInputStream(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void writeDeterministicFile(final Path file, final long bytes) throws IOException {
        final byte[] page = new byte[1024 * 1024];
        for (int i = 0; i < page.length; i++) {
            page[i] = (byte) (i * 31 + 7);
        }
        try (var out = Files.newOutputStream(file)) {
            long remaining = bytes;
            while (remaining > 0) {
                final int count = (int) Math.min(page.length, remaining);
                out.write(page, 0, count);
                remaining -= count;
            }
        }
    }

    private static long heapUsed() {
        final Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void settleHeap() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(100L);
        }
    }
}

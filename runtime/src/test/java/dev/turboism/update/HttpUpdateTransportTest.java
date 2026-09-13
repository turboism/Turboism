package dev.turboism.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HttpUpdateTransportTest {
    @Test
    void productionUrlConnectionPathUsesTheFixedEndpointAndReturnsTheBoundedResponse() throws Exception {
        final FakeConnection connection = new FakeConnection(200, bytes("{}"));
        connection.etag = "\"server-etag\"";
        final AtomicReference<URI> openedEndpoint = new AtomicReference<>();
        final HttpUpdateTransport transport = new HttpUpdateTransport(
            Duration.ofSeconds(1),
            endpoint -> {
                openedEndpoint.set(endpoint);
                return connection;
            }
        );

        final UpdateTransport.Response response = transport.fetch(Optional.of("\"client-etag\""))
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(HttpUpdateTransport.ENDPOINT, openedEndpoint.get());
        assertEquals(200, response.statusCode());
        assertArrayEquals(bytes("{}"), response.body());
        assertEquals(Optional.of("\"server-etag\""), response.etag());
        assertEquals("\"client-etag\"", connection.requestProperties.get("If-None-Match"));
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(awaitTrue(connection.disconnected), "connection teardown must complete");
    }

    /** Waits briefly for an asynchronous teardown flag set off the caller thread. */
    private static boolean awaitTrue(final AtomicBoolean flag) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (flag.get()) return true;
            Thread.sleep(5L);
        }
        return flag.get();
    }

    @Test
    void cancellingTheReturnedStageClosesTheBodyAndConnection() throws Exception {
        final BlockingInputStream body = new BlockingInputStream();
        final FakeConnection connection = new FakeConnection(200, body);
        final HttpUpdateTransport transport = transport(Duration.ofSeconds(2), connection);

        final CompletionStage<UpdateTransport.Response> returned = transport.fetch(Optional.empty());
        assertTrue(body.readStarted.await(2, TimeUnit.SECONDS));

        assertTrue(returned.toCompletableFuture().cancel(true));
        assertTrue(body.closed.await(2, TimeUnit.SECONDS));
        assertTrue(connection.disconnected.get());
        assertThrows(CancellationException.class, () -> returned.toCompletableFuture().join());
    }

    @Test
    void cancellingARealLoopbackHttpUrlConnectionReturnsBeforeTheRemoteBodyIsReleased() throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final CountDownLatch firstByteSent = new CountDownLatch(1);
        final CountDownLatch releaseBody = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                firstByteSent.countDown();
                releaseBody.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        Thread canceller = null;
        try {
            final URI loopback = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/"
            );
            final HttpUpdateTransport transport = new HttpUpdateTransport(
                Duration.ofSeconds(10),
                endpoint -> {
                    assertEquals(HttpUpdateTransport.ENDPOINT, endpoint);
                    return (HttpURLConnection) loopback.toURL().openConnection();
                }
            );
            final CompletionStage<UpdateTransport.Response> returned = transport.fetch(Optional.empty());
            assertTrue(firstByteSent.await(2, TimeUnit.SECONDS));
            final CountDownLatch cancelReturned = new CountDownLatch(1);
            canceller = new Thread(() -> {
                returned.toCompletableFuture().cancel(true);
                cancelReturned.countDown();
            }, "update-cancel-caller");
            canceller.setDaemon(true);
            canceller.start();
            assertTrue(
                cancelReturned.await(500, TimeUnit.MILLISECONDS),
                "cancellation must not wait for the remote response body"
            );
            assertThrows(CancellationException.class, () -> returned.toCompletableFuture().join());
        } finally {
            releaseBody.countDown();
            if (canceller != null) canceller.join(2_000);
            server.stop(0);
        }
    }

    @Test
    void cancellationRunsABlockingDisconnectOffTheCallingThread() throws Exception {
        final BlockingInputStream body = new BlockingInputStream();
        final BlockingDisconnectConnection connection = new BlockingDisconnectConnection(body);
        final CompletionStage<UpdateTransport.Response> returned = new HttpUpdateTransport(
            Duration.ofSeconds(2),
            ignored -> connection
        ).fetch(Optional.empty());
        final CountDownLatch cancelReturned = new CountDownLatch(1);
        final Thread caller = new Thread(() -> {
            returned.toCompletableFuture().cancel(true);
            cancelReturned.countDown();
        }, "update-cancel-caller");
        caller.setDaemon(true);
        try {
            assertTrue(body.readStarted.await(2, TimeUnit.SECONDS));
            caller.start();
            assertTrue(
                cancelReturned.await(500, TimeUnit.MILLISECONDS),
                "cancellation must not wait for disconnect"
            );
            assertTrue(connection.disconnectStarted.await(2, TimeUnit.SECONDS));
            assertFalse("update-cancel-caller".equals(connection.disconnectThread));
        } finally {
            connection.releaseDisconnect.countDown();
            caller.join(2_000);
        }
        assertTrue(body.closed.await(2, TimeUnit.SECONDS));
        assertTrue(connection.disconnectCompleted.get());
        assertThrows(CancellationException.class, () -> returned.toCompletableFuture().join());
    }

    @Test
    void absoluteDeadlineStopsAStalledReadEvenWhenTheReadTimeoutWouldBeReset() throws Exception {
        final BlockingInputStream body = new BlockingInputStream();
        final FakeConnection connection = new FakeConnection(200, body);
        final HttpUpdateTransport transport = transport(Duration.ofMillis(60), connection);

        final CompletionStage<UpdateTransport.Response> returned = transport.fetch(Optional.empty());
        assertTrue(body.readStarted.await(2, TimeUnit.SECONDS));

        assertThrows(ExecutionException.class, () -> returned.toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(body.closed.await(2, TimeUnit.SECONDS));
        assertTrue(connection.disconnected.get());
    }

    @Test
    void absoluteDeadlineStopsASlowDripAfterTheFirstChunk() throws Exception {
        final SlowDripInputStream body = new SlowDripInputStream();
        final FakeConnection connection = new FakeConnection(200, body);
        final HttpUpdateTransport transport = transport(Duration.ofMillis(70), connection);

        final CompletionStage<UpdateTransport.Response> returned = transport.fetch(Optional.empty());
        assertTrue(body.secondReadStarted.await(2, TimeUnit.SECONDS));

        assertThrows(ExecutionException.class, () -> returned.toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(body.closed.await(2, TimeUnit.SECONDS));
        assertEquals(2, body.reads.get());
        assertTrue(connection.disconnected.get());
    }

    @Test
    void cancellationBeforeConnectionRegistrationClosesAResourceRegisteredLater() throws Exception {
        final FakeConnection connection = new FakeConnection(200, bytes("{}"));
        final LateConnectionFactory factory = new LateConnectionFactory(connection);
        final HttpUpdateTransport transport = new HttpUpdateTransport(Duration.ofSeconds(2), factory);

        final CompletionStage<UpdateTransport.Response> returned = transport.fetch(Optional.empty());
        assertTrue(factory.openStarted.await(2, TimeUnit.SECONDS));
        assertTrue(returned.toCompletableFuture().cancel(true));
        factory.release.countDown();

        assertTrue(factory.openReturned.await(2, TimeUnit.SECONDS));
        assertTrue(awaitTrue(connection.disconnected), "the late registration must still be torn down");
    }

    @Test
    void rejectsAnOversizedResponseBeforeReadingAndDoesNotFollowRedirects() throws Exception {
        final FakeConnection oversized = new FakeConnection(200, bytes("ignored"));
        oversized.contentLength = (long) UpdateDiscoveryParser.MAX_BYTES + 1L;
        final HttpUpdateTransport oversizedTransport = transport(Duration.ofSeconds(1), oversized);

        assertThrows(
            ExecutionException.class,
            () -> oversizedTransport.fetch(Optional.empty()).toCompletableFuture().get(2, TimeUnit.SECONDS)
        );
        assertTrue(oversized.disconnected.get());

        final FakeConnection redirect = new FakeConnection(302, bytes("redirect-body"));
        final HttpUpdateTransport redirectTransport = transport(Duration.ofSeconds(1), redirect);
        final UpdateTransport.Response response = redirectTransport.fetch(Optional.empty())
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(302, response.statusCode());
        assertArrayEquals(bytes("redirect-body"), response.body());
        assertFalse(redirect.getInstanceFollowRedirects());
        assertTrue(redirect.disconnected.get());
    }

    private static HttpUpdateTransport transport(final Duration deadline, final FakeConnection connection) {
        return new HttpUpdateTransport(deadline, ignored -> connection);
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class LateConnectionFactory implements HttpUpdateTransport.ConnectionFactory {
        private final FakeConnection connection;
        private final CountDownLatch openStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch openReturned = new CountDownLatch(1);

        private LateConnectionFactory(final FakeConnection connection) {
            this.connection = connection;
        }

        @Override
        public HttpURLConnection open(final URI endpoint) {
            openStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            openReturned.countDown();
            return connection;
        }
    }

    private static class FakeConnection extends HttpURLConnection {
        private final InputStream input;
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final Map<String, String> requestProperties = new HashMap<>();
        private int responseCode;
        private long contentLength = -1L;
        private String etag;

        private FakeConnection(final int responseCode, final InputStream input) throws Exception {
            super(HttpUpdateTransport.ENDPOINT.toURL());
            this.responseCode = responseCode;
            this.input = input;
        }

        private FakeConnection(final int responseCode, final byte[] input) throws Exception {
            this(responseCode, new ByteArrayInputStream(input));
        }

        @Override
        public void disconnect() {
            disconnected.set(true);
            try {
                input.close();
            } catch (IOException ignored) {
                // Test resource cleanup is idempotent.
            }
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return input;
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public String getHeaderField(final String name) {
            return "ETag".equalsIgnoreCase(name) ? etag : null;
        }

        @Override
        public void setRequestProperty(final String key, final String value) {
            requestProperties.put(key, value);
        }
    }

    private static final class BlockingDisconnectConnection extends FakeConnection {
        private final CountDownLatch disconnectStarted = new CountDownLatch(1);
        private final CountDownLatch releaseDisconnect = new CountDownLatch(1);
        private final AtomicBoolean disconnectCompleted = new AtomicBoolean();
        private volatile String disconnectThread;

        private BlockingDisconnectConnection(final InputStream input) throws Exception {
            super(200, input);
        }

        @Override
        public void disconnect() {
            disconnectThread = Thread.currentThread().getName();
            disconnectStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseDisconnect.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            disconnectCompleted.set(true);
        }
    }

    private static class BlockingInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        protected final CountDownLatch closed = new CountDownLatch(1);
        private volatile boolean isClosed;

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            readStarted.countDown();
            while (!isClosed && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
            throw new IOException("closed");
        }

        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public void close() {
            isClosed = true;
            closed.countDown();
        }
    }

    private static final class SlowDripInputStream extends BlockingInputStream {
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch secondReadStarted = new CountDownLatch(1);

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            if (reads.incrementAndGet() == 1) {
                buffer[offset] = 'x';
                return 1;
            }
            secondReadStarted.countDown();
            return super.read(buffer, offset, length);
        }
    }
}

package dev.turboism.plugin.webdavbackup.webdav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedInputStreamBodyPublisherTest {

    private static final int CHUNK = BoundedInputStreamBodyPublisher.CHUNK_BYTES;

    /** Deterministic content: position-dependent bytes, never held twice. */
    private static byte[] content(final int size) {
        final byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    private static String sha256(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Records items, bytes, and terminal signals; all onNext traffic arrives on the drain thread. */
    private static final class RecordingSubscriber implements Flow.Subscriber<ByteBuffer> {
        final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        final AtomicLong emittedBytes = new AtomicLong();
        final AtomicInteger items = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch terminal = new CountDownLatch(1);
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final long initialRequest;

        RecordingSubscriber(final long initialRequest) {
            this.initialRequest = initialRequest;
        }

        @Override
        public void onSubscribe(final Flow.Subscription newSubscription) {
            assertTrue(subscription.compareAndSet(null, newSubscription), "onSubscribe must arrive exactly once");
            if (initialRequest > 0L) {
                newSubscription.request(initialRequest);
            }
        }

        @Override
        public void onNext(final ByteBuffer item) {
            final byte[] copy = new byte[item.remaining()];
            item.get(copy);
            synchronized (body) {
                body.write(copy, 0, copy.length);
            }
            emittedBytes.addAndGet(copy.length);
            items.incrementAndGet();
        }

        @Override
        public void onError(final Throwable throwable) {
            errors.incrementAndGet();
            failure.compareAndSet(null, throwable);
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            completions.incrementAndGet();
            terminal.countDown();
        }

        byte[] body() {
            synchronized (body) {
                return body.toByteArray();
            }
        }

        void awaitTerminal() throws InterruptedException {
            assertTrue(terminal.await(30L, TimeUnit.SECONDS), "the subscription must terminate");
        }
    }

    /** Counts bytes read from the delegate and samples the read-ahead gap on every read. */
    private static final class CountingStream extends InputStream {
        private final InputStream delegate;
        private final AtomicLong readBytes = new AtomicLong();
        private final AtomicLong emittedBytes;
        private final AtomicLong maxReadAhead = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();

        CountingStream(final InputStream delegate, final AtomicLong emittedBytes) {
            this.delegate = delegate;
            this.emittedBytes = emittedBytes;
        }

        private void record(final int read) {
            if (read > 0) {
                readBytes.addAndGet(read);
                maxReadAhead.accumulateAndGet(readBytes.get() - emittedBytes.get(), Math::max);
            }
        }

        @Override
        public int read() throws IOException {
            final int value = delegate.read();
            record(value >= 0 ? 1 : 0);
            return value;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            final int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                record(read);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            delegate.close();
        }
    }

    private static InputStream failingStream(final int failAfterBytes, final AtomicBoolean closed) {
        return new InputStream() {
            private int produced;

            @Override
            public int read() throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(final byte[] buffer, final int offset, final int length) throws IOException {
                if (produced >= failAfterBytes) {
                    throw new IOException("simulated read failure");
                }
                final int count = Math.min(length, failAfterBytes - produced);
                produced += count;
                return count;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    @Test
    void declaredContentLengthIsReported() {
        assertEquals(
                12345L,
                new BoundedInputStreamBodyPublisher(() -> InputStream.nullInputStream(), 12345L).contentLength());
    }

    @Test
    void streamsExactBytesForAllBoundarySizes() throws Exception {
        for (int size : new int[] {0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 7}) {
            final byte[] data = content(size);
            final HttpRequest.BodyPublisher publisher =
                    new BoundedInputStreamBodyPublisher(() -> new ByteArrayInputStream(data), size);
            final RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
            publisher.subscribe(subscriber);
            subscriber.awaitTerminal();
            assertEquals(1, subscriber.completions.get(), "size " + size + " must complete exactly once");
            assertNull(subscriber.failure.get(), "size " + size + " must not error");
            assertEquals(sha256(data), sha256(subscriber.body()), "size " + size + " must stream identical bytes");
            assertEquals(size, subscriber.emittedBytes.get(), "size " + size + " must emit every byte");
        }
    }

    @Test
    void eachDemandUnitYieldsOneBoundedItem() throws Exception {
        final byte[] data = content(2 * CHUNK + 5);
        final HttpRequest.BodyPublisher publisher =
                new BoundedInputStreamBodyPublisher(() -> new ByteArrayInputStream(data), data.length);
        final RecordingSubscriber subscriber = new RecordingSubscriber(0L);
        publisher.subscribe(subscriber);
        final Flow.Subscription subscription = subscriber.subscription.get();
        assertNotNull(subscription);
        // 3 items expected; pull them one demand unit at a time.
        for (int item = 1; item <= 3; item++) {
            subscription.request(1L);
            final int expectedItems = item;
            assertTrue(
                    waitFor(() -> subscriber.items.get() >= expectedItems || subscriber.terminal.getCount() == 0),
                    "item " + item + " must arrive after its demand unit");
        }
        subscriber.awaitTerminal();
        assertEquals(1, subscriber.completions.get());
        assertNull(subscriber.failure.get());
        assertEquals(3, subscriber.items.get(), "2*CHUNK+5 bytes must produce three items");
        assertEquals(sha256(data), sha256(subscriber.body()));
    }

    @Test
    void neverReadsAheadOfDemand() throws Exception {
        final byte[] data = content(4 * CHUNK + 13);
        final RecordingSubscriber subscriber = new RecordingSubscriber(0L);
        final CountingStream stream = new CountingStream(new ByteArrayInputStream(data), subscriber.emittedBytes);
        final HttpRequest.BodyPublisher publisher = new BoundedInputStreamBodyPublisher(() -> stream, data.length);
        publisher.subscribe(subscriber);
        final Flow.Subscription subscription = subscriber.subscription.get();
        assertNotNull(subscription);
        Thread.sleep(200L);
        assertEquals(0L, stream.readBytes.get(), "no demand must mean zero prefetch");
        assertEquals(0L, subscriber.items.get());
        // Pull the whole body one unit at a time; the gap between bytes read
        // from the stream and bytes consumed through onNext must stay bounded.
        for (int item = 1; item <= 5; item++) {
            subscription.request(1L);
            final int expectedItems = item;
            assertTrue(
                    waitFor(() -> subscriber.items.get() >= expectedItems || subscriber.terminal.getCount() == 0),
                    "item " + item + " must arrive after its demand unit");
            Thread.sleep(20L);
        }
        subscriber.awaitTerminal();
        assertEquals(1, subscriber.completions.get());
        assertTrue(
                stream.maxReadAhead.get() <= CHUNK,
                "read-ahead must stay within one chunk, observed " + stream.maxReadAhead.get());
        assertEquals(sha256(data), sha256(subscriber.body()));
    }

    @Test
    void unboundedDemandStaysWithinTheReadAheadBound() throws Exception {
        final byte[] data = content(8 * CHUNK);
        final RecordingSubscriber subscriber = new RecordingSubscriber(0L);
        final CountingStream stream = new CountingStream(new ByteArrayInputStream(data), subscriber.emittedBytes);
        final HttpRequest.BodyPublisher publisher = new BoundedInputStreamBodyPublisher(() -> stream, data.length);
        publisher.subscribe(subscriber);
        subscriber.subscription.get().request(Long.MAX_VALUE);
        subscriber.awaitTerminal();
        assertEquals(1, subscriber.completions.get());
        assertTrue(
                stream.maxReadAhead.get() <= CHUNK,
                "read-ahead must stay within one chunk, observed " + stream.maxReadAhead.get());
        assertEquals(8 * CHUNK, stream.readBytes.get(), "the stream must be read exactly once");
    }

    @Test
    void shorterStreamFailsInsteadOfSendingATruncatedBody() throws Exception {
        final byte[] data = content(CHUNK + 3);
        final AtomicBoolean closed = new AtomicBoolean();
        final InputStream stream = new InputStream() {
            private final InputStream delegate = new ByteArrayInputStream(data);

            @Override
            public int read() throws IOException {
                return delegate.read();
            }

            @Override
            public int read(final byte[] buffer, final int offset, final int length) throws IOException {
                return delegate.read(buffer, offset, length);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        final HttpRequest.BodyPublisher publisher = new BoundedInputStreamBodyPublisher(() -> stream, data.length + 1L);
        final RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber);
        subscriber.awaitTerminal();
        assertEquals(0, subscriber.completions.get());
        assertNotNull(subscriber.failure.get(), "a shrunken artifact must fail the upload");
        assertTrue(closed.get(), "the stream must be closed on failure");
        assertEquals(CHUNK, subscriber.emittedBytes.get(), "a short stream emits only complete chunks before failing");
    }

    @Test
    void longerStreamFailsInsteadOfSendingAGrownPrefix() throws Exception {
        final byte[] data = content(100);
        final HttpRequest.BodyPublisher publisher =
                new BoundedInputStreamBodyPublisher(() -> new ByteArrayInputStream(data), data.length - 1L);
        final RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber);
        subscriber.awaitTerminal();
        assertEquals(0, subscriber.completions.get());
        assertNotNull(subscriber.failure.get(), "a grown artifact must fail the upload");
        assertEquals(data.length - 1L, subscriber.emittedBytes.get(), "exactly the declared bytes are emitted");
    }

    @Test
    void readFailuresPropagateThroughOnErrorAndCloseTheStream() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean();
        final HttpRequest.BodyPublisher publisher =
                new BoundedInputStreamBodyPublisher(() -> failingStream(CHUNK, closed), 4L * CHUNK);
        final RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber);
        subscriber.awaitTerminal();
        assertEquals(0, subscriber.completions.get());
        final Throwable failure = subscriber.failure.get();
        assertNotNull(failure);
        assertEquals("simulated read failure", failure.getMessage());
        assertTrue(closed.get(), "the stream must be closed after a read failure");
        // A terminal signal is final: further demand must not emit anything.
        subscriber.subscription.get().request(10L);
        Thread.sleep(100L);
        assertEquals(1, subscriber.errors.get());
        assertEquals(CHUNK, subscriber.emittedBytes.get(), "no bytes may follow the failing position");
    }

    @Test
    void cancelClosesTheStreamAndStopsEmission() throws Exception {
        final byte[] data = content(4 * CHUNK);
        final AtomicBoolean closed = new AtomicBoolean();
        final InputStream stream = new ByteArrayInputStream(data) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        final HttpRequest.BodyPublisher publisher = new BoundedInputStreamBodyPublisher(() -> stream, data.length);
        final RecordingSubscriber subscriber = new RecordingSubscriber(0L);
        publisher.subscribe(subscriber);
        subscriber.subscription.get().cancel();
        assertTrue(waitFor(closed::get), "cancel must close the stream");
        subscriber.subscription.get().request(Long.MAX_VALUE);
        Thread.sleep(150L);
        assertEquals(0, subscriber.completions.get() + subscriber.errors.get(), "cancel must not signal");
        assertEquals(0L, subscriber.emittedBytes.get(), "cancel must stop all emission");
    }

    @Test
    void nonPositiveDemandFailsWithOnError() throws Exception {
        for (long bad : new long[] {0L, -1L}) {
            final HttpRequest.BodyPublisher publisher =
                    new BoundedInputStreamBodyPublisher(() -> new ByteArrayInputStream(content(10)), 10);
            final RecordingSubscriber subscriber = new RecordingSubscriber(0L);
            publisher.subscribe(subscriber);
            subscriber.subscription.get().request(bad);
            subscriber.awaitTerminal();
            assertEquals(0, subscriber.completions.get(), "request(" + bad + ") must not complete");
            assertInstanceOf(
                    IllegalArgumentException.class,
                    subscriber.failure.get(),
                    "request(" + bad + ") must signal onError");
            assertEquals(1, subscriber.errors.get());
        }
    }

    @Test
    void supplierFailuresSurfaceThroughOnError() throws Exception {
        final HttpRequest.BodyPublisher publisher = new BoundedInputStreamBodyPublisher(
                () -> {
                    throw new UncheckedIOException("vanished", new IOException("vanished"));
                },
                10);
        final RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber);
        subscriber.awaitTerminal();
        assertInstanceOf(UncheckedIOException.class, subscriber.failure.get());
        assertEquals(0, subscriber.completions.get());
    }

    @Test
    void emptyDeclaredBodyStillVerifiesTheStreamIsEmpty() throws Exception {
        final HttpRequest.BodyPublisher emptyPublisher =
                new BoundedInputStreamBodyPublisher(InputStream::nullInputStream, 0L);
        final RecordingSubscriber empty = new RecordingSubscriber(0L); // no demand at all
        emptyPublisher.subscribe(empty);
        empty.awaitTerminal();
        assertEquals(1, empty.completions.get(), "a zero-length body completes without demand");

        final HttpRequest.BodyPublisher grownPublisher =
                new BoundedInputStreamBodyPublisher(() -> new ByteArrayInputStream(content(1)), 0L);
        final RecordingSubscriber grown = new RecordingSubscriber(Long.MAX_VALUE);
        grownPublisher.subscribe(grown);
        grown.awaitTerminal();
        assertNotNull(grown.failure.get(), "extra bytes beyond a zero declaration must fail");
        assertEquals(0, grown.completions.get());
    }

    private static boolean waitFor(final Check check) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (System.nanoTime() < deadline) {
            if (check.passed()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return check.passed();
    }

    private interface Check {
        boolean passed();
    }
}

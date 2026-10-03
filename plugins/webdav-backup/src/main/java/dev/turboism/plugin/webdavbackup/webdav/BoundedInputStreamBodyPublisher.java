package dev.turboism.plugin.webdavbackup.webdav;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A {@link HttpRequest.BodyPublisher} that streams one {@link InputStream} with
 * a fixed Content-Length and a strictly bounded managed-heap footprint.
 *
 * <p>{@code BodyPublishers.ofInputStream} is not safe for large artifacts
 * inside a long-lived host JVM: it emits one fresh ~16KiB heap
 * {@code ByteBuffer} per item, so an upload drags the full artifact through
 * the young generation as garbage and a heap sampler observes a peak near the
 * file size before the next collection. This publisher instead performs
 * exactly one bounded read per unit of downstream demand — each
 * {@code request(n)} unit fills one {@link ByteBuffer#allocateDirect direct
 * buffer} of at most {@link #CHUNK_BYTES} bytes — so the artifact bytes never
 * enter the managed heap at all. Per demand unit the managed-heap cost is a
 * single small direct-buffer object; the staging array used to fill it is
 * allocated once per subscription and reused. It never reads without demand,
 * so at any instant at most one chunk (64KiB) is held between {@code read} and
 * {@code onNext}; anything further downstream is demand the subscriber itself
 * asked for.</p>
 *
 * <p>Reads run on a shared bounded pool of daemon threads (never on the
 * subscribing HttpClient thread, which may hold transport locks, and never on
 * a fresh unbounded thread per request). The stream is closed on normal
 * completion, error, or {@code cancel()}. A stream that ends before the
 * declared length, or still has bytes after it, fails the upload through
 * {@code onError} — a mid-upload file mutation can never produce a corrupt
 * truncated request.</p>
 */
final class BoundedInputStreamBodyPublisher implements HttpRequest.BodyPublisher {

    /** Bytes read and emitted per unit of demand; also the read-ahead bound. */
    static final int CHUNK_BYTES = 64 * 1024;

    private final Supplier<InputStream> streamSupplier;
    private final long contentLength;

    /**
     * @param streamSupplier opens a fresh stream per subscription; it is
     *        invoked when the client subscribes, so a supplier failure becomes
     *        that attempt's {@code onError}
     * @param contentLength the declared Content-Length; the stream must
     *        produce exactly this many bytes or the subscription errors
     */
    BoundedInputStreamBodyPublisher(final Supplier<InputStream> streamSupplier, final long contentLength) {
        this.streamSupplier = Objects.requireNonNull(streamSupplier, "streamSupplier");
        if (contentLength < 0L) {
            throw new IllegalArgumentException("contentLength must be nonnegative: " + contentLength);
        }
        this.contentLength = contentLength;
    }

    @Override
    public long contentLength() {
        return contentLength;
    }

    @Override
    public void subscribe(final Flow.Subscriber<? super ByteBuffer> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        final InputStream stream;
        try {
            stream = streamSupplier.get();
        } catch (UncheckedIOException failure) {
            // The artifact could not be opened: surface it as this attempt's
            // error so the send fails into the normal retry path. Other
            // runtime failures (e.g. permission rejections) keep propagating
            // out of subscribe, exactly as ofInputStream behaved before.
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(failure);
            return;
        }
        if (stream == null) {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("artifact stream supplier returned no stream"));
            return;
        }
        final StreamSubscription subscription = new StreamSubscription(subscriber, stream, contentLength);
        subscriber.onSubscribe(subscription);
        // Terminal signals need no demand: an empty body completes (or reports
        // a grown stream) even if the subscriber never calls request().
        subscription.schedule();
    }

    private enum EmptySubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(final long n) {}

        @Override
        public void cancel() {}
    }

    /** Lazily created bounded pool of daemon reader threads shared by all subscriptions. */
    private static final class ReaderPool {
        private static final ExecutorService INSTANCE = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable, "webdav-upload-reader-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Serialized demand-driven drain. All {@code onNext}/{@code onComplete}/
     * {@code onError} signals are emitted from the pool thread running
     * {@link #drainSerialized()}, which is the only thread that may signal:
     * {@code request()} and {@code cancel()} only mutate state. The
     * {@code scheduled} counter implements the standard missed-work protocol
     * so concurrent {@code request()} calls never run two drains at once.
     */
    private static final class StreamSubscription implements Flow.Subscription {

        private final Flow.Subscriber<? super ByteBuffer> downstream;
        private final InputStream stream;
        private final long declared;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicInteger scheduled = new AtomicInteger();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicReference<Throwable> pendingError = new AtomicReference<>();
        private volatile boolean cancelled;
        private long remaining; // drain thread only
        private byte[] staging; // drain thread only, allocated on first read

        StreamSubscription(
                final Flow.Subscriber<? super ByteBuffer> downstream, final InputStream stream, final long declared) {
            this.downstream = downstream;
            this.stream = stream;
            this.declared = declared;
            this.remaining = declared;
        }

        @Override
        public void request(final long n) {
            if (terminated.get()) {
                return;
            }
            if (n <= 0L) {
                // Rule 3.9: a non-positive request must signal onError. Route it
                // through the drain so signals stay on the drain thread.
                pendingError.compareAndSet(
                        null, new IllegalArgumentException("non-positive subscription request: " + n));
            } else {
                demand.getAndUpdate(current -> addCap(current, n));
            }
            schedule();
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (terminated.compareAndSet(false, true)) {
                closeStream();
            }
        }

        private void schedule() {
            if (terminated.get()) {
                return;
            }
            if (scheduled.getAndIncrement() == 0) {
                try {
                    ReaderPool.INSTANCE.execute(this::drainSerialized);
                } catch (RejectedExecutionException failure) {
                    scheduled.decrementAndGet();
                    signalError(failure);
                }
            }
        }

        private void drainSerialized() {
            int missed = 1;
            try {
                while (!terminated.get()) {
                    drain();
                    missed = scheduled.addAndGet(-missed);
                    if (missed == 0) {
                        return;
                    }
                }
            } catch (RuntimeException | Error fatal) {
                // A fatal throwable escaping the drain must still release the
                // artifact stream — nothing else will.
                cancel();
                throw fatal;
            }
        }

        private void drain() {
            final Throwable failure = pendingError.get();
            if (failure != null) {
                signalError(failure);
                return;
            }
            while (!cancelled && !terminated.get()) {
                if (remaining == 0L) {
                    finish();
                    return;
                }
                if (demand.get() <= 0L) {
                    return;
                }
                final int wanted = (int) Math.min(CHUNK_BYTES, remaining);
                final ByteBuffer chunk;
                try {
                    chunk = readChunk(wanted);
                } catch (IOException | RuntimeException | OutOfMemoryError readFailure) {
                    // IO failures, and also direct-buffer allocation failures:
                    // the upload must fail through onError instead of hanging
                    // the send.
                    signalError(readFailure);
                    return;
                }
                if (chunk == null) {
                    return; // cancelled mid-read
                }
                remaining -= chunk.remaining();
                try {
                    downstream.onNext(chunk);
                } catch (RuntimeException brokenSubscriber) {
                    // Rule 1.3/2.13: a subscriber that throws from onNext is
                    // broken — close the stream and stop without further signals.
                    cancel();
                    return;
                }
                decrementDemand();
            }
        }

        /**
         * Reads exactly {@code wanted} bytes for one unit of demand into a
         * fresh direct buffer, or {@code null} when cancelled mid-read. A
         * short stream fails the upload: the declared Content-Length was
         * already sent, so a truncated body would corrupt the request.
         */
        private ByteBuffer readChunk(final int wanted) throws IOException {
            if (staging == null) {
                staging = new byte[CHUNK_BYTES];
            }
            final ByteBuffer buffer = ByteBuffer.allocateDirect(wanted);
            while (buffer.position() < wanted) {
                if (cancelled) {
                    return null;
                }
                final int read = stream.read(staging, 0, wanted - buffer.position());
                if (read < 0) {
                    throw new IOException("artifact stream ended during upload after "
                            + (declared - remaining + buffer.position()) + " of " + declared + " declared bytes");
                }
                if (read == 0) {
                    throw new IOException("artifact stream returned zero bytes mid-upload");
                }
                buffer.put(staging, 0, read);
            }
            buffer.flip();
            return buffer;
        }

        /**
         * All declared bytes are emitted: probe one byte past the end. A grown
         * artifact fails the upload rather than silently sending a stale
         * Content-Length prefix of a mutated file.
         */
        private void finish() {
            try {
                if (stream.read() != -1) {
                    signalError(new IOException(
                            "artifact stream produced more than the declared " + declared + " bytes during upload"));
                    return;
                }
            } catch (IOException failure) {
                signalError(failure);
                return;
            }
            signalComplete();
        }

        private void signalError(final Throwable failure) {
            if (terminated.compareAndSet(false, true)) {
                closeStream();
                try {
                    downstream.onError(failure);
                } catch (RuntimeException ignored) {
                    // The subscriber is already broken; the terminal state stands.
                }
            }
        }

        private void signalComplete() {
            if (terminated.compareAndSet(false, true)) {
                closeStream();
                try {
                    downstream.onComplete();
                } catch (RuntimeException ignored) {
                    // The subscriber is already broken; the terminal state stands.
                }
            }
        }

        private void closeStream() {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Close failures must not mask the upload outcome.
            }
        }

        private void decrementDemand() {
            demand.getAndUpdate(current -> current == Long.MAX_VALUE ? current : current - 1L);
        }

        private static long addCap(final long current, final long increment) {
            final long updated = current + increment;
            return updated < 0L ? Long.MAX_VALUE : updated;
        }
    }
}

package dev.turboism.plugin.webdavbackup.webdav;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A {@link HttpRequest.BodyPublisher} that streams one {@link InputStream} with
 * a fixed Content-Length and a strictly bounded memory footprint.
 *
 * <p>{@code BodyPublishers.ofInputStream} is not safe for large artifacts
 * inside a long-lived host JVM: it emits one fresh ~16KiB heap
 * {@code ByteBuffer} per item regardless of demand, so an upload drags the
 * full artifact through the young generation as garbage and a heap sampler
 * observes a peak near the file size before the next collection. This
 * publisher instead performs exactly one bounded read per unit of downstream
 * demand — each {@code request(n)} unit fills one fresh heap byte array of at
 * most {@link #CHUNK_BYTES} bytes and emits it wrapped with
 * {@link ByteBuffer#wrap(byte[])} — so in-flight bytes are capped by
 * outstanding demand and never leave the managed heap. No direct
 * {@link ByteBuffer} is allocated, so an upload can never exhaust the JVM's
 * separate direct-memory budget (which GC pressure does not reclaim
 * predictably, and which a {@code -XX:+DisableExplicitGC} host can fill past
 * {@code MaxDirectMemorySize}). Heap chunks are young-generation garbage the
 * collector reclaims promptly, keeping resident usage bounded while
 * throughput churns. It never reads without demand, so at any instant at
 * most one chunk (64KiB) is held between {@code read} and {@code onNext};
 * anything further downstream is demand the subscriber itself asked for.</p>
 *
 * <p>Reads run on a caller-owned bounded executor (never on the subscribing
 * HttpClient thread, which may hold transport locks, and never on a fresh
 * unbounded thread per request). The stream is closed on normal completion,
 * error, {@code cancel()}, or a subscriber that throws from {@code onNext} or
 * {@code onSubscribe} (Reactive Streams rule 2.13: it must then consider the
 * subscription cancelled). A stream that ends before the declared length, or
 * still has bytes after it, fails the upload through {@code onError} — a
 * mid-upload file mutation can never produce a corrupt truncated request.</p>
 */
final class BoundedInputStreamBodyPublisher implements HttpRequest.BodyPublisher {

    /** Bytes read and emitted per unit of demand; also the read-ahead bound. */
    static final int CHUNK_BYTES = 64 * 1024;

    private final Supplier<InputStream> streamSupplier;
    private final long contentLength;
    private final ExecutorService readers;

    /**
     * Convenience for standalone construction: drains run on a shared,
     * self-timing-out daemon pool. Production callers should prefer the
     * three-argument form and inject an executor whose lifecycle they own.
     */
    BoundedInputStreamBodyPublisher(final Supplier<InputStream> streamSupplier, final long contentLength) {
        this(streamSupplier, contentLength, SharedReaders.POOL);
    }

    /**
     * @param streamSupplier opens a fresh stream per subscription; it is
     *        invoked when the client subscribes, so a supplier failure becomes
     *        that attempt's {@code onError}
     * @param contentLength the declared Content-Length; the stream must
     *        produce exactly this many bytes or the subscription errors
     *        @param readers the bounded executor that owns the drain threads;
     *        the publisher never creates or joins threads itself
     */
    BoundedInputStreamBodyPublisher(
            final Supplier<InputStream> streamSupplier, final long contentLength, final ExecutorService readers) {
        this.streamSupplier = Objects.requireNonNull(streamSupplier, "streamSupplier");
        if (contentLength < 0L) {
            throw new IllegalArgumentException("contentLength must be nonnegative: " + contentLength);
        }
        this.contentLength = contentLength;
        this.readers = Objects.requireNonNull(readers, "readers");
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
        final StreamSubscription subscription = new StreamSubscription(subscriber, stream, contentLength, readers);
        try {
            subscriber.onSubscribe(subscription);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            subscription.cancel();
            throw fatal;
        } catch (Throwable failure) {
            // Rule 2.13 also covers onSubscribe: the subscriber must consider
            // the subscription cancelled. Release the artifact stream before
            // surfacing the failure to the subscribe() caller.
            subscription.cancel();
            throw failure;
        }
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

    /**
     * Serialized demand-driven drain. All {@code onNext}/{@code onComplete}/
     * {@code onError} signals are emitted from the pool thread running
     * {@link #drainSerialized()}, which is the only thread that may signal:
     * {@code request()} and {@code cancel()} only mutate state. The
     * {@code scheduled} counter implements the standard missed-work protocol
     * so concurrent {@code request()} calls never run two drains at once.
     * A reentrant {@code request(n)} from inside {@code onNext} only extends
     * demand and enqueues another pass on the same serialized drain — it can
     * never recurse into a second signal sequence or escape the single
     * drain thread.
     */
    private static final class StreamSubscription implements Flow.Subscription {

        private final Flow.Subscriber<? super ByteBuffer> downstream;
        private final InputStream stream;
        private final long declared;
        private final ExecutorService readers;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicInteger scheduled = new AtomicInteger();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicReference<Throwable> pendingError = new AtomicReference<>();
        private volatile boolean cancelled;
        private long remaining; // drain thread only

        StreamSubscription(
                final Flow.Subscriber<? super ByteBuffer> downstream,
                final InputStream stream,
                final long declared,
                final ExecutorService readers) {
            this.downstream = downstream;
            this.stream = stream;
            this.declared = declared;
            this.remaining = declared;
            this.readers = readers;
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

        /**
         * Rule 3.5/3.6: cancel terminates the subscription and stops all
         * further signals. Closing the stream here also unblocks a
         * {@code read()} in progress on the drain thread.
         */
        @Override
        public void cancel() {
            cancelled = true;
            terminated.set(true);
            closeStream();
        }

        private void schedule() {
            if (terminated.get()) {
                return;
            }
            if (scheduled.getAndIncrement() == 0) {
                try {
                    readers.execute(this::drainSerialized);
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
                    // IO failures, and also chunk allocation failures: the
                    // upload must fail through onError instead of hanging the
                    // send.
                    signalError(readFailure);
                    return;
                }
                if (chunk == null) {
                    return; // cancelled mid-read
                }
                remaining -= chunk.remaining();
                try {
                    downstream.onNext(chunk);
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    cancel();
                    throw fatal;
                } catch (Throwable brokenSubscriber) {
                    // Rule 1.3/2.13: a subscriber that throws from onNext is
                    // broken — close the stream and stop without further
                    // signals.
                    cancel();
                    return;
                }
                decrementDemand();
            }
        }

        /**
         * Reads exactly {@code wanted} bytes for one unit of demand into a
         * fresh heap byte array wrapped by {@link ByteBuffer#wrap(byte[])}, or
         * {@code null} when cancelled mid-read. The emitted buffer owns its
         * array — nothing else writes into it after publication. A short
         * stream fails the upload: the declared Content-Length was already
         * sent, so a truncated body would corrupt the request.
         */
        private ByteBuffer readChunk(final int wanted) throws IOException {
            final byte[] chunk = new byte[wanted];
            int filled = 0;
            while (filled < wanted) {
                if (cancelled) {
                    return null;
                }
                final int read = stream.read(chunk, filled, wanted - filled);
                if (read < 0) {
                    throw new IOException("artifact stream ended during upload after " + (declared - remaining + filled)
                            + " of " + declared + " declared bytes");
                }
                if (read == 0) {
                    throw new IOException("artifact stream returned zero bytes mid-upload");
                }
                filled += read;
            }
            return ByteBuffer.wrap(chunk);
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

    /**
     * Lazily built fallback drain pool for the two-argument constructor.
     * Daemon threads and a core-thread timeout mean an idle pool holds no live
     * threads, so the class cannot pin a plugin classloader once its uploads
     * have quiesced — the plugin-scoped executor remains the strict contract.
     */
    private static final class SharedReaders {

        private static final ExecutorService POOL = build();

        private static ExecutorService build() {
            final java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                    2,
                    2,
                    10L,
                    java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(),
                    runnable -> {
                        final Thread thread = new Thread(runnable, "webdav-upload-reader");
                        thread.setDaemon(true);
                        return thread;
                    });
            pool.allowCoreThreadTimeOut(true);
            return pool;
        }
    }
}

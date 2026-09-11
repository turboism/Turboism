package dev.turboism.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed-origin HTTPS transport for the small stable-channel discovery response. */
public final class HttpUpdateTransport implements UpdateTransport {
    public static final URI ENDPOINT = URI.create(
        "https://api.turboism.dev/v1/releases/stable.json"
    );
    public static final Duration REQUEST_DEADLINE = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicLong REQUEST_IDS = new AtomicLong();

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI endpoint) throws IOException;
    }

    private static final ConnectionFactory DEFAULT_CONNECTION_FACTORY = endpoint -> {
        final URLConnection opened = endpoint.toURL().openConnection();
        if (!(opened instanceof HttpURLConnection http)) {
            throw new IOException("update endpoint did not create an HTTP connection");
        }
        return http;
    };

    private final Duration requestDeadline;
    private final ConnectionFactory connectionFactory;

    public HttpUpdateTransport() {
        this(REQUEST_DEADLINE, DEFAULT_CONNECTION_FACTORY);
    }

    HttpUpdateTransport(final Duration requestDeadline, final ConnectionFactory connectionFactory) {
        this.requestDeadline = Objects.requireNonNull(requestDeadline, "requestDeadline");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        if (requestDeadline.isZero() || requestDeadline.isNegative()) {
            throw new IllegalArgumentException("requestDeadline must be positive");
        }
    }

    @Override
    public CompletionStage<Response> fetch(final Optional<String> etag) {
        Objects.requireNonNull(etag, "etag");
        return new RequestOperation(etag).start();
    }

    private final class RequestOperation {
        private final Optional<String> etag;
        private final long deadlineNanos;
        private final Object resourceLock = new Object();
        private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(
            2,
            requestThreadFactory()
        );
        private final RequestFuture result = new RequestFuture(this);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean teardownScheduled = new AtomicBoolean();
        private volatile HttpURLConnection connection;
        private volatile InputStream body;
        private volatile Future<?> ioTask;
        private volatile ScheduledFuture<?> deadlineTask;

        private RequestOperation(final Optional<String> etag) {
            this.etag = etag;
            this.deadlineNanos = deadlineNanos(requestDeadline);
        }

        private CompletionStage<Response> start() {
            try {
                registerDeadline(executor.schedule(
                    this::timeout,
                    remainingNanos(),
                    TimeUnit.NANOSECONDS
                ));
                registerTask(executor.submit(this::run));
            } catch (RejectedExecutionException failure) {
                result.completeExceptionally(failure);
                finish();
            }
            return result;
        }

        private void run() {
            try {
                final Response response = exchange();
                if (!cancelled.get()) result.complete(response);
            } catch (IOException failure) {
                if (!cancelled.get()) {
                    result.completeExceptionally(new IllegalStateException("update request failed", failure));
                }
            } catch (Throwable failure) {
                if (!cancelled.get() && !result.isDone()) result.completeExceptionally(failure);
            } finally {
                finish();
            }
        }

        private Response exchange() throws IOException {
            checkDeadline();
            final HttpURLConnection opened = connectionFactory.open(ENDPOINT);
            registerConnection(opened);
            checkDeadline();

            opened.setInstanceFollowRedirects(false);
            opened.setUseCaches(false);
            final long remaining = remainingNanos();
            opened.setConnectTimeout(timeoutMillis(Math.min(CONNECT_TIMEOUT.toNanos(), remaining)));
            opened.setReadTimeout(timeoutMillis(remaining));
            opened.setRequestMethod("GET");
            opened.setRequestProperty("Accept", "application/json");
            etag.filter(value -> !value.isBlank())
                .ifPresent(value -> opened.setRequestProperty("If-None-Match", value));

            final int statusCode = opened.getResponseCode();
            checkDeadline();
            final long contentLength = opened.getContentLengthLong();
            if (contentLength > UpdateDiscoveryParser.MAX_BYTES) {
                throw new IOException("discovery metadata exceeds the 256 KiB limit");
            }
            final InputStream responseBody = statusCode >= 400
                ? opened.getErrorStream()
                : opened.getInputStream();
            registerBody(responseBody);
            return new Response(
                statusCode,
                readBounded(responseBody),
                Optional.ofNullable(opened.getHeaderField("ETag"))
            );
        }

        private byte[] readBounded(final InputStream input) throws IOException {
            if (input == null) return new byte[0];
            final ByteArrayOutputStream result = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int size = 0;
            for (int read; ; ) {
                checkDeadline();
                try {
                    read = input.read(buffer);
                } catch (RuntimeException failure) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("update request was interrupted", failure);
                    }
                    throw failure;
                }
                if (read == -1) break;
                if (read == 0) continue;
                if (read > UpdateDiscoveryParser.MAX_BYTES - size) {
                    throw new IOException("discovery metadata exceeds the 256 KiB limit");
                }
                result.write(buffer, 0, read);
                size += read;
            }
            checkDeadline();
            return result.toByteArray();
        }

        private void checkDeadline() throws IOException {
            if (cancelled.get()) throw new CancellationException("update request was cancelled");
            if (remainingNanos() <= 0) throw new DeadlineExceededException();
        }

        private long remainingNanos() {
            return deadlineNanos - System.nanoTime();
        }

        private void timeout() {
            if (result.isDone() || finished.get()) return;
            if (!cancelled.compareAndSet(false, true)) return;
            cancelTasks();
            result.completeExceptionally(new DeadlineExceededException());
            finish();
        }

        private void cancelFromCaller() {
            if (!cancelled.compareAndSet(false, true)) return;
            cancelTasks();
            finish();
        }

        private void registerConnection(final HttpURLConnection candidate) {
            Objects.requireNonNull(candidate, "connectionFactory result");
            boolean closeImmediately;
            synchronized (resourceLock) {
                closeImmediately = cancelled.get() || finished.get();
                if (!closeImmediately) connection = candidate;
            }
            if (closeImmediately) disconnect(candidate);
        }

        private void registerBody(final InputStream candidate) {
            if (candidate == null) return;
            boolean closeImmediately;
            synchronized (resourceLock) {
                closeImmediately = cancelled.get() || finished.get();
                if (!closeImmediately) body = candidate;
            }
            if (closeImmediately) close(candidate);
        }

        private void registerTask(final Future<?> candidate) {
            boolean cancelImmediately;
            synchronized (resourceLock) {
                cancelImmediately = cancelled.get() || finished.get();
                if (!cancelImmediately) ioTask = candidate;
            }
            if (cancelImmediately) candidate.cancel(true);
        }

        private void registerDeadline(final ScheduledFuture<?> candidate) {
            boolean cancelImmediately;
            synchronized (resourceLock) {
                cancelImmediately = cancelled.get() || finished.get();
                if (!cancelImmediately) deadlineTask = candidate;
            }
            if (cancelImmediately) candidate.cancel(false);
        }

        private void cancelTasks() {
            final Future<?> task;
            final ScheduledFuture<?> deadline;
            synchronized (resourceLock) {
                task = ioTask;
                deadline = deadlineTask;
                ioTask = null;
                deadlineTask = null;
            }
            if (task != null) task.cancel(true);
            if (deadline != null) deadline.cancel(false);
        }

        private void closeResources() {
            final InputStream registeredBody;
            final HttpURLConnection registeredConnection;
            synchronized (resourceLock) {
                registeredBody = body;
                registeredConnection = connection;
                body = null;
                connection = null;
            }
            disconnect(registeredConnection);
            close(registeredBody);
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) return;
            scheduleTeardown();
        }

        private void scheduleTeardown() {
            if (!teardownScheduled.compareAndSet(false, true)) return;
            executor.execute(this::teardown);
        }

        private void teardown() {
            final ScheduledFuture<?> deadline;
            synchronized (resourceLock) {
                deadline = deadlineTask;
                deadlineTask = null;
            }
            if (deadline != null) deadline.cancel(false);
            closeResources();
            executor.shutdownNow();
        }
    }

    private final class RequestFuture extends CompletableFuture<Response> {
        private final RequestOperation operation;

        private RequestFuture(final RequestOperation operation) {
            this.operation = operation;
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            final boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) operation.cancelFromCaller();
            return cancelled;
        }
    }

    private static ThreadFactory requestThreadFactory() {
        final long requestId = REQUEST_IDS.incrementAndGet();
        return runnable -> {
            final Thread thread = new Thread(runnable, "turboism-update-http-" + requestId);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static long deadlineNanos(final Duration duration) {
        final long durationNanos;
        try {
            durationNanos = duration.toNanos();
        } catch (ArithmeticException tooLarge) {
            return Long.MAX_VALUE;
        }
        final long now = System.nanoTime();
        if (durationNanos >= 0 && now > Long.MAX_VALUE - durationNanos) return Long.MAX_VALUE;
        return now + durationNanos;
    }

    private static int timeoutMillis(final long nanos) {
        final long millis = TimeUnit.NANOSECONDS.toMillis(Math.max(1L, nanos));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis));
    }

    private static void close(final InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException | RuntimeException ignored) {
            // The connection is also disconnected below; cleanup must remain non-blocking.
        }
    }

    private static void disconnect(final HttpURLConnection connection) {
        if (connection == null) return;
        try {
            connection.disconnect();
        } catch (RuntimeException ignored) {
            // Cancellation and timeout are best-effort resource cleanup paths.
        }
    }

    private static final class DeadlineExceededException extends IOException {
        private DeadlineExceededException() {
            super("update request exceeded its absolute deadline");
        }
    }
}

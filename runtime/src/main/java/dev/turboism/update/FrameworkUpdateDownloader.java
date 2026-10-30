package dev.turboism.update;

import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.turboism.core.runtime.work.FatalErrors;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Offline-enabled download session: authenticates documents before planning/streaming resources.
 * Not wired to production or UI. Staged observations always require fresh recovery before future apply.
 * Caller owns executor/scheduler lifecycle; this class creates no threads or global pools.
 */
public final class FrameworkUpdateDownloader {
    public enum Phase {
        RECEIVING,
        RESTARTING,
        VERIFIED,
        RETRYING,
        TASK
    }

    /** Per-resource extent plus verified target bytes; totals are zero until signature/plan validation. */
    public record Progress(
            Phase phase, String sha256, long received, long total, long verified, long taskVerified, long taskTotal) {}

    /** A verified staging observation, never installation or future apply authority. */
    public record StagedTask(String id, boolean directoryDurabilityConfirmed) {}

    public record Downloaded(
            Path manifest,
            Path envelope,
            FrameworkDeltaPlanner.Plan plan,
            Map<String, Path> objects,
            Optional<StagedTask> staged) {
        public Downloaded {
            objects = Map.copyOf(objects);
        }
    }

    private final Path cacheRoot;
    private final FrameworkUpdateSource source;
    private final UpdateManifestVerifier verifier;
    private final ExecutorService io;
    private final ScheduledExecutorService timer;
    private final Duration deadline;
    private final long quota;
    private final FrameworkUpdateCache.Faults faults;

    public FrameworkUpdateDownloader(
            final Path cacheRoot,
            final FrameworkUpdateSource source,
            final UpdateManifestVerifier verifier,
            final ExecutorService io,
            final ScheduledExecutorService timer) {
        this(cacheRoot, source, verifier, io, timer, Duration.ofMinutes(10));
    }

    FrameworkUpdateDownloader(
            final Path cacheRoot,
            final FrameworkUpdateSource source,
            final UpdateManifestVerifier verifier,
            final ExecutorService io,
            final ScheduledExecutorService timer,
            final Duration deadline) {
        this(cacheRoot, source, verifier, io, timer, deadline, FrameworkUpdateCache.DEFAULT_QUOTA, ignored -> {});
    }

    FrameworkUpdateDownloader(
            final Path cacheRoot,
            final FrameworkUpdateSource source,
            final UpdateManifestVerifier verifier,
            final ExecutorService io,
            final ScheduledExecutorService timer,
            final Duration deadline,
            final long quota,
            final FrameworkUpdateCache.Faults faults) {
        this.quota = quota;
        this.faults = Objects.requireNonNull(faults, "faults");
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.source = Objects.requireNonNull(source, "source");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.io = Objects.requireNonNull(io, "io");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative() || deadline.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Invalid session deadline");
        }
    }

    /** Context installation hashes must originate in authenticated installation evidence, never a scan. */
    CompletableFuture<Downloaded> download(
            final UpdateDiscovery.Candidate candidate,
            final UpdateVerificationContext context,
            final Path installationRoot,
            final Consumer<Progress> progress) {
        return operation(candidate, context, installationRoot, progress, false, false, null);
    }

    /** Downloads and atomically publishes an untrusted STAGED record after complete revalidation. */
    CompletableFuture<Downloaded> stage(
            final UpdateDiscovery.Candidate candidate,
            final UpdateVerificationContext context,
            final Path installationRoot,
            final Consumer<Progress> progress) {
        return operation(candidate, context, installationRoot, progress, true, false, null);
    }

    /** Cache-only recovery; fresh caller authority is mandatory, and missing resources never trigger network IO. */
    CompletableFuture<Downloaded> recover(
            final String taskId,
            final UpdateDiscovery.Candidate candidate,
            final UpdateVerificationContext context,
            final Path installationRoot,
            final Consumer<Progress> progress) {
        return operation(candidate, context, installationRoot, progress, true, true, taskId);
    }

    /** Explicitly releases a task's pins. Does not delete resources still referenced by other tasks. */
    public CompletableFuture<Void> discard(final String taskId) {
        return CompletableFuture.runAsync(
                () -> {
                    try (FrameworkUpdateCache cache = new FrameworkUpdateCache(cacheRoot, quota, faults)) {
                        FrameworkUpdateTaskStore.discard(cache, taskId);
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                },
                io);
    }

    private CompletableFuture<Downloaded> operation(
            final UpdateDiscovery.Candidate candidate,
            final UpdateVerificationContext context,
            final Path installationRoot,
            final Consumer<Progress> progress,
            final boolean persist,
            final boolean recovery,
            final String taskId) {
        final UpdateIncrementalDiscovery capability = candidate.incremental().orElseThrow();
        if (!capability.version().equals(context.expectedVersion())
                || !capability.channel().equals(context.expectedChannel())
                || capability.buildNumber() != context.expectedBuildNumber()
                || !capability.sourceRevision().equals(context.expectedSourceRevision())) {
            throw new IllegalArgumentException("Candidate/context identity mismatch");
        }
        final Path installation = installationRoot.toAbsolutePath().normalize();
        if (cacheRoot.startsWith(installation) || installation.startsWith(cacheRoot)) {
            throw new IllegalArgumentException("Resource cache must be separate from installation");
        }
        final FrameworkUpdateTaskStore task =
                persist ? new FrameworkUpdateTaskStore(capability, context, installation) : null;
        if (recovery && !task.id.equals(taskId)) throw new IllegalArgumentException("Task authority/binding changed");
        return new Operation(
                        capability, context, installation, Objects.requireNonNull(progress, "progress"), task, recovery)
                .start();
    }

    private final class Operation {
        private final UpdateIncrementalDiscovery capability;
        private final UpdateVerificationContext context;
        private final Path installation;
        private final Consumer<Progress> progress;
        private final Object control = new Object();
        private final Result result = new Result();
        private volatile boolean cancelled;
        private volatile boolean committing;
        private volatile boolean published;
        private boolean durable;
        private Downloaded completed;
        private final FrameworkUpdateTaskStore task;
        private final boolean recovery;
        private final Set<String> verifiedObjects = new HashSet<>();
        private FrameworkDeltaPlanner.Plan targetPlan;
        private long taskTotal;
        private Future<?> worker;
        private ScheduledFuture<?> timeout;
        private HttpURLConnection connection;
        private InputStream responseBody;

        private Operation(
                final UpdateIncrementalDiscovery capability,
                final UpdateVerificationContext context,
                final Path installation,
                final Consumer<Progress> progress,
                final FrameworkUpdateTaskStore task,
                final boolean recovery) {
            this.task = task;
            this.recovery = recovery;
            this.capability = capability;
            this.context = context;
            this.installation = installation;
            this.progress = progress;
        }

        private CompletableFuture<Downloaded> start() {
            synchronized (control) {
                try {
                    timeout = timer.schedule(() -> stop(true), deadline.toNanos(), TimeUnit.NANOSECONDS);
                    worker = io.submit(this::run);
                } catch (RuntimeException failure) {
                    if (timeout != null) timeout.cancel(false);
                    result.completeExceptionally(failure);
                }
            }
            return result;
        }

        private void run() {
            try {
                if (SwingUtilities.isEventDispatchThread()) throw new IOException("Download IO cannot run on EDT");
                active();
                context.revalidate();
                final Downloaded downloaded;
                FrameworkUpdateCache.installationAncestors(installation);
                try (FrameworkUpdateCache cache = new FrameworkUpdateCache(cacheRoot, quota, faults)) {
                    cache.pin("document", capability.manifest().sha256());
                    cache.pin("document", capability.envelope().sha256());
                    if (task != null) task.begin(cache, recovery);
                    validateInstalledEvidence();
                    final Path manifest = fetch(
                            cache,
                            "document",
                            capability.manifest().sha256(),
                            capability.manifest().bytes());
                    final Path envelope = fetch(
                            cache,
                            "document",
                            capability.envelope().sha256(),
                            capability.envelope().bytes());
                    active();
                    final TrustedUpdateManifest trusted = verifier.verify(
                            documentBytes(manifest, capability.manifest().bytes()),
                            documentBytes(envelope, capability.envelope().bytes()),
                            context,
                            Instant.now());
                    final Map<String, Long> closure = new java.util.TreeMap<>();
                    long total = 0;
                    for (final UpdateManifestEntry entry : trusted.entries()) {
                        total += entry.bytes();
                        final Long old = closure.putIfAbsent(entry.sha256(), entry.bytes());
                        if (old != null && old != entry.bytes()) throw new IOException("Conflicting object lengths");
                    }
                    if (total > 1024L * 1024 * 1024)
                        throw new IOException("Manifest expanded size exceeds session budget");
                    final ArrayNode inventory = UpdateFileSupport.JSON.createArrayNode();
                    closure.forEach((hash, bytes) ->
                            inventory.addObject().put("bytes", bytes).put("sha256", hash));
                    if (!sha(UpdateFileSupport.JSON.writeValueAsBytes(inventory))
                            .equals(capability.objectsSha256())) {
                        throw new IOException("Discovery object closure mismatch");
                    }
                    closure.keySet().forEach(hash -> cache.pin("object", hash));
                    if (task != null) task.references(cache, closure);
                    final FrameworkDeltaPlanner.Plan plan = FrameworkDeltaPlanner.plan(installation, trusted);
                    targetPlan = plan;
                    taskTotal = total;
                    emit(Phase.TASK, "", 0, 0);
                    final Map<String, Long> needed = new LinkedHashMap<>();
                    for (final FrameworkDeltaPlanner.Decision decision : plan.decisions()) {
                        if (decision.action() == FrameworkDeltaPlanner.Action.DOWNLOAD) {
                            needed.put(
                                    decision.entry().sha256(), decision.entry().bytes());
                        }
                    }
                    long growth = 0;
                    for (final var item : needed.entrySet())
                        growth += additional(cache, "object", item.getKey(), item.getValue());
                    cache.preflight(growth);
                    final Map<String, Path> objects = new LinkedHashMap<>();
                    for (final var item : needed.entrySet()) {
                        objects.put(item.getKey(), fetch(cache, "object", item.getKey(), item.getValue()));
                    }
                    // Rehash every result before returning; no local status marker can bypass this.
                    for (final var item : needed.entrySet()) {
                        active();
                        if (!cache.verified(objects.get(item.getKey()), item.getValue(), item.getKey())) {
                            throw new IOException("Cached object changed during session");
                        }
                    }
                    active();
                    emit(Phase.TASK, "", 0, 0);
                    completed = new Downloaded(manifest, envelope, plan, objects, Optional.empty());
                    if (task != null) {
                        // Rebuild everything from exact documents/current installation, never serialized actions or
                        // paths.
                        final TrustedUpdateManifest finalTrusted = verifier.verify(
                                documentBytes(manifest, capability.manifest().bytes()),
                                documentBytes(envelope, capability.envelope().bytes()),
                                context,
                                Instant.now());
                        if (!cache.verified(
                                        manifest,
                                        capability.manifest().bytes(),
                                        capability.manifest().sha256())
                                || !cache.verified(
                                        envelope,
                                        capability.envelope().bytes(),
                                        capability.envelope().sha256())
                                || !FrameworkDeltaPlanner.plan(installation, finalTrusted)
                                        .equals(plan)) {
                            throw new IOException("Documents or installation changed before staging");
                        }
                        faults.hit("before-stage-prepare");
                        final Path prepared = task.prepare(cache);
                        faults.hit("before-commit-claim");
                        if (!cache.verified(
                                        manifest,
                                        capability.manifest().bytes(),
                                        capability.manifest().sha256())
                                || !cache.verified(
                                        envelope,
                                        capability.envelope().bytes(),
                                        capability.envelope().sha256())) {
                            throw new IOException("Signed documents changed during commit preparation");
                        }
                        validateInstalledEvidence();
                        if (!FrameworkDeltaPlanner.plan(installation, finalTrusted)
                                .equals(plan)) {
                            throw new IOException("Installation changed during commit preparation");
                        }
                        for (final var item : needed.entrySet()) {
                            if (!cache.verified(objects.get(item.getKey()), item.getValue(), item.getKey())) {
                                throw new IOException("Object changed during commit preparation");
                            }
                        }
                        context.revalidate();
                        context.claimCommit(() -> {
                            synchronized (control) {
                                active();
                                committing =
                                        true; // Admission/cancellation claim; filesystem visibility is rename below.
                            }
                        });
                        cache.replace(
                                prepared, FrameworkUpdateTaskStore.path(cache, task.id, false), () -> published = true);
                        durable = true;
                        completed = stagedResult();
                    }
                    downloaded = completed;
                }
                synchronized (control) {
                    active();
                    result.complete(downloaded);
                }
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                if (published) result.complete(stagedResult());
                else if (!result.isDone()) result.completeExceptionally(failure);
            } finally {
                if (timeout != null) timeout.cancel(false);
                closeResponse();
            }
        }

        private void validateInstalledEvidence() throws IOException, InterruptedException {
            for (final var entry : context.installedFrameworkHashes().entrySet()) {
                active();
                final Path path = installation.resolve(entry.getKey());
                FrameworkUpdateCache.installationAncestors(path.getParent());
                final var attributes = FrameworkUpdateCache.attributes(path);
                if (attributes == null
                        || !attributes.isRegularFile()
                        || attributes.isSymbolicLink()
                        || attributes.isOther()
                        || attributes.size() > UpdateManifest.MAX_FILE_BYTES
                        || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                        || !FrameworkUpdateCache.hash(path, attributes.size()).equals(entry.getValue())) {
                    throw new IOException("Trusted old installation evidence no longer matches");
                }
            }
        }

        private Downloaded stagedResult() {
            return new Downloaded(
                    completed.manifest(),
                    completed.envelope(),
                    completed.plan(),
                    completed.objects(),
                    Optional.of(new StagedTask(task.id, durable)));
        }

        private long additional(final FrameworkUpdateCache cache, final String kind, final String hash, final long size)
                throws IOException, InterruptedException {
            if (cache.verified(cache.path(kind, hash, "verified"), size, hash)) return 0;
            final Path part = cache.path(kind, hash, "part");
            if (cache.present(part) && strong(cache.etag(cache.path(kind, hash, "etag"))) && Files.size(part) <= size) {
                return size - Files.size(part);
            }
            return size;
        }

        private Path fetch(final FrameworkUpdateCache cache, final String kind, final String hash, final long size)
                throws IOException, InterruptedException {
            active();
            cache.pin(kind, hash);
            final Path verified = cache.path(kind, hash, "verified");
            if (cache.verified(verified, size, hash)) {
                emit(Phase.VERIFIED, hash, size, size);
                return verified;
            }
            if (recovery) throw new IOException("Recovery resource missing or corrupt");
            cache.remove(verified);
            final Path part = cache.path(kind, hash, "part");
            final Path validator = cache.path(kind, hash, "etag");
            int restarts = 0;
            int retries = 0;
            for (int attempt = 0; attempt < 4; attempt++) {
                active();
                long offset = cache.present(part) ? Files.size(part) : 0;
                String etag = cache.etag(validator);
                if (offset > size || !strong(etag)) {
                    cache.remove(part);
                    cache.remove(validator);
                    offset = 0;
                    etag = null;
                }
                if (offset == size && cache.verified(part, size, hash)) {
                    cache.replace(part, verified);
                    cache.remove(validator);
                    emit(Phase.VERIFIED, hash, size, size);
                    return verified;
                }
                if (offset == size) { // Corrupt complete partial must never become an HTTP range authority.
                    cache.remove(part);
                    cache.remove(validator);
                    offset = 0;
                    etag = null;
                }
                final HttpURLConnection opened = source.open(kind, hash);
                synchronized (control) {
                    if (cancelled) {
                        opened.disconnect();
                        active();
                    }
                    connection = opened;
                }
                final long requestEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                final ScheduledFuture<?> requestTimeout = timer.schedule(() -> stop(true), 30, TimeUnit.SECONDS);
                try {
                    opened.setInstanceFollowRedirects(false);
                    opened.setUseCaches(false);
                    opened.setConnectTimeout(5000);
                    opened.setReadTimeout(5000);
                    opened.setRequestMethod("GET");
                    opened.setRequestProperty("Accept-Encoding", "identity");
                    if (offset > 0) {
                        opened.setRequestProperty("Range", "bytes=" + offset + "-");
                        opened.setRequestProperty("If-Range", etag);
                    }
                    active();
                    final int status;
                    try {
                        status = opened.getResponseCode();
                    } catch (IOException failure) {
                        throw transientNetwork(failure);
                    }
                    active();
                    if (opened.getHeaderField("Content-Encoding") != null
                            && !"identity".equalsIgnoreCase(opened.getHeaderField("Content-Encoding"))) {
                        throw new IOException("Encoded framework response rejected");
                    }
                    if (Set.of(408, 429, 500, 502, 503, 504).contains(status)) {
                        throw new TransientFailure(
                                "Temporary HTTP status " + status, retryAfter(opened.getHeaderField("Retry-After")));
                    }
                    final String returnedEtag = opened.getHeaderField("ETag");
                    if (offset > 0
                            && (status == 200
                                    || status == 416
                                    || (status == 206 && !Objects.equals(etag, returnedEtag)))) {
                        if (++restarts > 1) throw new IOException("Resource restart budget exhausted");
                        cache.remove(part);
                        cache.remove(validator);
                        emit(Phase.RESTARTING, hash, 0, size);
                        continue; // Shares the four-request budget with transient retries.
                    }
                    if (offset > 0) {
                        if (status != 206
                                || !("bytes " + offset + "-" + (size - 1) + "/" + size)
                                        .equals(opened.getHeaderField("Content-Range"))) {
                            throw new IOException("Invalid resumed response range");
                        }
                    } else if (status != 200 || opened.getHeaderField("Content-Range") != null) {
                        throw new IOException("Unexpected framework response status/range");
                    }
                    if (opened.getContentLengthLong() != size - offset)
                        throw new IOException("Resource length mismatch");
                    if (!cache.present(part)) cache.create(part);
                    if (strong(returnedEtag)) cache.etag(validator, returnedEtag);
                    else cache.remove(validator);
                    cache.preflight(size - offset);
                    final InputStream input;
                    try {
                        input = opened.getInputStream();
                    } catch (IOException failure) {
                        throw transientNetwork(failure);
                    }
                    synchronized (control) {
                        if (cancelled) {
                            input.close();
                            active();
                        }
                        responseBody = input;
                    }
                    emit(Phase.RECEIVING, hash, offset, size);
                    try (input;
                            OutputStream output =
                                    Files.newOutputStream(part, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                        final byte[] buffer = new byte[32768];
                        long count = offset;
                        for (int read; ; ) {
                            try {
                                read = input.read(buffer);
                            } catch (IOException failure) {
                                throw transientNetwork(failure);
                            }
                            if (read == -1) break;
                            active();
                            if (System.nanoTime() >= requestEnd)
                                throw new IOException("Resource request deadline exceeded");
                            if (read > size - count) {
                                cache.remove(validator);
                                throw new IOException("Resource exceeds expected length");
                            }
                            output.write(buffer, 0, read);
                            count += read;
                            emit(Phase.RECEIVING, hash, count, size);
                        }
                        if (count != size) throw new TransientFailure("Resource ended early; partial retained", 0);
                    }
                    active();
                    if (!cache.verified(part, size, hash)) {
                        cache.remove(part);
                        cache.remove(validator);
                        throw new IOException("Resource digest mismatch");
                    }
                    cache.replace(part, verified);
                    cache.remove(validator);
                    emit(Phase.VERIFIED, hash, size, size);
                    return verified;
                } catch (TransientFailure failure) {
                    requestTimeout.cancel(false);
                    closeResponse();
                    if (++retries > 2 || attempt == 3) throw failure;
                    emit(Phase.RETRYING, hash, cache.present(part) ? Files.size(part) : 0, size);
                    backoff(Math.max(100L << (retries - 1), failure.delayMillis));
                } finally {
                    requestTimeout.cancel(false);
                    closeResponse();
                    // Cancellation may have detached this connection before a blocked operation returned.
                    opened.disconnect();
                }
            }
            throw new IOException("Resource restart budget exhausted");
        }

        private void backoff(final long millis) throws IOException, InterruptedException {
            active();
            final Future<?> wait = timer.schedule(() -> {}, Math.min(2000, millis), TimeUnit.MILLISECONDS);
            try {
                wait.get(Math.min(2000, millis) + 250, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException | CancellationException failure) {
                throw new IOException("Retry timer unavailable", failure);
            } finally {
                wait.cancel(false);
            }
            active();
        }

        private void emit(final Phase phase, final String hash, final long received, final long total) {
            active();
            if (phase == Phase.VERIFIED && targetPlan != null) verifiedObjects.add(hash);
            long verified = 0;
            if (targetPlan != null) {
                for (final var decision : targetPlan.decisions()) {
                    if (decision.action() == FrameworkDeltaPlanner.Action.REUSE
                            || verifiedObjects.contains(decision.entry().sha256())) {
                        verified += decision.entry().bytes();
                    }
                }
            }
            progress.accept(new Progress(
                    phase, hash, received, total, phase == Phase.VERIFIED ? total : 0, verified, taskTotal));
            active();
        }

        private void active() {
            if (timer.isShutdown() && !published) throw new IllegalStateException("Download timer shut down");
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new CancellationException("Framework download cancelled");
        }

        private boolean stop(final boolean timedOut) {
            synchronized (control) {
                if (result.isDone() || committing) return false;
                cancelled = true;
                if (timedOut) result.completeExceptionally(new IOException("Download deadline exceeded"));
                else result.cancelResult();
                if (worker != null) worker.cancel(true);
                if (timeout != null) timeout.cancel(false);
            }
            // HttpURLConnection close/disconnect may block; never do it on the cancelling/UI thread.
            try {
                timer.execute(this::closeResponse);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Caller shut down its timer; the interrupted worker still owns bounded read teardown.
            }
            return true;
        }

        private void closeResponse() {
            final InputStream input;
            final HttpURLConnection opened;
            synchronized (control) {
                input = responseBody;
                opened = connection;
                responseBody = null;
                connection = null;
            }
            if (opened != null) opened.disconnect();
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    /* Best-effort transport teardown. */
                }
            }
        }

        private final class Result extends CompletableFuture<Downloaded> {
            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                return stop(false);
            }

            private void cancelResult() {
                super.cancel(true);
            }
        }
    }

    private static final class TransientFailure extends IOException {
        private final long delayMillis;

        private TransientFailure(final String message, final long delayMillis) {
            super(message);
            this.delayMillis = delayMillis;
        }
    }

    private static IOException transientNetwork(final IOException failure) {
        if (failure instanceof SocketException
                || failure instanceof SocketTimeoutException
                || failure instanceof EOFException) {
            return new TransientFailure("Temporary network failure", 0);
        }
        return failure;
    }

    private static long retryAfter(final String header) {
        if (header == null) return 0;
        try {
            return Math.min(2000, Math.max(0, Math.multiplyExact(Long.parseLong(header), 1000)));
        } catch (NumberFormatException | ArithmeticException ignored) {
            try {
                final Instant date = java.time.ZonedDateTime.parse(
                                header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                return Math.min(
                        2000, Math.max(0, Duration.between(Instant.now(), date).toMillis()));
            } catch (java.time.DateTimeException failure) {
                return 0;
            }
        }
    }

    private static byte[] documentBytes(final Path path, final long size) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = input.readNBytes(Math.toIntExact(size) + 1);
            if (bytes.length != size) throw new IOException("Cached document length changed");
            return bytes;
        }
    }

    private static boolean strong(final String etag) {
        return etag != null && etag.length() <= 512 && etag.matches("\"[\\x21\\x23-\\x7E]*\"");
    }

    private static String sha(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

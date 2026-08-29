package dev.turboism.graal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs one pinned GraalVM Community runtime below Turboism home.
 *
 * <p>The service never changes {@code JAVA_HOME}, {@code PATH}, the registry,
 * or Cubism's installation. Downloads are user-initiated, pinned to an exact
 * official archive and digest, extracted into a private staging directory,
 * probed with Turboism's packaged Graal host, and atomically activated only
 * after every check succeeds.</p>
 */
public final class ManagedGraalRuntimeService implements AutoCloseable {

    @FunctionalInterface
    interface Probe {
        void verify(Path javaExecutable) throws Exception;
    }

    public static final String GRAAL_VERSION = "25.2.4";
    public static final String JAVA_VERSION = "25.0.4";
    private static final long MAX_ARCHIVE_BYTES = 400L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRIES = 32_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final String ACTIVATION_MARKER_NAME = ".runtime-activation";
    private static final String PREVIOUS_RUNTIME_NAME = ".runtime-previous";
    private static final ObjectMapper PROTOCOL_JSON = new ObjectMapper();
    private static final Set<String> DOWNLOAD_HOSTS = Set.of(
        "github.com", "release-assets.githubusercontent.com"
    );
    private static final Set<String> INHERITED_JAVA_OPTIONS = Set.of(
        "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"
    );
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
        "CON", "PRN", "AUX", "NUL", "CLOCK$", "CONIN$", "CONOUT$",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private final Path turboismHome;
    private final Path graalRoot;
    private final Path runtimeHome;
    private final Path cacheRoot;
    private final HttpClient client;
    private final Platform platform;
    private final ExecutorService executor;
    private final Consumer<String> diagnostic;
    private final Probe probe;
    private final Duration downloadTimeout;
    private final Object operationLock = new Object();
    private volatile Operation active;
    private volatile boolean closed;

    public ManagedGraalRuntimeService(
        final Path turboismHome,
        final Consumer<String> diagnostic
    ) {
        this(
            turboismHome,
            HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            Platform.detect(),
            diagnostic,
            null,
            REQUEST_TIMEOUT
        );
    }

    ManagedGraalRuntimeService(
        final Path turboismHome,
        final HttpClient client,
        final Platform platform,
        final Consumer<String> diagnostic,
        final Probe probe
    ) {
        this(turboismHome, client, platform, diagnostic, probe, REQUEST_TIMEOUT);
    }

    ManagedGraalRuntimeService(
        final Path turboismHome,
        final HttpClient client,
        final Platform platform,
        final Consumer<String> diagnostic,
        final Probe probe,
        final Duration downloadTimeout
    ) {
        this.turboismHome = Objects.requireNonNull(turboismHome, "turboismHome")
            .toAbsolutePath().normalize();
        this.graalRoot = confined(this.turboismHome.resolve("graal"));
        this.runtimeHome = confined(graalRoot.resolve("runtime"));
        this.cacheRoot = confined(this.turboismHome.resolve("cache/runtime/graal"));
        this.client = Objects.requireNonNull(client, "client");
        this.platform = platform;
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.probe = probe == null ? this::probeHost : probe;
        this.downloadTimeout = Objects.requireNonNull(downloadTimeout, "downloadTimeout");
        if (downloadTimeout.isZero() || downloadTimeout.isNegative()) {
            throw new IllegalArgumentException("downloadTimeout must be positive");
        }
        reconcileInterruptedActivation();
        this.executor = Executors.newSingleThreadExecutor(new InstallerThreadFactory());
    }

    /** @return a point-in-time state of the managed runtime only */
    public Status status() {
        final Operation current = active;
        if (current != null && !current.result.isDone()) {
            return current.status();
        }
        return inspectInstalled();
    }

    /** Starts one user-authorized installation. Concurrent attempts share it. */
    public Operation install() {
        synchronized (operationLock) {
            requireOpen();
            if (platform == null) {
                return completedOperation(Status.unsupported(
                    "GraalVM Community automatic installation is unavailable on this platform."
                ));
            }
            final Operation existing = active;
            if (existing != null && !existing.result.isDone()) return existing;
            final Operation operation = new Operation(platform.manifest());
            active = operation;
            executor.execute(() -> runInstall(operation));
            return operation;
        }
    }

    /**
     * Returns the managed Java executable only after the complete managed path
     * chain and exact pinned release metadata pass validation.
     */
    public Optional<Path> managedJavaExecutableIfReady() {
        final Platform runtimePlatform = platform == null ? Platform.WINDOWS_X64 : platform;
        return managedJavaExecutableIfReady(turboismHome, runtimePlatform.javaRelativePath);
    }

    static Optional<Path> managedJavaExecutableIfReady(
        final Path turboismHome,
        final String javaRelativePath
    ) {
        final Path normalizedHome = Objects.requireNonNull(turboismHome, "turboismHome")
            .toAbsolutePath().normalize();
        final Path runtime = normalizedHome.resolve("graal/runtime").normalize();
        if (!Files.exists(runtime, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            requireDirectoryChain(normalizedHome, runtime);
            final Path java = runtime.resolve(javaRelativePath).toAbsolutePath().normalize();
            requireCompatible(java);
            return Optional.of(java);
        } catch (InstallFailure | IOException | RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /** Revalidates the installed runtime and runs the isolated host probe. */
    public Status verify() {
        requireOpen();
        final Status inspected = inspectInstalled();
        if (inspected.state() != State.READY) return inspected;
        try {
            probe.verify(inspected.javaExecutable().orElseThrow());
            return inspected;
        } catch (InstallFailure failure) {
            report(failure.code);
            return Status.failed(failure.code, failure.getMessage());
        } catch (Exception failure) {
            report("GRAAL_RUNTIME_HOST_PROBE_FAILED");
            return Status.failed(
                "GRAAL_RUNTIME_HOST_PROBE_FAILED",
                "The isolated Graal host probe failed: " + safeMessage(failure)
            );
        }
    }

    /** Removes only the managed runtime. External GraalVM installations are untouched. */
    public Status remove() {
        synchronized (operationLock) {
            requireOpen();
            if (active != null && !active.result.isDone()) {
                return Status.failed(
                    "GRAAL_RUNTIME_BUSY",
                    "Cancel the active GraalVM installation before removing the runtime."
                );
            }
            try {
                requireDirectoryChain(turboismHome, graalRoot);
                if (Files.exists(runtimeHome, LinkOption.NOFOLLOW_LINKS)) {
                    requireDirectoryChain(turboismHome, runtimeHome);
                }
                deleteTree(runtimeHome);
                cleanupEmptyParent(graalRoot);
                return Status.absent();
            } catch (IOException | InstallFailure failure) {
                report("GRAAL_RUNTIME_REMOVE_FAILED");
                return Status.failed(
                    "GRAAL_RUNTIME_REMOVE_FAILED",
                    "Could not remove the Turboism-managed GraalVM runtime."
                );
            }
        }
    }

    @Override
    public void close() {
        final Operation current;
        synchronized (operationLock) {
            if (closed) return;
            closed = true;
            current = active;
        }
        final boolean cancelled = current != null && current.cancel();
        executor.shutdown();
        if (current != null && cancelled && !current.started()) {
            current.cancelBeforeStart();
        }
        try {
            if (!executor.awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS)
                && current != null && !current.committing()) {
                final List<Runnable> queued = executor.shutdownNow();
                if (!queued.isEmpty()) current.cancelBeforeStart();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (current != null && !current.committing()) {
                final List<Runnable> queued = executor.shutdownNow();
                if (!queued.isEmpty()) current.cancelBeforeStart();
            }
        }
    }

    private void reconcileInterruptedActivation() {
        final Path marker = graalRoot.resolve(ACTIVATION_MARKER_NAME);
        final Path previous = graalRoot.resolve(PREVIOUS_RUNTIME_NAME);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            requireDirectoryChain(turboismHome, graalRoot);
            requireOwnedMarker(marker);
            if (!Files.exists(runtimeHome, LinkOption.NOFOLLOW_LINKS)
                && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectoryChain(turboismHome, previous);
                moveAtomically(previous, runtimeHome);
            }
            if (Files.exists(runtimeHome, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectoryChain(turboismHome, runtimeHome);
                final Path java = javaExecutable(runtimeHome);
                requireCompatible(java);
                if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                    requireDirectoryChain(turboismHome, previous);
                    deleteTree(previous);
                }
                Files.delete(marker);
            }
        } catch (IOException | InstallFailure failure) {
            report("GRAAL_RUNTIME_RECOVERY_FAILED");
        }
    }

    private static void requireOwnedMarker(final Path marker) throws IOException {
        final BasicFileAttributes attributes = Files.readAttributes(
            marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        if (!hasUsableIdentity(attributes) || !attributes.isRegularFile()
            || attributes.isSymbolicLink() || attributes.isOther()
            || isWindowsReparsePoint(marker) || attributes.size() > 128L) {
            throw new IOException("managed runtime activation marker is unsafe");
        }
        if (!GRAAL_VERSION.equals(Files.readString(marker, StandardCharsets.US_ASCII).trim())) {
            throw new IOException("managed runtime activation marker has wrong version");
        }
    }

    private void writeActivationMarker(final Path marker) throws IOException, InstallFailure {
        final BasicFileAttributes parent = requireOrdinaryDirectory(graalRoot);
        Files.writeString(
            marker,
            GRAAL_VERSION + "\n",
            StandardCharsets.US_ASCII,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        );
        requireDirectoryIdentity(graalRoot, parent);
        requireOwnedMarker(marker);
    }

    private void runInstall(final Operation operation) {
        operation.attachThread(Thread.currentThread());
        final Path temporaryArchive = cacheRoot.resolve(
            ".graalvm-" + UUID.randomUUID() + ".download"
        );
        final Path staging = graalRoot.resolve(
            ".runtime-staging-" + UUID.randomUUID()
        );
        final Path previous = graalRoot.resolve(PREVIOUS_RUNTIME_NAME);
        final Path activationMarker = graalRoot.resolve(ACTIVATION_MARKER_NAME);
        boolean previousMoved = false;
        boolean activated = false;
        BasicFileAttributes temporaryArchiveIdentity = null;
        try {
            operation.phase(State.DOWNLOADING, 0L, operation.manifest.archiveBytes,
                "Downloading GraalVM Community " + GRAAL_VERSION + ".");
            prepareRoots();
            temporaryArchiveIdentity = download(operation, temporaryArchive);
            operation.checkCancelled();

            operation.phase(State.EXTRACTING, operation.manifest.archiveBytes,
                operation.manifest.archiveBytes, "Extracting the managed runtime.");
            final Path extracted = extractZip(operation, temporaryArchive, staging);
            operation.checkCancelled();

            operation.phase(State.VERIFYING, operation.manifest.archiveBytes,
                operation.manifest.archiveBytes, "Verifying GraalVM and the isolated script host.");
            final Path java = javaExecutable(extracted);
            requireCompatible(java);
            try {
                probe.verify(java);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (InstallFailure failure) {
                throw failure;
            } catch (Exception failure) {
                throw failure(
                    "GRAAL_RUNTIME_HOST_PROBE_FAILED",
                    "The isolated Graal host probe failed: " + safeMessage(failure),
                    failure
                );
            }
            operation.checkCancelled();
            operation.beginCommit();

            if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(activationMarker, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(
                    "GRAAL_RUNTIME_RECOVERY_REQUIRED",
                    "A previous managed runtime activation requires recovery."
                );
            }
            writeActivationMarker(activationMarker);
            final BasicFileAttributes activationRoot = requireOrdinaryDirectory(graalRoot);
            if (Files.exists(runtimeHome, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectoryChain(turboismHome, runtimeHome);
                moveAtomically(runtimeHome, previous);
                previousMoved = true;
                requireDirectoryIdentity(graalRoot, activationRoot);
                requireDirectoryChain(turboismHome, previous);
            }
            try {
                requireDirectoryIdentity(graalRoot, activationRoot);
                moveAtomically(extracted, runtimeHome);
                requireDirectoryIdentity(graalRoot, activationRoot);
                requireDirectoryChain(turboismHome, runtimeHome);
            } catch (IOException activationFailure) {
                if (previousMoved && !Files.exists(runtimeHome, LinkOption.NOFOLLOW_LINKS)) {
                    moveAtomically(previous, runtimeHome);
                    previousMoved = false;
                }
                throw activationFailure;
            }
            activated = true;
            operation.succeed(javaExecutable(runtimeHome));
        } catch (Cancelled cancelled) {
            operation.cancelled();
        } catch (InstallFailure failure) {
            report(failure.code);
            operation.fail(failure.code, failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            operation.cancelled();
        } catch (Exception failure) {
            if (operation.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                operation.cancelled();
            } else {
                report("GRAAL_RUNTIME_INSTALL_FAILED");
                operation.fail(
                    "GRAAL_RUNTIME_INSTALL_FAILED",
                    "GraalVM installation failed: " + safeMessage(failure)
                );
            }
        } finally {
            tryDeleteOwnedFile(temporaryArchive, temporaryArchiveIdentity);
            tryDeleteTree(staging);
            if (previousMoved) {
                if (activated) {
                    tryDeleteTree(previous);
                } else {
                    restorePrevious(previous, activationMarker);
                }
            }
            if (activated && !Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                tryDeleteOwnedMarker(activationMarker);
            } else if (!activated && !previousMoved) {
                tryDeleteOwnedMarker(activationMarker);
            }
            operation.detachThread(Thread.currentThread());
        }
    }

    private void restorePrevious(final Path previous, final Path activationMarker) {
        try {
            if (Files.exists(runtimeHome, LinkOption.NOFOLLOW_LINKS)) {
                report("GRAAL_RUNTIME_ROLLBACK_FAILED");
                return;
            }
            if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectoryChain(turboismHome, previous);
                moveAtomically(previous, runtimeHome);
            }
            if (Files.exists(activationMarker, LinkOption.NOFOLLOW_LINKS)) {
                requireOwnedMarker(activationMarker);
                Files.delete(activationMarker);
            }
        } catch (IOException | InstallFailure failure) {
            report("GRAAL_RUNTIME_ROLLBACK_FAILED");
        }
    }

    private void prepareRoots() throws IOException, InstallFailure {
        createDirectoryChain(turboismHome, cacheRoot);
        createDirectoryChain(turboismHome, graalRoot);
        if (Files.exists(runtimeHome, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectoryChain(turboismHome, runtimeHome);
        }
    }

    private static void createDirectoryChain(final Path root, final Path path)
        throws IOException, InstallFailure {
        final Path normalizedRoot = root.toAbsolutePath().normalize();
        final Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw failure("GRAAL_RUNTIME_PATH_REJECTED", "Managed runtime path escaped Turboism home.");
        }
        Path current = normalizedRoot;
        requireOrdinaryDirectory(current);
        for (Path segment : normalizedRoot.relativize(normalized)) {
            final Path next = current.resolve(segment);
            final BasicFileAttributes parent = requireOrdinaryDirectory(current);
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(next);
                } catch (java.nio.file.FileAlreadyExistsException raced) {
                    requireOrdinaryDirectory(next);
                }
            }
            requireOrdinaryDirectory(next);
            requireDirectoryIdentity(current, parent);
            current = next;
        }
    }

    private BasicFileAttributes download(final Operation operation, final Path target)
        throws IOException, InterruptedException, InstallFailure {
        final AtomicReference<InputStream> activeBody = new AtomicReference<>();
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        final Thread installer = Thread.currentThread();
        final Thread deadline = new Thread(() -> {
            try {
                Thread.sleep(downloadTimeout.toMillis());
                timedOut.set(true);
                closeQuietly(activeBody.get());
                installer.interrupt();
            } catch (InterruptedException completed) {
                Thread.currentThread().interrupt();
            }
        }, "turboism-graal-runtime-download-deadline");
        deadline.setDaemon(true);
        deadline.start();
        try {
            return downloadBeforeDeadline(operation, target, activeBody, timedOut);
        } catch (IOException failure) {
            if (timedOut.get()) {
                Thread.interrupted();
                throw failure(
                    "GRAAL_RUNTIME_DOWNLOAD_TIMEOUT",
                    "GraalVM download exceeded the whole-download deadline.",
                    failure
                );
            }
            throw failure;
        } catch (InterruptedException interrupted) {
            if (timedOut.get() && !operation.cancelRequested.get()) {
                Thread.interrupted();
                throw failure(
                    "GRAAL_RUNTIME_DOWNLOAD_TIMEOUT",
                    "GraalVM download exceeded the whole-download deadline.",
                    interrupted
                );
            }
            throw interrupted;
        } finally {
            deadline.interrupt();
            closeQuietly(activeBody.getAndSet(null));
        }
    }

    private BasicFileAttributes downloadBeforeDeadline(
        final Operation operation,
        final Path target,
        final AtomicReference<InputStream> activeBody,
        final AtomicBoolean timedOut
    ) throws IOException, InterruptedException, InstallFailure {
        final MessageDigest digest = sha256();
        URI current = operation.manifest.uri;
        int redirects = 0;
        while (true) {
            if (timedOut.get()) {
                throw failure(
                    "GRAAL_RUNTIME_DOWNLOAD_TIMEOUT",
                    "GraalVM download exceeded the whole-download deadline."
                );
            }
            requireOfficialUri(current);
            final HttpRequest request = HttpRequest.newBuilder(current)
                .timeout(downloadTimeout)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "Turboism-GraalVM-Installer/" + GRAAL_VERSION)
                .GET()
                .build();
            final HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream()
            );
            activeBody.set(response.body());
            final int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(activeBody.getAndSet(null));
                if (++redirects > MAX_REDIRECTS) {
                    throw failure("GRAAL_RUNTIME_REDIRECT_REJECTED", "Too many download redirects.");
                }
                final String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> failure(
                        "GRAAL_RUNTIME_REDIRECT_REJECTED", "Download redirect had no location."
                    ));
                current = current.resolve(location);
                continue;
            }
            if (status != 200) {
                closeQuietly(activeBody.getAndSet(null));
                throw failure(
                    "GRAAL_RUNTIME_DOWNLOAD_FAILED",
                    "GraalVM download returned HTTP " + status + "."
                );
            }
            final long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > operation.manifest.archiveBytes || declared > MAX_ARCHIVE_BYTES) {
                closeQuietly(activeBody.getAndSet(null));
                throw failure(
                    "GRAAL_RUNTIME_ARCHIVE_TOO_LARGE",
                    "GraalVM archive exceeded the pinned size."
                );
            }
            long written = 0L;
            try (InputStream input = new BufferedInputStream(response.body());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                     target,
                     java.nio.file.StandardOpenOption.CREATE_NEW,
                     java.nio.file.StandardOpenOption.WRITE,
                     LinkOption.NOFOLLOW_LINKS
                 ))) {
                final byte[] buffer = new byte[BUFFER_SIZE];
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read == 0) continue;
                    if (timedOut.get()) {
                        throw failure(
                            "GRAAL_RUNTIME_DOWNLOAD_TIMEOUT",
                            "GraalVM download exceeded the whole-download deadline."
                        );
                    }
                    operation.checkCancelled();
                    written += read;
                    if (written > operation.manifest.archiveBytes || written > MAX_ARCHIVE_BYTES) {
                        throw failure(
                            "GRAAL_RUNTIME_ARCHIVE_TOO_LARGE",
                            "GraalVM archive exceeded the pinned size."
                        );
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    operation.phase(
                        State.DOWNLOADING, written, operation.manifest.archiveBytes,
                        "Downloading GraalVM Community " + GRAAL_VERSION + "."
                    );
                }
            } finally {
                activeBody.set(null);
            }
            if (written != operation.manifest.archiveBytes) {
                throw failure(
                    "GRAAL_RUNTIME_SIZE_MISMATCH",
                    "Downloaded GraalVM archive did not match the pinned size."
                );
            }
            final String actual = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                operation.manifest.sha256.getBytes(StandardCharsets.US_ASCII)
            )) {
                throw failure(
                    "GRAAL_RUNTIME_HASH_MISMATCH",
                    "Downloaded GraalVM archive did not match the pinned SHA-256."
                );
            }
            return requireOrdinaryRegularFile(target);
        }
    }

    private Path extractZip(
        final Operation operation,
        final Path archive,
        final Path staging
    ) throws IOException, InstallFailure {
        Files.createDirectory(staging);
        requireDirectoryChain(turboismHome, staging);
        String root = null;
        long extractedBytes = 0L;
        int entries = 0;
        final Set<Path> seen = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(
            new BufferedInputStream(Files.newInputStream(archive)), StandardCharsets.UTF_8
        )) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                operation.checkCancelled();
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw failure(
                        "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                        "GraalVM archive contained too many entries."
                    );
                }
                final String normalizedName = normalizeEntry(entry.getName());
                final int slash = normalizedName.indexOf('/');
                final String entryRoot = slash < 0 ? normalizedName : normalizedName.substring(0, slash);
                if (root == null) root = entryRoot;
                if (!root.equals(entryRoot)) {
                    throw failure(
                        "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                        "GraalVM archive did not have one root directory."
                    );
                }
                final Path target = staging.resolve(normalizedName).normalize();
                if (!target.startsWith(staging) || !seen.add(target)) {
                    throw failure(
                        "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                        "GraalVM archive contained an unsafe or duplicate path."
                    );
                }
                if (entry.isDirectory()) {
                    createDirectoryChain(staging, target);
                    continue;
                }
                final long declared = entry.getSize();
                if (declared > MAX_EXTRACTED_BYTES) {
                    throw failure(
                        "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                        "GraalVM archive entry exceeded the extraction limit."
                    );
                }
                createDirectoryChain(staging, target.getParent());
                final BasicFileAttributes parent = requireOrdinaryDirectory(target.getParent());
                long entryBytes = 0L;
                try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                    target,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                ))) {
                    final byte[] buffer = new byte[BUFFER_SIZE];
                    for (int read; (read = zip.read(buffer)) >= 0;) {
                        if (read == 0) continue;
                        operation.checkCancelled();
                        entryBytes += read;
                        extractedBytes += read;
                        if (entryBytes > MAX_EXTRACTED_BYTES
                            || extractedBytes > MAX_EXTRACTED_BYTES) {
                            throw failure(
                                "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                                "GraalVM archive exceeded the extraction limit."
                            );
                        }
                        output.write(buffer, 0, read);
                    }
                }
                requireDirectoryIdentity(target.getParent(), parent);
                final BasicFileAttributes extractedFile = Files.readAttributes(
                    target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
                );
                if (!extractedFile.isRegularFile() || !hasUsableIdentity(extractedFile)
                    || extractedFile.isSymbolicLink() || extractedFile.isOther()
                    || isWindowsReparsePoint(target)) {
                    throw failure(
                        "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                        "GraalVM archive extracted an unsafe file."
                    );
                }
                if (declared >= 0L && declared != entryBytes) {
                    throw failure(
                        "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                        "GraalVM archive entry size did not match its metadata."
                    );
                }
            }
        } catch (Cancelled cancelled) {
            throw cancelled;
        }
        if (root == null) {
            throw failure("GRAAL_RUNTIME_ARCHIVE_REJECTED", "GraalVM archive was empty.");
        }
        final Path extracted = staging.resolve(root).normalize();
        if (!extracted.startsWith(staging)
            || !Files.isDirectory(extracted, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(
                "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                "GraalVM archive root was unavailable."
            );
        }
        return extracted;
    }

    private Status inspectInstalled() {
        if (platform == null) {
            return Status.unsupported(
                "GraalVM Community automatic installation is unavailable on this platform."
            );
        }
        if (!Files.exists(runtimeHome, LinkOption.NOFOLLOW_LINKS)) return Status.absent();
        try {
            requireDirectoryChain(turboismHome, runtimeHome);
            final Path java = javaExecutable(runtimeHome);
            requireCompatible(java);
            return Status.ready(java);
        } catch (InstallFailure | IOException failure) {
            return Status.failed(
                "GRAAL_RUNTIME_INVALID",
                "The Turboism-managed GraalVM runtime is incomplete or incompatible."
            );
        }
    }

    private void probeHost(final Path javaExecutable) throws InstallFailure {
        final Path libraryRoot = confined(turboismHome.resolve("graal/lib"));
        try {
            requireDirectoryChain(turboismHome, libraryRoot);
        } catch (IOException failure) {
            throw failure(
                "GRAAL_RUNTIME_HOST_PROBE_FAILED",
                "The packaged Graal host library path is unsafe.",
                failure
            );
        }
        if (!Files.isDirectory(libraryRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(
                "GRAAL_RUNTIME_HOST_PROBE_FAILED",
                "The packaged Graal host libraries are unavailable."
            );
        }
        final String classpath = libraryRoot + java.io.File.separator + "*";
        final Process process;
        try {
            final ProcessBuilder builder = new ProcessBuilder(
                javaExecutable.toString(), "-cp", classpath,
                "dev.turboism.graalhost.GraalHostMain"
            );
            for (String key : INHERITED_JAVA_OPTIONS) builder.environment().remove(key);
            process = builder.start();
        } catch (IOException failure) {
            throw failure(
                "GRAAL_RUNTIME_HOST_PROBE_FAILED",
                "Could not start the isolated Graal host probe.",
                failure
            );
        }
        try {
            final CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> {
                try (InputStream input = process.getInputStream()) {
                    return readBoundedLine(input, 64 * 1024);
                } catch (IOException failure) {
                    return "";
                }
            });
            final CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> {
                try (InputStream input = process.getErrorStream()) {
                    return drainBounded(input, 64 * 1024);
                } catch (IOException ignored) {
                    // Process stdout/protocol state remains the authoritative result.
                    return "";
                }
            });
            final String line = stdout.get(PROBE_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!isCompatibleReadyMessage(line)) {
                stderr.getNow("");
                throw failure(
                    "GRAAL_RUNTIME_HOST_PROBE_FAILED",
                    "The isolated Graal host rejected the managed runtime."
                );
            }
        } catch (InstallFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure(
                "GRAAL_RUNTIME_HOST_PROBE_FAILED",
                "The isolated Graal host probe timed out or failed.",
                failure
            );
        } finally {
            destroyTree(process);
        }
    }

    private Path javaExecutable(final Path home) {
        final Platform runtimePlatform = platform == null ? Platform.WINDOWS_X64 : platform;
        return home.resolve(runtimePlatform.javaRelativePath).toAbsolutePath().normalize();
    }

    private static void requireCompatible(final Path java) throws InstallFailure {
        final Path bin = java.getParent();
        final Path home = bin == null ? null : bin.getParent();
        final Path release = home == null ? null : home.resolve("release");
        if (home == null || !java.startsWith(home) || release == null) {
            throw failure("GRAAL_RUNTIME_INVALID", "GraalVM Java executable is unavailable.");
        }
        try {
            requireDirectoryChain(home, bin);
            final BasicFileAttributes javaAttributes = requireOrdinaryRegularFile(java);
            final BasicFileAttributes releaseAttributes = requireOrdinaryRegularFile(release);
            if (releaseAttributes.size() > 64 * 1024L) {
                throw failure("GRAAL_RUNTIME_INVALID", "GraalVM release metadata is too large.");
            }
            final String metadata = Files.readString(release, StandardCharsets.UTF_8);
            requireRegularFileIdentity(release, releaseAttributes);
            requireRegularFileIdentity(java, javaAttributes);
            if (!releaseValue(metadata, "IMPLEMENTOR").orElse("").equals("GraalVM Community")
                || !releaseValue(metadata, "GRAALVM_VERSION").orElse("").equals(GRAAL_VERSION)
                || !releaseValue(metadata, "JAVA_VERSION").orElse("").equals(JAVA_VERSION)) {
                throw failure(
                    "GRAAL_RUNTIME_INVALID",
                    "GraalVM release metadata did not match the pinned runtime."
                );
            }
        } catch (IOException failure) {
            throw failure(
                "GRAAL_RUNTIME_INVALID", "Could not read GraalVM release metadata.", failure
            );
        }
    }

    private static BasicFileAttributes requireOrdinaryRegularFile(final Path path)
        throws IOException, InstallFailure {
        final BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        if (!hasUsableIdentity(attributes) || !attributes.isRegularFile()
            || attributes.isSymbolicLink() || attributes.isOther()
            || isWindowsReparsePoint(path)) {
            throw failure(
                "GRAAL_RUNTIME_INVALID",
                "GraalVM runtime contains a linked or special file."
            );
        }
        return attributes;
    }

    private static void requireRegularFileIdentity(
        final Path path,
        final BasicFileAttributes expected
    ) throws IOException, InstallFailure {
        final BasicFileAttributes current = requireOrdinaryRegularFile(path);
        if (!sameIdentity(expected, current)
            || expected.size() != current.size()) {
            throw failure(
                "GRAAL_RUNTIME_INVALID",
                "GraalVM runtime changed while being validated."
            );
        }
    }

    private static Optional<String> releaseValue(final String metadata, final String key) {
        final String prefix = key + "=";
        return metadata.lines()
            .map(String::trim)
            .filter(line -> line.startsWith(prefix))
            .map(line -> line.substring(prefix.length()).trim())
            .map(value -> value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value)
            .findFirst();
    }

    private void requireOfficialUri(final URI uri) throws InstallFailure {
        final String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || host == null
            || !DOWNLOAD_HOSTS.contains(host.toLowerCase(Locale.ROOT))
            || uri.getUserInfo() != null
            || uri.getFragment() != null) {
            throw failure(
                "GRAAL_RUNTIME_DOWNLOAD_URI_REJECTED",
                "GraalVM download left the approved official HTTPS hosts."
            );
        }
    }

    private static String normalizeEntry(final String raw) throws InstallFailure {
        if (raw == null || raw.isBlank() || raw.length() > 4096 || raw.indexOf('\0') >= 0) {
            throw failure("GRAAL_RUNTIME_ARCHIVE_REJECTED", "GraalVM archive path was invalid.");
        }
        final String value = raw.replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw failure("GRAAL_RUNTIME_ARCHIVE_REJECTED", "GraalVM archive path was absolute.");
        }
        final List<String> clean = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                throw failure("GRAAL_RUNTIME_ARCHIVE_REJECTED", "GraalVM archive path escaped staging.");
            }
            requireUnambiguousWindowsSegment(segment);
            clean.add(segment);
        }
        if (clean.isEmpty()) {
            throw failure("GRAAL_RUNTIME_ARCHIVE_REJECTED", "GraalVM archive path was empty.");
        }
        return String.join("/", clean);
    }

    private static void requireUnambiguousWindowsSegment(final String segment)
        throws InstallFailure {
        if (segment.endsWith(".") || segment.endsWith(" ") || segment.indexOf(':') >= 0) {
            throw failure(
                "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                "GraalVM archive path had an ambiguous Windows segment."
            );
        }
        final int dot = segment.indexOf('.');
        final String base = (dot < 0 ? segment : segment.substring(0, dot))
            .toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_NAMES.contains(base)) {
            throw failure(
                "GRAAL_RUNTIME_ARCHIVE_REJECTED",
                "GraalVM archive path used a reserved Windows device name."
            );
        }
    }

    private Path confined(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(turboismHome)) {
            throw new IllegalArgumentException("managed Graal path escapes Turboism home");
        }
        return normalized;
    }

    private static void requireDirectoryChain(final Path root, final Path path)
        throws IOException, InstallFailure {
        final Path normalizedRoot = root.toAbsolutePath().normalize();
        final Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw failure("GRAAL_RUNTIME_PATH_REJECTED", "Managed runtime path escaped Turboism home.");
        }
        Path current = normalizedRoot;
        requireOrdinaryDirectory(current);
        for (Path segment : normalizedRoot.relativize(normalized)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            requireOrdinaryDirectory(current);
        }
    }

    private static BasicFileAttributes requireOrdinaryDirectory(final Path path)
        throws IOException, InstallFailure {
        final BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()
            || isWindowsReparsePoint(path)) {
            throw failure(
                "GRAAL_RUNTIME_PATH_REJECTED",
                "Managed runtime path contains a link, reparse point, or non-directory."
            );
        }
        return attributes;
    }

    private static void requireDirectoryIdentity(
        final Path path,
        final BasicFileAttributes expected
    ) throws IOException, InstallFailure {
        final BasicFileAttributes current = requireOrdinaryDirectory(path);
        if (!sameIdentity(expected, current)) {
            throw failure(
                "GRAAL_RUNTIME_PATH_REJECTED",
                "Managed runtime directory changed during filesystem mutation."
            );
        }
    }

    private static boolean hasUsableIdentity(final BasicFileAttributes attributes) {
        return hasUsableIdentity(attributes, isWindows());
    }

    static boolean hasUsableIdentity(
        final BasicFileAttributes attributes,
        final boolean windows
    ) {
        return attributes.fileKey() != null || windows;
    }

    private static boolean sameIdentity(
        final BasicFileAttributes expected,
        final BasicFileAttributes current
    ) {
        return sameIdentity(expected, current, isWindows());
    }

    static boolean sameIdentity(
        final BasicFileAttributes expected,
        final BasicFileAttributes current,
        final boolean windows
    ) {
        if (expected.isRegularFile() != current.isRegularFile()
            || expected.isDirectory() != current.isDirectory()
            || expected.isSymbolicLink() != current.isSymbolicLink()
            || expected.isOther() != current.isOther()) return false;
        final Object expectedKey = expected.fileKey();
        final Object currentKey = current.fileKey();
        if (expectedKey != null || currentKey != null) {
            return expectedKey != null && currentKey != null
                && Objects.equals(expectedKey, currentKey);
        }
        if (!windows || !expected.creationTime().equals(current.creationTime())) return false;
        return expected.isDirectory()
            || expected.size() == current.size()
                && expected.lastModifiedTime().equals(current.lastModifiedTime());
    }

    private static boolean isWindowsReparsePoint(final Path path) throws IOException {
        if (!isWindows()) return false;
        try {
            // OpenJDK's Windows provider exposes non-symlink reparse points (including
            // junctions) as BasicFileAttributes.isOther() under NOFOLLOW_LINKS. The DOS
            // attribute view has no portable raw "attributes" field and rejects it on
            // ordinary Windows paths.
            return Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            ).isOther();
        } catch (UnsupportedOperationException | IllegalArgumentException unavailable) {
            throw new IOException("Windows reparse-point inspection was unavailable", unavailable);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void moveAtomically(final Path source, final Path target)
        throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("managed runtime activation requires atomic moves", unsupported);
        }
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        deleteOrdinaryTree(root);
    }

    private static void deleteOrdinaryTree(final Path path) throws IOException {
        final BasicFileAttributes attributes = safeAttributesForDeletion(path);
        if (attributes.isRegularFile()) {
            requireSameIdentity(path, attributes);
            Files.delete(path);
            return;
        }
        if (!attributes.isDirectory()) {
            throw new IOException("refusing to remove special managed runtime entry");
        }
        final List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path child : entries) {
                safeAttributesForDeletion(child);
                children.add(child);
            }
        }
        for (Path child : children) deleteOrdinaryTree(child);
        requireSameIdentity(path, attributes);
        Files.delete(path);
    }

    private static BasicFileAttributes safeAttributesForDeletion(final Path path)
        throws IOException {
        final BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        if (!hasUsableIdentity(attributes)
            || attributes.isSymbolicLink()
            || attributes.isOther()
            || isWindowsReparsePoint(path)
            || !attributes.isDirectory() && !attributes.isRegularFile()) {
            throw new IOException("refusing to remove linked or special managed runtime entry");
        }
        return attributes;
    }

    private static void requireSameIdentity(
        final Path path,
        final BasicFileAttributes expected
    ) throws IOException {
        final BasicFileAttributes current = safeAttributesForDeletion(path);
        if (!sameIdentity(expected, current)) {
            throw new IOException("managed runtime entry changed during removal");
        }
    }

    private static void cleanupEmptyParent(final Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            if (!entries.iterator().hasNext()) Files.deleteIfExists(directory);
        }
    }

    private static void tryDeleteOwnedFile(
        final Path path,
        final BasicFileAttributes expected
    ) {
        if (expected == null) return;
        try {
            requireRegularFileIdentity(path, expected);
            Files.delete(path);
        } catch (IOException | InstallFailure ignored) {
        }
    }

    private static void tryDeleteOwnedMarker(final Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            requireOwnedMarker(path);
            Files.delete(path);
        } catch (IOException ignored) {
        }
    }

    private static void tryDeleteTree(final Path path) {
        try {
            deleteTree(path);
        } catch (IOException ignored) {
        }
    }

    private static void destroyTree(final Process process) {
        if (process == null) return;
        final ProcessHandle handle = process.toHandle();
        handle.descendants().sorted(Comparator.reverseOrder()).forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
        try {
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean isCompatibleReadyMessage(final String line) {
        try {
            final JsonNode ready = PROTOCOL_JSON.readTree(line);
            return ready != null
                && "READY".equals(ready.path("type").asText())
                && ready.path("protocolVersion").asInt(-1) == 1
                && ready.path("graalAvailable").asBoolean(false)
                && JAVA_VERSION.equals(ready.path("javaVersion").asText());
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static String readBoundedLine(final InputStream input, final int maxBytes)
        throws IOException {
        final byte[] buffer = new byte[maxBytes + 1];
        int count = 0;
        while (count < buffer.length) {
            final int value = input.read();
            if (value < 0 || value == '\n') break;
            if (value != '\r') buffer[count++] = (byte) value;
        }
        if (count > maxBytes) return "";
        return new String(buffer, 0, count, StandardCharsets.UTF_8);
    }

    private static String drainBounded(final InputStream input, final int captureBytes)
        throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE];
        final ByteArrayOutputStream retained = new ByteArrayOutputStream(captureBytes);
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (read == 0) continue;
            final int remaining = captureBytes - retained.size();
            if (remaining > 0) retained.write(buffer, 0, Math.min(read, remaining));
        }
        return retained.toString(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void closeQuietly(final InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private void report(final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("managed Graal runtime service is closed");
    }

    private static String safeMessage(final Throwable failure) {
        final String raw = failure == null ? "" : Objects.toString(failure.getMessage(), "");
        final String normalized = raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isEmpty()) return failure == null ? "unknown error" : failure.getClass().getSimpleName();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    private static InstallFailure failure(final String code, final String message) {
        return new InstallFailure(code, message, null);
    }

    private static InstallFailure failure(
        final String code,
        final String message,
        final Throwable cause
    ) {
        return new InstallFailure(code, message, cause);
    }

    /** Lifecycle state reported for the Turboism-managed runtime. */
    public enum State {
        ABSENT,
        DOWNLOADING,
        EXTRACTING,
        VERIFYING,
        READY,
        FAILED,
        CANCELLED,
        UNSUPPORTED
    }

    /** Immutable point-in-time state, progress, and terminal detail for the managed runtime. */
    public record Status(
        State state,
        String version,
        String javaVersion,
        Optional<Path> javaExecutable,
        long completedBytes,
        long totalBytes,
        String code,
        String message
    ) {
        public Status {
            state = Objects.requireNonNull(state, "state");
            version = Objects.requireNonNullElse(version, "");
            javaVersion = Objects.requireNonNullElse(javaVersion, "");
            javaExecutable = Objects.requireNonNull(javaExecutable, "javaExecutable");
            code = Objects.requireNonNullElse(code, "");
            message = Objects.requireNonNullElse(message, "");
            if (completedBytes < 0L || totalBytes < 0L || completedBytes > totalBytes) {
                throw new IllegalArgumentException("invalid managed runtime progress");
            }
        }

        static Status absent() {
            return new Status(State.ABSENT, GRAAL_VERSION, JAVA_VERSION, Optional.empty(), 0L, 0L, "", "");
        }

        static Status ready(final Path java) {
            return new Status(
                State.READY, GRAAL_VERSION, JAVA_VERSION, Optional.of(java), 0L, 0L, "",
                "GraalVM Community " + GRAAL_VERSION + " is ready."
            );
        }

        static Status failed(final String code, final String message) {
            return new Status(State.FAILED, GRAAL_VERSION, JAVA_VERSION, Optional.empty(), 0L, 0L, code, message);
        }

        static Status unsupported(final String message) {
            return new Status(State.UNSUPPORTED, GRAAL_VERSION, JAVA_VERSION, Optional.empty(), 0L, 0L, "GRAAL_RUNTIME_PLATFORM_UNSUPPORTED", message);
        }
    }

    /** Handle for one asynchronous managed-runtime installation. */
    public final class Operation {
        private final Manifest manifest;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final AtomicBoolean committing = new AtomicBoolean(false);
        private final CompletableFuture<Status> result = new CompletableFuture<>();
        private volatile Status status;
        private volatile Thread thread;

        private Operation(final Manifest manifest) {
            this.manifest = manifest;
            this.status = Status.absent();
        }

        private Operation(final Status completed) {
            this.manifest = null;
            this.status = completed;
            this.result.complete(completed);
        }

        /** @return the latest immutable operation snapshot */
        public Status status() {
            return status;
        }

        /** @return the terminal operation result */
        public CompletionStage<Status> completion() {
            return result;
        }

        /** @return true only when this call requested cancellation */
        public boolean cancel() {
            synchronized (this) {
                if (result.isDone() || committing.get()
                    || !cancelRequested.compareAndSet(false, true)) return false;
            }
            final Thread current = thread;
            if (current != null) current.interrupt();
            return true;
        }

        private void beginCommit() throws Cancelled {
            synchronized (this) {
                if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                    throw new Cancelled();
                }
                committing.set(true);
            }
        }

        private boolean started() {
            return thread != null;
        }

        private boolean committing() {
            return committing.get();
        }

        private void cancelBeforeStart() {
            if (thread == null && cancelRequested.get()) cancelled();
        }

        private void attachThread(final Thread current) {
            thread = current;
            if (cancelRequested.get()) current.interrupt();
        }

        private void detachThread(final Thread current) {
            if (thread == current) thread = null;
        }

        private void phase(
            final State state,
            final long completedBytes,
            final long totalBytes,
            final String message
        ) {
            status = new Status(
                state, GRAAL_VERSION, JAVA_VERSION, Optional.empty(), completedBytes,
                totalBytes, "", message
            );
        }

        private void checkCancelled() throws Cancelled {
            if (cancelRequested.get() || Thread.currentThread().isInterrupted()) throw new Cancelled();
        }

        private void succeed(final Path java) {
            finish(Status.ready(java));
        }

        private void fail(final String code, final String message) {
            finish(Status.failed(code, message));
        }

        private void cancelled() {
            finish(new Status(
                State.CANCELLED, GRAAL_VERSION, JAVA_VERSION, Optional.empty(), 0L, 0L,
                "GRAAL_RUNTIME_CANCELLED", "GraalVM installation was cancelled."
            ));
        }

        private void finish(final Status terminal) {
            status = terminal;
            result.complete(terminal);
        }
    }

    private Operation completedOperation(final Status status) {
        return new Operation(status);
    }

    static final class Platform {
        static final Platform WINDOWS_X64 = new Platform(
            "windows-x64",
            "bin/java.exe",
            "graalvm-community-jdk-25i2-25.0.4_windows-x64_bin.zip",
            341_299_924L,
            "789d2af1c06c3c24f402d2d4a711bdbb19b36f7d8c74afe6a959492fd121ef33"
        );

        private final String id;
        private final String javaRelativePath;
        private final String archiveName;
        private final long archiveBytes;
        private final String sha256;

        Platform(
            final String id,
            final String javaRelativePath,
            final String archiveName,
            final long archiveBytes,
            final String sha256
        ) {
            this.id = Objects.requireNonNull(id, "id");
            this.javaRelativePath = Objects.requireNonNull(javaRelativePath, "javaRelativePath");
            this.archiveName = Objects.requireNonNull(archiveName, "archiveName");
            this.archiveBytes = archiveBytes;
            this.sha256 = Objects.requireNonNull(sha256, "sha256");
        }

        Manifest manifest() {
            return new Manifest(
                URI.create(
                    "https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-"
                        + GRAAL_VERSION + "/" + archiveName
                ), archiveBytes, sha256
            );
        }

        static Platform detect() {
            final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            return os.contains("win") && ("amd64".equals(arch) || "x86_64".equals(arch))
                ? WINDOWS_X64
                : null;
        }
    }

    record Manifest(URI uri, long archiveBytes, String sha256) {
    }

    private static final class InstallFailure extends Exception {
        private final String code;

        private InstallFailure(final String code, final String message, final Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    private static final class Cancelled extends RuntimeException {
    }

    private static final class InstallerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable, "turboism-graal-runtime-installer");
            thread.setDaemon(true);
            return thread;
        }
    }
}

package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.PathLocks;
import dev.turboism.runtime.exec.RuntimeThreads;
import dev.turboism.runtime.json.RuntimeJson;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persists per-ArtMesh mirror axis lock state, angle, and pivot position under
 * {@code <runtimeDataDir>/artmesh-mirror-axes/<sha256(modelGuid)>.json}.
 */
final class ArtMeshMirrorAxisStore implements AutoCloseable {
    private static final String DIRECTORY_NAME = "artmesh-mirror-axes";
    private static final String FILE_EXTENSION = ".json";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String LOG_CATEGORY = "artmesh-mirror";
    private static final String WRITER_THREAD_NAME = RuntimeThreads.THREAD_NAME_PREFIX + "-artmesh-mirror-axis-store";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final long FLUSH_TIMEOUT_MILLIS = 3_000L;

    record SavedAxis(float pivotX, float pivotY, double angle, boolean locked) {
        SavedAxis {
            if (!Float.isFinite(pivotX) || !Float.isFinite(pivotY) || !Double.isFinite(angle)) {
                throw new IllegalArgumentException("non-finite saved axis");
            }
        }
    }

    private record ModelAxesDocument(int schemaVersion, String modelGuid, Map<String, SavedAxis> axes) {}

    private final Path directory;
    private final Object lock = new Object();
    private final Map<String, Map<String, SavedAxis>> cache = new HashMap<>();
    private final Map<String, Map<String, SavedAxis>> pendingSnapshots = new HashMap<>();
    private final Set<String> scheduledModels = new HashSet<>();
    private ExecutorService writerExecutor;
    private boolean closed;

    private ArtMeshMirrorAxisStore(final Path directory) {
        this.directory = directory;
    }

    static ArtMeshMirrorAxisStore at(final Path runtimeDataDir) {
        Objects.requireNonNull(runtimeDataDir, "runtimeDataDir");
        return new ArtMeshMirrorAxisStore(
                runtimeDataDir.toAbsolutePath().normalize().resolve(DIRECTORY_NAME));
    }

    SavedAxis get(final String modelGuid, final String artMeshGuid) {
        final String model = normalizeKey(modelGuid);
        final String mesh = normalizeKey(artMeshGuid);
        if (model == null || mesh == null) {
            return null;
        }
        synchronized (lock) {
            return modelCacheLocked(model).get(mesh);
        }
    }

    void put(final String modelGuid, final String artMeshGuid, final SavedAxis saved) {
        Objects.requireNonNull(saved, "saved");
        final String model = normalizeKey(modelGuid);
        final String mesh = normalizeKey(artMeshGuid);
        if (model == null || mesh == null) {
            return;
        }
        synchronized (lock) {
            final Map<String, SavedAxis> axes = modelCacheLocked(model);
            final SavedAxis previous = axes.put(mesh, saved);
            if (!saved.equals(previous)) {
                scheduleWriteLocked(model, Map.copyOf(axes));
            }
        }
    }

    void remove(final String modelGuid, final String artMeshGuid) {
        final String model = normalizeKey(modelGuid);
        final String mesh = normalizeKey(artMeshGuid);
        if (model == null || mesh == null) {
            return;
        }
        synchronized (lock) {
            final Map<String, SavedAxis> axes = modelCacheLocked(model);
            if (axes.remove(mesh) != null) {
                scheduleWriteLocked(model, Map.copyOf(axes));
            }
        }
    }

    void flush() {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FLUSH_TIMEOUT_MILLIS);
        synchronized (lock) {
            while (!scheduledModels.isEmpty()) {
                final long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    RuntimeDiagnostics.warn(
                            LOG_CATEGORY, "Timed out waiting for ArtMesh mirror axis persistence flush.");
                    break;
                }
                final long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                try {
                    lock.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public void close() {
        flush();
        final ExecutorService executor;
        synchronized (lock) {
            closed = true;
            executor = writerExecutor;
            writerExecutor = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Map<String, SavedAxis> modelCacheLocked(final String model) {
        return cache.computeIfAbsent(model, this::readModel);
    }

    private void scheduleWriteLocked(final String model, final Map<String, SavedAxis> snapshot) {
        if (closed) {
            return;
        }
        pendingSnapshots.put(model, snapshot);
        if (scheduledModels.add(model)) {
            writerExecutorLocked().execute(() -> drainWrite(model));
        }
    }

    private ExecutorService writerExecutorLocked() {
        if (writerExecutor == null) {
            writerExecutor = RuntimeThreads.serialLane(RuntimeThreads.daemonThreadsWithFixedName(WRITER_THREAD_NAME));
        }
        return writerExecutor;
    }

    private void drainWrite(final String model) {
        while (true) {
            final Map<String, SavedAxis> snapshot;
            synchronized (lock) {
                snapshot = pendingSnapshots.remove(model);
                if (snapshot == null) {
                    scheduledModels.remove(model);
                    lock.notifyAll();
                    return;
                }
            }
            try {
                writeModel(model, snapshot);
            } catch (IOException | RuntimeException failure) {
                RuntimeDiagnostics.warn(LOG_CATEGORY, "Cannot save ArtMesh mirror axis state: " + failure.getMessage());
            }
        }
    }

    private Map<String, SavedAxis> readModel(final String model) {
        final Path file = fileForModel(model);
        final Object pathLock = PathLocks.forPath(file);
        synchronized (pathLock) {
            try {
                rejectLink(directory);
                rejectLink(file);
                if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                    return new LinkedHashMap<>();
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    RuntimeDiagnostics.warn(LOG_CATEGORY, "ArtMesh mirror axis file is not a regular file.");
                    return new LinkedHashMap<>();
                }
                final byte[] bytes;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = input.readNBytes(MAX_FILE_BYTES + 1);
                }
                if (bytes.length > MAX_FILE_BYTES) {
                    RuntimeDiagnostics.warn(LOG_CATEGORY, "ArtMesh mirror axis file exceeds size limit.");
                    return new LinkedHashMap<>();
                }
                final ModelAxesDocument document = RuntimeJson.strict().readValue(bytes, ModelAxesDocument.class);
                if (document == null
                        || document.schemaVersion() != SCHEMA_VERSION
                        || !model.equals(document.modelGuid())
                        || document.axes() == null) {
                    RuntimeDiagnostics.warn(LOG_CATEGORY, "Ignoring invalid ArtMesh mirror axis document.");
                    return new LinkedHashMap<>();
                }
                final Map<String, SavedAxis> validated = new LinkedHashMap<>();
                for (Map.Entry<String, SavedAxis> entry : document.axes().entrySet()) {
                    final String mesh = normalizeKey(entry.getKey());
                    final SavedAxis value = entry.getValue();
                    if (mesh != null && value != null) {
                        validated.put(mesh, value);
                    }
                }
                return validated;
            } catch (IOException | RuntimeException failure) {
                RuntimeDiagnostics.warn(LOG_CATEGORY, "Cannot read ArtMesh mirror axis state: " + failure.getMessage());
                return new LinkedHashMap<>();
            }
        }
    }

    private void writeModel(final String model, final Map<String, SavedAxis> snapshot) throws IOException {
        final Path file = fileForModel(model);
        final Object pathLock = PathLocks.forPath(file);
        synchronized (pathLock) {
            rejectLink(directory);
            rejectLink(file);
            if (snapshot.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(directory);
            final byte[] bytes =
                    RuntimeJson.strict().writeValueAsBytes(new ModelAxesDocument(SCHEMA_VERSION, model, snapshot));
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException("ArtMesh mirror axis document exceeds size limit.");
            }
            DurableFile.at(file)
                    .guardedBy((staged, target) -> rejectLink(target))
                    .replace(bytes);
        }
    }

    Path fileForModel(final String modelGuid) {
        return directory.resolve(sha256Hex(modelGuid) + FILE_EXTENSION);
    }

    private static void rejectLink(final Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Symbolic links are forbidden for ArtMesh mirror axis storage.");
        }
    }

    private static String normalizeKey(final String value) {
        if (value == null) {
            return null;
        }
        final String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String sha256Hex(final String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            final byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}

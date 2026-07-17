package dev.turboism.storage;

import dev.turboism.failure.RuntimeFailure;
import dev.turboism.failure.RuntimeFailureDomain;
import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageError;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.cleanup.CleanupEvidenceCollector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Plugin-facing permission facade over confined storage and runtime-owned I/O. */
public final class RuntimePluginStorage implements PluginStorage, AutoCloseable {

    private final String pluginId;
    private final Set<String> permissions;
    private final ConfinedStorageBackend backend;
    private final StorageIoExecutor io;
    private final RuntimeFailureSink failureSink;

    public RuntimePluginStorage(
        final String pluginId,
        final Map<StorageRoot, Path> roots,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler taskScheduler,
        final DisposableScope disposableScope
    ) throws IOException {
        this(
            pluginId,
            roots,
            permissions,
            taskScheduler,
            disposableScope,
            new CleanupEvidenceCollector(),
            RuntimeFailureSink.noop()
        );
    }

    public RuntimePluginStorage(
        final String pluginId,
        final Map<StorageRoot, Path> roots,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler taskScheduler,
        final DisposableScope disposableScope,
        final CleanupEvidenceCollector cleanupEvidence
    ) throws IOException {
        this(
            pluginId,
            roots,
            permissions,
            taskScheduler,
            disposableScope,
            cleanupEvidence,
            RuntimeFailureSink.noop()
        );
    }

    public RuntimePluginStorage(
        final String pluginId,
        final Map<StorageRoot, Path> roots,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler taskScheduler,
        final DisposableScope disposableScope,
        final CleanupEvidenceCollector cleanupEvidence,
        final RuntimeFailureSink failureSink
    ) throws IOException {
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        this.backend = new ConfinedStorageBackend(roots, cleanupEvidence);
        this.failureSink = RuntimeFailureSink.require(failureSink);
        this.io = new StorageIoExecutor(
            this.pluginId,
            taskScheduler,
            disposableScope
        );
    }

    @Override
    public CompletionStage<StorageReadResult<String>> readUtf8(
        final StoragePath path,
        final int maxBytes
    ) {
        requireNonNegative(maxBytes, "maxBytes");
        if (!has(PermissionIds.TURBOISM_FILE_READ)) {
            return io.immediate(readFailure(
                path,
                StorageErrorCode.PERMISSION_DENIED,
                "storage.readUtf8"
            ));
        }
        if (maxBytes > ConfinedStorageBackend.MAX_OPERATION_BYTES) {
            return io.immediate(readFailure(
                path,
                StorageErrorCode.SIZE_LIMIT_EXCEEDED,
                "storage.readUtf8"
            ));
        }
        return io.submit(
            () -> observe(backend.readUtf8(path, maxBytes), "storage.readUtf8"),
            () -> readFailure(path, StorageErrorCode.CANCELED, "storage.readUtf8"),
            () -> readFailure(
                path,
                StorageErrorCode.RUNTIME_UNAVAILABLE,
                "storage.readUtf8"
            )
        );
    }

    @Override
    public CompletionStage<StorageReadResult<byte[]>> readBytes(
        final StoragePath path,
        final int maxBytes
    ) {
        requireNonNegative(maxBytes, "maxBytes");
        if (!has(PermissionIds.TURBOISM_FILE_READ)) {
            return io.immediate(readFailure(
                path,
                StorageErrorCode.PERMISSION_DENIED,
                "storage.readBytes"
            ));
        }
        if (maxBytes > ConfinedStorageBackend.MAX_OPERATION_BYTES) {
            return io.immediate(readFailure(
                path,
                StorageErrorCode.SIZE_LIMIT_EXCEEDED,
                "storage.readBytes"
            ));
        }
        return io.submit(
            () -> observe(backend.readBytes(path, maxBytes), "storage.readBytes"),
            () -> readFailure(path, StorageErrorCode.CANCELED, "storage.readBytes"),
            () -> readFailure(
                path,
                StorageErrorCode.RUNTIME_UNAVAILABLE,
                "storage.readBytes"
            )
        );
    }

    @Override
    public CompletionStage<StorageWriteResult> writeUtf8Atomic(
        final StoragePath path,
        final String content
    ) {
        Objects.requireNonNull(content, "content");
        return writeBytesAtomic(path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CompletionStage<StorageWriteResult> writeBytesAtomic(
        final StoragePath path,
        final byte[] content
    ) {
        Objects.requireNonNull(path, "path");
        final byte[] snapshot = Objects.requireNonNull(content, "content").clone();
        if (!has(PermissionIds.TURBOISM_FILE_WRITE)) {
            return io.immediate(writeFailure(
                path,
                StorageErrorCode.PERMISSION_DENIED,
                "storage.writeBytesAtomic"
            ));
        }
        if (snapshot.length > ConfinedStorageBackend.MAX_OPERATION_BYTES) {
            return io.immediate(writeFailure(
                path,
                StorageErrorCode.SIZE_LIMIT_EXCEEDED,
                "storage.writeBytesAtomic"
            ));
        }
        return io.submit(
            () -> observe(
                backend.writeBytesAtomic(path, snapshot, true),
                "storage.writeBytesAtomic"
            ),
            () -> writeFailure(path, StorageErrorCode.CANCELED, "storage.writeBytesAtomic"),
            () -> writeFailure(
                path,
                StorageErrorCode.RUNTIME_UNAVAILABLE,
                "storage.writeBytesAtomic"
            )
        );
    }

    @Override
    public CompletionStage<StorageListResult> list(
        final StoragePath directory,
        final int maxEntries
    ) {
        requireNonNegative(maxEntries, "maxEntries");
        if (maxEntries > ConfinedStorageBackend.MAX_LIST_ENTRIES) {
            throw new IllegalArgumentException("maxEntries exceeds runtime limit");
        }
        if (!has(PermissionIds.TURBOISM_FILE_READ)) {
            return io.immediate(listFailure(
                directory,
                StorageErrorCode.PERMISSION_DENIED,
                "storage.list"
            ));
        }
        return io.submit(
            () -> observe(backend.list(directory, maxEntries), "storage.list"),
            () -> listFailure(directory, StorageErrorCode.CANCELED, "storage.list"),
            () -> listFailure(
                directory,
                StorageErrorCode.RUNTIME_UNAVAILABLE,
                "storage.list"
            )
        );
    }

    @Override
    public CompletionStage<StorageMutationResult> copy(
        final StoragePath source,
        final StoragePath target,
        final boolean replaceExisting
    ) {
        requirePaths(source, target);
        if (!canReadAndWrite()) {
            return io.immediate(mutationFailure(
                target,
                StorageErrorCode.PERMISSION_DENIED,
                "storage.copy",
                !has(PermissionIds.TURBOISM_FILE_READ)
            ));
        }
        return io.submit(
            () -> observe(backend.copy(source, target, replaceExisting), "storage.copy"),
            () -> mutationFailure(
                target,
                StorageErrorCode.CANCELED,
                "storage.copy",
                true
            ),
            () -> mutationFailure(
                target,
                StorageErrorCode.RUNTIME_UNAVAILABLE,
                "storage.copy",
                true
            )
        );
    }

    @Override
    public CompletionStage<StorageMutationResult> moveAtomic(
        final StoragePath source,
        final StoragePath target,
        final boolean replaceExisting
    ) {
        requirePaths(source, target);
        if (!has(PermissionIds.TURBOISM_FILE_WRITE)) {
            return io.immediate(mutationFailure(
                target,
                StorageErrorCode.PERMISSION_DENIED,
                "storage.moveAtomic",
                false
            ));
        }
        if (source.root() != target.root()) {
            return io.immediate(mutationFailure(
                target,
                StorageErrorCode.CROSS_ROOT_ATOMIC_MOVE_UNSUPPORTED,
                "storage.moveAtomic",
                false
            ));
        }
        return io.submit(
            () -> observe(
                backend.moveAtomic(source, target, replaceExisting),
                "storage.moveAtomic"
            ),
            () -> mutationFailure(
                target,
                StorageErrorCode.CANCELED,
                "storage.moveAtomic",
                false
            ),
            () -> mutationFailure(
                target,
                StorageErrorCode.RUNTIME_UNAVAILABLE,
                "storage.moveAtomic",
                false
            )
        );
    }

    @Override
    public CompletionStage<StorageMutationResult> delete(
        final StoragePath path,
        final boolean recursive
    ) {
        Objects.requireNonNull(path, "path");
        if (!has(PermissionIds.TURBOISM_FILE_WRITE)) {
            return io.immediate(mutationFailure(
                path,
                StorageErrorCode.PERMISSION_DENIED,
                "storage.delete",
                false
            ));
        }
        return io.submit(
            () -> observe(backend.delete(path, recursive), "storage.delete"),
            () -> mutationFailure(
                path,
                StorageErrorCode.CANCELED,
                "storage.delete",
                false
            ),
            () -> mutationFailure(
                path,
                StorageErrorCode.RUNTIME_UNAVAILABLE,
                "storage.delete",
                false
            )
        );
    }

    @Override
    public void close() {
        io.close();
    }

    private boolean has(final String permissionId) {
        return permissions.contains(permissionId);
    }

    private boolean canReadAndWrite() {
        return has(PermissionIds.TURBOISM_FILE_READ)
            && has(PermissionIds.TURBOISM_FILE_WRITE);
    }

    private static void requirePaths(
        final StoragePath source,
        final StoragePath target
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }

    private static int requireNonNegative(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private <T> StorageReadResult<T> observe(
        final StorageReadResult<T> result,
        final String operationId
    ) {
        result.error().ifPresent(error -> record(error, operationId, null));
        return result;
    }

    private StorageWriteResult observe(
        final StorageWriteResult result,
        final String operationId
    ) {
        result.error().ifPresent(error -> record(error, operationId, null));
        return result;
    }

    private StorageListResult observe(
        final StorageListResult result,
        final String operationId
    ) {
        result.error().ifPresent(error -> record(error, operationId, null));
        return result;
    }

    private StorageMutationResult observe(
        final StorageMutationResult result,
        final String operationId
    ) {
        result.error().ifPresent(error -> record(error, operationId, null));
        return result;
    }

    private StorageError error(
        final StoragePath path,
        final StorageErrorCode code,
        final String operationId,
        final String permissionId
    ) {
        final StorageError error = new StorageError(
            code,
            message(code),
            Objects.requireNonNull(path, "path")
        );
        record(error, operationId, permissionId);
        return error;
    }

    private void record(
        final StorageError error,
        final String operationId,
        final String permissionId
    ) {
        failureSink.record(RuntimeFailureDomain.STORAGE, new RuntimeFailure(
            error.code().name(),
            "ERROR",
            "storage",
            pluginId,
            operationId,
            permissionId,
            error.message(),
            null,
            1
        ));
    }

    private static String message(final StorageErrorCode code) {
        return switch (code) {
            case PERMISSION_DENIED -> "Storage permission was denied.";
            case SIZE_LIMIT_EXCEEDED -> "Storage operation exceeds the size limit.";
            case CROSS_ROOT_ATOMIC_MOVE_UNSUPPORTED -> "Atomic move across storage roots is unsupported.";
            case CANCELED -> "Storage operation was canceled.";
            case RUNTIME_UNAVAILABLE -> "Storage runtime is unavailable.";
            default -> "Storage operation failed safely.";
        };
    }

    private <T> StorageReadResult<T> readFailure(
        final StoragePath path,
        final StorageErrorCode code,
        final String operationId
    ) {
        return new StorageReadResult<>(
            Optional.empty(),
            Optional.of(error(path, code, operationId, permissionFor(code, true, false))),
            false
        );
    }

    private StorageWriteResult writeFailure(
        final StoragePath path,
        final StorageErrorCode code,
        final String operationId
    ) {
        return new StorageWriteResult(
            false,
            Optional.of(error(path, code, operationId, permissionFor(code, false, true)))
        );
    }

    private StorageListResult listFailure(
        final StoragePath path,
        final StorageErrorCode code,
        final String operationId
    ) {
        return new StorageListResult(
            List.of(),
            Optional.of(error(path, code, operationId, permissionFor(code, true, false))),
            false
        );
    }

    private StorageMutationResult mutationFailure(
        final StoragePath path,
        final StorageErrorCode code,
        final String operationId,
        final boolean requiresRead
    ) {
        return new StorageMutationResult(
            false,
            Optional.of(error(
                path,
                code,
                operationId,
                permissionFor(code, requiresRead, true)
            ))
        );
    }

    private static String permissionFor(
        final StorageErrorCode code,
        final boolean requiresRead,
        final boolean requiresWrite
    ) {
        if (code != StorageErrorCode.PERMISSION_DENIED) {
            return null;
        }
        if (requiresRead) {
            return PermissionIds.TURBOISM_FILE_READ;
        }
        return requiresWrite ? PermissionIds.TURBOISM_FILE_WRITE : null;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

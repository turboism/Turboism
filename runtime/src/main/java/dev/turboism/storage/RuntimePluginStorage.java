package dev.turboism.storage;

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

    private final Set<String> permissions;
    private final ConfinedStorageBackend backend;
    private final StorageIoExecutor io;

    public RuntimePluginStorage(
        final String pluginId,
        final Map<StorageRoot, Path> roots,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler taskScheduler,
        final DisposableScope disposableScope
    ) throws IOException {
        this(pluginId, roots, permissions, taskScheduler, disposableScope,
            new CleanupEvidenceCollector());
    }

    public RuntimePluginStorage(
        final String pluginId,
        final Map<StorageRoot, Path> roots,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler taskScheduler,
        final DisposableScope disposableScope,
        final CleanupEvidenceCollector cleanupEvidence
    ) throws IOException {
        this.permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        this.backend = new ConfinedStorageBackend(roots, cleanupEvidence);
        this.io = new StorageIoExecutor(
            pluginId,
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
            return io.immediate(readFailure(path, StorageErrorCode.PERMISSION_DENIED));
        }
        if (maxBytes > ConfinedStorageBackend.MAX_OPERATION_BYTES) {
            return io.immediate(readFailure(path, StorageErrorCode.SIZE_LIMIT_EXCEEDED));
        }
        return io.submit(
            () -> backend.readUtf8(path, maxBytes),
            () -> readFailure(path, StorageErrorCode.CANCELED),
            () -> readFailure(path, StorageErrorCode.RUNTIME_UNAVAILABLE)
        );
    }

    @Override
    public CompletionStage<StorageReadResult<byte[]>> readBytes(
        final StoragePath path,
        final int maxBytes
    ) {
        requireNonNegative(maxBytes, "maxBytes");
        if (!has(PermissionIds.TURBOISM_FILE_READ)) {
            return io.immediate(readFailure(path, StorageErrorCode.PERMISSION_DENIED));
        }
        if (maxBytes > ConfinedStorageBackend.MAX_OPERATION_BYTES) {
            return io.immediate(readFailure(path, StorageErrorCode.SIZE_LIMIT_EXCEEDED));
        }
        return io.submit(
            () -> backend.readBytes(path, maxBytes),
            () -> readFailure(path, StorageErrorCode.CANCELED),
            () -> readFailure(path, StorageErrorCode.RUNTIME_UNAVAILABLE)
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
            return io.immediate(writeFailure(path, StorageErrorCode.PERMISSION_DENIED));
        }
        if (snapshot.length > ConfinedStorageBackend.MAX_OPERATION_BYTES) {
            return io.immediate(writeFailure(path, StorageErrorCode.SIZE_LIMIT_EXCEEDED));
        }
        return io.submit(
            () -> backend.writeBytesAtomic(path, snapshot, true),
            () -> writeFailure(path, StorageErrorCode.CANCELED),
            () -> writeFailure(path, StorageErrorCode.RUNTIME_UNAVAILABLE)
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
            return io.immediate(listFailure(directory, StorageErrorCode.PERMISSION_DENIED));
        }
        return io.submit(
            () -> backend.list(directory, maxEntries),
            () -> listFailure(directory, StorageErrorCode.CANCELED),
            () -> listFailure(directory, StorageErrorCode.RUNTIME_UNAVAILABLE)
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
            return io.immediate(mutationFailure(target, StorageErrorCode.PERMISSION_DENIED));
        }
        return io.submit(
            () -> backend.copy(source, target, replaceExisting),
            () -> mutationFailure(target, StorageErrorCode.CANCELED),
            () -> mutationFailure(target, StorageErrorCode.RUNTIME_UNAVAILABLE)
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
            return io.immediate(mutationFailure(target, StorageErrorCode.PERMISSION_DENIED));
        }
        if (source.root() != target.root()) {
            return io.immediate(mutationFailure(
                target,
                StorageErrorCode.CROSS_ROOT_ATOMIC_MOVE_UNSUPPORTED
            ));
        }
        return io.submit(
            () -> backend.moveAtomic(source, target, replaceExisting),
            () -> mutationFailure(target, StorageErrorCode.CANCELED),
            () -> mutationFailure(target, StorageErrorCode.RUNTIME_UNAVAILABLE)
        );
    }

    @Override
    public CompletionStage<StorageMutationResult> delete(
        final StoragePath path,
        final boolean recursive
    ) {
        Objects.requireNonNull(path, "path");
        if (!has(PermissionIds.TURBOISM_FILE_WRITE)) {
            return io.immediate(mutationFailure(path, StorageErrorCode.PERMISSION_DENIED));
        }
        return io.submit(
            () -> backend.delete(path, recursive),
            () -> mutationFailure(path, StorageErrorCode.CANCELED),
            () -> mutationFailure(path, StorageErrorCode.RUNTIME_UNAVAILABLE)
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

    private static StorageError error(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageError(code, message(code), Objects.requireNonNull(path, "path"));
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

    private static <T> StorageReadResult<T> readFailure(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageReadResult<>(Optional.empty(), Optional.of(error(path, code)), false);
    }

    private static StorageWriteResult writeFailure(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageWriteResult(false, Optional.of(error(path, code)));
    }

    private static StorageListResult listFailure(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageListResult(List.of(), Optional.of(error(path, code)), false);
    }

    private static StorageMutationResult mutationFailure(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageMutationResult(false, Optional.of(error(path, code)));
    }
}

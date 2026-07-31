package dev.turboism.storage;

import dev.turboism.cleanup.CleanupEvidenceCollector;

import dev.turboism.sdk.storage.StorageEntry;
import dev.turboism.sdk.storage.StorageError;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Synchronous path-confinement and atomic filesystem implementation. */
final class ConfinedStorageBackend {

    static final long MAX_OPERATION_BYTES = 8L * 1024L * 1024L;
    static final int MAX_LIST_ENTRIES = 10_000;
    private static final long ROOT_QUOTA_BYTES = 64L * 1024L * 1024L;

    private final Map<StorageRoot, Path> roots;
    private final CleanupEvidenceCollector cleanupEvidence;
    private final AtomicMover atomicMover;
    private final TemporaryFileDeleter temporaryFileDeleter;
    private final DeleteLimits deleteLimits;

    ConfinedStorageBackend(final Map<StorageRoot, Path> roots) throws IOException {
        this(
            roots,
            new CleanupEvidenceCollector(),
            StorageAtomicMover::move,
            Files::deleteIfExists,
            DeleteLimits.defaults()
        );
    }

    ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final CleanupEvidenceCollector cleanupEvidence
    ) throws IOException {
        this(
            roots,
            cleanupEvidence,
            StorageAtomicMover::move,
            Files::deleteIfExists,
            DeleteLimits.defaults()
        );
    }

    ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final DeleteLimits deleteLimits
    ) throws IOException {
        this(
            roots,
            new CleanupEvidenceCollector(),
            StorageAtomicMover::move,
            Files::deleteIfExists,
            deleteLimits
        );
    }

    ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final CleanupEvidenceCollector cleanupEvidence,
        final AtomicMover atomicMover
    ) throws IOException {
        this(
            roots,
            cleanupEvidence,
            atomicMover,
            Files::deleteIfExists,
            DeleteLimits.defaults()
        );
    }

    ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final CleanupEvidenceCollector cleanupEvidence,
        final AtomicMover atomicMover,
        final TemporaryFileDeleter temporaryFileDeleter
    ) throws IOException {
        this(
            roots,
            cleanupEvidence,
            atomicMover,
            temporaryFileDeleter,
            DeleteLimits.defaults()
        );
    }

    private ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final CleanupEvidenceCollector cleanupEvidence,
        final AtomicMover atomicMover,
        final TemporaryFileDeleter temporaryFileDeleter,
        final DeleteLimits deleteLimits
    ) throws IOException {
        this.cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        this.atomicMover = Objects.requireNonNull(atomicMover, "atomicMover");
        this.temporaryFileDeleter = Objects.requireNonNull(
            temporaryFileDeleter,
            "temporaryFileDeleter"
        );
        this.deleteLimits = Objects.requireNonNull(deleteLimits, "deleteLimits");
        this.roots = validateRoots(roots);
    }

    StorageReadResult<String> readUtf8(
        final StoragePath path,
        final int maxBytes
    ) {
        final StorageReadResult<byte[]> bytes = readBytes(path, maxBytes);
        if (bytes.error().isPresent()) {
            return new StorageReadResult<>(Optional.empty(), bytes.error(), false);
        }
        try {
            final String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.value().orElseThrow()))
                .toString();
            return new StorageReadResult<>(
                Optional.of(value),
                Optional.empty(),
                bytes.truncated()
            );
        } catch (CharacterCodingException exception) {
            return readFailure(path, StorageErrorCode.IO_FAILURE);
        }
    }

    StorageReadResult<byte[]> readBytes(
        final StoragePath path,
        final int maxBytes
    ) {
        try {
            checkCanceled();
            final Path target = resolveExisting(path);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return readFailure(path, StorageErrorCode.TYPE_MISMATCH);
            }
            try (InputStream input = Files.newInputStream(
                target,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
            )) {
                return readBounded(input, maxBytes);
            }
        } catch (StorageFault failure) {
            return readFailure(path, failure.code);
        } catch (SecurityException exception) {
            return readFailure(path, StorageErrorCode.IO_FAILURE);
        } catch (NoSuchFileException exception) {
            return readFailure(path, StorageErrorCode.NOT_FOUND);
        } catch (IOException exception) {
            return readFailure(path, StorageErrorCode.IO_FAILURE);
        }
    }

    StorageWriteResult writeBytesAtomic(
        final StoragePath path,
        final byte[] content,
        final boolean replaceExisting
    ) {
        try (StorageMutationLocks.LockScope ignored = acquireMutationLocks(path.root())) {
            return writeBytesAtomicLocked(path, content, replaceExisting);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return writeFailure(path, StorageErrorCode.CANCELED);
        } catch (StorageFault failure) {
            return writeFailure(path, failure.code);
        } catch (SecurityException exception) {
            return writeFailure(path, StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        } catch (FileAlreadyExistsException exception) {
            return writeFailure(path, StorageErrorCode.ALREADY_EXISTS);
        } catch (AtomicMoveNotSupportedException exception) {
            return writeFailure(path, StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        } catch (IOException exception) {
            return writeFailure(path, StorageErrorCode.IO_FAILURE);
        }
    }

    StorageListResult list(
        final StoragePath directory,
        final int maxEntries
    ) {
        try {
            checkCanceled();
            final Path target = resolveExisting(directory);
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                return listFailure(directory, StorageErrorCode.TYPE_MISMATCH);
            }
            final List<Path> children = children(target, directory);
            final boolean truncated = children.size() > maxEntries;
            final List<StorageEntry> entries = new ArrayList<>();
            for (Path child : children.subList(0, Math.min(children.size(), maxEntries))) {
                entries.add(StorageFiles.entry(directory, child));
            }
            return new StorageListResult(entries, Optional.empty(), truncated);
        } catch (StorageFault failure) {
            return listFailure(directory, failure.code);
        } catch (SecurityException exception) {
            return listFailure(directory, StorageErrorCode.IO_FAILURE);
        } catch (NoSuchFileException exception) {
            return listFailure(directory, StorageErrorCode.NOT_FOUND);
        } catch (IOException exception) {
            return listFailure(directory, StorageErrorCode.IO_FAILURE);
        }
    }

    StorageMutationResult copy(
        final StoragePath source,
        final StoragePath target,
        final boolean replaceExisting
    ) {
        try (StorageMutationLocks.LockScope ignored = acquireMutationLocks(
            source.root(),
            target.root()
        )) {
            return copyLocked(source, target, replaceExisting);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return mutationFailure(target, StorageErrorCode.CANCELED);
        } catch (StorageFault failure) {
            return mutationFailure(target, failure.code);
        } catch (SecurityException exception) {
            return mutationFailure(target, StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        } catch (NoSuchFileException exception) {
            return mutationFailure(target, StorageErrorCode.NOT_FOUND);
        } catch (FileAlreadyExistsException exception) {
            return mutationFailure(target, StorageErrorCode.ALREADY_EXISTS);
        } catch (AtomicMoveNotSupportedException exception) {
            return mutationFailure(target, StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        } catch (IOException exception) {
            return mutationFailure(target, StorageErrorCode.IO_FAILURE);
        }
    }

    private StorageWriteResult writeBytesAtomicLocked(
        final StoragePath path,
        final byte[] content,
        final boolean replaceExisting
    ) throws IOException, StorageFault, InterruptedException {
        Path temporary = null;
        try {
            checkCanceled();
            final Path target = resolveForWrite(path);
            final StorageWriteResult existing = validateWriteTarget(
                path,
                target,
                replaceExisting
            );
            if (existing != null) {
                return existing;
            }
            if (rootUsage(path.root()) + content.length > ROOT_QUOTA_BYTES) {
                return writeFailure(path, StorageErrorCode.QUOTA_EXCEEDED);
            }
            temporary = uniqueTemporarySibling(target);
            StorageFiles.writeDurably(temporary, content);
            atomicMover.move(temporary, target, replaceExisting);
            temporary = null;
            return new StorageWriteResult(true, Optional.empty());
        } finally {
            deleteTemporary(temporary);
        }
    }

    private StorageMutationResult copyLocked(
        final StoragePath source,
        final StoragePath target,
        final boolean replaceExisting
    ) throws IOException, StorageFault, InterruptedException {
        Path temporary = null;
        try {
            checkCanceled();
            final Path sourcePath = resolveExisting(source);
            if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                return mutationFailure(target, StorageErrorCode.TYPE_MISMATCH);
            }
            final byte[] bytes = readCopySource(sourcePath);
            final Path targetPath = resolveForWrite(target);
            final StorageWriteResult existing = validateWriteTarget(
                target,
                targetPath,
                replaceExisting
            );
            if (existing != null) {
                return mutationFailure(target, existing.error().orElseThrow().code());
            }
            if (rootUsage(target.root()) + bytes.length > ROOT_QUOTA_BYTES) {
                return mutationFailure(target, StorageErrorCode.QUOTA_EXCEEDED);
            }
            temporary = uniqueTemporarySibling(targetPath);
            StorageFiles.writeDurably(temporary, bytes);
            atomicMover.move(temporary, targetPath, replaceExisting);
            temporary = null;
            return new StorageMutationResult(true, Optional.empty());
        } finally {
            deleteTemporary(temporary);
        }
    }

    StorageMutationResult moveAtomic(
        final StoragePath source,
        final StoragePath target,
        final boolean replaceExisting
    ) {
        try (StorageMutationLocks.LockScope ignored = acquireMutationLocks(
            source.root(),
            target.root()
        )) {
            checkCanceled();
            final Path sourcePath = resolveExisting(source);
            final Path targetPath = resolveForWrite(target);
            atomicMover.move(sourcePath, targetPath, replaceExisting);
            return new StorageMutationResult(true, Optional.empty());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return mutationFailure(target, StorageErrorCode.CANCELED);
        } catch (StorageFault failure) {
            return mutationFailure(target, failure.code);
        } catch (SecurityException exception) {
            return mutationFailure(target, StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        } catch (NoSuchFileException exception) {
            return mutationFailure(source, StorageErrorCode.NOT_FOUND);
        } catch (FileAlreadyExistsException exception) {
            return mutationFailure(target, StorageErrorCode.ALREADY_EXISTS);
        } catch (AtomicMoveNotSupportedException exception) {
            return mutationFailure(target, StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        } catch (IOException exception) {
            return mutationFailure(target, StorageErrorCode.IO_FAILURE);
        }
    }

    StorageMutationResult delete(
        final StoragePath path,
        final boolean recursive
    ) {
        try (StorageMutationLocks.LockScope ignored = acquireMutationLocks(path.root())) {
            checkCanceled();
            final Path target = resolveExisting(path);
            if (!recursive) {
                Files.delete(target);
                return new StorageMutationResult(true, Optional.empty());
            }
            final BoundedStorageDeleter.Result result = new BoundedStorageDeleter(
                deleteLimits,
                this::verifyDeleteTarget
            ).delete(path, target);
            if (result.isSuccessful()) {
                return new StorageMutationResult(true, Optional.empty());
            }
            final StorageErrorCode code = result.changed()
                ? StorageErrorCode.PARTIAL_DELETE
                : result.failureCode();
            return new StorageMutationResult(
                result.changed(),
                Optional.of(error(result.failurePath(), code))
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return mutationFailure(path, StorageErrorCode.CANCELED);
        } catch (StorageFault failure) {
            return mutationFailure(path, failure.code);
        } catch (SecurityException exception) {
            return mutationFailure(path, StorageErrorCode.IO_FAILURE);
        } catch (NoSuchFileException exception) {
            return mutationFailure(path, StorageErrorCode.NOT_FOUND);
        } catch (IOException exception) {
            return mutationFailure(path, StorageErrorCode.IO_FAILURE);
        }
    }

    private StorageReadResult<byte[]> readBounded(
        final InputStream input,
        final int maxBytes
    ) throws IOException, StorageFault {
        try {
            return StorageFiles.readBounded(input, maxBytes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StorageFault(StorageErrorCode.CANCELED);
        }
    }

    private byte[] readCopySource(final Path sourcePath) throws IOException, StorageFault {
        try {
            return StorageFiles.readCopySource(
                sourcePath,
                (int) MAX_OPERATION_BYTES
            );
        } catch (StorageFiles.TooLargeException exception) {
            throw new StorageFault(StorageErrorCode.SIZE_LIMIT_EXCEEDED);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StorageFault(StorageErrorCode.CANCELED);
        }
    }

    private StorageWriteResult validateWriteTarget(
        final StoragePath path,
        final Path target,
        final boolean replaceExisting
    ) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return writeFailure(path, StorageErrorCode.TYPE_MISMATCH);
        }
        return null;
    }

    private List<Path> children(
        final Path directory,
        final StoragePath logicalDirectory
    ) throws IOException, StorageFault {
        final List<Path> children;
        try {
            children = StorageFiles.children(directory);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StorageFault(StorageErrorCode.CANCELED);
        }
        for (Path child : children) {
            checkCanceled();
            if (Files.isSymbolicLink(child)) {
                throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
            }
            verifyExisting(logicalDirectory, child);
        }
        return children;
    }

    private StorageErrorCode verifyDeleteTarget(
        final StoragePath logicalPath,
        final Path target
    ) throws IOException {
        try {
            verifyExisting(logicalPath, target);
            return null;
        } catch (StorageFault failure) {
            return failure.code;
        }
    }

    private Path resolveExisting(final StoragePath path) throws IOException, StorageFault {
        final Path resolved = resolve(path, false);
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(path.relativePath());
        }
        verifyExisting(path, resolved);
        return resolved;
    }

    private Path resolveForWrite(final StoragePath path) throws IOException, StorageFault {
        return resolve(path, true);
    }

    private Path resolve(
        final StoragePath path,
        final boolean createParents
    ) throws IOException, StorageFault {
        Objects.requireNonNull(path, "path");
        final Path root = roots.get(path.root());
        if (root == null) {
            throw new StorageFault(StorageErrorCode.INVALID_PATH);
        }
        final Path rootReal = verifyRoot(root);
        Path current = rootReal;
        final String[] segments = path.relativePath().split("/");
        for (int index = 0; index < segments.length; index++) {
            current = current.resolve(segments[index]);
            final boolean last = index == segments.length - 1;
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                verifyExisting(path, current);
                if (!last && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new StorageFault(StorageErrorCode.TYPE_MISMATCH);
                }
            } else if (!last && createParents) {
                createDirectorySafely(path, current);
            } else if (!last) {
                throw new NoSuchFileException(path.relativePath());
            }
            if (!current.toAbsolutePath().normalize().startsWith(rootReal)) {
                throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
            }
        }
        final Path parent = current.getParent();
        if (parent == null || !parent.toRealPath().startsWith(rootReal)) {
            throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
        }
        return current;
    }

    private void createDirectorySafely(
        final StoragePath path,
        final Path directory
    ) throws IOException, StorageFault {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ignored) {
            // A concurrent creator is allowed only after the same validation.
        }
        verifyExisting(path, directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageFault(StorageErrorCode.TYPE_MISMATCH);
        }
    }

    private Path verifyRoot(final Path root) throws IOException, StorageFault {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
        }
        return root.toRealPath();
    }

    private void verifyExisting(
        final StoragePath path,
        final Path candidate
    ) throws IOException, StorageFault {
        if (Files.isSymbolicLink(candidate)) {
            throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
        }
        final Path rootReal = roots.get(path.root()).toRealPath();
        if (!candidate.toRealPath().startsWith(rootReal)) {
            throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
        }
    }

    private StorageMutationLocks.LockScope acquireMutationLocks(
        final StorageRoot... storageRoots
    ) throws IOException, StorageFault, InterruptedException {
        final List<Path> canonicalRoots = new ArrayList<>(storageRoots.length);
        for (StorageRoot storageRoot : storageRoots) {
            final Path root = roots.get(storageRoot);
            if (root == null) {
                throw new StorageFault(StorageErrorCode.INVALID_PATH);
            }
            canonicalRoots.add(verifyRoot(root));
        }
        return StorageMutationLocks.acquire(canonicalRoots);
    }

    private Path uniqueTemporarySibling(final Path target) {
        return target.resolveSibling(
            "." + target.getFileName() + ".turboism-" + UUID.randomUUID() + ".tmp"
        );
    }

    private long rootUsage(final StorageRoot storageRoot) throws IOException, StorageFault {
        final Path root = roots.get(storageRoot);
        long total = 0L;
        try (var stream = Files.walk(root)) {
            final var iterator = stream.iterator();
            while (iterator.hasNext()) {
                final Path path = iterator.next();
                checkCanceled();
                if (Files.isSymbolicLink(path)) {
                    throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        total = Math.addExact(total, Files.size(path));
                    } catch (ArithmeticException exception) {
                        return Long.MAX_VALUE;
                    }
                    if (total > ROOT_QUOTA_BYTES) {
                        return total;
                    }
                }
            }
        }
        return total;
    }

    private void checkCanceled() throws StorageFault {
        if (Thread.currentThread().isInterrupted()) {
            throw new StorageFault(StorageErrorCode.CANCELED);
        }
    }

    private StorageError error(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageError(code, message(code), path);
    }

    private static String message(final StorageErrorCode code) {
        return switch (code) {
            case INVALID_PATH -> "Storage path is invalid.";
            case PERMISSION_DENIED -> "Storage permission was denied.";
            case NOT_FOUND -> "Storage entry was not found.";
            case ALREADY_EXISTS -> "Storage entry already exists.";
            case TYPE_MISMATCH -> "Storage entry type does not match the operation.";
            case SIZE_LIMIT_EXCEEDED -> "Storage operation exceeds the size limit.";
            case QUOTA_EXCEEDED -> "Plugin storage quota was exceeded.";
            case LINK_ESCAPE -> "Storage path contains or traverses a link.";
            case ATOMIC_REPLACE_UNAVAILABLE -> "Atomic storage replacement is unavailable.";
            case CROSS_ROOT_ATOMIC_MOVE_UNSUPPORTED -> "Atomic move across storage roots is unsupported.";
            case PARTIAL_DELETE -> "Recursive delete stopped after a partial change.";
            case CONFLICT -> "Storage entry changed during the operation.";
            case CANCELED -> "Storage operation was canceled.";
            case RUNTIME_UNAVAILABLE -> "Storage runtime is unavailable.";
            case IO_FAILURE -> "Storage operation failed safely.";
        };
    }

    private <T> StorageReadResult<T> readFailure(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageReadResult<>(Optional.empty(), Optional.of(error(path, code)), false);
    }

    private StorageWriteResult writeFailure(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageWriteResult(false, Optional.of(error(path, code)));
    }

    private StorageListResult listFailure(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageListResult(List.of(), Optional.of(error(path, code)), false);
    }

    private StorageMutationResult mutationFailure(
        final StoragePath path,
        final StorageErrorCode code
    ) {
        return new StorageMutationResult(false, Optional.of(error(path, code)));
    }

    private void deleteTemporary(final Path temporary) {
        if (temporary != null) {
            try {
                if (temporaryFileDeleter.delete(temporary)) {
                    cleanupEvidence.temporaryFileDeleted();
                }
            } catch (IOException ignored) {
                cleanupEvidence.cleanupFailed();
            }
        }
    }

    private static Map<StorageRoot, Path> validateRoots(
        final Map<StorageRoot, Path> roots
    ) throws IOException {
        Objects.requireNonNull(roots, "roots");
        final EnumMap<StorageRoot, Path> normalized = new EnumMap<>(StorageRoot.class);
        for (StorageRoot root : StorageRoot.values()) {
            final Path path = Objects.requireNonNull(roots.get(root), "root " + root)
                .toAbsolutePath()
                .normalize();
            Files.createDirectories(path);
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Plugin storage root must not be a symbolic link");
            }
            normalized.put(root, path);
        }
        return Map.copyOf(normalized);
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target, boolean replaceExisting) throws IOException;
    }

    @FunctionalInterface
    interface TemporaryFileDeleter {
        boolean delete(Path temporary) throws IOException;
    }

    private static final class StorageFault extends Exception {
        private final StorageErrorCode code;

        private StorageFault(final StorageErrorCode code) {
            this.code = Objects.requireNonNull(code, "code");
        }
    }

}

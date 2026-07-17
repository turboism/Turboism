package dev.turboism.storage;

import dev.turboism.cleanup.CleanupEvidenceCollector;

import dev.turboism.sdk.storage.StorageEntry;
import dev.turboism.sdk.storage.StorageEntryType;
import dev.turboism.sdk.storage.StorageError;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final DeleteLimits deleteLimits;

    ConfinedStorageBackend(final Map<StorageRoot, Path> roots) throws IOException {
        this(roots, new CleanupEvidenceCollector(), DeleteLimits.defaults());
    }

    ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final CleanupEvidenceCollector cleanupEvidence
    ) throws IOException {
        this(roots, cleanupEvidence, DeleteLimits.defaults());
    }

    ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final DeleteLimits deleteLimits
    ) throws IOException {
        this(roots, new CleanupEvidenceCollector(), deleteLimits);
    }

    private ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final CleanupEvidenceCollector cleanupEvidence,
        final DeleteLimits deleteLimits
    ) throws IOException {
        this.cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
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
                return readBounded(path, input, maxBytes);
            }
        } catch (StorageFault failure) {
            return readFailure(path, failure.code);
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
            writeDurably(path, temporary, content);
            installAtomic(temporary, target, replaceExisting);
            temporary = null;
            return new StorageWriteResult(true, Optional.empty());
        } catch (StorageFault failure) {
            return writeFailure(path, failure.code);
        } catch (FileAlreadyExistsException exception) {
            return writeFailure(path, StorageErrorCode.ALREADY_EXISTS);
        } catch (AtomicMoveNotSupportedException exception) {
            return writeFailure(path, StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        } catch (IOException exception) {
            return writeFailure(path, StorageErrorCode.IO_FAILURE);
        } finally {
            deleteTemporary(temporary);
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
                entries.add(entry(directory, child));
            }
            return new StorageListResult(entries, Optional.empty(), truncated);
        } catch (StorageFault failure) {
            return listFailure(directory, failure.code);
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
        final StorageReadResult<byte[]> read = readBytes(
            source,
            (int) MAX_OPERATION_BYTES
        );
        if (read.error().isPresent()) {
            return mutationFailure(target, read.error().orElseThrow().code());
        }
        if (read.truncated()) {
            return mutationFailure(target, StorageErrorCode.SIZE_LIMIT_EXCEEDED);
        }
        final StorageWriteResult write = writeBytesAtomic(
            target,
            read.value().orElseThrow(),
            replaceExisting
        );
        return write.written()
            ? new StorageMutationResult(true, Optional.empty())
            : mutationFailure(target, write.error().orElseThrow().code());
    }

    StorageMutationResult moveAtomic(
        final StoragePath source,
        final StoragePath target,
        final boolean replaceExisting
    ) {
        try {
            checkCanceled();
            final Path sourcePath = resolveExisting(source);
            final Path targetPath = resolveForWrite(target);
            if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) && !replaceExisting) {
                return mutationFailure(target, StorageErrorCode.ALREADY_EXISTS);
            }
            final List<java.nio.file.CopyOption> options = new ArrayList<>();
            options.add(StandardCopyOption.ATOMIC_MOVE);
            if (replaceExisting) {
                options.add(StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(
                sourcePath,
                targetPath,
                options.toArray(java.nio.file.CopyOption[]::new)
            );
            return new StorageMutationResult(true, Optional.empty());
        } catch (StorageFault failure) {
            return mutationFailure(target, failure.code);
        } catch (NoSuchFileException exception) {
            return mutationFailure(source, StorageErrorCode.NOT_FOUND);
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
        final DeleteProgress progress = new DeleteProgress();
        try {
            checkCanceled();
            final Path target = resolveExisting(path);
            if (!recursive) {
                Files.delete(target);
                return new StorageMutationResult(true, Optional.empty());
            }
            final DeleteBudget budget = new DeleteBudget(deleteLimits);
            budget.includeRoot(path);
            deleteRecursively(path, target, 0, progress, budget);
            return new StorageMutationResult(true, Optional.empty());
        } catch (DeleteFault failure) {
            final StorageErrorCode code = progress.changed
                ? StorageErrorCode.PARTIAL_DELETE
                : failure.code;
            return new StorageMutationResult(
                progress.changed,
                Optional.of(error(failure.path, code))
            );
        } catch (StorageFault failure) {
            return mutationFailure(path, failure.code);
        } catch (NoSuchFileException exception) {
            return mutationFailure(path, StorageErrorCode.NOT_FOUND);
        } catch (IOException exception) {
            return mutationFailure(path, StorageErrorCode.IO_FAILURE);
        }
    }

    private StorageReadResult<byte[]> readBounded(
        final StoragePath path,
        final InputStream input,
        final int maxBytes
    ) throws IOException, StorageFault {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(
            Math.min(maxBytes, 8192)
        );
        final byte[] buffer = new byte[Math.min(8192, maxBytes + 1)];
        int remaining = maxBytes + 1;
        while (remaining > 0) {
            checkCanceled();
            final int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        final byte[] raw = output.toByteArray();
        final boolean truncated = raw.length > maxBytes;
        final byte[] value = truncated
            ? java.util.Arrays.copyOf(raw, maxBytes)
            : raw;
        return new StorageReadResult<>(Optional.of(value), Optional.empty(), truncated);
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

    private void writeDurably(
        final StoragePath path,
        final Path temporary,
        final byte[] content
    ) throws IOException, StorageFault {
        try (FileChannel channel = FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )) {
            final ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                checkCanceled();
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private List<Path> children(
        final Path directory,
        final StoragePath logicalDirectory
    ) throws IOException, StorageFault {
        final List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                checkCanceled();
                if (Files.isSymbolicLink(child)) {
                    throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
                }
                verifyExisting(logicalDirectory, child);
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return children;
    }

    private StorageEntry entry(
        final StoragePath directory,
        final Path child
    ) throws IOException {
        final StorageEntryType type = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
            ? StorageEntryType.DIRECTORY
            : StorageEntryType.FILE;
        final long size = type == StorageEntryType.FILE ? Files.size(child) : 0L;
        return new StorageEntry(
            new StoragePath(
                directory.root(),
                directory.relativePath() + "/" + child.getFileName()
            ),
            type,
            size
        );
    }

    private void deleteRecursively(
        final StoragePath logicalPath,
        final Path target,
        final int depth,
        final DeleteProgress progress,
        final DeleteBudget budget
    ) throws DeleteFault {
        try {
            checkCanceled();
            budget.enter(logicalPath, depth);
            if (Files.isSymbolicLink(target)) {
                throw new DeleteFault(logicalPath, StorageErrorCode.LINK_ESCAPE);
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                for (DeleteChild child : deleteChildren(target, logicalPath, budget)) {
                    deleteRecursively(
                        child.logicalPath,
                        child.target,
                        depth + 1,
                        progress,
                        budget
                    );
                }
            }
            budget.beforeDelete(logicalPath);
            Files.delete(target);
            progress.changed = true;
        } catch (DeleteFault failure) {
            throw failure;
        } catch (StorageFault failure) {
            throw new DeleteFault(logicalPath, failure.code, failure);
        } catch (IOException failure) {
            throw new DeleteFault(logicalPath, StorageErrorCode.IO_FAILURE, failure);
        }
    }

    private List<DeleteChild> deleteChildren(
        final Path directory,
        final StoragePath logicalDirectory,
        final DeleteBudget budget
    ) throws IOException, DeleteFault {
        final List<DeleteChild> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            try {
                for (Path child : stream) {
                    try {
                        checkCanceled();
                    } catch (StorageFault failure) {
                        throw new DeleteFault(logicalDirectory, failure.code, failure);
                    }
                    final StoragePath logicalChild = new StoragePath(
                        logicalDirectory.root(),
                        logicalDirectory.relativePath() + "/" + child.getFileName()
                    );
                    budget.discover(logicalDirectory);
                    if (Files.isSymbolicLink(child)) {
                        throw new DeleteFault(
                            logicalChild,
                            StorageErrorCode.LINK_ESCAPE
                        );
                    }
                    try {
                        verifyExisting(logicalChild, child);
                    } catch (StorageFault failure) {
                        throw new DeleteFault(logicalChild, failure.code, failure);
                    }
                    children.add(new DeleteChild(logicalChild, child));
                }
            } catch (DirectoryIteratorException failure) {
                throw failure.getCause();
            }
        }
        children.sort(Comparator.comparing(
            child -> child.target.getFileName().toString()
        ));
        return children;
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
        Path current = root;
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

    private void installAtomic(
        final Path temporary,
        final Path target,
        final boolean replaceExisting
    ) throws IOException {
        if (replaceExisting) {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } else {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        }
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
                if (Files.deleteIfExists(temporary)) {
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

    private static final class StorageFault extends Exception {
        private final StorageErrorCode code;

        private StorageFault(final StorageErrorCode code) {
            this.code = Objects.requireNonNull(code, "code");
        }
    }

    private static final class DeleteFault extends Exception {
        private final StoragePath path;
        private final StorageErrorCode code;

        private DeleteFault(
            final StoragePath path,
            final StorageErrorCode code
        ) {
            this(path, code, null);
        }

        private DeleteFault(
            final StoragePath path,
            final StorageErrorCode code,
            final Throwable cause
        ) {
            super(cause);
            this.path = Objects.requireNonNull(path, "path");
            this.code = Objects.requireNonNull(code, "code");
        }
    }

    private static final class DeleteBudget {
        private final int maxDepth;
        private long entriesRemaining;
        private long workRemaining;

        private DeleteBudget(final DeleteLimits limits) {
            maxDepth = limits.maxDepth();
            entriesRemaining = limits.maxEntries();
            workRemaining = limits.maxWork();
        }

        private void includeRoot(final StoragePath path) throws DeleteFault {
            consumeEntry(path);
        }

        private void enter(
            final StoragePath path,
            final int depth
        ) throws DeleteFault {
            if (depth > maxDepth) {
                throw limit(path);
            }
            consumeWork(depth + 1L, path);
        }

        private void discover(final StoragePath directory) throws DeleteFault {
            consumeEntry(directory);
            consumeWork(1L, directory);
        }

        private void beforeDelete(final StoragePath path) throws DeleteFault {
            consumeWork(1L, path);
        }

        private void consumeEntry(final StoragePath path) throws DeleteFault {
            if (entriesRemaining < 1L) {
                throw limit(path);
            }
            entriesRemaining -= 1L;
        }

        private void consumeWork(
            final long amount,
            final StoragePath path
        ) throws DeleteFault {
            if (workRemaining < amount) {
                throw limit(path);
            }
            workRemaining -= amount;
        }

        private DeleteFault limit(final StoragePath path) {
            return new DeleteFault(path, StorageErrorCode.SIZE_LIMIT_EXCEEDED);
        }
    }

    private static final class DeleteChild {
        private final StoragePath logicalPath;
        private final Path target;

        private DeleteChild(
            final StoragePath logicalPath,
            final Path target
        ) {
            this.logicalPath = logicalPath;
            this.target = target;
        }
    }

    private static final class DeleteProgress {
        private boolean changed;
    }
}

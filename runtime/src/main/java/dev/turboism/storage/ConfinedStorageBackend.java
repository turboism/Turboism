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
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Synchronous path-confinement and atomic filesystem implementation. */
final class ConfinedStorageBackend {

    static final long MAX_OPERATION_BYTES = 8L * 1024L * 1024L;
    static final int MAX_LIST_ENTRIES = 10_000;
    private static final long ROOT_QUOTA_BYTES = 64L * 1024L * 1024L;

    private final Map<StorageRoot, Path> roots;
    private final CleanupEvidenceCollector cleanupEvidence;
    private final AtomicWriteInterlock atomicWriteInterlock;
    private final MutationInterlock mutationInterlock;

    ConfinedStorageBackend(final Map<StorageRoot, Path> roots) throws IOException {
        this(
            roots,
            new CleanupEvidenceCollector(),
            NoopAtomicWriteInterlock.INSTANCE,
            NoopMutationInterlock.INSTANCE
        );
    }

    ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final CleanupEvidenceCollector cleanupEvidence
    ) throws IOException {
        this(
            roots,
            cleanupEvidence,
            NoopAtomicWriteInterlock.INSTANCE,
            NoopMutationInterlock.INSTANCE
        );
    }

    ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final CleanupEvidenceCollector cleanupEvidence,
        final AtomicWriteInterlock atomicWriteInterlock
    ) throws IOException {
        this(
            roots,
            cleanupEvidence,
            atomicWriteInterlock,
            NoopMutationInterlock.INSTANCE
        );
    }

    ConfinedStorageBackend(
        final Map<StorageRoot, Path> roots,
        final CleanupEvidenceCollector cleanupEvidence,
        final AtomicWriteInterlock atomicWriteInterlock,
        final MutationInterlock mutationInterlock
    ) throws IOException {
        this.cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        this.atomicWriteInterlock = Objects.requireNonNull(
            atomicWriteInterlock,
            "atomicWriteInterlock"
        );
        this.mutationInterlock = Objects.requireNonNull(
            mutationInterlock,
            "mutationInterlock"
        );
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
        AtomicWriteContext context = null;
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
            context = openAtomicWriteContext(path);
            atomicWriteInterlock.beforeTemporaryCreation();
            verifyAtomicWriteParent(context);
            context.temporaryName = uniqueTemporaryName(context.targetName);
            writeDurably(path, context, content);
            atomicWriteInterlock.beforeInstall();
            verifyAtomicWriteParent(context);
            installAtomic(context, replaceExisting);
            context.temporaryName = null;
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
            cleanupAtomicWrite(context);
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
        SecurePathContext sourceContext = null;
        SecurePathContext targetContext = null;
        try {
            checkCanceled();
            sourceContext = openSecurePathContext(source, false, true);
            targetContext = openSecurePathContext(target, false, false);
            mutationInterlock.beforeMove();
            verifySecureParent(sourceContext);
            verifySecureParent(targetContext);
            verifyExpectedEntry(sourceContext, true);
            verifyExpectedEntry(targetContext, false);
            if (!replaceExisting && entryExists(targetContext.parentStream, targetContext.name)) {
                return mutationFailure(target, StorageErrorCode.ALREADY_EXISTS);
            }
            sourceContext.parentStream.move(
                sourceContext.name,
                targetContext.parentStream,
                targetContext.name
            );
            verifySecureParent(sourceContext);
            verifySecureParent(targetContext);
            return new StorageMutationResult(true, Optional.empty());
        } catch (StorageFault failure) {
            return mutationFailure(target, failure.code);
        } catch (NoSuchFileException exception) {
            return mutationFailure(source, StorageErrorCode.NOT_FOUND);
        } catch (AtomicMoveNotSupportedException exception) {
            return mutationFailure(target, StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        } catch (IOException exception) {
            return mutationFailure(target, StorageErrorCode.IO_FAILURE);
        } finally {
            closeSecurePathContexts(sourceContext, targetContext);
        }
    }

    StorageMutationResult delete(
        final StoragePath path,
        final boolean recursive
    ) {
        SecurePathContext context = null;
        try {
            checkCanceled();
            context = openSecurePathContext(path, false, true);
            final RecursiveDeleteSnapshot snapshot = recursive
                ? captureRecursiveDeleteSnapshot(path, context)
                : null;
            mutationInterlock.beforeDelete();
            verifySecureParent(context);
            verifyExpectedEntry(context, true);
            final DeleteProgress progress = new DeleteProgress();
            deleteSecureEntry(
                path,
                context.parentStream,
                context.name,
                recursive,
                progress,
                snapshot
            );
            verifySecureParent(context);
            return new StorageMutationResult(true, Optional.empty());
        } catch (PartialDeleteFault failure) {
            final StorageErrorCode code = failure.changed
                ? StorageErrorCode.PARTIAL_DELETE
                : failure.code;
            return new StorageMutationResult(
                failure.changed,
                Optional.of(error(failure.path, code))
            );
        } catch (StorageFault failure) {
            return mutationFailure(path, failure.code);
        } catch (NoSuchFileException exception) {
            return mutationFailure(path, StorageErrorCode.NOT_FOUND);
        } catch (IOException exception) {
            return mutationFailure(path, StorageErrorCode.IO_FAILURE);
        } finally {
            closeSecurePathContexts(context);
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
        final AtomicWriteContext context,
        final byte[] content
    ) throws IOException, StorageFault {
        try (var channel = context.parentStream.newByteChannel(
            context.temporaryName,
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        )) {
            context.temporaryCreated = true;
            context.temporaryFileKey = fileKey(context.parentStream, context.temporaryName);
            if (context.temporaryFileKey == null) {
                throw new StorageFault(StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
            }
            final ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                checkCanceled();
                channel.write(buffer);
            }
            if (channel instanceof FileChannel fileChannel) {
                fileChannel.force(true);
            } else {
                throw new StorageFault(StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
            }
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

    private AtomicWriteContext openAtomicWriteContext(
        final StoragePath path
    ) throws IOException, StorageFault {
        final SecurePathContext context = openSecurePathContext(path, true, false);
        return new AtomicWriteContext(
            context.parentStream,
            context.parentPath,
            context.name,
            context.parentFileKey
        );
    }

    private void verifyAtomicWriteParent(final AtomicWriteContext context)
        throws IOException, StorageFault {
        verifySecureParent(
            context.parentStream,
            context.parentPath,
            context.parentFileKey
        );
    }

    private SecurePathContext openSecurePathContext(
        final StoragePath path,
        final boolean createParents,
        final boolean requireEntry
    ) throws IOException, StorageFault {
        Objects.requireNonNull(path, "path");
        final Path root = roots.get(path.root());
        if (root == null) {
            throw new StorageFault(StorageErrorCode.INVALID_PATH);
        }
        DirectoryStream<Path> opened = Files.newDirectoryStream(root);
        if (!(opened instanceof SecureDirectoryStream<Path> rootStream)) {
            opened.close();
            throw new StorageFault(StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        }
        SecureDirectoryStream<Path> current = rootStream;
        Path currentPath = root;
        try {
            final String[] segments = path.relativePath().split("/");
            for (int index = 0; index < segments.length - 1; index++) {
                final Path segment = Path.of(segments[index]);
                SecureDirectoryStream<Path> child;
                try {
                    child = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException exception) {
                    if (!createParents) {
                        throw exception;
                    }
                    createSecureDirectory(path, current, currentPath, segment);
                    child = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                }
                current.close();
                current = child;
                currentPath = currentPath.resolve(segment);
            }
            final Object parentFileKey = fileKey(current);
            if (parentFileKey == null) {
                throw new StorageFault(StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
            }
            final Path name = Path.of(segments[segments.length - 1]);
            final Object entryFileKey = fileKey(current, name);
            if (requireEntry && entryFileKey == null) {
                throw new NoSuchFileException(path.relativePath());
            }
            return new SecurePathContext(
                current,
                currentPath,
                name,
                parentFileKey,
                entryFileKey
            );
        } catch (IOException | StorageFault failure) {
            current.close();
            throw failure;
        }
    }

    private void createSecureDirectory(
        final StoragePath path,
        final SecureDirectoryStream<Path> parentStream,
        final Path parentPath,
        final Path name
    ) throws IOException, StorageFault {
        verifySecureParent(
            parentStream,
            parentPath,
            fileKey(parentStream)
        );
        try {
            Files.createDirectory(parentPath.resolve(name));
        } catch (FileAlreadyExistsException ignored) {
            // A concurrent creator is accepted only after the bound lookup below.
        }
        try (SecureDirectoryStream<Path> ignored = parentStream.newDirectoryStream(
            name,
            LinkOption.NOFOLLOW_LINKS
        )) {
            // The lookup is relative to the still-open parent descriptor.
        } catch (java.nio.file.NotDirectoryException exception) {
            throw new StorageFault(StorageErrorCode.TYPE_MISMATCH);
        }
        if (Files.isSymbolicLink(parentPath.resolve(name))) {
            throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
        }
        verifyExisting(path, parentPath.resolve(name));
    }

    private void verifySecureParent(final SecurePathContext context)
        throws IOException, StorageFault {
        verifySecureParent(
            context.parentStream,
            context.parentPath,
            context.parentFileKey
        );
    }

    private void verifySecureParent(
        final SecureDirectoryStream<Path> parentStream,
        final Path parentPath,
        final Object parentFileKey
    ) throws IOException, StorageFault {
        final Object boundFileKey = fileKey(parentStream);
        if (boundFileKey == null || !parentFileKey.equals(boundFileKey)) {
            throw new StorageFault(StorageErrorCode.CONFLICT);
        }
        final Object currentFileKey;
        try {
            currentFileKey = Files.readAttributes(
                parentPath,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
            ).fileKey();
        } catch (NoSuchFileException exception) {
            throw new StorageFault(StorageErrorCode.CONFLICT);
        }
        if (currentFileKey == null || !parentFileKey.equals(currentFileKey)) {
            throw new StorageFault(StorageErrorCode.CONFLICT);
        }
    }

    private void verifyExpectedEntry(
        final SecurePathContext context,
        final boolean required
    ) throws IOException, StorageFault {
        final BasicFileAttributes attributes = readAttributesIfPresent(
            context.parentStream,
            context.name
        );
        if (attributes == null) {
            if (required) {
                throw new NoSuchFileException(context.name.toString());
            }
            if (context.entryFileKey != null) {
                throw new StorageFault(StorageErrorCode.CONFLICT);
            }
            return;
        }
        if (context.entryFileKey == null) {
            throw new StorageFault(StorageErrorCode.CONFLICT);
        }
        if (attributes.isSymbolicLink()) {
            throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
        }
        if (context.entryFileKey != null
            && !context.entryFileKey.equals(attributes.fileKey())) {
            throw new StorageFault(StorageErrorCode.CONFLICT);
        }
    }

    private Object fileKey(final SecureDirectoryStream<Path> stream) throws IOException {
        final BasicFileAttributeView view = stream.getFileAttributeView(
            BasicFileAttributeView.class
        );
        return view == null ? null : view.readAttributes().fileKey();
    }

    private void installAtomic(
        final AtomicWriteContext context,
        final boolean replaceExisting
    ) throws IOException, StorageFault {
        verifyAtomicWriteTarget(context, replaceExisting);
        final Path temporaryName = context.temporaryName;
        context.parentStream.move(
            temporaryName,
            context.parentStream,
            context.targetName
        );
        context.temporaryCreated = false;
        context.temporaryName = null;
        verifyAtomicWriteParentAfterInstall(context);
    }

    private void verifyAtomicWriteParentAfterInstall(final AtomicWriteContext context)
        throws IOException, StorageFault {
        verifyAtomicWriteParent(context);
    }

    private void verifyAtomicWriteTarget(
        final AtomicWriteContext context,
        final boolean replaceExisting
    ) throws IOException, StorageFault {
        final BasicFileAttributes attributes = readAttributesIfPresent(
            context.parentStream,
            context.targetName
        );
        if (attributes == null) {
            return;
        }
        if (attributes.isSymbolicLink()) {
            throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
        }
        if (!attributes.isRegularFile()) {
            throw new StorageFault(StorageErrorCode.TYPE_MISMATCH);
        }
        if (!replaceExisting) {
            throw new FileAlreadyExistsException(context.targetName.toString());
        }
    }

    private boolean entryExists(
        final SecureDirectoryStream<Path> parent,
        final Path name
    ) throws IOException {
        return fileKey(parent, name) != null;
    }

    private RecursiveDeleteSnapshot captureRecursiveDeleteSnapshot(
        final StoragePath logicalPath,
        final SecurePathContext context
    ) throws IOException, StorageFault {
        final BasicFileAttributes attributes = readAttributes(
            context.parentStream,
            context.name
        );
        if (attributes.isSymbolicLink()) {
            throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
        }
        final RecursiveDeleteSnapshot snapshot = new RecursiveDeleteSnapshot();
        snapshot.include(logicalPath, attributes);
        if (!attributes.isDirectory()) {
            return snapshot;
        }
        try (SecureDirectoryStream<Path> directory = context.parentStream.newDirectoryStream(
            context.name,
            LinkOption.NOFOLLOW_LINKS
        )) {
            captureRecursiveDeleteSnapshot(logicalPath, directory, snapshot);
        }
        return snapshot;
    }

    private void captureRecursiveDeleteSnapshot(
        final StoragePath logicalDirectory,
        final SecureDirectoryStream<Path> directory,
        final RecursiveDeleteSnapshot snapshot
    ) throws IOException, StorageFault {
        for (Path child : secureChildren(directory)) {
            final BasicFileAttributes attributes = readAttributes(directory, child);
            if (attributes.isSymbolicLink()) {
                throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
            }
            final StoragePath logicalChild = new StoragePath(
                logicalDirectory.root(),
                logicalDirectory.relativePath() + "/" + child
            );
            snapshot.include(logicalChild, attributes);
            if (!attributes.isDirectory()) {
                continue;
            }
            try (SecureDirectoryStream<Path> nested = directory.newDirectoryStream(
                child,
                LinkOption.NOFOLLOW_LINKS
            )) {
                captureRecursiveDeleteSnapshot(logicalChild, nested, snapshot);
            }
        }
    }

    private void deleteSecureEntry(
        final StoragePath logicalPath,
        final SecureDirectoryStream<Path> parent,
        final Path name,
        final boolean recursive,
        final DeleteProgress progress,
        final RecursiveDeleteSnapshot snapshot
    ) throws PartialDeleteFault {
        try {
            checkCanceled();
            final BasicFileAttributes attributes = readAttributes(parent, name);
            if (snapshot != null) {
                snapshot.verify(logicalPath, attributes);
            }
            if (attributes.isSymbolicLink()) {
                throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
            }
            if (attributes.isDirectory()) {
                if (!recursive) {
                    parent.deleteDirectory(name);
                    progress.changed = true;
                    return;
                }
                try (SecureDirectoryStream<Path> directory = parent.newDirectoryStream(
                    name,
                    LinkOption.NOFOLLOW_LINKS
                )) {
                    final List<Path> children = secureChildren(directory);
                    for (Path child : children) {
                        deleteSecureEntry(
                            new StoragePath(
                                logicalPath.root(),
                                logicalPath.relativePath() + "/" + child
                            ),
                            directory,
                            child,
                            true,
                            progress,
                            snapshot
                        );
                    }
                }
                parent.deleteDirectory(name);
            } else {
                parent.deleteFile(name);
            }
            progress.changed = true;
        } catch (StorageFault failure) {
            throw new PartialDeleteFault(
                logicalPath,
                failure.code,
                progress.changed,
                failure
            );
        } catch (IOException failure) {
            throw new PartialDeleteFault(
                logicalPath,
                StorageErrorCode.IO_FAILURE,
                progress.changed,
                failure
            );
        }
    }

    private BasicFileAttributes readAttributes(
        final SecureDirectoryStream<Path> parent,
        final Path name
    ) throws IOException, StorageFault {
        final BasicFileAttributes attributes = readAttributesIfPresent(parent, name);
        if (attributes == null) {
            throw new NoSuchFileException(name.toString());
        }
        return attributes;
    }

    private BasicFileAttributes readAttributesIfPresent(
        final SecureDirectoryStream<Path> parent,
        final Path name
    ) throws IOException, StorageFault {
        final BasicFileAttributeView view = parent.getFileAttributeView(
            name,
            BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            throw new StorageFault(StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        }
        try {
            return view.readAttributes();
        } catch (NoSuchFileException exception) {
            return null;
        }
    }

    private List<Path> secureChildren(
        final SecureDirectoryStream<Path> directory
    ) throws IOException, StorageFault {
        final List<Path> children = new ArrayList<>();
        for (Path child : directory) {
            checkCanceled();
            final Path name = child.getFileName();
            final BasicFileAttributes attributes = readAttributes(directory, name);
            if (attributes.isSymbolicLink()) {
                throw new StorageFault(StorageErrorCode.LINK_ESCAPE);
            }
            children.add(name);
        }
        children.sort(Comparator.comparing(Path::toString));
        return children;
    }

    private void closeSecurePathContexts(final SecurePathContext... contexts) {
        final Set<SecureDirectoryStream<Path>> closed = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>()
        );
        for (SecurePathContext context : contexts) {
            if (context == null || !closed.add(context.parentStream)) {
                continue;
            }
            try {
                context.parentStream.close();
            } catch (IOException ignored) {
                // Mutation completion does not report resource-close bookkeeping as a mutation.
            }
        }
    }

    private Path uniqueTemporaryName(final Path targetName) {
        return Path.of(
            "." + targetName + ".turboism-" + UUID.randomUUID() + ".tmp"
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

    private void cleanupAtomicWrite(final AtomicWriteContext context) {
        if (context == null) {
            return;
        }
        try {
            if (context.temporaryCreated && context.temporaryName != null) {
                final Object currentFileKey = fileKey(context.parentStream, context.temporaryName);
                if (context.temporaryFileKey == null
                    || currentFileKey == null
                    || !context.temporaryFileKey.equals(currentFileKey)) {
                    cleanupEvidence.cleanupFailed();
                } else {
                    context.parentStream.deleteFile(context.temporaryName);
                    cleanupEvidence.temporaryFileDeleted();
                }
            }
        } catch (IOException ignored) {
            if (context.temporaryCreated) {
                cleanupEvidence.cleanupFailed();
            }
        } finally {
            try {
                context.parentStream.close();
            } catch (IOException ignored) {
                cleanupEvidence.cleanupFailed();
            }
        }
    }

    private Object fileKey(
        final SecureDirectoryStream<Path> parent,
        final Path name
    ) throws IOException {
        final BasicFileAttributeView view = parent.getFileAttributeView(
            name,
            BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            return null;
        }
        try {
            return view.readAttributes().fileKey();
        } catch (NoSuchFileException exception) {
            return null;
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

    private static final class SecurePathContext {
        private final SecureDirectoryStream<Path> parentStream;
        private final Path parentPath;
        private final Path name;
        private final Object parentFileKey;
        private final Object entryFileKey;

        private SecurePathContext(
            final SecureDirectoryStream<Path> parentStream,
            final Path parentPath,
            final Path name,
            final Object parentFileKey,
            final Object entryFileKey
        ) {
            this.parentStream = parentStream;
            this.parentPath = parentPath;
            this.name = name;
            this.parentFileKey = parentFileKey;
            this.entryFileKey = entryFileKey;
        }
    }

    private static final class RecursiveDeleteSnapshot {
        private final Map<StoragePath, Object> fileKeys = new java.util.HashMap<>();

        private void include(
            final StoragePath path,
            final BasicFileAttributes attributes
        ) throws StorageFault {
            final Object fileKey = attributes.fileKey();
            if (fileKey == null) {
                throw new StorageFault(StorageErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
            }
            fileKeys.put(path, fileKey);
        }

        private void verify(
            final StoragePath path,
            final BasicFileAttributes attributes
        ) throws StorageFault {
            final Object expectedFileKey = fileKeys.get(path);
            if (expectedFileKey == null || !expectedFileKey.equals(attributes.fileKey())) {
                throw new StorageFault(StorageErrorCode.CONFLICT);
            }
        }
    }

    private static final class AtomicWriteContext {
        private final SecureDirectoryStream<Path> parentStream;
        private final Path parentPath;
        private final Path targetName;
        private final Object parentFileKey;
        private Path temporaryName;
        private Object temporaryFileKey;
        private boolean temporaryCreated;

        private AtomicWriteContext(
            final SecureDirectoryStream<Path> parentStream,
            final Path parentPath,
            final Path targetName,
            final Object parentFileKey
        ) {
            this.parentStream = parentStream;
            this.parentPath = parentPath;
            this.targetName = targetName;
            this.parentFileKey = parentFileKey;
        }
    }

    interface AtomicWriteInterlock {
        default void beforeTemporaryCreation() throws IOException {
        }

        default void beforeInstall() throws IOException {
        }
    }

    interface MutationInterlock {
        default void beforeMove() throws IOException {
        }

        default void beforeDelete() throws IOException {
        }
    }

    private enum NoopAtomicWriteInterlock implements AtomicWriteInterlock {
        INSTANCE
    }

    private enum NoopMutationInterlock implements MutationInterlock {
        INSTANCE
    }

    private static final class StorageFault extends Exception {
        private final StorageErrorCode code;

        private StorageFault(final StorageErrorCode code) {
            this.code = Objects.requireNonNull(code, "code");
        }
    }

    private static final class PartialDeleteFault extends Exception {
        private final StoragePath path;
        private final StorageErrorCode code;
        private final boolean changed;

        private PartialDeleteFault(
            final StoragePath path,
            final StorageErrorCode code,
            final boolean changed,
            final Throwable cause
        ) {
            super(cause);
            this.path = path;
            this.code = code;
            this.changed = changed;
        }
    }

    private static final class DeleteProgress {
        private boolean changed;
    }
}

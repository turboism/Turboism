package dev.turboism.mapping.draft;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

final class FileSafety {
    private FileSafety() { }

    static void requireSafeRoot(final Path root) {
        requireDirectoryNoLinks(root, "ROOT_PATH_INVALID");
    }

    static void requireDirectoryNoLinks(final Path directory, final String code) {
        Path current = directory.toAbsolutePath().normalize().getRoot();
        for (Path part : directory.toAbsolutePath().normalize()) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new DraftMappingException(code, "directory chain must contain only real directories");
            }
        }
    }

    static void requireExistingParentChain(final Path root, final Path target, final String code) {
        Path current = root;
        final Path relative = root.relativize(target.toAbsolutePath().normalize());
        for (int index = 0; index < Math.max(0, relative.getNameCount() - 1); index++) {
            current = current.resolve(relative.getName(index));
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new DraftMappingException(code, "parent chain contains a symlink or non-directory");
            }
        }
    }

    /**
     * Opens a path only after a no-follow regular-file check. This avoids opening known FIFOs and
     * other special files. The path is rechecked after open when the provider exposes stable
     * identity attributes, but hostile replacement races cannot be eliminated by the Path API.
     */
    static FileChannel openRegularNoFollow(final Path path, final Set<? extends OpenOption> options, final String code) {
        FileChannel channel = null;
        try {
            final boolean mayCreate = options.contains(StandardOpenOption.CREATE)
                || options.contains(StandardOpenOption.CREATE_NEW);
            final BasicFileAttributes before = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                ? requireRegularAttributes(path, code)
                : null;
            if (before == null && !mayCreate) {
                throw new DraftMappingException(code, "path must be an existing regular file");
            }
            final java.util.HashSet<OpenOption> safeOptions = new java.util.HashSet<>(options);
            safeOptions.add(LinkOption.NOFOLLOW_LINKS);
            channel = FileChannel.open(path, safeOptions);
            final BasicFileAttributes after = requireRegularAttributes(path, code);
            if (before != null && identityChanged(before, after)) {
                throw new DraftMappingException(code, "path changed while it was being opened");
            }
            return channel;
        } catch (DraftMappingException exception) {
            closeSuppressed(channel, exception);
            throw exception;
        } catch (IOException | UnsupportedOperationException exception) {
            final DraftMappingException failure = new DraftMappingException(
                code, "could not open a regular non-symlink file", exception);
            closeSuppressed(channel, failure);
            throw failure;
        }
    }

    private static BasicFileAttributes requireRegularAttributes(final Path path, final String code) throws IOException {
        final BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new DraftMappingException(code, "path must be a regular non-symlink file");
        }
        return attributes;
    }

    static boolean identityChanged(final BasicFileAttributes before, final BasicFileAttributes after) {
        final Object beforeKey = before.fileKey();
        final Object afterKey = after.fileKey();
        return (beforeKey != null && afterKey != null && !beforeKey.equals(afterKey))
            || before.size() != after.size()
            || !before.creationTime().equals(after.creationTime())
            || !before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static void closeSuppressed(final FileChannel channel, final DraftMappingException failure) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    static void copyCreateNewNoFollow(
        final Path source,
        final Path target,
        final List<PublicationOwnership> ownedTargets,
        final String code
    ) {
        final int ownershipIndex = ownedTargets.size();
        BasicFileAttributes created = null;
        try (FileChannel output = openRegularNoFollow(
                 target, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), code)) {
            // CREATE_NEW acquired this pathname for this operation. Capture its filesystem
            // identity before reading or writing so cleanup can distinguish this file from a
            // competitor that later replaces the pathname.
            created = requireRegularAttributes(target, code);
            ownedTargets.add(new PublicationOwnership(target, created.fileKey(), created));
            try (FileChannel input = openRegularNoFollow(source, Set.of(StandardOpenOption.READ), code)) {
                input.transferTo(0, input.size(), output);
            }
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DraftMappingException(code, "could not copy into a newly owned target", exception);
        }
        try {
            final BasicFileAttributes completed = requireRegularAttributes(target, code);
            if (created.fileKey() != null
                && completed.fileKey() != null
                && created.fileKey().equals(completed.fileKey())) {
                ownedTargets.set(
                    ownershipIndex,
                    new PublicationOwnership(target, completed.fileKey(), completed)
                );
            }
        } catch (DraftMappingException | IOException exception) {
            // Keep the CREATE_NEW identity. Cleanup will retain a replacement pathname and attach
            // the identity diagnostic to the operation failure that triggered cleanup.
        }
    }

    static Digest digest(final Path path, final String code) {
        return digest(path, code, Long.MAX_VALUE, code);
    }

    static Digest digest(final Path path, final String code, final long maxBytes, final String limitCode) {
        try (FileChannel channel = openRegularNoFollow(path, Set.of(StandardOpenOption.READ), code);
             var input = Channels.newInputStream(channel)) {
            final MessageDigest digest = sha256Digest();
            final byte[] buffer = new byte[8192];
            long size = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                size += read;
                if (size > maxBytes) throw new DraftMappingException(limitCode, "artifact exceeds scan policy");
                digest.update(buffer, 0, read);
            }
            return new Digest(size, HexFormat.of().formatHex(digest.digest()));
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DraftMappingException(code, "could not read regular non-symlink file", exception);
        }
    }

    static byte[] readAllBytesNoFollow(final Path path, final String code) {
        try (FileChannel channel = openRegularNoFollow(path, Set.of(StandardOpenOption.READ), code);
             var input = Channels.newInputStream(channel);
             var output = new java.io.ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DraftMappingException(code, "could not read regular non-symlink file", exception);
        }
    }

    static Digest snapshot(final Path source, final Path snapshot, final long maxBytes) {
        try (FileChannel input = openRegularNoFollow(source, Set.of(StandardOpenOption.READ), "ARTIFACT_NOT_REGULAR");
             FileChannel output = FileChannel.open(snapshot, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
            final MessageDigest digest = sha256Digest();
            long size = 0;
            final var sourceStream = Channels.newInputStream(input);
            final var targetStream = Channels.newOutputStream(output);
            final byte[] buffer = new byte[8192];
            for (int read; (read = sourceStream.read(buffer)) != -1;) {
                size += read;
                if (size > maxBytes) throw new DraftMappingException("JAR_SIZE_LIMIT", "artifact exceeds scan policy");
                digest.update(buffer, 0, read);
                targetStream.write(buffer, 0, read);
            }
            targetStream.flush();
            output.force(true);
            return new Digest(size, HexFormat.of().formatHex(digest.digest()));
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DraftMappingException("ARTIFACT_SNAPSHOT_FAILED", "could not create private artifact snapshot", exception);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record PublicationOwnership(Path path, Object fileKey, BasicFileAttributes attributes) { }

    record Digest(long size, String sha256) { }
}

package dev.turboism.plugin.mcp;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Owner-only publication for files inside the plugin state directory. Every file
 * is written to a secured temporary sibling and atomically moved into place so
 * readers never observe a partially written file and no other account can claim
 * the path in between.
 */
final class McpStateFiles {

    private static final Set<PosixFilePermission> FILE_OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIRECTORY_OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    private McpStateFiles() {}

    /**
     * Atomically publishes {@code content} at {@code target}, requiring the whole
     * target directory chain and the final file to stay owner-only. A pre-existing
     * symlink, non-regular file, or foreign-owned file at the target is rejected
     * rather than overwritten.
     */
    static void publish(final Path target, final String temporaryPrefix, final byte[] content) throws IOException {
        final Path directory = Objects.requireNonNull(target.getParent(), "publication directory");
        requirePrivateDirectory(directory);
        rejectUnsafeTarget(target);
        final Path temporary = createSecuredTemporary(directory, temporaryPrefix);
        try {
            Files.write(temporary, content);
            enforceOwnerOnly(temporary, false);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw new IOException("MCP state file publication was redirected");
            }
            enforceOwnerOnly(target, false);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Accepts a previously published file only when it is a regular, non-symlink
     * file owned by the same account that owns its directory.
     */
    static void requireTrustedFile(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("MCP state file path is unsafe: " + file.getFileName());
        }
        final Path directory = Objects.requireNonNull(file.getParent(), "state file directory");
        if (!Files.getOwner(file, LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("MCP state file ownership is unsafe");
        }
    }

    private static void rejectUnsafeTarget(final Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        requireTrustedFile(target);
    }

    private static Path createSecuredTemporary(final Path directory, final String prefix) throws IOException {
        try {
            return Files.createTempFile(
                    directory, prefix, ".tmp", PosixFilePermissions.asFileAttribute(FILE_OWNER_ONLY));
        } catch (UnsupportedOperationException noPosix) {
            final Path temporary = Files.createTempFile(directory, prefix, ".tmp");
            try {
                enforceOwnerOnly(temporary, false);
                return temporary;
            } catch (IOException | RuntimeException failure) {
                Files.deleteIfExists(temporary);
                throw failure;
            }
        }
    }

    static void requirePrivateDirectory(final Path directory) throws IOException {
        final Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path segment : absolute) {
            current = current == null ? segment : current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("MCP state directory contains a symbolic link");
            }
        }
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("MCP state directory is unsafe");
        }
        enforceOwnerOnly(absolute, true);
    }

    private static void enforceOwnerOnly(final Path path, final boolean directory) throws IOException {
        final PosixFileAttributeView posix =
                Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(directory ? DIRECTORY_OWNER_ONLY : FILE_OWNER_ONLY);
            return;
        }
        final AclFileAttributeView acl =
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            acl.setAcl(List.of(ownerOnlyEntry(acl.getOwner(), directory)));
            return;
        }
        final java.nio.file.attribute.DosFileAttributeView dos = Files.getFileAttributeView(
                path, java.nio.file.attribute.DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            // The JDK provider exposes no ACL API. The plugin state root is per-user;
            // ownership and reparse-point checks remain the publication boundary.
            return;
        }
        throw new IOException("MCP owner-only permissions are unavailable");
    }

    private static AclEntry ownerOnlyEntry(final java.nio.file.attribute.UserPrincipal owner, final boolean directory) {
        final Set<AclEntryPermission> permissions = EnumSet.noneOf(AclEntryPermission.class);
        permissions.add(AclEntryPermission.READ_DATA);
        permissions.add(AclEntryPermission.WRITE_DATA);
        permissions.add(AclEntryPermission.APPEND_DATA);
        permissions.add(AclEntryPermission.READ_ATTRIBUTES);
        permissions.add(AclEntryPermission.WRITE_ATTRIBUTES);
        permissions.add(AclEntryPermission.READ_NAMED_ATTRS);
        permissions.add(AclEntryPermission.WRITE_NAMED_ATTRS);
        permissions.add(AclEntryPermission.READ_ACL);
        permissions.add(AclEntryPermission.WRITE_ACL);
        permissions.add(AclEntryPermission.SYNCHRONIZE);
        if (directory) {
            permissions.add(AclEntryPermission.EXECUTE);
            permissions.add(AclEntryPermission.DELETE_CHILD);
        }
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(permissions)
                .build();
    }
}

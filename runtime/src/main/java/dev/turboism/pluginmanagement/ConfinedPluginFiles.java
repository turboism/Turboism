package dev.turboism.pluginmanagement;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Minimal confined filesystem guard for Runtime-owned plugin state. */
final class ConfinedPluginFiles {
    private final Path home;

    ConfinedPluginFiles(final Path requestedHome) {
        home = requestedHome.toAbsolutePath().normalize();
    }

    ParentIdentity parent(final Path requested) throws IOException {
        final Path path = confined(requested);
        rejectLinks(path);
        final Path parent = path.getParent();
        if (parent == null) throw new IOException("path has no parent");
        Files.createDirectories(parent);
        rejectLinks(parent);
        return new ParentIdentity(parent, attributes(parent));
    }

    SeekableByteChannel createNew(final Path path, final ParentIdentity parent) throws IOException {
        parent.verify();
        if (!path.toAbsolutePath().normalize().getParent().equals(parent.path())) throw new IOException("parent changed");
        return Files.newByteChannel(path, Set.<OpenOption>of(
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
        ));
    }

    void move(final Path source, final Path target, final ParentIdentity parent, final boolean replace) throws IOException {
        parent.verify();
        rejectLinks(source);
        final var options = replace
            ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
            : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        Files.move(source, target, options);
        parent.verify();
    }

    void delete(final Path path, final ParentIdentity parent) throws IOException {
        parent.verify();
        rejectLinks(path);
        Files.deleteIfExists(path);
        parent.verify();
    }

    Path confined(final Path requested) throws IOException {
        final Path path = requested.toAbsolutePath().normalize();
        if (!path.startsWith(home)) throw new IOException("path escaped Turboism home");
        return path;
    }

    void rejectLinks(final Path requested) throws IOException {
        Path path = confined(requested);
        while (true) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
                throw new IOException("symbolic link rejected");
            }
            if (path.equals(home)) return;
            path = path.getParent();
            if (path == null) throw new IOException("path escaped Turboism home");
        }
    }

    private static BasicFileAttributes attributes(final Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    record ParentIdentity(Path path, BasicFileAttributes attributes) {
        void verify() throws IOException {
            if (Files.isSymbolicLink(path)) throw new IOException("parent became symbolic link");
            final BasicFileAttributes current = ConfinedPluginFiles.attributes(path);
            if (!current.isDirectory()
                || !java.util.Objects.equals(attributes.fileKey(), current.fileKey())
                || !attributes.creationTime().equals(current.creationTime())) {
                throw new IOException("parent identity changed");
            }
        }
    }
}

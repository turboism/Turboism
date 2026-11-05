package dev.turboism.pluginmanagement;

import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.PublishGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Minimal confined filesystem guard for Runtime-owned plugin state. */
final class ConfinedPluginFiles {
    private final Path home;
    private final AttributeReader reader;

    ConfinedPluginFiles(final Path requestedHome) {
        this(requestedHome, ConfinedPluginFiles::attributes);
    }

    ConfinedPluginFiles(final Path requestedHome, final AttributeReader reader) {
        home = requestedHome.toAbsolutePath().normalize();
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    ParentIdentity parent(final Path requested) throws IOException {
        final Path path = confined(requested);
        rejectLinks(path);
        final Path parent = path.getParent();
        if (parent == null) throw new IOException("path has no parent");
        Files.createDirectories(parent);
        rejectLinks(parent);
        return new ParentIdentity(parent, reader.read(parent), reader);
    }

    /** Publishes {@code bytes} at {@code target} through the durable-write module. */
    void writeBytes(final Path target, final byte[] bytes, final ParentIdentity parent, final boolean replace)
            throws IOException {
        final Path path = confined(target);
        parent.verify();
        if (!path.getParent().equals(parent.path())) throw new IOException("parent changed");
        publication(path, parent, replace).replace(bytes);
    }

    /**
     * Publishes an already written sibling temporary file. The temporary is left in place when
     * publication fails, so the caller keeps owning its cleanup.
     */
    void publish(final Path temporary, final Path target, final ParentIdentity parent, final boolean replace)
            throws IOException {
        final Path path = confined(target);
        rejectLinks(temporary);
        parent.verify();
        if (!path.getParent().equals(parent.path())) throw new IOException("parent changed");
        publication(path, parent, replace).adoptKeepingSource(temporary);
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

    /** A replace-or-create-once handle that rechecks the parent identity across the rename. */
    private DurableFile publication(final Path target, final ParentIdentity parent, final boolean replace) {
        return (replace ? DurableFile.at(target) : DurableFile.createOnce(target)).guardedBy(identityGuard(parent));
    }

    private PublishGuard identityGuard(final ParentIdentity parent) {
        return new PublishGuard() {
            @Override
            public void beforePublish(final Path staged, final Path target) throws IOException {
                rejectLinks(staged);
                parent.verify();
            }

            @Override
            public void afterPublish(final Path staged, final Path target) throws IOException {
                parent.verify();
            }
        };
    }

    private static BasicFileAttributes attributes(final Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    @FunctionalInterface
    interface AttributeReader {
        BasicFileAttributes read(Path path) throws IOException;
    }

    record ParentIdentity(Path path, BasicFileAttributes attributes, AttributeReader reader) {
        void verify() throws IOException {
            if (Files.isSymbolicLink(path)) throw new IOException("parent became symbolic link");
            final BasicFileAttributes current = reader.read(path);
            final Object expectedKey = attributes.fileKey();
            if (!current.isDirectory() || (expectedKey != null && !Objects.equals(expectedKey, current.fileKey()))) {
                throw new IOException("parent identity changed");
            }
        }
    }
}

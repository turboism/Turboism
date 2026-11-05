package dev.turboism.distribution;

import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.PublishGuard;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;

/** Confines package extraction to an existing, stable, non-link staging directory. */
final class ConfinedStagingFiles {
    private ConfinedStagingFiles() {}

    static Target create(final Path requestedDirectory, final String targetName) throws IOException {
        return create(requestedDirectory, targetName, ConfinedStagingFiles::attributes);
    }

    static Target create(final Path requestedDirectory, final String targetName, final AttributeReader reader)
            throws IOException {
        final Path directory = requestedDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        rejectLinks(directory);
        final BasicFileAttributes identity = reader.read(directory);
        final Path target = directory.resolve(targetName).normalize();
        if (!target.getParent().equals(directory)) throw new IOException("staging target escaped");
        final Path temporary = directory.resolve("." + targetName + "-" + UUID.randomUUID() + ".tmp");
        // Windows rejects NOFOLLOW_LINKS on the stream factory; FileChannel preserves the guard.
        final var output = Channels.newOutputStream(FileChannel.open(
                temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
        return new Target(directory, identity, temporary, target, output, reader);
    }

    private static void rejectLinks(Path path) throws IOException {
        while (path != null) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
                throw new IOException("symbolic link staging path rejected");
            }
            path = path.getParent();
        }
    }

    private static BasicFileAttributes attributes(final Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    @FunctionalInterface
    interface AttributeReader {
        BasicFileAttributes read(Path path) throws IOException;
    }

    record Target(
            Path directory,
            BasicFileAttributes identity,
            Path temporary,
            Path target,
            java.io.OutputStream output,
            AttributeReader reader) {
        void publish() throws IOException {
            output.close();
            verify();
            // The staged name is content-addressed, so re-staging the same package publishes the
            // same bytes over the previous one instead of failing on an existing name.
            DurableFile.at(target).guardedBy(publishGuard()).adoptKeepingSource(temporary);
        }

        private PublishGuard publishGuard() {
            return new PublishGuard() {
                @Override
                public void beforePublish(final Path staged, final Path published) throws IOException {
                    verify();
                }

                @Override
                public void afterPublish(final Path staged, final Path published) throws IOException {
                    verify();
                }
            };
        }

        void cleanup() {
            try {
                output.close();
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }

        private void verify() throws IOException {
            rejectLinks(directory);
            final BasicFileAttributes current = reader.read(directory);
            final Object expectedKey = identity.fileKey();
            final Object currentKey = current.fileKey();
            if (!current.isDirectory() || (expectedKey != null && !Objects.equals(expectedKey, currentKey))) {
                throw new IOException("staging directory identity changed");
            }
        }
    }
}

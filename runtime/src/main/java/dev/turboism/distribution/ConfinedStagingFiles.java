package dev.turboism.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/** Confines package extraction to an existing, stable, non-link staging directory. */
final class ConfinedStagingFiles {
    private ConfinedStagingFiles() { }

    static Target create(final Path requestedDirectory, final String targetName) throws IOException {
        return create(requestedDirectory, targetName, ConfinedStagingFiles::attributes);
    }

    static Target create(final Path requestedDirectory, final String targetName,
                         final AttributeReader reader) throws IOException {
        final Path directory = requestedDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        rejectLinks(directory);
        final BasicFileAttributes identity = reader.read(directory);
        final Path target = directory.resolve(targetName).normalize();
        if (!target.getParent().equals(directory)) throw new IOException("staging target escaped");
        final Path temporary = directory.resolve("." + targetName + "-" + UUID.randomUUID() + ".tmp");
        final var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
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
    interface AttributeReader { BasicFileAttributes read(Path path) throws IOException; }

    record Target(Path directory, BasicFileAttributes identity, Path temporary, Path target,
                  java.io.OutputStream output, AttributeReader reader) {
        void publish() throws IOException {
            output.close();
            verify();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            verify();
        }

        void cleanup() { try { output.close(); } catch (IOException ignored) { }
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }

        private void verify() throws IOException {
            rejectLinks(directory);
            final BasicFileAttributes current = reader.read(directory);
            final Object expectedKey = identity.fileKey();
            final Object currentKey = current.fileKey();
            if (!current.isDirectory()
                || (expectedKey != null && !java.util.Objects.equals(expectedKey, currentKey))) {
                throw new IOException("staging directory identity changed");
            }
        }
    }
}

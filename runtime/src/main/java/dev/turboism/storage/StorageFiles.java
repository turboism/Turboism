package dev.turboism.storage;

import dev.turboism.sdk.storage.StorageEntry;
import dev.turboism.sdk.storage.StorageEntryType;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Package-private primitives shared by the confined storage backend. */
final class StorageFiles {

    private StorageFiles() {
    }

    static StorageReadResult<byte[]> readBounded(
        final InputStream input,
        final int maxBytes
    ) throws IOException, InterruptedException {
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

    static byte[] readCopySource(
        final Path sourcePath,
        final int maxBytes
    ) throws IOException, InterruptedException, TooLargeException {
        try (InputStream input = Files.newInputStream(
            sourcePath,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        )) {
            final StorageReadResult<byte[]> read = readBounded(input, maxBytes);
            if (read.truncated()) {
                throw new TooLargeException();
            }
            return read.value().orElseThrow();
        }
    }

    static void writeDurably(final Path temporary, final byte[] content)
        throws IOException, InterruptedException {
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

    static List<Path> children(final Path directory)
        throws IOException, InterruptedException {
        final List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                checkCanceled();
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return children;
    }

    static StorageEntry entry(
        final StoragePath directory,
        final Path child
    ) throws IOException {
        final StorageEntryType type = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
            ? StorageEntryType.DIRECTORY
            : StorageEntryType.FILE;
        return new StorageEntry(
            new StoragePath(
                directory.root(),
                directory.relativePath() + "/" + child.getFileName()
            ),
            type,
            type == StorageEntryType.FILE ? Files.size(child) : 0L
        );
    }

    private static void checkCanceled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    static final class TooLargeException extends Exception {
        private TooLargeException() {
        }
    }
}

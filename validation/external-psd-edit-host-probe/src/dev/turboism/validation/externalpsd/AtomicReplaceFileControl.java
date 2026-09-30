package dev.turboism.validation.externalpsd;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Properties;

/** Independent small-file controls; never retries or opens the issued PSD. */
final class AtomicReplaceFileControl {
    private static final byte[] OLD = {1, 2, 3};
    private static final byte[] NEW = {4, 5, 6};

    private AtomicReplaceFileControl() { }

    static void observe(Properties result, Path issuedPsd) {
        final String prefix = "atomicFileControl.";
        result.setProperty(prefix + "status", "UNAVAILABLE");
        result.setProperty(prefix + "semantics",
            "independent small files; each pair moved once; no issued PSD mutation or retry");
        final long started = System.nanoTime();
        final long deadline = started + 2_000_000_000L;
        try {
            final Path parent = issuedPsd.toAbsolutePath().getParent();
            if (parent == null || !parent.toRealPath().equals(parent))
                throw new IOException("control parent is not canonical");
            final Path root = Files.createTempDirectory(parent, "atomic-file-control-");
            if (!root.toRealPath().equals(root))
                throw new IOException("control directory identity changed");
            result.setProperty(prefix + "directory", root.getFileName().toString());
            for (String mode : new String[] {"unopened", "heldRead", "closedRead"}) {
                if (System.nanoTime() >= deadline) throw new IOException("control deadline exhausted");
                final Path target = root.resolve(mode + ".target.bin");
                final Path incoming = root.resolve(mode + ".incoming.bin");
                Files.write(target, OLD, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                Files.write(incoming, NEW, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                if (mode.equals("heldRead")) {
                    try (FileChannel owned = FileChannel.open(target,
                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                        if (!owned.isOpen()) throw new IOException("control channel not open");
                        moveOnce(result, prefix + mode + ".", incoming, target);
                    }
                } else {
                    if (mode.equals("closedRead")) {
                        try (FileChannel owned = FileChannel.open(target,
                            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                            if (!owned.isOpen()) throw new IOException("control channel not open");
                        }
                    }
                    moveOnce(result, prefix + mode + ".", incoming, target);
                }
                final boolean moved = "MOVED".equals(result.getProperty(prefix + mode + ".status"));
                final boolean expected = Arrays.equals(Files.readAllBytes(target), moved ? NEW : OLD)
                    && (moved ? !Files.exists(incoming)
                        : Arrays.equals(Files.readAllBytes(incoming), NEW));
                result.setProperty(prefix + mode + ".contentConsistent", Boolean.toString(expected));
                if (!expected) throw new IOException("unexpected control file contents");
            }
            result.setProperty(prefix + "status", "OBSERVED");
        } catch (Exception | LinkageError | OutOfMemoryError failure) {
            result.setProperty(prefix + "diagnostic", failure.getClass().getSimpleName());
        } finally {
            result.setProperty(prefix + "elapsedNanos", Long.toString(System.nanoTime() - started));
        }
    }

    private static void moveOnce(Properties result, String prefix, Path incoming, Path target) {
        try {
            Files.move(incoming, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            result.setProperty(prefix + "status", "MOVED");
        } catch (IOException failure) {
            result.setProperty(prefix + "status", "REJECTED");
            result.setProperty(prefix + "exception", failure.getClass().getSimpleName());
        }
    }
}

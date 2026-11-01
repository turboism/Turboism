package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameworkUpdateLockTest {
    @TempDir
    Path temporary;

    /** Real second JVM; failures outside acquisition remain process failures. */
    public static final class Probe {
        public static void main(final String[] args) throws Exception {
            final AutoCloseable acquired;
            try {
                acquired = args[0].equals("cache")
                        ? new FrameworkUpdateCache(Path.of(args[1]))
                        : FrameworkUpdateLock.acquire(Path.of(args[1]));
            } catch (IOException refused) {
                System.out.println("REFUSED");
                return;
            }
            try (acquired) {
                System.out.println("ACQUIRED");
            }
        }
    }

    @Test
    void rejectedLocalCacheWriterCannotReleaseTheOwnersProcessLock() throws Exception {
        final Path cache = temporary.resolve("cache");
        try (var owner = new FrameworkUpdateCache(cache)) {
            assertEquals("REFUSED", probe("cache", cache));
            assertThrows(IOException.class, () -> new FrameworkUpdateCache(cache));
            assertEquals("REFUSED", probe("cache", cache));
        }
        assertEquals("ACQUIRED", probe("cache", cache));
    }

    @Test
    void closingAnOldOwnerAgainCannotReleaseItsSuccessor() throws Exception {
        final Path work = temporary.resolve("work");
        final var old = FrameworkUpdateLock.acquire(work);
        old.close();
        try (var current = FrameworkUpdateLock.acquire(work)) {
            old.close();
            assertThrows(IOException.class, () -> FrameworkUpdateLock.acquire(work));
            assertEquals("REFUSED", probe("transaction", work));
        }
        assertEquals("ACQUIRED", probe("transaction", work));
    }

    @Test
    void failedOpenDoesNotLeaveThePathReserved() throws Exception {
        final Path work = temporary.resolve("work");
        final Path lock = temporary.resolve("helper-transaction.lock");
        Files.createDirectory(lock);
        assertThrows(IOException.class, () -> FrameworkUpdateLock.acquire(work));
        Files.delete(lock);
        try (var acquired = FrameworkUpdateLock.acquire(work)) {
            assertEquals("REFUSED", probe("transaction", work));
        }
    }

    @Test
    void separateInstallationsCanHoldTheirLocksTogether() throws Exception {
        final Path first = temporary.resolve("first/work");
        final Path second = temporary.resolve("second/work");
        try (var firstLock = FrameworkUpdateLock.acquire(first);
                var secondLock = FrameworkUpdateLock.acquire(second)) {
            assertEquals("REFUSED", probe("transaction", first));
            assertEquals("REFUSED", probe("transaction", second));
        }
    }

    private String probe(final String kind, final Path path) throws Exception {
        final Path output = temporary.resolve("probe.log");
        final Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Probe.class.getName(),
                        kind,
                        path.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "second JVM timed out");
            final String result =
                    Files.readString(output, StandardCharsets.UTF_8).trim();
            assertEquals(0, process.exitValue(), result);
            return result;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}

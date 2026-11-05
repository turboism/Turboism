package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression protection for the non-typed legacy {@code config.json} family (#111).
 *
 * <p>Every case drives the production {@link RuntimeConfigRepository}; no test doubles or a
 * second reader/writer implementation are involved. The central invariant is fail-closed reads
 * and writes: a document this build does not understand must never be rewritten, truncated or
 * replaced by defaults.</p>
 */
class RuntimeConfigNonTypedCompatibilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private Path config() {
        return home.resolve("config.json");
    }

    private byte[] write(final String document) throws Exception {
        Files.writeString(config(), document);
        return Files.readAllBytes(config());
    }

    private byte[] validDocument(final java.util.function.Consumer<ObjectNode> customizer) throws Exception {
        final ObjectNode root = RuntimeConfigRepository.defaults();
        customizer.accept(root);
        return write(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private static void assertRejected(final String expectedCode, final Runnable action) {
        final IllegalStateException failure = assertThrows(IllegalStateException.class, action::run);
        assertEquals(expectedCode, failure.getMessage());
    }

    @Test
    void futureSchemaVersionIsRefusedOnReadAndUpdateWithoutRewritingTheFile() throws Exception {
        final byte[] original = validDocument(root -> root.put("schemaVersion", 2));
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});

        assertRejected("RUNTIME_CONFIG_INVALID", repository::read);
        assertArrayEquals(original, Files.readAllBytes(config()), "a refused read must not rewrite the document");
        assertRejected(
                "RUNTIME_CONFIG_INVALID",
                () -> repository.update(root -> {
                    root.put("safeMode", true);
                    return root;
                }));
        assertArrayEquals(original, Files.readAllBytes(config()), "a refused update must not rewrite the document");
    }

    @Test
    void unknownFieldsAreRefusedOnReadAndUpdateWithoutRewritingTheFile() throws Exception {
        final byte[] original = validDocument(root -> {
            root.putObject("futureSettings").put("enabled", true);
            root.putObject("launcher").put("futureLauncherFlag", true);
        });
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});

        assertRejected("RUNTIME_CONFIG_INVALID", repository::read);
        assertArrayEquals(original, Files.readAllBytes(config()));
        assertRejected(
                "RUNTIME_CONFIG_INVALID",
                () -> repository.update(root -> {
                    root.put("safeMode", true);
                    return root;
                }));
        assertArrayEquals(original, Files.readAllBytes(config()), "unknown data must survive a rejected update");
    }

    @Test
    void duplicateJsonKeysAreRefusedInsteadOfLastValueWins() throws Exception {
        final byte[] original = write("""
                {
                  "format": "turboism.runtime.config",
                  "schemaVersion": 1,
                  "worktreeId": "first",
                  "worktreeId": "second",
                  "pluginDirs": ["plugins"],
                  "logLevel": "INFO",
                  "safeMode": false,
                  "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
                }
                """);
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});

        assertRejected("RUNTIME_CONFIG_UNREADABLE", repository::read);
        assertArrayEquals(original, Files.readAllBytes(config()));
    }

    @Test
    void trailingJsonTokensAreRefusedInsteadOfSilentlyDiscarded() throws Exception {
        final byte[] original = write("""
                {
                  "format": "turboism.runtime.config",
                  "schemaVersion": 1,
                  "worktreeId": "trailing",
                  "pluginDirs": ["plugins"],
                  "logLevel": "INFO",
                  "safeMode": false,
                  "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
                }
                {"future": true}
                """);
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});

        assertRejected("RUNTIME_CONFIG_UNREADABLE", repository::read);
        assertArrayEquals(original, Files.readAllBytes(config()));
    }

    @Test
    void corruptJsonIsRefusedOnReadAndUpdateWithoutRewritingTheFile() throws Exception {
        final byte[] original = write("""
                {
                  "format": "turboism.runtime.config",
                  "schemaVersion": 1,
                  "worktreeId": "truncated",
                """);
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});

        assertRejected("RUNTIME_CONFIG_UNREADABLE", repository::read);
        assertArrayEquals(original, Files.readAllBytes(config()));
        assertRejected(
                "RUNTIME_CONFIG_UNREADABLE",
                () -> repository.update(root -> {
                    root.put("safeMode", true);
                    return root;
                }));
        assertArrayEquals(original, Files.readAllBytes(config()), "corrupt bytes must never be replaced silently");
    }

    @Test
    void invalidFieldValuesAreRefusedOnReadAndUpdateWithoutRewritingTheFile() throws Exception {
        final byte[] original = validDocument(root -> root.put("logLevel", "LOUD"));
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});

        assertRejected("RUNTIME_CONFIG_INVALID", repository::read);
        assertArrayEquals(original, Files.readAllBytes(config()));
        assertRejected(
                "RUNTIME_CONFIG_INVALID",
                () -> repository.update(root -> {
                    root.put("safeMode", true);
                    return root;
                }));
        assertArrayEquals(original, Files.readAllBytes(config()));
    }

    @Test
    void rejectedUpdateDoesNotReplaceTheOriginalBytes() throws Exception {
        final byte[] original = validDocument(root -> {});
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});

        assertRejected(
                "RUNTIME_CONFIG_WRITE_INVALID",
                () -> repository.update(root -> {
                    root.put("maxLogStorageMiB", -1);
                    return root;
                }));
        assertArrayEquals(original, Files.readAllBytes(config()));
    }

    @Test
    void unrelatedUpdatePreservesToleratedLocaleAndMemoryProfileOnDisk() throws Exception {
        final byte[] original = validDocument(root -> {
            root.put("locale", "fr");
            root.putObject("launcher").put("memoryProfile", "huge8g");
        });
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});

        final ObjectNode updated = repository.update(root -> {
            root.put("safeMode", true);
            return root;
        });

        assertTrue(updated.path("safeMode").asBoolean());
        assertEquals("fr", updated.path("locale").asText(), "update returns the document as persisted");
        final String persisted = Files.readString(config());
        assertTrue(persisted.contains("\"locale\" : \"fr\""), "the tolerated locale must survive an unrelated update");
        assertTrue(
                persisted.contains("\"memoryProfile\" : \"huge8g\""),
                "the tolerated memory profile must survive an unrelated update");
        assertFalse(
                java.util.Arrays.equals(original, Files.readAllBytes(config())),
                "the unrelated change itself must still be written");
    }

    @Test
    void failedIoWriteLeavesTheOriginalBytesInPlace() throws Exception {
        final byte[] original = validDocument(root -> {});
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});
        Assumptions.assumeTrue(
                Files.getFileStore(home).supportsFileAttributeView("posix"), "POSIX permission semantics required");
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root bypasses directory permissions");
        final var originalPermissions = Files.getPosixFilePermissions(home);
        try {
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("r-x------"));
            assertRejected(
                    "RUNTIME_CONFIG_WRITE_FAILED",
                    () -> repository.update(root -> {
                        root.put("safeMode", true);
                        return root;
                    }));
        } finally {
            Files.setPosixFilePermissions(home, originalPermissions);
        }
        assertArrayEquals(original, Files.readAllBytes(config()));
        try (var entries = Files.list(home)) {
            assertFalse(
                    entries.anyMatch(path -> path.getFileName().toString().startsWith(".config-")),
                    "a failed write must clean up its temporary sibling");
        }
    }

    @Test
    void concurrentUpdatesSerializeWithoutLosingFieldsOrCorruptingTheDocument() throws Exception {
        validDocument(root -> {});
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});
        final List<String> flags = List.of(
                "zgc",
                "modelUpdateSkip",
                "incrementalUpdate",
                "uniformLocationCache",
                "uploadElision",
                "meshInterpolation",
                "shaderUniform",
                "matrixScratch");
        final ExecutorService pool = Executors.newFixedThreadPool(flags.size());
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (final String flag : flags) {
                futures.add(pool.submit(() -> {
                    start.await();
                    repository.update(root -> {
                        root.withObject("launcher").put(flag, true);
                        return root;
                    });
                    return null;
                }));
            }
            start.countDown();
            for (final Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        final JsonNode persisted = JSON.readTree(config().toFile());
        assertEquals("turboism.runtime.config", persisted.path("format").asText());
        for (final String flag : flags) {
            assertTrue(persisted.path("launcher").path(flag).asBoolean(), "lost concurrent field " + flag);
        }
        try (var entries = Files.list(home)) {
            assertFalse(
                    entries.anyMatch(path -> path.getFileName().toString().startsWith(".config-")),
                    "concurrent writes must not leave temporary siblings");
        }
    }

    @Test
    void nonRegularConfigPathIsRejectedAndNeverReplaced() throws Exception {
        Files.createDirectory(config());
        final RuntimeConfigRepository repository = new RuntimeConfigRepository(home, ignored -> {});

        assertRejected("RUNTIME_CONFIG_FILE_REJECTED", repository::read);
        assertTrue(Files.isDirectory(config()), "the refused path must stay untouched");
    }
}

package dev.turboism.script;

import dev.turboism.sdk.script.ScriptId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScriptRegistryTest {

    @TempDir
    Path home;

    @Test
    void discoversValidScriptsInStableIdOrder() throws Exception {
        writeScript("z-last", "z.example", "print('z');");
        writeScript("a-first", "a.example", "print('a');");

        final List<String> diagnostics = new ArrayList<>();
        final ScriptRegistry registry = new ScriptRegistry(home, diagnostics::add);

        assertEquals(
            List.of(new ScriptId("a.example"), new ScriptId("z.example")),
            registry.discover().stream().map(script -> script.descriptor().id()).toList()
        );
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void rejectsEveryScriptInADuplicateIdGroup() throws Exception {
        writeScript("z-first-on-disk", "same.example", "print('one');");
        writeScript("a-second-on-disk", "same.example", "print('two');");
        writeScript("other", "other.example", "print('other');");

        final List<String> diagnostics = new ArrayList<>();
        final ScriptRegistry registry = new ScriptRegistry(home, diagnostics::add);

        assertEquals(
            List.of(new ScriptId("other.example")),
            registry.discover().stream().map(script -> script.descriptor().id()).toList()
        );
        assertTrue(registry.find(new ScriptId("same.example")).isEmpty());
        assertTrue(diagnostics.stream().anyMatch(message -> message.equals("SCRIPT_DUPLICATE_ID: same.example")));
    }

    @Test
    void rejectsTopLevelScriptsRootSymlink() throws Exception {
        final Path outsideHome = home.resolve("outside-home");
        writeScript(outsideHome, "outside", "outside.example", "print('escaped');");
        try {
            Files.createSymbolicLink(home.resolve("scripts"), outsideHome.resolve("scripts"));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            Assumptions.abort("symbolic links are not available on this filesystem");
        }

        final List<String> diagnostics = new ArrayList<>();
        final ScriptRegistry registry = new ScriptRegistry(home, diagnostics::add);

        assertTrue(registry.discover().isEmpty());
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void rejectsTopLevelScriptDirectorySymlink() throws Exception {
        final Path outside = home.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("main.js"), "print('escaped');");
        Files.writeString(outside.resolve("script.json"), manifest("linked.example", "print('escaped');"));
        Files.createDirectories(home.resolve("scripts"));
        try {
            Files.createSymbolicLink(home.resolve("scripts/linked"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            Assumptions.abort("symbolic links are not available on this filesystem");
        }

        final List<String> diagnostics = new ArrayList<>();
        final ScriptRegistry registry = new ScriptRegistry(home, diagnostics::add);

        assertTrue(registry.discover().isEmpty());
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void admitsTheSameLexicographicCandidatesWhenTheDirectoryExceedsTheLimit() throws Exception {
        for (int index = 0; index <= 256; index++) {
            final String suffix = "%03d".formatted(index);
            writeScript("candidate-" + suffix, "script-" + suffix, "print('" + suffix + "');");
        }
        final List<String> diagnostics = new ArrayList<>();

        final ScriptRegistry registry = new ScriptRegistry(home, diagnostics::add);

        assertEquals(
            java.util.stream.IntStream.range(0, 256)
                .mapToObj(index -> new ScriptId("script-%03d".formatted(index)))
                .toList(),
            registry.discover().stream().map(script -> script.descriptor().id()).toList()
        );
        assertTrue(diagnostics.stream().anyMatch(message -> message.startsWith("SCRIPT_LIMIT_REACHED:")));
    }

    @Test
    @ResourceLock("os.name")
    void acceptsOrdinaryPathsWhenWindowsReparseMetadataIsUnavailable() throws Exception {
        writeScript("ordinary", "ordinary.example", "print('ordinary');");
        final String originalOsName = System.getProperty("os.name");
        System.setProperty("os.name", "Windows 11");
        try {
            final ScriptRegistry registry = new ScriptRegistry(home, ignored -> { });

            assertEquals(
                List.of(new ScriptId("ordinary.example")),
                registry.discover().stream().map(script -> script.descriptor().id()).toList()
            );
        } finally {
            if (originalOsName == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOsName);
            }
        }
    }

    @Test
    void rejectsLegacySchemaVersionWithoutDigestPinning() throws Exception {
        final Path root = writeScript("legacy", "legacy.example", "print('legacy');");
        Files.writeString(root.resolve("script.json"), manifest(
            "legacy.example", "print('legacy');"
        ).replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
            .replaceAll("(?m)^\\s*\"sourceSha256\".*\\R", ""));
        final List<String> diagnostics = new ArrayList<>();

        final ScriptRegistry registry = new ScriptRegistry(home, diagnostics::add);

        assertTrue(registry.discover().isEmpty());
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains(
            "Only script schemaVersion 2 is supported"
        )));
    }

    @Test
    void rejectsNonIntegralSchemaVersion() throws Exception {
        final Path root = writeScript("schema-fraction", "schema.example", "print('x');");
        Files.writeString(root.resolve("script.json"), manifest("schema.example", "print('x');")
            .replace("\"schemaVersion\": 2", "\"schemaVersion\": 2.9"));
        final List<String> diagnostics = new ArrayList<>();

        final ScriptRegistry registry = new ScriptRegistry(home, diagnostics::add);

        assertTrue(registry.discover().isEmpty());
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains(
            "Only script schemaVersion 2 is supported"
        )));
    }

    @Test
    void acceptsSourceReturnedToItsOriginalContentAfterDiscovery() throws Exception {
        final Path root = writeScript("aba", "aba.example", "print('original');");
        final Path source = root.resolve("main.js");
        final var directoryModified = Files.getLastModifiedTime(root);
        final ScriptRegistry registry = new ScriptRegistry(home, ignored -> { });
        final ScriptRegistry.InstalledScript script = registry.find(
            new ScriptId("aba.example")
        ).orElseThrow();
        final Path parkedOriginal = source.resolveSibling("parked-original.js");
        Files.move(source, parkedOriginal);
        Files.writeString(source, "print('replacement');");
        Files.delete(source);
        Files.move(parkedOriginal, source);
        Files.setLastModifiedTime(root, directoryModified);

        assertEquals("print('original');", script.source());
    }

    @Test
    void rejectsConfiguredHomeThatIsASymlink() throws Exception {
        final Path realHome = home.resolve("real-home");
        writeScript(realHome, "script", "ancestor.example", "print('safe');");
        final Path linkedHome = home.resolve("linked-home");
        try {
            Files.createSymbolicLink(linkedHome, realHome);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            Assumptions.abort("symbolic links are not available on this filesystem");
        }

        final ScriptRegistry registry = new ScriptRegistry(linkedHome, ignored -> { });

        assertTrue(registry.discover().isEmpty());
    }

    @Test
    void confinesLinkChecksToTheConfiguredHomeBoundary() throws Exception {
        final Path realParent = home.resolve("real-parent");
        final Path linkedParent = home.resolve("linked-parent");
        try {
            Files.createDirectories(realParent);
            Files.createSymbolicLink(linkedParent, realParent);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            Assumptions.abort("symbolic links are not available on this filesystem");
        }
        final Path configuredHome = linkedParent.resolve("turboism-home");
        writeScript(configuredHome, "script", "boundary.example", "print('safe');");

        final ScriptRegistry registry = new ScriptRegistry(configuredHome, ignored -> { });

        assertEquals(
            List.of(new ScriptId("boundary.example")),
            registry.discover().stream().map(script -> script.descriptor().id()).toList()
        );
    }

    @Test
    void rejectsEntryThatEscapesThroughAnIntermediateSymlink() throws Exception {
        final Path outside = home.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("main.js"), "print('escaped');");

        final Path root = home.resolve("scripts/linked-entry");
        Files.createDirectories(root);
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            Assumptions.abort("symbolic links are not available on this filesystem");
        }
        Files.writeString(root.resolve("script.json"), """
            {
              "schemaVersion": 2,
              "id": "linked.example",
              "name": "Linked entry",
              "version": "1.0.0",
              "language": "js",
              "entry": "linked/main.js",
              "sourceSha256": "%s",
              "permissions": []
            }
            """.formatted(sha256("print('escaped');")));

        final List<String> diagnostics = new ArrayList<>();
        final ScriptRegistry registry = new ScriptRegistry(home, diagnostics::add);

        assertTrue(registry.discover().isEmpty());
        assertTrue(diagnostics.stream().anyMatch(message ->
            message.contains("SCRIPT_INVALID linked-entry")
                && message.contains("symbolic link")
        ));
    }

    @Test
    void discoveryDoesNotOpenEveryScriptSourceBody() throws Exception {
        final Path first = writeScript("first", "first.example", "print('first');");
        final Path second = writeScript("second", "second.example", "print('second');");
        final java.util.Set<Path> sources = java.util.Set.of(
            first.resolve("main.js"), second.resolve("main.js")
        );
        final AtomicInteger sourceOpens = new AtomicInteger();
        final ScriptRegistry registry = new ScriptRegistry(home, ignored -> { }, path -> {
            if (sources.contains(path)) {
                sourceOpens.incrementAndGet();
                throw new AssertionError("discovery opened source " + path);
            }
            return Files.newInputStream(path);
        });

        assertEquals(2, registry.discover().size());
        assertEquals(0, sourceOpens.get());
    }

    @Test
    void rejectsSameSizeSourceRewriteWithRestoredTimestamp() throws Exception {
        final Path root = writeScript("same-size", "same-size.example", "print('before');");
        final Path source = root.resolve("main.js");
        final var modified = Files.getLastModifiedTime(source);
        final ScriptRegistry registry = new ScriptRegistry(home, ignored -> { });
        final ScriptRegistry.InstalledScript script = registry.find(
            new ScriptId("same-size.example")
        ).orElseThrow();

        Files.writeString(source, "print('after!');");
        Files.setLastModifiedTime(source, modified);

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            script::source
        );
    }

    @Test
    void acceptsFileAtTheSourceSizeLimit() throws Exception {
        writeScript("limit", "limit.example", "x".repeat(384 * 1024));

        final ScriptRegistry registry = new ScriptRegistry(home, ignored -> { });

        assertEquals(List.of(new ScriptId("limit.example")), registry.discover().stream()
            .map(script -> script.descriptor().id())
            .toList());
    }

    @Test
    void rejectsSourceThatGrowsOneBytePastTheLimit() throws Exception {
        writeScript("too-large", "large.example", "x".repeat(384 * 1024 + 1));
        final List<String> diagnostics = new ArrayList<>();

        final ScriptRegistry registry = new ScriptRegistry(home, diagnostics::add);

        final ScriptRegistry.InstalledScript script = registry.find(
            new ScriptId("large.example")
        ).orElseThrow();
        final IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            script::source
        );
        assertTrue(failure.getMessage().contains("exceeded 393216 bytes"));
    }

    @Test
    void rejectsSourceThatGrowsPastTheLimitWhileItIsRead() throws Exception {
        final Path root = writeScript("growing", "growing.example", "print('old');");
        final Path source = root.resolve("main.js");
        final AtomicInteger opens = new AtomicInteger();
        final ScriptRegistry registry = new ScriptRegistry(home, ignored -> { }, path -> {
            if (!path.equals(source) || opens.getAndIncrement() != 0) {
                return Files.newInputStream(path);
            }
            return new ByteArrayInputStream("x".repeat(384 * 1024 + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });

        final ScriptRegistry.InstalledScript script = registry.find(
            new ScriptId("growing.example")
        ).orElseThrow();
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            script::source
        );
    }

    @Test
    void rejectsSourceReplacedWhileItIsRead() throws Exception {
        final Path root = writeScript("replaced", "replaced.example", "print('old');");
        final Path source = root.resolve("main.js");
        final AtomicInteger opens = new AtomicInteger();
        final ScriptRegistry registry = new ScriptRegistry(home, ignored -> { }, path -> {
            if (!path.equals(source) || opens.getAndIncrement() != 0) {
                return Files.newInputStream(path);
            }
            final byte[] current = Files.readAllBytes(path);
            Files.move(path, path.resolveSibling("previous.js"));
            Files.writeString(path, "print('replacement');");
            return new ByteArrayInputStream(current);
        });

        final ScriptRegistry.InstalledScript script = registry.find(
            new ScriptId("replaced.example")
        ).orElseThrow();
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            script::source
        );
    }

    private Path writeScript(
        final String directory,
        final String id,
        final String source
    ) throws Exception {
        return writeScript(home, directory, id, source);
    }

    private Path writeScript(
        final Path scriptHome,
        final String directory,
        final String id,
        final String source
    ) throws Exception {
        final Path root = scriptHome.resolve("scripts").resolve(directory);
        Files.createDirectories(root);
        Files.writeString(root.resolve("main.js"), source);
        Files.writeString(root.resolve("script.json"), manifest(id, source));
        return root;
    }

    private static String manifest(final String id, final String source) {
        return """
            {
              "schemaVersion": 2,
              "id": "%s",
              "name": "%s",
              "version": "1.0.0",
              "language": "js",
              "entry": "main.js",
              "sourceSha256": "%s",
              "permissions": []
            }
            """.formatted(id, id, sha256(source));
    }

    private static String sha256(final String source) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                    source.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            );
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }
}

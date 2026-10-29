package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameworkDeltaPlannerTest {
    @TempDir
    Path home;

    @Test
    void selectsOnlyChangedAndMissingJarsWithoutModifyingAnything() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        Files.createDirectories(home.resolve("graal/lib"));
        Files.writeString(home.resolve("turboism-agent.jar"), "old-agent");
        Files.writeString(home.resolve("plugins/demo.jar"), "unchanged-plugin");
        Files.writeString(home.resolve("config.json"), "{\"mySettings\":true}");

        final var entries = List.of(
                entry("turboism-agent.jar", "new-agent"),
                entry("plugins/demo.jar", "unchanged-plugin"),
                entry("graal/lib/truffle-api-1.jar", "graal"));
        final FrameworkDeltaPlanner.Plan plan = FrameworkDeltaPlanner.plan(home, entries);

        assertEquals(
                List.of(
                        FrameworkDeltaPlanner.Action.DOWNLOAD,
                        FrameworkDeltaPlanner.Action.REUSE,
                        FrameworkDeltaPlanner.Action.DOWNLOAD),
                plan.decisions().stream().map(FrameworkDeltaPlanner.Decision::action).toList());
        assertEquals("new-agent".getBytes(StandardCharsets.UTF_8).length + 5L, plan.bytesToDownload());
        assertEquals("old-agent", Files.readString(home.resolve("turboism-agent.jar")));
        assertEquals("unchanged-plugin", Files.readString(home.resolve("plugins/demo.jar")));
        assertEquals("{\"mySettings\":true}", Files.readString(home.resolve("config.json")));
        assertTrue(Files.notExists(home.resolve("graal/lib/truffle-api-1.jar")));
    }

    @Test
    void detectsSameSizeCorruptionByHash() throws Exception {
        Files.writeString(home.resolve("turboism-agent.jar"), "abcd");
        final var plan = FrameworkDeltaPlanner.plan(home, List.of(entry("turboism-agent.jar", "abce")));
        assertEquals(FrameworkDeltaPlanner.Action.DOWNLOAD, plan.decisions().get(0).action());
        assertEquals(4L, plan.bytesToDownload());
    }

    @Test
    void rejectsUserFilesTraversalAndDuplicateEntries() {
        assertThrows(IllegalArgumentException.class, () -> entry("config.json", "x"));
        assertThrows(IllegalArgumentException.class, () -> entry("../plugins/demo.jar", "x"));
        assertThrows(IllegalArgumentException.class, () -> entry("plugins/../../config.json", "x"));
        assertThrows(IllegalArgumentException.class, () -> entry("plugins/DEMO.jar", "x"));
        final var item = entry("plugins/demo.jar", "x");
        assertThrows(IllegalArgumentException.class, () -> FrameworkDeltaPlanner.plan(home, List.of(item, item)));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkDeltaPlanner.Entry(
                "plugins/demo.jar", 1, "not-sha256"));
    }

    @Test
    void rejectsInstalledDirectoryMasqueradingAsJar() throws Exception {
        Files.createDirectories(home.resolve("plugins/demo.jar"));
        assertThrows(
                IOException.class,
                () -> FrameworkDeltaPlanner.plan(home, List.of(entry("plugins/demo.jar", "x"))));
    }

    @Test
    void rejectsSymlinkComponentsWhenSupported() throws Exception {
        final Path external = Files.createDirectory(home.resolve("external"));
        final Path link = home.resolve("plugins");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException | SecurityException noSymlinkPrivilege) {
            Assumptions.assumeTrue(false, "symlink creation is unavailable on this host");
        }
        assertThrows(
                IOException.class,
                () -> FrameworkDeltaPlanner.plan(home, List.of(entry("plugins/demo.jar", "x"))));
    }

    private static FrameworkDeltaPlanner.Entry entry(final String path, final String contents) {
        final byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        final byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return new FrameworkDeltaPlanner.Entry(path, bytes.length, HexFormat.of().formatHex(hash));
    }
}

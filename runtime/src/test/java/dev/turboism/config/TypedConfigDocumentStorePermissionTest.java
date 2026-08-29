package dev.turboism.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused regression for the owner-only typed-config persistence invariant.
 *
 * <p>Baseline bug: the shared atomic write path enforced atomicity but no
 * permission invariant, so credential-bearing configs landed with directory
 * {@code 0755} and file {@code 0644}, readable by other local users. POSIX
 * targets here are directory {@code 0700} and file {@code 0600} with real
 * filesystem observations.
 *
 * <p>POSIX-only assertions are skipped whenever the current file system lacks a
 * POSIX attribute view (for example on Windows) so the separately reviewed ACL
 * branch can be exercised there instead of failing on absent POSIX APIs; this
 * guard never weakens production fail-closed behavior. The unsupported-model
 * branch is exercised directly through a non-POSIX {@link java.nio.file.FileSystem}.
 */
class TypedConfigDocumentStorePermissionTest {

    private static final Set<PosixFilePermission> FILE_OWNER_ONLY =
        PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_OWNER_ONLY =
        PosixFilePermissions.fromString("rwx------");

    @TempDir
    Path temporary;

    /** Skips a POSIX-only test when the file system exposes no POSIX attribute view. */
    private static void assumePosixAvailable(final Path path) {
        Assumptions.assumeTrue(
            Files.getFileAttributeView(path, PosixFileAttributeView.class) != null,
            "POSIX attribute view is unavailable; skipping POSIX-only assertion"
        );
    }


    @Test
    void newStoreOwnerOnlyAndWrittenDocumentsAre0600With0700Parents() throws Exception {
        assumePosixAvailable(temporary);
        final Path root = temporary.resolve("config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);

        assertEquals(DIR_OWNER_ONLY, Files.getPosixFilePermissions(root));

        store.writeAtomic("plugin/webdav.cfg", document("line=value"));

        final Path file = root.resolve("plugin/webdav.cfg");
        final Path parent = file.getParent();
        assertTrue(Files.isRegularFile(file));
        assertEquals(DIR_OWNER_ONLY, Files.getPosixFilePermissions(parent));
        assertEquals(FILE_OWNER_ONLY, Files.getPosixFilePermissions(file));

        final Optional<TypedConfigDocumentStore.StoredDocument> stored = store.read("plugin/webdav.cfg");
        assertTrue(stored.isPresent());
        assertEquals("value", stored.orElseThrow().encodedValues().get("line"));
    }

    @Test
    void existingInsecurePathIsTightenedBeforeRead() throws Exception {
        assumePosixAvailable(temporary);
        final Path root = temporary.resolve("config");
        final Path pluginDir = root.resolve("plugin");
        Files.createDirectories(pluginDir);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(pluginDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        final Path insecure = pluginDir.resolve("webdav.cfg");
        Files.writeString(insecure, encode(document("password=secret")));
        Files.setPosixFilePermissions(insecure, PosixFilePermissions.fromString("rw-r--r--"));

        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);

        final Optional<TypedConfigDocumentStore.StoredDocument> stored =
            store.read("plugin/webdav.cfg");

        assertTrue(stored.isPresent(), "existing config must remain readable after tightening");
        assertEquals("secret", stored.orElseThrow().encodedValues().get("password"));
        assertEquals(FILE_OWNER_ONLY, Files.getPosixFilePermissions(insecure));
        assertEquals(DIR_OWNER_ONLY, Files.getPosixFilePermissions(pluginDir));
        assertEquals(DIR_OWNER_ONLY, Files.getPosixFilePermissions(root));
    }

    @Test
    void replacingAnInsecureExistingFileLeavesOwnerOnlyAndReadable() throws Exception {
        assumePosixAvailable(temporary);
        final Path root = temporary.resolve("config");
        final Path pluginDir = root.resolve("plugin");
        Files.createDirectories(pluginDir);
        final Path existing = pluginDir.resolve("webdav.cfg");
        Files.writeString(existing, encode(document("old=1")));
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(pluginDir, PosixFilePermissions.fromString("rwxr-xr-x"));

        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        store.writeAtomic("plugin/webdav.cfg", document("new=2"));

        assertEquals(FILE_OWNER_ONLY, Files.getPosixFilePermissions(existing));
        assertTrue(Files.readString(existing).contains("bmV3:Mg"),
            "replacement must have atomically replaced the file content");
        final Optional<TypedConfigDocumentStore.StoredDocument> stored = store.read("plugin/webdav.cfg");
        assertEquals("2", stored.orElseThrow().encodedValues().get("new"));
    }

    @Test
    void malformedDocumentsStillFailAfterOwnerOnlyEnforcement() throws Exception {
        assumePosixAvailable(temporary);
        final Path root = temporary.resolve("config");
        final TypedConfigDocumentStore store = new TypedConfigDocumentStore(root);
        store.writeAtomic("plugin/valid.cfg", document("k=v"));

        final Path invalid = root.resolve("plugin/bad.cfg");
        Files.writeString(invalid, "not-a-typed-config\n");
        Files.setPosixFilePermissions(invalid, PosixFilePermissions.fromString("rw-r--r--"));

        boolean failed = false;
        try {
            store.read("plugin/bad.cfg");
        } catch (java.io.IOException expected) {
            failed = true;
        }
        assertTrue(failed, "malformed input must still fail closed");
        assertEquals(FILE_OWNER_ONLY, Files.getPosixFilePermissions(invalid));
    }



    @Test
    void aclOwnerEntryRetainsTheWindowsSynchronizePermission() {
        assertTrue(TypedConfigDocumentStore.ownerOnlyEntry(
            () -> "owner", false
        ).permissions().contains(AclEntryPermission.SYNCHRONIZE));
    }

    @Test
    void unsupportedFileSystemFailsClosedAndWritesNothingInsecurely() throws Exception {
        final Path archive = temporary.resolve("unsupported-model.zip");
        try (FileSystem zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            final Path root = zip.getPath("/config");
            boolean failed = false;
            try {
                new TypedConfigDocumentStore(root);
            } catch (java.io.IOException expected) {
                failed = true;
            }
            assertTrue(failed,
                "a file system exposing neither POSIX nor ACL controls must fail closed");
        }
    }

    private static TypedConfigDocumentStore.StoredDocument document(final String encodedPair) {
        final String[] parts = encodedPair.split("=", 2);
        return new TypedConfigDocumentStore.StoredDocument(1, 0, Map.of(parts[0], parts[1]));
    }

    /** Mirrors the store's canonical on-disk serialization for hand-built fixtures. */
    private static String encode(final TypedConfigDocumentStore.StoredDocument document) {
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        final StringBuilder builder = new StringBuilder();
        builder.append("turboism-typed-config-v1\n").append("schemaVersion=1\n")
            .append("revision=0\n").append("count=").append(document.encodedValues().size())
            .append("\n");
        final TreeMap<String, String> sorted = new TreeMap<>(document.encodedValues());
        sorted.forEach((key, value) ->
            builder.append(encoder.encodeToString(key.getBytes(StandardCharsets.UTF_8)))
                .append(':')
                .append(encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .append('\n'));
        return builder.toString();
    }
}

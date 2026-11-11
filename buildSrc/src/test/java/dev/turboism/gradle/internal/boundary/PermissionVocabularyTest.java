package dev.turboism.gradle.internal.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared permission vocabulary: the audit reads the same file the SDK exports from.
 *
 * <p>The gate's whole claim is that a boundary rule names a permission id the framework actually
 * publishes. These cases pin the parse contract and the one invariant that makes the shared file
 * worth having: every rule id is an id of the vocabulary.
 */
class PermissionVocabularyTest {

    @TempDir
    Path temporary;

    @Test
    void parsesIdsInFileOrderIgnoringCommentsAndBlanks() {
        assertEquals(
                List.of("turboism.file.read", "turboism.file.write"),
                PermissionVocabulary.parse("# comment\n\nturboism.file.read\n   turboism.file.write  \n"));
    }

    @Test
    void rejectsAnEmptyVocabulary() {
        final IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> PermissionVocabulary.parse("# nothing here\n"));
        assertTrue(failure.getMessage().contains("declares no id"));
    }

    @Test
    void rejectsADuplicatedId() {
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> PermissionVocabulary.parse("turboism.file.read\nturboism.file.read\n"));
        assertTrue(failure.getMessage().contains("turboism.file.read"));
    }

    @Test
    void rejectsAMissingResource() {
        final IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> PermissionVocabulary.read(temporary));
        assertTrue(failure.getMessage().contains(PermissionVocabulary.RESOURCE_PATH));
    }

    @Test
    void readsTheTrackedVocabularyFromTheRepositoryRoot() {
        final Path root = repositoryRoot();
        final List<String> ids = PermissionVocabulary.read(root);

        assertTrue(!ids.isEmpty(), "the canonical vocabulary must declare ids");
        assertEquals(ids.size(), Set.copyOf(ids).size(), "the canonical vocabulary must not repeat an id");
        assertTrue(
                ids.containsAll(PluginPermissionAudit.boundaryPermissions()),
                "every audit rule id must be an id of the shared vocabulary: "
                        + PluginPermissionAudit.boundaryPermissions().stream()
                                .filter(id -> !ids.contains(id))
                                .toList());
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null
                && !Files.isRegularFile(current.resolve(PermissionVocabulary.RESOURCE_PATH))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate " + PermissionVocabulary.RESOURCE_PATH
                    + " from " + System.getProperty("user.dir"));
        }
        return current;
    }
}

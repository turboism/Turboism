package dev.turboism.sdk.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Locks {@link PermissionIds#KNOWN_IDS} to the constants in the same file, and both to the
 * canonical vocabulary resource the buildSrc permission audit reads, so the exported set, the
 * named ids and the shared file can never drift apart.
 */
final class PermissionIdsContractTest {

    @Test
    void knownIdsContainsEveryStringConstantExactly() throws Exception {
        final Set<String> constants = new HashSet<>();
        for (final var field : PermissionIds.class.getDeclaredFields()) {
            if (field.getType() == String.class
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isPublic(field.getModifiers())) {
                constants.add((String) field.get(null));
            }
        }
        assertEquals(constants, PermissionIds.KNOWN_IDS);
        assertTrue(PermissionIds.KNOWN_IDS.stream().allMatch(id -> id.startsWith("turboism.")));
    }

    @Test
    void vocabularyResourceDeclaresExactlyTheExportedIds() {
        assertEquals(PermissionIds.KNOWN_IDS, PermissionIds.Vocabulary.idSet());
        assertEquals(
                PermissionIds.KNOWN_IDS.size(), PermissionIds.Vocabulary.ids().size());
    }

    /**
     * The agent loads the SDK through {@code Boot-Class-Path}, so the vocabulary class reports a
     * null defining loader. Reading the resource through that null made the real Cubism host fail
     * on its first permission check, so the fallback must reach the same resource.
     */
    @Test
    void vocabularyResourceIsReachableWithoutADefiningLoader() {
        assertTrue(
                ClassLoader.getSystemResourceAsStream(PermissionIds.Vocabulary.RESOURCE) != null,
                "the system loader must see the vocabulary the bootstrap-loaded SDK reads");
    }

    @Test
    void retiredPermissionIdsAreNoLongerExported() {
        // Each id here gated no runtime call site and no first-party manifest declared it, so the
        // vocabulary dropped it. Re-adding one without a consumer would restore dead surface.
        final Set<String> retired = Set.of("turboism.ui.menu", "turboism.ui.palette", "turboism.ui.toolbar");
        for (final String id : retired) {
            assertTrue(!PermissionIds.KNOWN_IDS.contains(id), "retired id is exported again: " + id);
        }
    }

    @Test
    void vocabularyParseIgnoresCommentsAndRejectsDuplicates() throws Exception {
        final List<String> parsed = PermissionIds.Vocabulary.parse(stream("# comment\n\nturboism.a\nturboism.b\n"));
        assertEquals(List.of("turboism.a", "turboism.b"), parsed);

        final IllegalStateException duplicate = assertThrows(
                IllegalStateException.class, () -> PermissionIds.Vocabulary.parse(stream("turboism.a\nturboism.a\n")));
        assertTrue(duplicate.getMessage().contains("turboism.a"));

        assertThrows(IllegalStateException.class, () -> PermissionIds.Vocabulary.parse(stream("# only a comment\n")));
    }

    private static InputStream stream(final String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}

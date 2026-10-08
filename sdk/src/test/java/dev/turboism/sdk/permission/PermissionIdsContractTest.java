package dev.turboism.sdk.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Locks {@link PermissionIds#KNOWN_IDS} to the constants in the same file so the
 * exported set can never drift from the named ids.
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
}

package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class NativeMeshMirrorReflectionOpsTest {

    /**
     * The host hands the eraser a list of lists, but a plain list arrives on the point routes.
     * Both must flatten to the same dispatch argument, and nulls must never reach the host.
     */
    @Test
    void flattensNestedAndPlainSourceLists() {
        final List<Object> first = List.of("a", "b");
        final List<Object> second = List.of("c");

        assertEquals(List.of("a", "b", "c"), NativeMeshMirrorReflectionOps.flatten(List.of(first, second)));
        assertEquals(List.of("a", "b"), NativeMeshMirrorReflectionOps.flatten(first));
        assertEquals(List.of("a"), NativeMeshMirrorReflectionOps.flatten(java.util.Arrays.asList("a", null)));
        assertTrue(NativeMeshMirrorReflectionOps.flatten("not a collection").isEmpty());
        assertTrue(NativeMeshMirrorReflectionOps.flatten(null).isEmpty());
    }

    /** Host handles have no useful equals(), so membership is answered by reference alone. */
    @Test
    void answersMembershipByIdentity() {
        final List<Object> collected = new ArrayList<>();
        final Object first = new String("equal");
        final Object second = new String("equal");
        collected.add(first);

        assertTrue(NativeMeshMirrorReflectionOps.containsSame(collected, first));
        assertFalse(NativeMeshMirrorReflectionOps.containsSame(collected, second));
        assertTrue(NativeMeshMirrorReflectionOps.containsIdentity(List.of(first), first));
        assertFalse(NativeMeshMirrorReflectionOps.containsIdentity(List.of(first), second));
    }

    @Test
    void buildsAnIdentityKeyedSet() {
        final Object first = new String("equal");
        final Object second = new String("equal");

        final Set<Object> set = NativeMeshMirrorReflectionOps.identitySet(List.of(first, second, first));

        assertEquals(2, set.size());
        assertTrue(set.contains(first));
        assertTrue(set.contains(second));
    }

    @Test
    void readsNumbersAndRejectsEverythingElse() {
        assertEquals(3, NativeMeshMirrorReflectionOps.number(3).intValue());
        assertEquals(2.5f, NativeMeshMirrorReflectionOps.number(2.5f).floatValue(), 0.0001f);
        assertThrows(IllegalArgumentException.class, () -> NativeMeshMirrorReflectionOps.number("3"));
    }
}

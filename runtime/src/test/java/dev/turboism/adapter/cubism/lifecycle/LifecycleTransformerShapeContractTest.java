package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins sanitized selector and argument-slot shapes consumed by lifecycle transformers. */
final class LifecycleTransformerShapeContractTest {

    @Test
    void exact5303ProjectProfileCarriesOnlyThePublicSanitizedBindingShapes() {
        final ProjectLifecycleHostProfile profile = ProjectLifecycleHostProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_03
        ).orElseThrow();

        assertEquals("5.3.03", profile.hostVersion());
        assertEquals(7, profile.bindings().size());
        for (ProjectLifecycleNativeMethodTransformer.Binding binding : profile.bindings()) {
            assertFalse(binding.ownerInternalName().isBlank());
            assertFalse(binding.methodName().isBlank());
            assertFalse(binding.descriptor().isBlank());
            Type.getMethodType(binding.descriptor());
        }
        assertEquals(List.of(1, 2, 3, 4, 5), argumentSlots(profile.bindings().get(0)));
        assertEquals(List.of(1, 2, 3, 4), argumentSlots(profile.bindings().get(1)));
    }

    @Test
    void exact5303StaticContractCarriesParameterLifecycleAliasWhileRuntimeStaysClosed() {
        assertTrue(dev.turboism.mapping.verification.EditorModelVerificationManifest
            .cubism5303StaticAliases()
            .contains("cubism.editor-model.parameter-operation.set-value"));
        assertEquals(List.of(1, 2), argumentSlots("(Ljava/lang/Object;F)V"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    private static List<Integer> argumentSlots(
        final ProjectLifecycleNativeMethodTransformer.Binding binding
    ) {
        return argumentSlots(binding.descriptor());
    }

    private static List<Integer> argumentSlots(final String descriptor) {
        final java.util.ArrayList<Integer> slots = new java.util.ArrayList<>();
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            slots.add(slot);
            slot += argument.getSize();
        }
        return List.copyOf(slots);
    }
}

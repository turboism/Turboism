package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeAutoMeshAlgorithmRegistryTest {

    private static final AlgorithmParameterDescriptor.DoubleParameter SCALE =
            new AlgorithmParameterDescriptor.DoubleParameter("scale", "Scale", "", 0.0d, 2.0d, 0.1d, 2, 1.0d);

    private final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();

    @Test
    void listsTheEditorGeneratorFirstThenRegistrationOrder() {
        final RuntimeAutoMeshAlgorithmRegistry registry = registry("plugin", 1L);
        registry.register(algorithm("fine"));
        registry.register(algorithm("coarse"));

        final List<AutoMeshAlgorithm> listed = registry.algorithms();
        assertEquals(
                List.of("native", "fine", "coarse"),
                listed.stream().map(AutoMeshAlgorithm::id).toList());
        assertTrue(listed.get(0).isNative());
        assertTrue(registry.find(AutoMeshAlgorithm.NATIVE_ALGORITHM_ID)
                .orElseThrow()
                .isNative());
    }

    @Test
    void duplicateIdsFailWithinOneGenerationAndAcrossPlugins() {
        final RuntimeAutoMeshAlgorithmRegistry first = registry("plugin", 1L);
        final RuntimeAutoMeshAlgorithmRegistry second = registry("other", 2L);
        first.register(algorithm("fine"));

        assertThrows(IllegalStateException.class, () -> first.register(algorithm("fine")));
        assertThrows(IllegalStateException.class, () -> second.register(algorithm("fine")));
        assertEquals(List.of("native", "fine"), ids(first.algorithms()));
    }

    @Test
    void registrationPublishesCanonicalPresetText() {
        final RuntimeAutoMeshAlgorithmRegistry registry = registry("plugin", 1L);
        registry.register(algorithm("fine"));

        assertEquals(
                Map.of("scale", "1.2"),
                registry.find("fine").orElseThrow().presets().get(0).values());
    }

    @Test
    void closingARegistrationIsIdempotentAndLeavesOtherEntriesAlone() {
        final RuntimeAutoMeshAlgorithmRegistry registry = registry("plugin", 1L);
        final Registration fine = registry.register(algorithm("fine"));
        final Registration coarse = registry.register(algorithm("coarse"));

        fine.close();
        fine.close();

        assertEquals(List.of("native", "coarse"), ids(registry.algorithms()));
        coarse.close();
        assertEquals(List.of("native"), ids(registry.algorithms()));
    }

    @Test
    void closingTheRegistryFencesEveryEntryOfTheGeneration() {
        final RuntimeAutoMeshAlgorithmRegistry registry = registry("plugin", 1L);
        final RuntimeAutoMeshAlgorithmRegistry other = registry("other", 2L);
        final Registration fine = registry.register(algorithm("fine"));
        other.register(algorithm("coarse"));

        registry.close();
        registry.close();

        assertEquals(List.of("native", "coarse"), ids(other.algorithms()));
        assertTrue(other.find("fine").isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.register(algorithm("later")));
        // A registration handle obtained before the close must not resurrect the entry.
        fine.close();
        assertEquals(List.of("native", "coarse"), ids(other.algorithms()));
    }

    @Test
    void scopeCloseRevokesTheGeneration() throws Exception {
        final RuntimeAutoMeshAlgorithmRegistry registry = registry("plugin", 1L);
        final DisposableScope scope = new DisposableScope();
        registry.bind(scope);
        registry.register(algorithm("fine"));

        scope.close();

        assertEquals(List.of("native"), ids(registry.algorithms()));
    }

    @Test
    void selectionFallsBackToTheEditorGeneratorWhileTheAlgorithmIsUnregistered() {
        final RuntimeAutoMeshAlgorithmRegistry registry = registry("plugin", 1L);
        registry.register(algorithm("fine"));
        assertTrue(registry.selectedAlgorithmId().isEmpty());

        registry.select("fine");
        assertEquals(Optional.of("fine"), registry.selectedAlgorithmId());

        registry.close();
        final RuntimeAutoMeshAlgorithmRegistry reloaded = registry("plugin", 2L);
        assertTrue(reloaded.selectedAlgorithmId().isEmpty(), "a fenced generation falls back to the generator");

        reloaded.register(algorithm("fine"));
        assertEquals(Optional.of("fine"), reloaded.selectedAlgorithmId(), "selection survives a reload");

        reloaded.select(AutoMeshAlgorithm.NATIVE_ALGORITHM_ID);
        assertTrue(reloaded.selectedAlgorithmId().isEmpty());
        reloaded.select(" ");
        assertTrue(reloaded.selectedAlgorithmId().isEmpty());
    }

    @Test
    void capabilityAndPermissionGateRegistrationAndSelection() {
        final RuntimeAutoMeshAlgorithmRegistry noCapability =
                new RuntimeAutoMeshAlgorithmRegistry("plugin", 1L, PermissionChecker.allowAll(), false, catalog);
        final RuntimeAutoMeshAlgorithmRegistry denied = new RuntimeAutoMeshAlgorithmRegistry(
                "plugin",
                2L,
                (permission, operation) -> {
                    throw new SecurityException(permission);
                },
                true,
                catalog);

        assertThrows(UnsupportedOperationException.class, () -> noCapability.register(algorithm("fine")));
        assertThrows(SecurityException.class, () -> denied.register(algorithm("fine")));
        assertThrows(SecurityException.class, () -> denied.select("fine"));
        assertEquals(List.of("native"), ids(catalog.algorithms()));
    }

    private RuntimeAutoMeshAlgorithmRegistry registry(final String pluginId, final long generation) {
        return new RuntimeAutoMeshAlgorithmRegistry(pluginId, generation, PermissionChecker.allowAll(), true, catalog);
    }

    private static List<String> ids(final List<AutoMeshAlgorithm> algorithms) {
        return algorithms.stream().map(AutoMeshAlgorithm::id).toList();
    }

    private static AutoMeshAlgorithm algorithm(final String id) {
        return new AutoMeshAlgorithm(
                id,
                id,
                "",
                List.of(SCALE),
                List.of(new AlgorithmPreset("Fine", Map.of("scale", "1.20"))),
                (request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
    }
}

package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorAnimationReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAutoYureReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelInstanceReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPhysicsReadSelectorContract;
import org.junit.jupiter.api.Test;

/**
 * Exact-record contract verification for the Editor read-only document families
 * (physics settings, auto-Yure evaluations, animation file contents) against the
 * reviewed real JAR artifacts.
 *
 * <p>This is the gate that reproduces the Wave 1 host BLOCKED state: the records
 * previously advertised none of the three read capabilities, so
 * {@code authorizesFeature} never returned {@code true} on the host.</p>
 */
class EditorDocumentReadSelectorContractTest {

    @Test
    void exact5302RecordVerifiesAllDocumentReadContracts() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.3.02");
        assertTrue(resolver.authorizesFeature(
                EditorPhysicsReadSelectorContract.ADAPTER_SLICE_ID,
                EditorPhysicsReadSelectorContract.CAPABILITY_ID,
                EditorPhysicsReadSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorAutoYureReadSelectorContract.ADAPTER_SLICE_ID,
                EditorAutoYureReadSelectorContract.CAPABILITY_ID,
                EditorAutoYureReadSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
                EditorAnimationReadSelectorContract.CAPABILITY_ID,
                EditorAnimationReadSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorModelInstanceReadSelectorContract.ADAPTER_SLICE_ID,
                EditorModelInstanceReadSelectorContract.CAPABILITY_ID,
                EditorModelInstanceReadSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5203RecordVerifiesAllDocumentReadContracts() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.2.03");
        assertTrue(resolver.authorizesFeature(
                EditorPhysicsReadSelectorContract.ADAPTER_SLICE_ID,
                EditorPhysicsReadSelectorContract.CAPABILITY_ID,
                EditorPhysicsReadSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorAutoYureReadSelectorContract.ADAPTER_SLICE_ID,
                EditorAutoYureReadSelectorContract.CAPABILITY_ID,
                EditorAutoYureReadSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
                EditorAnimationReadSelectorContract.CAPABILITY_ID,
                EditorAnimationReadSelectorContract.REQUIRED_ALIASES));
        final java.util.HashSet<String> aliases52 =
                new java.util.HashSet<>(EditorModelInstanceReadSelectorContract.REQUIRED_ALIASES);
        aliases52.removeAll(EditorModelInstanceReadSelectorContract.ONION_SKIN_ALIASES);
        assertTrue(resolver.authorizesFeature(
                EditorModelInstanceReadSelectorContract.ADAPTER_SLICE_ID,
                EditorModelInstanceReadSelectorContract.CAPABILITY_ID,
                java.util.Set.copyOf(aliases52)));
    }
}

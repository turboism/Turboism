package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CapabilitySelectorDependenciesTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
            })
    void inspectorDependenciesRetainExactLegacyContractAndRejectMissingColorSelector(final String version)
            throws Exception {
        final var record = record(version);
        final String capability = "cubism.editor-model.art-mesh.inspector-write";
        assertTrue(record.capabilityIds().contains(capability));
        final Set<String> required = CapabilitySelectorDependencies.requiredAliases(record, capability);
        assertTrue(CapabilitySelectorDependencies.allAliases(record).containsAll(required));
        final String alpha = "cubism.editor-model.art-mesh-source.set-alpha-composition";
        assertEquals(version.startsWith("5.3."), required.contains(alpha));
        final Set<String> verified = new HashSet<>(CapabilitySelectorDependencies.allAliases(record));
        assertTrue(verified.remove("cubism.editor-model.drawable-form.screen-color"));
        assertFalse(
                CapabilitySelectorDependencies.capabilities(record, verified).contains(capability));
        assertTrue(CapabilitySelectorDependencies.capabilities(record, verified).contains("cubism.editor-model.read"));
        if (version.startsWith("5.3.")) {
            final Set<String> withoutAlpha = new HashSet<>(CapabilitySelectorDependencies.allAliases(record));
            assertTrue(withoutAlpha.remove(alpha));
            assertFalse(CapabilitySelectorDependencies.capabilities(record, withoutAlpha)
                    .contains(capability));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
            })
    void optionalTextureWriteFailureKeepsReadsAndExcludesTheUnverifiedAlias(final String version) throws Exception {
        final var record = record(version);
        final String missing = "cubism.editor-model.texture-handler.add-texture-atlas";
        final Set<String> verified = new HashSet<>(CapabilitySelectorDependencies.allAliases(record));
        assertTrue(verified.remove(missing));
        final var capabilities = CapabilitySelectorDependencies.capabilities(record, verified);
        assertTrue(capabilities.contains("cubism.editor-model.read"));
        assertTrue(capabilities.contains("cubism.editor-model.texture.read"));
        assertFalse(capabilities.contains("cubism.editor-model.texture.write"));

        final var digest = new HostArtifactDigest(100, "e".repeat(64));
        final var report = new StaticSelectorVerifier.StructureVerificationReport(
                digest,
                record.selectors().stream()
                        .map(selector -> new StaticSelectorResult(
                                selector,
                                selector.alias().equals(missing)
                                        ? StaticVerificationStatus.MEMBER_MISSING
                                        : StaticVerificationStatus.VERIFIED_STATIC,
                                "fixture verification"))
                        .toList());
        final var plan = VerifiedAccessPlan.fromCompatibility(
                record, report, "5.3.99", new HostArtifactFingerprint("5.3.99", digest.size(), digest.sha256()));
        assertTrue(plan.authorizesFeature(
                record.adapterSliceId(),
                "cubism.editor-model.texture.read",
                dev.turboism.mapping.verification.selector.EditorTextureSelectorContract.READ_REQUIRED_ALIASES));
        assertFalse(plan.authorizesFeature(record.adapterSliceId(), "cubism.editor-model.texture.write", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> plan.selector(missing));
        assertEquals("5.3.99", plan.cubismVersion());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
            })
    void missingModelBindingOrUndoRelationshipDisablesItsDependencyClosure(final String version) throws Exception {
        final var record = record(version);
        final Set<String> verified = new HashSet<>(CapabilitySelectorDependencies.allAliases(record));
        assertTrue(verified.remove("cubism.editor-model.inherits.GroupUndo.extends.ACUndoable"));
        final var withoutUndo = CapabilitySelectorDependencies.capabilities(record, verified);
        assertTrue(withoutUndo.contains("cubism.editor-model.read"));
        assertTrue(withoutUndo.contains("cubism.editor-model.texture.read"));
        assertFalse(withoutUndo.contains("cubism.editor-model.texture.write"));
        verified.remove("cubism.editor-model.model-source.current-instance");
        assertTrue(CapabilitySelectorDependencies.capabilities(record, verified).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03"
            })
    void missingLegacyRemovalFactoryDisablesWritesButKeepsReads(final String version) throws Exception {
        final var record = record(version);
        final Set<String> verified = new HashSet<>(CapabilitySelectorDependencies.allAliases(record));
        assertTrue(record.capabilityIds().contains("cubism.editor-model.texture.write"));
        assertTrue(verified.remove("cubism.editor-model.texture-undo.layer-input.create"));
        final var capabilities = CapabilitySelectorDependencies.capabilities(record, verified);
        assertTrue(capabilities.contains("cubism.editor-model.texture.read"));
        assertFalse(capabilities.contains("cubism.editor-model.texture.write"));
    }

    private static StaticVerificationRecord record(final String version) throws Exception {
        return new StaticVerificationRecordLoader()
                .load(Path.of("../build/generated/verification", "cubism-" + version + "-editor-model.json"))
                .record();
    }
}

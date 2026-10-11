package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EditorModelRecordPinTest {
    @Test
    void exact5302ManifestCanActuallyAuthorizeItsTextureAtlasProvider() {
        assertTrue(EditorModelVerificationManifest.cubism5302Capabilities()
                .contains(dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasSelectorContract.CAPABILITY_ID));
        final var missing = new java.util.HashSet<>(
                dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasSelectorContract.REQUIRED_ALIASES);
        missing.removeAll(EditorModelVerificationManifest.cubism5302Aliases());
        assertEquals(
                Set.of(),
                missing,
                "The real record must admit every provider selector; synthetic fixtures cannot supply missing aliases.");
    }

    @Test
    void qSelectorRecordsMatchThePinsUsedBeforeHostConnection() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("compatibility/cubism/verification")))
            root = root.getParent();
        assertNotNull(root, "Repository verification records must be available; never skip pin validation");
        for (var pin : List.of(
                EditorModelVerificationManifest.RECORD_5_2_03,
                EditorModelVerificationManifest.RECORD_5_3_02,
                EditorModelVerificationManifest.RECORD_5_3_03)) {
            var loaded = new StaticVerificationRecordLoader()
                    .load(root.resolve(
                            "compatibility/cubism/verification/cubism-" + pin.cubismVersion() + "-editor-model.json"));
            // Static selector validity alone is insufficient: runtime admission pins the entire record bytes.
            assertEquals(pin.recordSha256(), loaded.sha256(), "Runtime record pin drift: " + pin.cubismVersion());
            assertEquals(pin.verificationId(), loaded.record().verificationId());
            assertTrue(
                    loaded.record().selectors().stream()
                            .anyMatch(s -> s.alias().equals("cubism.texture-atlas.native.item.scale")),
                    "q selector missing");
        }
    }

    @Test
    void reviewedRecordRosterMatchesRuntimeManifestExactly() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("compatibility/cubism/verification")))
            root = root.getParent();
        assertNotNull(root, "Repository verification records must be available; never skip roster validation");
        for (var pin : List.of(
                EditorModelVerificationManifest.RECORD_5_2_03,
                EditorModelVerificationManifest.RECORD_5_3_02,
                EditorModelVerificationManifest.RECORD_5_3_03)) {
            var loaded = new StaticVerificationRecordLoader()
                    .load(root.resolve(
                            "compatibility/cubism/verification/cubism-" + pin.cubismVersion() + "-editor-model.json"));
            var manifest = EditorModelVerificationManifest.forArtifact(pin.artifact());
            Set<String> recordAliases = loaded.record().selectors().stream()
                    .map(StaticSelector::alias)
                    .collect(Collectors.toSet());
            if (pin.cubismVersion().equals("5.3.03")) {
                // Exact 5.3.03 is admitted through a runtime scope: the manifest names
                // only the aliases mature contracts consume while the record carries
                // every statically verified selector (mirrors
                // VerificationManifestAdmissionPreflightTest.isRuntimeScopedAdmission).
                assertTrue(
                        recordAliases.containsAll(manifest.requiredAliases()),
                        "Runtime selector roster drift: " + pin.cubismVersion());
            } else {
                // PinnedVerifiedResolverWorkflow.authorizes requires exact equality, not subset:
                // a reviewed selector absent from the runtime manifest fails host admission.
                assertEquals(
                        manifest.requiredAliases(),
                        recordAliases,
                        "Runtime selector roster drift: " + pin.cubismVersion());
            }
            assertEquals(
                    manifest.capabilityIds(),
                    Set.copyOf(loaded.record().capabilityIds()),
                    "Runtime capability roster drift: " + pin.cubismVersion());
            assertEquals(manifest.adapterSliceId(), loaded.record().adapterSliceId());
            assertEquals(manifest.verificationId(), loaded.record().verificationId());
        }
    }
}

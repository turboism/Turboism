package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.adapter.cubism.mesh.MeshToolSessionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorSelectionReadSelectorContract;
import dev.turboism.ui.mesh.MeshToolbarSelectorContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SelectionBrushManifestBindingTest {
    @Test
    void actualReviewedRecordsMatchEveryRuntimeTrustRootAndMeshScope() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("compatibility/cubism/verification"))) {
            root = root.getParent();
            assertNotNull(root, "repository root");
        }
        for (HostArtifactDigest artifact : List.of(
                ReviewedHostArtifacts.CUBISM_5_2_03,
                ReviewedHostArtifacts.CUBISM_5_3_02,
                ReviewedHostArtifacts.CUBISM_5_3_03)) {
            var editor = EditorModelVerificationManifest.forArtifact(artifact);
            var toolbar = MainToolbarVerificationManifest.forArtifact(artifact);
            assertBinding(
                    root,
                    editor,
                    "editor-model",
                    MeshToolSessionSelectorContract.CAPABILITY_ID,
                    MeshToolSessionSelectorContract.REQUIRED_ALIASES);
            assertBinding(
                    root,
                    editor,
                    "editor-model",
                    EditorSelectionReadSelectorContract.CAPABILITY_ID,
                    EditorSelectionReadSelectorContract.REQUIRED_ALIASES);
            var readerRecord = new StaticVerificationRecordLoader()
                    .load(root.resolve("compatibility/cubism/verification/cubism-" + editor.cubismVersion()
                            + "-editor-model.json"))
                    .record();
            assertEquals(
                    List.of("structure"),
                    readerRecord.capabilityConditions().get(EditorSelectionReadSelectorContract.CAPABILITY_ID),
                    editor.cubismVersion() + " selection reader conditions");
            var packMetadata = new ObjectMapper()
                    .readTree(root.resolve("compatibility/cubism/mapping-packs/draft/cubism-" + editor.cubismVersion()
                                    + "-editor-model-read.json")
                            .toFile())
                    .path("metadata");
            Set<String> packCapabilities = new java.util.HashSet<>();
            packMetadata.path("capabilityIds").forEach(id -> packCapabilities.add(id.asText()));
            assertEquals(Set.copyOf(readerRecord.capabilityIds()), packCapabilities, "pack capability scope");
            assertEquals(
                    packCapabilities.size(),
                    packMetadata.path("capabilityCount").asInt(),
                    "pack count");
            assertBinding(
                    root,
                    toolbar,
                    "ui-main-toolbar",
                    MeshToolbarSelectorContract.CAPABILITY_ID,
                    MeshToolbarSelectorContract.REQUIRED_ALIASES);
        }
        var scope = EditorModelVerificationManifest.cubism5303RuntimeScope();
        assertTrue(scope.capabilityIds().contains(MeshToolSessionSelectorContract.CAPABILITY_ID));
        assertTrue(scope.requiredAliases().containsAll(MeshToolSessionSelectorContract.REQUIRED_ALIASES));
    }

    private static void assertBinding(
            Path root,
            PinnedVerifiedResolverWorkflow.Manifest manifest,
            String slice,
            String meshCapability,
            Set<String> meshAliases)
            throws Exception {
        var loaded = new StaticVerificationRecordLoader()
                .load(root.resolve("compatibility/cubism/verification/cubism-" + manifest.cubismVersion() + "-" + slice
                        + ".json"));
        assertAll(
                manifest.cubismVersion() + "/" + slice,
                () -> assertEquals(loaded.sha256(), manifest.recordSha256(), "record trust-root digest"),
                () -> assertEquals(
                        Set.copyOf(loaded.record().capabilityIds()), manifest.capabilityIds(), "exact capabilities"),
                () -> assertEquals(
                        loaded.record().selectors().stream()
                                .map(StaticSelector::alias)
                                .collect(Collectors.toSet()),
                        manifest.requiredAliases(),
                        "exact selector roster"),
                () -> assertTrue(manifest.capabilityIds().contains(meshCapability), "mesh capability"),
                () -> assertTrue(manifest.requiredAliases().containsAll(meshAliases), "mesh selector scope"));
    }
}

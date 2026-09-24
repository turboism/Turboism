package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.mapping.verification.selector.EditorWarpMirrorSelectorContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Record/pack membership of the Warp mirror contract: every required alias must be a
 * verified selector on each exact record, the capability must be admitted, and the draft
 * mapping packs must carry the same entries so review material stays consistent.
 */
final class EditorWarpMirrorSelectorContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PROJECT_ROOT = locateProjectRoot();

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02", "5.3.03"})
    void exactRecordAdmitsTheWarpMirrorContract(final String version) throws Exception {
        final var loaded = new StaticVerificationRecordLoader().load(PROJECT_ROOT.resolve(
            "compatibility/cubism/verification/cubism-" + version + "-editor-model.json"));

        assertTrue(loaded.record().capabilityIds().contains(
            EditorWarpMirrorSelectorContract.CAPABILITY_ID),
            "capability missing on " + version);
        assertEquals(EditorWarpMirrorSelectorContract.ADAPTER_SLICE_ID,
            loaded.record().adapterSliceId(), "adapter slice mismatch on " + version);

        final List<String> missing = new ArrayList<>();
        for (String alias : EditorWarpMirrorSelectorContract.REQUIRED_ALIASES) {
            final boolean present = loaded.record().selectors().stream()
                .anyMatch(selector -> selector.alias().equals(alias));
            if (!present) {
                missing.add(alias);
            }
        }
        assertTrue(missing.isEmpty(),
            "selectors missing on " + version + ": " + missing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02", "5.3.03"})
    void exactRecordMatchesTheRuntimeOwnedManifest(final String version) throws Exception {
        final var loaded = new StaticVerificationRecordLoader().load(PROJECT_ROOT.resolve(
            "compatibility/cubism/verification/cubism-" + version + "-editor-model.json"));
        final HostArtifactDigest artifact = switch (version) {
            case "5.2.03" -> ReviewedHostArtifacts.CUBISM_5_2_03;
            case "5.3.02" -> ReviewedHostArtifacts.CUBISM_5_3_02;
            default -> ReviewedHostArtifacts.CUBISM_5_3_03;
        };
        final var manifest = EditorModelVerificationManifest.forArtifact(artifact);

        final java.util.Set<String> recordAliases = new java.util.HashSet<>();
        loaded.record().selectors().forEach(selector -> recordAliases.add(selector.alias()));
        assertEquals(manifest.requiredAliases(), recordAliases,
            "record/manifest alias set mismatch on " + version);
        assertEquals(manifest.capabilityIds(),
            java.util.Set.copyOf(loaded.record().capabilityIds()),
            "record/manifest capability set mismatch on " + version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02", "5.3.03"})
    void draftMappingPackCarriesEveryRequiredAlias(final String version) throws Exception {
        final JsonNode pack = JSON.readTree(Files.readString(PROJECT_ROOT.resolve(
            "compatibility/cubism/mapping-packs/draft/cubism-" + version
                + "-editor-model-read.json")));
        final java.util.Set<String> names = new java.util.HashSet<>();
        pack.get("entries").forEach(entry -> names.add(entry.get("name").asText()));

        final List<String> missing = new ArrayList<>();
        for (String alias : EditorWarpMirrorSelectorContract.REQUIRED_ALIASES) {
            if (!names.contains(alias)) {
                missing.add(alias);
            }
        }
        assertTrue(missing.isEmpty(),
            "draft pack entries missing on " + version + ": " + missing);
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("project root is unavailable");
        }
        return current;
    }
}

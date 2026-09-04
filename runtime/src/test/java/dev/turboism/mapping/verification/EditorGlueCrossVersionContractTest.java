package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorGlueInspectorSelectorContract;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-version authorization guard for the public Glue read/write MCP slice. */
final class EditorGlueCrossVersionContractTest {

    private static final List<VersionCase> VERSIONS = List.of(
        new VersionCase(
            "5.2.03",
            ReviewedHostArtifacts.CUBISM_5_2_03,
            "compatibility/cubism/verification/cubism-5.2.03-editor-model.json"
        ),
        new VersionCase(
            "5.3.02",
            ReviewedHostArtifacts.CUBISM_5_3_02,
            "compatibility/cubism/verification/cubism-5.3.02-editor-model.json"
        ),
        new VersionCase(
            "5.3.03",
            ReviewedHostArtifacts.CUBISM_5_3_03,
            "compatibility/cubism/verification/cubism-5.3.03-editor-model.json"
        )
    );

    @Test
    void eachExactVersionManifestRequiresTheCompleteGlueWriterContract() {
        for (VersionCase version : VERSIONS) {
            final PinnedVerifiedResolverWorkflow.Manifest manifest =
                EditorModelVerificationManifest.forArtifact(version.artifact());

            assertTrue(
                manifest.capabilityIds().contains(
                    EditorGlueInspectorSelectorContract.CAPABILITY_ID
                ),
                version.version() + " must admit the exact Glue writer capability"
            );
            assertTrue(
                manifest.requiredAliases().containsAll(
                    EditorGlueInspectorSelectorContract.REQUIRED_ALIASES
                ),
                version.version() + " must require every Glue selector alias"
            );
        }
    }

    @Test
    void eachCommittedExactVersionRecordContainsTheCompleteGlueWriterContract()
        throws Exception {
        for (VersionCase version : VERSIONS) {
            final Path path = projectRoot().resolve(version.recordPath());
            final StaticVerificationRecord record =
                new StaticVerificationRecordLoader().load(path).record();
            final Set<String> aliases = new HashSet<>();
            for (StaticSelector selector : record.selectors()) {
                aliases.add(selector.alias());
            }

            assertEquals(version.version(), record.cubismVersion());
            assertEquals(version.artifact().sha256(), record.artifact().sha256());
            assertTrue(
                record.capabilityIds().contains(
                    EditorGlueInspectorSelectorContract.CAPABILITY_ID
                ),
                path + " must declare the Glue writer capability"
            );
            assertTrue(
                aliases.containsAll(EditorGlueInspectorSelectorContract.REQUIRED_ALIASES),
                path + " must contain every exact Glue selector alias"
            );
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root is unavailable");
        return current;
    }

    private record VersionCase(
        String version,
        HostArtifactDigest artifact,
        String recordPath
    ) {
    }
}

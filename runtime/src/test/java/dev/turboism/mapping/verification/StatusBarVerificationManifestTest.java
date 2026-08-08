package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusBarVerificationManifestTest {

    private static final String RECORD_NAME = "cubism-5.3.02-ui-status-bar.json";
    private static final HostArtifactDigest REVIEWED_5302 = new HostArtifactDigest(
        41_922_739L,
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
    );
    private static final HostArtifactDigest REVIEWED_52 = new HostArtifactDigest(
        40_805_584L,
        "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void manifestMatchesTheReviewedRecordBytesAndAllTwentyOneAliases() throws Exception {
        final Path record = repositoryPath("cubism-ref/verification/" + RECORD_NAME);
        final String recordSha = sha256(record);
        assertEquals(StatusBarVerificationManifest.RECORD_SHA256, recordSha,
            "manifest record digest must match the reviewed record bytes");

        final JsonNode root = mapper.readTree(record.toFile());
        assertEquals(StatusBarVerificationManifest.VERIFICATION_ID, root.get("verificationId").asText());
        assertEquals(StatusBarVerificationManifest.ADAPTER_SLICE_ID, root.get("adapterSliceId").asText());
        assertEquals(StatusBarVerificationManifest.CUBISM_VERSION, root.get("cubismVersion").asText());
        assertEquals(StatusBarVerificationManifest.PROFILE_ID, root.get("profileId").asText());
        assertEquals(
            StatusBarVerificationManifest.CAPABILITY_IDS,
            Set.copyOf(new HashSet<>(mapper.convertValue(
                root.get("capabilityIds"),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { }
            )))
        );
        assertEquals(
            StatusBarVerificationManifest.ARTIFACT_SIZE,
            root.get("artifact").get("size").asLong()
        );
        assertEquals(
            StatusBarVerificationManifest.ARTIFACT_SHA256,
            root.get("artifact").get("sha256").asText()
        );

        final Set<String> recordAliases = new HashSet<>();
        for (JsonNode selector : root.get("selectors")) {
            recordAliases.add(selector.get("alias").asText());
            assertEquals("VERIFIED_STATIC", selector.get("status").asText());
        }
        assertEquals(21, recordAliases.size());
        assertEquals(StatusBarVerificationManifest.REQUIRED_ALIASES, recordAliases,
            "manifest must authorize exactly the reviewed record selectors");
    }

    @Test
    void forArtifactAcceptsOnlyTheReviewed5302Artifact() {
        assertEquals(
            StatusBarVerificationManifest.CUBISM_VERSION,
            StatusBarVerificationManifest.forArtifact(REVIEWED_5302).cubismVersion()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> StatusBarVerificationManifest.forArtifact(REVIEWED_52),
            "5.2 must keep failing closed: no reviewed 5.2 status-bar record exists"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> StatusBarVerificationManifest.forArtifact(new HostArtifactDigest(
                1L,
                "0".repeat(64)
            )),
            "unknown artifact must fail closed"
        );
    }

    @Test
    void admissionForArtifactExposesTheReviewedManifestMaterial() {
        final StatusBarVerificationManifest.AdmissionEvidence admission =
            StatusBarVerificationManifest.admissionForArtifact(REVIEWED_5302);
        assertEquals("5.3.02", admission.cubismVersion());
        assertEquals(41_922_739L, admission.artifactSize());
        assertEquals(REVIEWED_5302.sha256(), admission.artifactSha256());
        assertEquals("adapter.editor-ui.status-bar", admission.adapterSliceId());
        assertEquals(StatusBarVerificationManifest.RECORD_SHA256, admission.recordSha256());
    }

    @Test
    void resolverFactoryFailsClosedBeforeTrustingAnyUnreviewedMaterial() throws Exception {
        final VerifiedStatusBarResolverFactory factory = new VerifiedStatusBarResolverFactory();
        final Path reviewed = repositoryPath("cubism-ref/verification/" + RECORD_NAME);

        // Missing artifact: fail closed before the record is even considered.
        assertThrows(java.nio.file.NoSuchFileException.class, () -> factory.create(
            reviewed, Path.of("missing-host-artifact.jar"), getClass().getClassLoader()
        ));

        // Existing but unreviewed artifact: the pinned manifest rejects it before
        // any record bytes are loaded or used.
        final Path foreignArtifact = Files.createTempFile("status-bar-foreign", ".jar");
        Files.writeString(foreignArtifact, "not-the-reviewed-cubism-jar");
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            reviewed, foreignArtifact, getClass().getClassLoader()
        ));
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            repositoryPath("cubism-ref/verification/cubism-5.3.02-project-workspace.json"),
            foreignArtifact,
            getClass().getClassLoader()
        ));

        // Invalid record with an unreviewed artifact must also fail closed.
        final Path invalid = Files.createTempFile("status-bar-invalid", ".json");
        Files.writeString(invalid, "{\"format\":\"turboism.static.verification.record\"}");
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            invalid, foreignArtifact, getClass().getClassLoader()
        ));
    }

    private static Path repositoryPath(final String relative) {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return root.resolve(relative);
    }

    private static String sha256(final Path file) throws Exception {
        try (java.io.InputStream input = Files.newInputStream(file)) {
            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            final StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        }
    }
}

package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard for the in-tree textures authorization chain (w13-20260807-forwarding-audit, lane B).
 *
 * <p>Both the Cubism 5.3.02 manifest and its 5.2.03-derived variant must pin the
 * texture read/write capability IDs and cover the texture selector aliases. The
 * 5.2.03 variant must additionally NOT require the 5.3.02-only non-dialog
 * raw-image removal alias (the real host fails closed on 5.2 for that path).</p>
 */
class EditorModelTextureAuthorizationGuardTest {

    private static final HostArtifactDigest REVIEWED_5302 = new HostArtifactDigest(
        41_922_739L,
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
    );
    private static final HostArtifactDigest REVIEWED_52 = new HostArtifactDigest(
        40_805_584L,
        "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
    );

    @Test
    void cubism5302ManifestPinsBothTextureCapabilitiesAndAllRequiredAliases() {
        final PinnedVerifiedResolverWorkflow.Manifest manifest =
            EditorModelVerificationManifest.forArtifact(REVIEWED_5302);

        final Set<String> capabilities = manifest.capabilityIds();
        assertTrue(capabilities.contains(EditorTextureSelectorContract.READ_CAPABILITY_ID),
            "5.3.02 manifest must pin the texture read capability");
        assertTrue(capabilities.contains(EditorTextureSelectorContract.WRITE_CAPABILITY_ID),
            "5.3.02 manifest must pin the texture write capability");

        // WRITE_REQUIRED_ALIASES already covers READ_REQUIRED_ALIASES by construction,
        // but pin both explicitly so a future refactor of the selector cannot silently
        // drop the read side of the chain.
        final Set<String> aliases = manifest.requiredAliases();
        assertTrue(aliases.containsAll(EditorTextureSelectorContract.READ_REQUIRED_ALIASES),
            "5.3.02 manifest must require every texture read alias");
        assertTrue(aliases.containsAll(EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES),
            "5.3.02 manifest must require every texture write alias");
        assertTrue(aliases.containsAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES),
            "5.3.02 manifest must require the raw-image removal alias");
    }

    @Test
    void cubism52DerivedManifestPinsTextureCapabilitiesButNotThe5302OnlyRawImageRemoval() {
        final PinnedVerifiedResolverWorkflow.Manifest manifest =
            EditorModelVerificationManifest.forArtifact(REVIEWED_52);

        final Set<String> capabilities = manifest.capabilityIds();
        assertTrue(capabilities.contains(EditorTextureSelectorContract.READ_CAPABILITY_ID),
            "5.2-derived manifest must pin the texture read capability");
        assertTrue(capabilities.contains(EditorTextureSelectorContract.WRITE_CAPABILITY_ID),
            "5.2-derived manifest must pin the texture write capability");

        final Set<String> aliases = manifest.requiredAliases();
        assertTrue(aliases.containsAll(EditorTextureSelectorContract.READ_REQUIRED_ALIASES),
            "5.2-derived manifest must require every texture read alias");
        assertTrue(aliases.containsAll(EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES),
            "5.2-derived manifest must require every texture write alias");
        assertFalse(aliases.containsAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES),
            "5.2-derived manifest must not require the 5.3.02-only raw-image removal alias");
    }

    @Test
    void pinnedRecordSha256sMatchTheReviewedRecordFiles() throws Exception {
        final Path record52 = projectRoot().resolve(
            "cubism-ref/verification/cubism-5.2-editor-model.json"
        );
        final Path record5302 = projectRoot().resolve(
            "cubism-ref/verification/cubism-5.3.02-editor-model.json"
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            EditorModelVerificationManifest52.RECORD_SHA256,
            sha256(record52)
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            EditorModelVerificationManifest.RECORD_SHA256,
            sha256(record5302)
        );
    }

    private static String sha256(final Path file) throws Exception {
        final java.security.MessageDigest digest =
            java.security.MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        final StringBuilder hex = new StringBuilder();
        for (final byte value : digest.digest()) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root is unavailable");
        return current;
    }
}

package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.HashSet;

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

    private static final HostArtifactDigest REVIEWED_5302 = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest REVIEWED_52 = ReviewedHostArtifacts.CUBISM_5_2_03;

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
    void cubism5302RecordContainsEveryTextureContractAlias() throws Exception {
        assertRecordCoversContract(
            projectRoot().resolve("cubism-ref/verification/cubism-5.3.02-editor-model.json"),
            EditorTextureSelectorContract.READ_REQUIRED_ALIASES,
            EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES
        );
    }

    @Test
    void cubism52RecordContainsEveryTextureContractAlias() throws Exception {
        assertRecordCoversContract(
            projectRoot().resolve("cubism-ref/verification/cubism-5.2.03-editor-model.json"),
            EditorTextureSelectorContract.READ_REQUIRED_ALIASES,
            EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES
        );
    }

    private static void assertRecordCoversContract(
        final Path recordPath,
        final Set<String> readAliases,
        final Set<String> writeAliases
    ) throws Exception {
        final StaticVerificationRecord record =
            new StaticVerificationRecordLoader().load(recordPath).record();
        final HashSet<String> recordAliases = new HashSet<>();
        for (final StaticSelector selector : record.selectors()) {
            recordAliases.add(selector.alias());
        }
        assertTrue(
            recordAliases.containsAll(readAliases),
            recordPath + " record must contain every texture READ alias");
        assertTrue(
            recordAliases.containsAll(writeAliases),
            recordPath + " record must contain every texture WRITE alias");
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
            "cubism-ref/verification/cubism-5.2.03-editor-model.json"
        );
        final Path record5302 = projectRoot().resolve(
            "cubism-ref/verification/cubism-5.3.02-editor-model.json"
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            EditorModelVerificationManifest.RECORD_5_2_03.recordSha256(),
            sha256(record52)
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            EditorModelVerificationManifest.RECORD_5_3_02.recordSha256(),
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

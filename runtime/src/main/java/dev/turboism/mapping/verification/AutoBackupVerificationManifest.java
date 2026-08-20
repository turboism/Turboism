package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Runtime trust root for the exact Cubism auto-backup manager slices
 * (5.3.02 and 5.2.03; the frozen selector list is byte-identical across both).
 */
public final class AutoBackupVerificationManifest {

    public static final String ADAPTER_SLICE_ID = "adapter.cubism.autobackup";
    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.autobackup.settings",
        "cubism.autobackup.backup"
    );

    public static final String VERIFICATION_ID_53 = "cubism-5.3.02.autobackup.static";
    public static final String RECORD_SHA256_53 =
        "94eae6454eca81643c68b019c31768b6bbdc34e70bbcce976c6d99af5ad282af";
    public static final String CUBISM_VERSION_53 = "5.3.02";
    public static final String PROFILE_ID_53 = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE_53 = ReviewedHostArtifacts.CUBISM_5_3_02.size();
    public static final String ARTIFACT_SHA256_53 = ReviewedHostArtifacts.CUBISM_5_3_02.sha256();

    public static final String VERIFICATION_ID_52 = "cubism-5.2.03.autobackup.static";
    public static final String RECORD_SHA256_52 =
        "d8fc0ca3606538831164aa8c8fc32bc956b4c1f8b599930d5dc3baed8e25b008";
    public static final String CUBISM_VERSION_52 = "5.2.03";
    public static final String PROFILE_ID_52 = "cubism-5.2.03";
    public static final long ARTIFACT_SIZE_52 = ReviewedHostArtifacts.CUBISM_5_2_03.size();
    public static final String ARTIFACT_SHA256_52 = ReviewedHostArtifacts.CUBISM_5_2_03.sha256();

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.auto-backup.manager.class",
        "cubism.auto-backup.manager.instance",
        "cubism.auto-backup.app-controller.instance",
        "cubism.auto-backup.is-enabled",
        "cubism.auto-backup.set-enabled",
        "cubism.auto-backup.set-interval-minute",
        "cubism.auto-backup.attach-pack",
        "cubism.auto-backup.get-interval-minute",
        "cubism.auto-backup.set-max-mb",
        "cubism.auto-backup.get-max-mb",
        "cubism.auto-backup.update",
        "cubism.auto-backup.backup-dir",
        "cubism.auto-backup.app-controller.class",
        "cubism.auto-backup.app-controller.get-complete-pack",
        "cubism.auto-backup.complete-pack.class",
        "cubism.auto-backup.complete-pack.file-contents",
        "cubism.auto-backup.file-content.class",
        "cubism.auto-backup.file-content.last-auto-backup-time",
        "cubism.auto-backup.file-content.set-last-auto-backup-time",
        "cubism.auto-backup.file-content.last-saved-time",
        "cubism.auto-backup.file-content.modified-after-saving",
        "cubism.auto-backup.file-content.file",
        "cubism.auto-backup.document-uid.modeling",
        "cubism.auto-backup.scene-docs",
        "cubism.auto-backup.document-uid.scene"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        if (artifact.size() == ARTIFACT_SIZE_53 && artifact.sha256().equals(ARTIFACT_SHA256_53)) {
            return manifest53();
        }
        if (artifact.size() == ARTIFACT_SIZE_52 && artifact.sha256().equals(ARTIFACT_SHA256_52)) {
            return manifest52();
        }
        throw new IllegalArgumentException(
            "host artifact is not the reviewed Cubism auto-backup artifact"
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest53() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_53,
            RECORD_SHA256_53,
            CUBISM_VERSION_53,
            PROFILE_ID_53,
            ARTIFACT_SIZE_53,
            ARTIFACT_SHA256_53,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest52() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_52,
            RECORD_SHA256_52,
            CUBISM_VERSION_52,
            PROFILE_ID_52,
            ARTIFACT_SIZE_52,
            ARTIFACT_SHA256_52,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private AutoBackupVerificationManifest() {
    }
}

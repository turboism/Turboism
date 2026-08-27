package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Exact-version static trust roots for Cubism auto-backup manager selectors.
 * Runtime backup behavior remains separately gated until exact-host validation admits it.
 */
public final class AutoBackupVerificationManifest {

    private static final String VALIDATION_PROPERTY =
        "turboism.validation.autoBackup";
    private static final String VALIDATION_TOKEN =
        "EXACT_5303_AUTOBACKUP_CANDIDATE";
    private static final String VALIDATION_HOST_VERSION_PROPERTY =
        "turboism.validation.hostVersion";
    private static final String VALIDATION_HOST_VERSION = "5303";
    private static final String VALIDATION_MODE_PROPERTY =
        "turboism.validation.autoBackup.mode";
    private static final String VALIDATION_MODE = "matrix";
    private static final String VALIDATION_RUN_ID_PROPERTY =
        "turboism.validation.runId";

    public static final String ADAPTER_SLICE_ID = "adapter.cubism.autobackup";
    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.autobackup.settings",
        "cubism.autobackup.backup"
    );

    public static final String VERIFICATION_ID_5303 = "cubism-5.3.03.autobackup.static";
    public static final String RECORD_SHA256_5303 =
        "7ed9f31588a9f9ba9fff3ac9300d9e1e8ff324aac2428b601c94599b8f0070ad";
    public static final String CUBISM_VERSION_5303 = "5.3.03";
    public static final String PROFILE_ID_5303 = "cubism-5.3.03";
    public static final long ARTIFACT_SIZE_5303 = ReviewedHostArtifacts.CUBISM_5_3_03.size();
    public static final String ARTIFACT_SHA256_5303 = ReviewedHostArtifacts.CUBISM_5_3_03.sha256();

    public static final String VERIFICATION_ID_53 = "cubism-5.3.02.autobackup.static";
    public static final String RECORD_SHA256_53 =
        "a5c2806810c84856f60296e2a9c92a35737994dcff9c636d7da1bc8e6a1ab19b";
    public static final String CUBISM_VERSION_53 = "5.3.02";
    public static final String PROFILE_ID_53 = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE_53 = ReviewedHostArtifacts.CUBISM_5_3_02.size();
    public static final String ARTIFACT_SHA256_53 = ReviewedHostArtifacts.CUBISM_5_3_02.sha256();

    public static final String VERIFICATION_ID_52 = "cubism-5.2.03.autobackup.static";
    public static final String RECORD_SHA256_52 =
        "380b2683b24fbfeaf3b49c41827a61d8217191c2dd1789d544c3b6047f341a47";
    public static final String CUBISM_VERSION_52 = "5.2.03";
    public static final String PROFILE_ID_52 = "cubism-5.2.03";
    public static final long ARTIFACT_SIZE_52 = ReviewedHostArtifacts.CUBISM_5_2_03.size();
    public static final String ARTIFACT_SHA256_52 = ReviewedHostArtifacts.CUBISM_5_2_03.sha256();

    /**
     * Returns whether this JVM is the exact task-scoped 5.3.03 auto-backup matrix lane.
     *
     * <p>The reviewed static record and exact host artifact are checked independently by the
     * resolver workflow. This identity admits only composition of the auto-backup adapter for the
     * validation plugin; unrelated runtime families and normal production admission stay closed.</p>
     */
    public static boolean admits5303ValidationCandidate() {
        return admits5303ValidationCandidate(
            System.getProperty(VALIDATION_PROPERTY),
            System.getProperty(VALIDATION_HOST_VERSION_PROPERTY),
            System.getProperty(VALIDATION_MODE_PROPERTY),
            System.getProperty(VALIDATION_RUN_ID_PROPERTY)
        );
    }

    static boolean admits5303ValidationCandidate(
        final String token,
        final String hostVersion,
        final String mode,
        final String runId
    ) {
        return VALIDATION_TOKEN.equals(token)
            && VALIDATION_HOST_VERSION.equals(hostVersion)
            && VALIDATION_MODE.equals(mode)
            && runId != null
            && !runId.isBlank();
    }

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
        if (artifact.size() == ARTIFACT_SIZE_5303
            && artifact.sha256().equals(ARTIFACT_SHA256_5303)) {
            return manifest5303();
        }
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

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5303() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5303,
            RECORD_SHA256_5303,
            CUBISM_VERSION_5303,
            PROFILE_ID_5303,
            ARTIFACT_SIZE_5303,
            ARTIFACT_SHA256_5303,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
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

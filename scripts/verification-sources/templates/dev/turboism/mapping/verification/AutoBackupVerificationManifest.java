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

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-autobackup.json:adapterSliceId}";
    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.autobackup.settings",
        "cubism.autobackup.backup"
    );

    public static final String VERIFICATION_ID_5_1_00 = "${record:cubism-5.1.00-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_1_00 =
        "${record:cubism-5.1.00-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_1_00 = "${record:cubism-5.1.00-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_1_00 = "${record:cubism-5.1.00-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_1_00 = ReviewedHostArtifacts.CUBISM_5_1_00.size();
    public static final String ARTIFACT_SHA256_5_1_00 = ReviewedHostArtifacts.CUBISM_5_1_00.sha256();

    public static final String VERIFICATION_ID_5_1_01 = "${record:cubism-5.1.01-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_1_01 =
        "${record:cubism-5.1.01-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_1_01 = "${record:cubism-5.1.01-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_1_01 = "${record:cubism-5.1.01-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_1_01 = ReviewedHostArtifacts.CUBISM_5_1_01.size();
    public static final String ARTIFACT_SHA256_5_1_01 = ReviewedHostArtifacts.CUBISM_5_1_01.sha256();

    public static final String VERIFICATION_ID_5_1_02 = "${record:cubism-5.1.02-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_1_02 =
        "${record:cubism-5.1.02-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_1_02 = "${record:cubism-5.1.02-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_1_02 = "${record:cubism-5.1.02-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_1_02 = ReviewedHostArtifacts.CUBISM_5_1_02.size();
    public static final String ARTIFACT_SHA256_5_1_02 = ReviewedHostArtifacts.CUBISM_5_1_02.sha256();

    public static final String VERIFICATION_ID_5_1_03 = "${record:cubism-5.1.03-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_1_03 =
        "${record:cubism-5.1.03-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_1_03 = "${record:cubism-5.1.03-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_1_03 = "${record:cubism-5.1.03-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_1_03 = ReviewedHostArtifacts.CUBISM_5_1_03.size();
    public static final String ARTIFACT_SHA256_5_1_03 = ReviewedHostArtifacts.CUBISM_5_1_03.sha256();

    public static final String VERIFICATION_ID_5_2_00 = "${record:cubism-5.2.00-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_2_00 =
        "${record:cubism-5.2.00-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_2_00 = "${record:cubism-5.2.00-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_2_00 = "${record:cubism-5.2.00-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_2_00 = ReviewedHostArtifacts.CUBISM_5_2_00.size();
    public static final String ARTIFACT_SHA256_5_2_00 = ReviewedHostArtifacts.CUBISM_5_2_00.sha256();

    public static final String VERIFICATION_ID_5_2_01 = "${record:cubism-5.2.01-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_2_01 =
        "${record:cubism-5.2.01-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_2_01 = "${record:cubism-5.2.01-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_2_01 = "${record:cubism-5.2.01-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_2_01 = ReviewedHostArtifacts.CUBISM_5_2_01.size();
    public static final String ARTIFACT_SHA256_5_2_01 = ReviewedHostArtifacts.CUBISM_5_2_01.sha256();

    public static final String VERIFICATION_ID_5_2_02 = "${record:cubism-5.2.02-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_2_02 =
        "${record:cubism-5.2.02-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_2_02 = "${record:cubism-5.2.02-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_2_02 = "${record:cubism-5.2.02-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_2_02 = ReviewedHostArtifacts.CUBISM_5_2_02.size();
    public static final String ARTIFACT_SHA256_5_2_02 = ReviewedHostArtifacts.CUBISM_5_2_02.sha256();

    public static final String VERIFICATION_ID_5_2_03 = "${record:cubism-5.2.03-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_2_03 =
        "${record:cubism-5.2.03-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_2_03 = "${record:cubism-5.2.03-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_2_03 = "${record:cubism-5.2.03-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_2_03 = ReviewedHostArtifacts.CUBISM_5_2_03.size();
    public static final String ARTIFACT_SHA256_5_2_03 = ReviewedHostArtifacts.CUBISM_5_2_03.sha256();

    public static final String VERIFICATION_ID_5_3_00 = "${record:cubism-5.3.00-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_3_00 =
        "${record:cubism-5.3.00-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_3_00 = "${record:cubism-5.3.00-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_3_00 = "${record:cubism-5.3.00-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_3_00 = ReviewedHostArtifacts.CUBISM_5_3_00.size();
    public static final String ARTIFACT_SHA256_5_3_00 = ReviewedHostArtifacts.CUBISM_5_3_00.sha256();

    public static final String VERIFICATION_ID_5_3_01 = "${record:cubism-5.3.01-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_3_01 =
        "${record:cubism-5.3.01-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_3_01 = "${record:cubism-5.3.01-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_3_01 = "${record:cubism-5.3.01-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_3_01 = ReviewedHostArtifacts.CUBISM_5_3_01.size();
    public static final String ARTIFACT_SHA256_5_3_01 = ReviewedHostArtifacts.CUBISM_5_3_01.sha256();

    public static final String VERIFICATION_ID_5_3_02 = "${record:cubism-5.3.02-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_3_02 =
        "${record:cubism-5.3.02-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_3_02 = "${record:cubism-5.3.02-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_3_02 = "${record:cubism-5.3.02-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_3_02 = ReviewedHostArtifacts.CUBISM_5_3_02.size();
    public static final String ARTIFACT_SHA256_5_3_02 = ReviewedHostArtifacts.CUBISM_5_3_02.sha256();

    public static final String VERIFICATION_ID_5_3_03 = "${record:cubism-5.3.03-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_3_03 =
        "${record:cubism-5.3.03-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_3_03 = "${record:cubism-5.3.03-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_3_03 = "${record:cubism-5.3.03-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_3_03 = ReviewedHostArtifacts.CUBISM_5_3_03.size();
    public static final String ARTIFACT_SHA256_5_3_03 = ReviewedHostArtifacts.CUBISM_5_3_03.sha256();

    public static final String VERIFICATION_ID_5_3_04 = "${record:cubism-5.3.04-autobackup.json:verificationId}";
    public static final String RECORD_SHA256_5_3_04 =
        "${record:cubism-5.3.04-autobackup.json:sha256}";
    public static final String CUBISM_VERSION_5_3_04 = "${record:cubism-5.3.04-autobackup.json:cubismVersion}";
    public static final String PROFILE_ID_5_3_04 = "${record:cubism-5.3.04-autobackup.json:profileId}";
    public static final long ARTIFACT_SIZE_5_3_04 = ReviewedHostArtifacts.CUBISM_5_3_04.size();
    public static final String ARTIFACT_SHA256_5_3_04 = ReviewedHostArtifacts.CUBISM_5_3_04.sha256();
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
        if (artifact.size() == ARTIFACT_SIZE_5_1_00
            && artifact.sha256().equals(ARTIFACT_SHA256_5_1_00)) {
            return manifest5_1_00();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_1_01
            && artifact.sha256().equals(ARTIFACT_SHA256_5_1_01)) {
            return manifest5_1_01();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_1_02
            && artifact.sha256().equals(ARTIFACT_SHA256_5_1_02)) {
            return manifest5_1_02();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_1_03
            && artifact.sha256().equals(ARTIFACT_SHA256_5_1_03)) {
            return manifest5_1_03();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_2_00
            && artifact.sha256().equals(ARTIFACT_SHA256_5_2_00)) {
            return manifest5_2_00();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_2_01
            && artifact.sha256().equals(ARTIFACT_SHA256_5_2_01)) {
            return manifest5_2_01();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_2_02
            && artifact.sha256().equals(ARTIFACT_SHA256_5_2_02)) {
            return manifest5_2_02();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_2_03
            && artifact.sha256().equals(ARTIFACT_SHA256_5_2_03)) {
            return manifest5_2_03();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_3_00
            && artifact.sha256().equals(ARTIFACT_SHA256_5_3_00)) {
            return manifest5_3_00();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_3_01
            && artifact.sha256().equals(ARTIFACT_SHA256_5_3_01)) {
            return manifest5_3_01();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_3_02
            && artifact.sha256().equals(ARTIFACT_SHA256_5_3_02)) {
            return manifest5_3_02();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_3_03
            && artifact.sha256().equals(ARTIFACT_SHA256_5_3_03)) {
            return manifest5_3_03();
        }
        if (artifact.size() == ARTIFACT_SIZE_5_3_04
            && artifact.sha256().equals(ARTIFACT_SHA256_5_3_04)) {
            return manifest5_3_04();
        }
        throw new IllegalArgumentException(
            "host artifact is not the reviewed Cubism auto-backup artifact"
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_1_00() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_1_00,
            RECORD_SHA256_5_1_00,
            CUBISM_VERSION_5_1_00,
            PROFILE_ID_5_1_00,
            ARTIFACT_SIZE_5_1_00,
            ARTIFACT_SHA256_5_1_00,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_1_01() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_1_01,
            RECORD_SHA256_5_1_01,
            CUBISM_VERSION_5_1_01,
            PROFILE_ID_5_1_01,
            ARTIFACT_SIZE_5_1_01,
            ARTIFACT_SHA256_5_1_01,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_1_02() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_1_02,
            RECORD_SHA256_5_1_02,
            CUBISM_VERSION_5_1_02,
            PROFILE_ID_5_1_02,
            ARTIFACT_SIZE_5_1_02,
            ARTIFACT_SHA256_5_1_02,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_1_03() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_1_03,
            RECORD_SHA256_5_1_03,
            CUBISM_VERSION_5_1_03,
            PROFILE_ID_5_1_03,
            ARTIFACT_SIZE_5_1_03,
            ARTIFACT_SHA256_5_1_03,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_2_00() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_2_00,
            RECORD_SHA256_5_2_00,
            CUBISM_VERSION_5_2_00,
            PROFILE_ID_5_2_00,
            ARTIFACT_SIZE_5_2_00,
            ARTIFACT_SHA256_5_2_00,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_2_01() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_2_01,
            RECORD_SHA256_5_2_01,
            CUBISM_VERSION_5_2_01,
            PROFILE_ID_5_2_01,
            ARTIFACT_SIZE_5_2_01,
            ARTIFACT_SHA256_5_2_01,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_2_02() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_2_02,
            RECORD_SHA256_5_2_02,
            CUBISM_VERSION_5_2_02,
            PROFILE_ID_5_2_02,
            ARTIFACT_SIZE_5_2_02,
            ARTIFACT_SHA256_5_2_02,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_2_03() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_2_03,
            RECORD_SHA256_5_2_03,
            CUBISM_VERSION_5_2_03,
            PROFILE_ID_5_2_03,
            ARTIFACT_SIZE_5_2_03,
            ARTIFACT_SHA256_5_2_03,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_3_00() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_3_00,
            RECORD_SHA256_5_3_00,
            CUBISM_VERSION_5_3_00,
            PROFILE_ID_5_3_00,
            ARTIFACT_SIZE_5_3_00,
            ARTIFACT_SHA256_5_3_00,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_3_01() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_3_01,
            RECORD_SHA256_5_3_01,
            CUBISM_VERSION_5_3_01,
            PROFILE_ID_5_3_01,
            ARTIFACT_SIZE_5_3_01,
            ARTIFACT_SHA256_5_3_01,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_3_02() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_3_02,
            RECORD_SHA256_5_3_02,
            CUBISM_VERSION_5_3_02,
            PROFILE_ID_5_3_02,
            ARTIFACT_SIZE_5_3_02,
            ARTIFACT_SHA256_5_3_02,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_3_03() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_3_03,
            RECORD_SHA256_5_3_03,
            CUBISM_VERSION_5_3_03,
            PROFILE_ID_5_3_03,
            ARTIFACT_SIZE_5_3_03,
            ARTIFACT_SHA256_5_3_03,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest5_3_04() {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            VERIFICATION_ID_5_3_04,
            RECORD_SHA256_5_3_04,
            CUBISM_VERSION_5_3_04,
            PROFILE_ID_5_3_04,
            ARTIFACT_SIZE_5_3_04,
            ARTIFACT_SHA256_5_3_04,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private AutoBackupVerificationManifest() {
    }
}

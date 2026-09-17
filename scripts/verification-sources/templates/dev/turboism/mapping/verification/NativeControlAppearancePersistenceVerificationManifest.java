package dev.turboism.mapping.verification;

import java.util.Set;

/** Task-only exact-5.3.03 admission for native control-appearance persistence phases. */
public final class NativeControlAppearancePersistenceVerificationManifest {

    public static final String PROPERTY =
        "turboism.validation.editorNativeControlAppearancePersistence";
    public static final String TOKEN =
        "EXACT_5303_NATIVE_CONTROL_APPEARANCE_PERSISTENCE_CANDIDATE";
    public static final String WRITE_MODE =
        "native-control-appearance-persist-write-5303";
    public static final String REOPEN_MODE =
        "native-control-appearance-persist-reopen-5303";
    public static final String FINAL_MODE =
        "native-control-appearance-persist-final-5303";
    public static final Set<String> MODES = Set.of(WRITE_MODE, REOPEN_MODE, FINAL_MODE);

    /** Reports whether this process is admitted to a native 5.3.03 validation phase. */
    public static boolean admits5303ValidationCandidate() {
        return admits5303ValidationCandidate(
            System.getProperty(PROPERTY),
            System.getProperty("turboism.validation.hostVersion"),
            System.getProperty("turboism.editorObjectValidation.mode"),
            System.getProperty("turboism.validation.runId")
        );
    }

    static boolean admits5303ValidationCandidate(
        final String token,
        final String hostVersion,
        final String mode,
        final String runId
    ) {
        return TOKEN.equals(token)
            && "5303".equals(hostVersion)
            && MODES.contains(mode)
            && runId != null
            && !runId.isBlank();
    }

    private NativeControlAppearancePersistenceVerificationManifest() {
    }
}

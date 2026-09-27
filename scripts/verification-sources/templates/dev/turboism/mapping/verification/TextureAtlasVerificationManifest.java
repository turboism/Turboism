package dev.turboism.mapping.verification;

/** Task-scoped runtime admission for exact-host 5.3.03 texture-atlas Hook validation. */
public final class TextureAtlasVerificationManifest {

    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    private static final String VALIDATION_PROPERTY =
        "turboism.validation.textureAtlas";
    private static final String DATA_MODEL_VALIDATION_TOKEN =
        "EXACT_5303_TEXTURE_ATLAS_HOOK_CANDIDATE";
    private static final String AUTO_LAYOUT_VALIDATION_TOKEN =
        "EXACT_5303_TEXTURE_ATLAS_AUTO_LAYOUT_CALLBACK_CANDIDATE";
    private static final String NATIVE_FALLBACK_VALIDATION_TOKEN =
        "EXACT_5303_TEXTURE_ATLAS_NATIVE_FALLBACK_COMPLETION_CANDIDATE";
    private static final String THROWABLE_FALLBACK_VALIDATION_TOKEN =
        "EXACT_5303_TEXTURE_ATLAS_THROWABLE_FALLBACK_CANDIDATE";
    private static final String HANDLED_VALIDATION_TOKEN =
        "EXACT_5303_TEXTURE_ATLAS_HANDLED_TRUE_CANDIDATE";
    private static final String UI_LIFECYCLE_VALIDATION_TOKEN =
        "EXACT_5303_TEXTURE_ATLAS_UI_LIFECYCLE_CANDIDATE";
    private static final String PERSISTENCE_VALIDATION_TOKEN =
        "EXACT_5303_TEXTURE_ATLAS_PERSISTENCE_CANDIDATE";
    private static final String VALIDATION_HOST_VERSION_PROPERTY =
        "turboism.validation.hostVersion";
    private static final String VALIDATION_HOST_VERSION = "5303";
    private static final String VALIDATION_MODE_PROPERTY =
        "turboism.textureAtlasValidation.mode";
    private static final String DATA_MODEL_VALIDATION_MODE = "texture-atlas-hook-5303";
    private static final String AUTO_LAYOUT_VALIDATION_MODE =
        "texture-atlas-auto-layout-callback-5303";
    private static final String NATIVE_FALLBACK_VALIDATION_MODE =
        "texture-atlas-native-fallback-completion-5303";
    private static final String THROWABLE_FALLBACK_VALIDATION_MODE =
        "texture-atlas-throwable-fallback-5303";
    private static final String HANDLED_VALIDATION_MODE =
        "texture-atlas-handled-true-5303";
    private static final String UI_LIFECYCLE_VALIDATION_MODE =
        "texture-atlas-ui-lifecycle-5303";
    private static final String PERSIST_WRITE_VALIDATION_MODE =
        "texture-atlas-persist-write-5303";
    private static final String PERSIST_REOPEN_VALIDATION_MODE =
        "texture-atlas-persist-reopen-5303";
    private static final String PERSIST_FINAL_VALIDATION_MODE =
        "texture-atlas-persist-final-5303";
    private static final String VALIDATION_RUN_ID_PROPERTY =
        "turboism.validation.runId";

    /** Returns whether this JVM is the exact task-scoped 5.3.03 texture-atlas lane. */
    public static boolean admits5303ValidationCandidate() {
        return admits5303ValidationCandidate(
            System.getProperty(VALIDATION_PROPERTY),
            System.getProperty(VALIDATION_HOST_VERSION_PROPERTY),
            System.getProperty(VALIDATION_MODE_PROPERTY),
            System.getProperty(VALIDATION_RUN_ID_PROPERTY)
        );
    }

    /** Returns whether this JVM is the handled-true validation lane with restore-on-return. */
    public static boolean admits5303HandledValidationCandidate() {
        return admits5303HandledValidationCandidate(
            System.getProperty(VALIDATION_PROPERTY),
            System.getProperty(VALIDATION_HOST_VERSION_PROPERTY),
            System.getProperty(VALIDATION_MODE_PROPERTY),
            System.getProperty(VALIDATION_RUN_ID_PROPERTY)
        );
    }

    /** Returns whether this JVM is one of the three exact persistence protocol phases. */
    public static boolean admits5303PersistenceValidationCandidate() {
        return admits5303PersistenceValidationCandidate(
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
        return (DATA_MODEL_VALIDATION_TOKEN.equals(token)
                && DATA_MODEL_VALIDATION_MODE.equals(mode)
            || AUTO_LAYOUT_VALIDATION_TOKEN.equals(token)
                && AUTO_LAYOUT_VALIDATION_MODE.equals(mode)
            || NATIVE_FALLBACK_VALIDATION_TOKEN.equals(token)
                && NATIVE_FALLBACK_VALIDATION_MODE.equals(mode)
            || THROWABLE_FALLBACK_VALIDATION_TOKEN.equals(token)
                && THROWABLE_FALLBACK_VALIDATION_MODE.equals(mode)
            || handledIdentity(token, mode)
            || uiLifecycleIdentity(token, mode)
            || persistenceIdentity(token, mode))
            && exactTask(hostVersion, runId);
    }

    static boolean admits5303HandledValidationCandidate(
        final String token,
        final String hostVersion,
        final String mode,
        final String runId
    ) {
        return handledIdentity(token, mode) && exactTask(hostVersion, runId);
    }

    static boolean admits5303PersistenceValidationCandidate(
        final String token,
        final String hostVersion,
        final String mode,
        final String runId
    ) {
        return persistenceIdentity(token, mode) && exactTask(hostVersion, runId);
    }

    private static boolean handledIdentity(final String token, final String mode) {
        return HANDLED_VALIDATION_TOKEN.equals(token)
            && HANDLED_VALIDATION_MODE.equals(mode);
    }

    private static boolean uiLifecycleIdentity(final String token, final String mode) {
        return UI_LIFECYCLE_VALIDATION_TOKEN.equals(token)
            && UI_LIFECYCLE_VALIDATION_MODE.equals(mode);
    }

    private static boolean persistenceIdentity(final String token, final String mode) {
        return PERSISTENCE_VALIDATION_TOKEN.equals(token)
            && (PERSIST_WRITE_VALIDATION_MODE.equals(mode)
                || PERSIST_REOPEN_VALIDATION_MODE.equals(mode)
                || PERSIST_FINAL_VALIDATION_MODE.equals(mode));
    }

    private static boolean exactTask(final String hostVersion, final String runId) {
        return VALIDATION_HOST_VERSION.equals(hostVersion)
            && runId != null
            && !runId.isBlank();
    }

    private TextureAtlasVerificationManifest() { }
}

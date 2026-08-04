package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorModelEditLevelReadSelectorContract;
import dev.turboism.mapping.verification.EditorModelEditLevelWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.ModelEditLevel;

import java.util.Objects;

/** Verified access to Cubism Editor's active model edit level. */
final class EditorModelEditLevelAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorModelEditLevelAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    ModelEditLevel level(final String expectedIdentity, final Object expectedModel) {
        requireAuthorization(
            EditorModelEditLevelReadSelectorContract.CAPABILITY_ID,
            EditorModelEditLevelReadSelectorContract.REQUIRED_ALIASES,
            "Model edit-level access"
        );
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
        return currentLevel(app());
    }

    void setLevel(
        final String expectedIdentity,
        final Object expectedModel,
        final ModelEditLevel level
    ) {
        Objects.requireNonNull(level, "level");
        requireAuthorization(
            EditorModelEditLevelWriteSelectorContract.CAPABILITY_ID,
            EditorModelEditLevelWriteSelectorContract.REQUIRED_ALIASES,
            "Model edit-level switching"
        );
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
        final Object app = app();
        if (currentLevel(app) == level) {
            return;
        }
        resolver.invoke(
            "cubism.editor-model.app-controller.set-edit-level",
            app,
            Integer.valueOf(level.ordinal() + 1)
        );
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
    }

    private Object app() {
        return resolver.invokeStatic("cubism.editor-model.app-controller.instance");
    }

    private ModelEditLevel currentLevel(final Object app) {
        final Object value = resolver.invoke(
            "cubism.editor-model.app-controller.edit-level",
            app
        );
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Cubism model edit-level state is unavailable.");
        }
        final int index = number.intValue() - 1;
        final ModelEditLevel[] levels = ModelEditLevel.values();
        if (index < 0 || index >= levels.length) {
            throw new IllegalStateException(
                "Unsupported Cubism model edit level: " + number.intValue()
            );
        }
        return levels[index];
    }

    private void requireAuthorization(
        final String capabilityId,
        final java.util.Set<String> aliases,
        final String label
    ) {
        if (!resolver.authorizesFeature(
            EditorModelEditLevelReadSelectorContract.ADAPTER_SLICE_ID,
            capabilityId,
            aliases
        )) {
            throw new UnsupportedOperationException(
                label + " is unavailable without exact verified host evidence."
            );
        }
    }
}

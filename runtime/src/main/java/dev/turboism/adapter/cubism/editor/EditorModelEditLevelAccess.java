package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorModelEditLevelReadSelectorContract;
import dev.turboism.mapping.verification.EditorModelEditLevelWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.ModelEditLevel;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
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
        return dispatchOnEdt(() -> {
            modelGuard.requireCurrent(expectedIdentity, expectedModel);
            return currentLevel(app());
        });
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
        dispatchOnEdt(() -> {
            modelGuard.requireCurrent(expectedIdentity, expectedModel);
            final Object app = app();
            if (currentLevel(app) == level) {
                return null;
            }
            resolver.invoke(
                "cubism.editor-model.app-controller.set-edit-level",
                app,
                Integer.valueOf(level.ordinal() + 1)
            );
            modelGuard.requireCurrent(expectedIdentity, expectedModel);
            return null;
        });
    }

    private static <T> T dispatchOnEdt(final java.util.function.Supplier<T> task) {
        if (SwingUtilities.isEventDispatchThread()) return task.get();
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { result[0] = task.get(); }
                catch (Throwable throwable) { failure[0] = throwable; }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cubism model edit-level EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Cubism model edit-level EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) throw exception;
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] != null) {
            throw new IllegalStateException("Cubism model edit-level EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
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

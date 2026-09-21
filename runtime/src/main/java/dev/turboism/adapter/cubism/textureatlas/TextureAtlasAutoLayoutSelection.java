package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection;

import java.util.Objects;

/**
 * Runtime-owned automatic-layout selection state: the selected algorithm id (null for
 * the native default) and the parallel-search flag.
 *
 * <p>This is the single owner of selection state for the host session. The dialog
 * contribution writes it when the user changes the controls, plugins may request a
 * selection through {@code TextureAtlasLayoutAlgorithmRegistry.select}, and the
 * dispatcher reads it at invocation time. State is persisted through the supplied
 * {@link Persistence}; persistence failures degrade to in-memory state and are
 * reported, never thrown into callers.</p>
 */
public final class TextureAtlasAutoLayoutSelection {

    /** Load/save hook for a persistent backing store. */
    public interface Persistence {
        /** @return the stored selection; never null (use the native default when absent) */
        TextureAtlasLayoutSelection load();

        /** Persists the selection; failures are the store's responsibility to report. */
        void save(TextureAtlasLayoutSelection selection);
    }

    private final Persistence persistence;
    private TextureAtlasLayoutSelection current = TextureAtlasLayoutSelection.nativeDefault();

    /** In-memory selection with no persistence. */
    public TextureAtlasAutoLayoutSelection() {
        this(null);
    }

    public TextureAtlasAutoLayoutSelection(final Persistence persistence) {
        this.persistence = persistence;
        if (persistence != null) {
            try {
                final TextureAtlasLayoutSelection loaded = persistence.load();
                if (loaded != null) {
                    current = loaded;
                }
            } catch (RuntimeException failure) {
                report("Texture-atlas automatic-layout selection could not be loaded", failure);
            }
        }
    }

    /** @return the current selection; never null. */
    public synchronized TextureAtlasLayoutSelection selection() {
        return current;
    }

    /**
     * Replaces the selection and persists it. Applies immediately for the next
     * dispatch; an in-flight invocation keeps the selection it started with.
     *
     * <p>The mutation and the save are serialized under the monitor: a slower
     * earlier save can never overwrite a later selection in the store, so the
     * persisted state always matches the winning in-memory selection.</p>
     */
    public void select(final TextureAtlasLayoutSelection next) {
        Objects.requireNonNull(next, "next");
        synchronized (this) {
            if (next.equals(current)) {
                return;
            }
            current = next;
            if (persistence != null) {
                try {
                    persistence.save(next);
                } catch (RuntimeException failure) {
                    report(
                        "Texture-atlas automatic-layout selection could not be persisted",
                        failure
                    );
                }
            }
        }
    }

    private static void report(final String message, final RuntimeException failure) {
        dev.turboism.runtime.log.RuntimeDiagnostics.error("texture-atlas", message, failure);
    }
}

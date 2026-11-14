package dev.turboism.adapter.cubism.editor;

/** Resolves the generation-bound native source behind an SDK object view. */
final class EditorNativeSources {

    private static final String UNBOUND_MESSAGE = " is not bound to the active Editor model generation.";

    /**
     * Returns the native source of a generation-bound view.
     *
     * @param view the SDK object view, nullable
     * @param label the view description reported when the view is unbound
     * @return the native source, or {@code null} when {@code view} is {@code null}
     * @throws IllegalStateException when the view is not bound to the active model generation
     */
    static Object nativeSourceOf(final Object view, final String label) {
        if (view == null) {
            return null;
        }
        if (!(view instanceof EditorNativeObjectRef ref)) {
            throw new IllegalStateException("The " + label + UNBOUND_MESSAGE);
        }
        return ref.nativeSource();
    }

    private EditorNativeSources() {}
}

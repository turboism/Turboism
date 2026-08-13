package dev.turboism.mapping.verification;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for Editor Inspector Drawable/ArtMesh family
 * authoring writes on the Cubism 5.2 host. Identical to the 5.3.02 contract
 * except alpha-composition aliases (the {@code AlphaComposition} enum does not
 * exist in the 5.2 artifact) and color-composition writes restricted to the
 * 5.2 enum's NORMAL/ADD/MULTIPLY values (enforced by host {@code valueOf}).
 */
public final class EditorInspectorDrawableWrite52SelectorContract {

    public static final String CUBISM_VERSION = "5.2.0";
    public static final String ADAPTER_SLICE_ID = EditorInspectorDrawableWriteSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID;

    public static final Set<String> REQUIRED_ALIASES = aliases();

    private static Set<String> aliases() {
        final HashSet<String> values = new HashSet<>(
            EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES
        );
        values.removeAll(EditorInspectorDrawableWriteSelectorContract.ALPHA_COMPOSITION_ALIASES);
        return Set.copyOf(values);
    }

    private EditorInspectorDrawableWrite52SelectorContract() {
    }
}

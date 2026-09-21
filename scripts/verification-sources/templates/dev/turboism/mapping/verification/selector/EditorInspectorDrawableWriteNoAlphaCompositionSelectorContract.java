package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for Editor Inspector Drawable/ArtMesh family
 * authoring writes on the host whose {@link #CUBISM_VERSION} declares the bound version.
 * Identical to {@link EditorInspectorDrawableWriteSelectorContract} except alpha-composition
 * aliases (the {@code AlphaComposition} enum does not exist in that artifact) and
 * color-composition writes restricted to that enum's NORMAL/ADD/MULTIPLY values (enforced by
 * host {@code valueOf}).
 */
public final class EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract {

    public static final String CUBISM_VERSION = "${record:cubism-5.2.03-editor-model.json:cubismVersion}";
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

    private EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract() {
    }
}

package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for the parameter-key (keyform) family of the
 * external-application editing surface: {@code AddParameterKey}, {@code DeleteParameterKey},
 * {@code MoveParameterKey}, {@code GetParameterKeys}, and {@code GetObjectsByParameterKeys}.
 *
 * <p>Members are drawn from the verified keyform-grid and parameter-binding surface of the exact
 * Cubism 5.2.03, 5.3.02, and 5.3.03 host artifacts. The capability ids are declared ahead of
 * their verification records; {@code authorizesFeature} rejects every row until then.</p>
 */
public final class EditorEditParameterKeySelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    public static final String ADD_PARAMETER_KEY_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-key.add-parameter-key";
    public static final String DELETE_PARAMETER_KEY_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-key.delete-parameter-key";
    public static final String MOVE_PARAMETER_KEY_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-key.move-parameter-key";
    public static final String GET_PARAMETER_KEYS_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-key.get-parameter-keys";
    public static final String GET_OBJECTS_BY_PARAMETER_KEYS_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-key.get-objects-by-parameter-keys";

    /**
     * Members that resolve a model-object identity to its keyform grid and resolve a parameter
     * identity to its binding: object enumeration, controllable-source identity, grid and binding
     * navigation, and parameter identity lookup.
     */
    public static final Set<String> OBJECT_KEYFORM_LOOKUP_ALIASES = Set.of(
        "cubism.editor-model.model-source.all-objects",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.parameter-controllable-source.guid",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.class",
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-grid.find-binding",
        "cubism.editor-model.keyform-binding.class",
        "cubism.editor-model.keyform-binding.parameter-id",
        "cubism.editor-model.keyform-binding.parameter-guid",
        "cubism.editor-model.keyform-binding.keys",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.id.value",
        "cubism.editor-model.model.parameter-set",
        "cubism.editor-model.parameter-set.class",
        "cubism.editor-model.parameter-set.parameters",
        "cubism.editor-model.parameter.class",
        "cubism.editor-model.parameter.id",
        "cubism.editor-model.parameter.source",
        "cubism.editor-model.parameter-source.id",
        "cubism.editor-model.parameter-source.guid"
    );

    /**
     * Members that apply a keyform mutation inside the session transaction: the controllable
     * handler's undo capture plus the parameter/palette refresh.
     */
    private static final Set<String> KEYFORM_WRITE_APPLY_ALIASES = Set.of(
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.class",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette"
    );

    /** {@code AddParameterKey}: {@code KeyformGridSource.addKey(F, CParameterGuid)}. */
    public static final Set<String> ADD_PARAMETER_KEY_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        OBJECT_KEYFORM_LOOKUP_ALIASES,
        KEYFORM_WRITE_APPLY_ALIASES,
        Set.of("cubism.editor-model.keyform-grid.add-key")
    );

    /**
     * {@code DeleteParameterKey}: {@code removeKey} plus {@code removeAllKey} for the loose and
     * whole-parameter deletion shapes.
     */
    public static final Set<String> DELETE_PARAMETER_KEY_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        OBJECT_KEYFORM_LOOKUP_ALIASES,
        KEYFORM_WRITE_APPLY_ALIASES,
        Set.of(
            "cubism.editor-model.keyform-grid.remove-key",
            "cubism.editor-model.keyform-grid.remove-all-key"
        )
    );

    /** {@code MoveParameterKey}: {@code rearrangeKeyformsOnParameter}. */
    public static final Set<String> MOVE_PARAMETER_KEY_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        OBJECT_KEYFORM_LOOKUP_ALIASES,
        KEYFORM_WRITE_APPLY_ALIASES,
        Set.of("cubism.editor-model.keyform-grid.rearrange-keys")
    );

    /** {@code GetParameterKeys}: enumerate one object's parameter bindings and their keys. */
    public static final Set<String> GET_PARAMETER_KEYS_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.SESSION_NAVIGATION_ALIASES,
        OBJECT_KEYFORM_LOOKUP_ALIASES
    );

    /**
     * {@code GetObjectsByParameterKeys}: the same lookup surface, enumerated across every object
     * to invert the binding.
     */
    public static final Set<String> GET_OBJECTS_BY_PARAMETER_KEYS_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.SESSION_NAVIGATION_ALIASES,
        OBJECT_KEYFORM_LOOKUP_ALIASES
    );

    private static Set<String> unionAll(final Set<String>... sets) {
        final HashSet<String> values = new HashSet<>();
        for (final Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }

    private EditorEditParameterKeySelectorContract() {
    }
}

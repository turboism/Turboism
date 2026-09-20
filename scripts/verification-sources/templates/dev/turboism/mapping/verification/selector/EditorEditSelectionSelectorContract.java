package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for the selection family of the external-application editing
 * surface: {@code GetSelectedObjects}, {@code AddSelectedObjects}, and
 * {@code ClearSelectedObjects}.
 *
 * <p>{@code GetSelectedObjects} and {@code AddSelectedObjects} are deliberately declared in a
 * verification-record-missing shape: {@code update-manager.selection-guid-list} is only verified
 * on 5.3.03, the 5.2.03/5.3.02 records do not carry it, and the boolean semantics of
 * {@code update-manager.set-selection} are not yet host-validated. Both capability rows must stay
 * rejected at runtime until the missing verification lands; declaring them here pins the intended
 * member set without weakening admission.</p>
 */
public final class EditorEditSelectionSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    public static final String GET_SELECTED_OBJECTS_CAPABILITY_ID =
        "cubism.editor-model.edit.selection.get-selected-objects";
    public static final String ADD_SELECTED_OBJECTS_CAPABILITY_ID =
        "cubism.editor-model.edit.selection.add-selected-objects";
    public static final String CLEAR_SELECTED_OBJECTS_CAPABILITY_ID =
        "cubism.editor-model.edit.selection.clear-selected-objects";

    /** Members that reach the update manager owning the editor selection. */
    private static final Set<String> SELECTION_ACCESS_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.update-manager",
        "cubism.editor-model.update-manager.class"
    );

    /** Members that translate object ids to and from the host's selection guid list. */
    private static final Set<String> SELECTION_IDENTITY_ALIASES = Set.of(
        "cubism.editor-model.model-source.all-objects",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.parameter-controllable-source.guid",
        "cubism.editor-model.id.value",
        "cubism.editor-model.guid.value"
    );

    /**
     * {@code GetSelectedObjects} — verification record missing on 5.2.03 and 5.3.02:
     * {@code update-manager.selection-guid-list} is verified only on 5.3.03, and the runtime
     * selection snapshot is not yet wired. Must remain rejected until then.
     */
    public static final Set<String> GET_SELECTED_OBJECTS_REQUIRED_ALIASES = unionAll(
        SELECTION_ACCESS_ALIASES,
        SELECTION_IDENTITY_ALIASES,
        Set.of("cubism.editor-model.update-manager.selection-guid-list")
    );

    /**
     * {@code AddSelectedObjects} — verification record missing: the {@code setSelection} boolean
     * flags are not host-validated and the read side of the selection is not yet wired, so the
     * append semantics cannot be confirmed. Must remain rejected until then.
     */
    public static final Set<String> ADD_SELECTED_OBJECTS_REQUIRED_ALIASES = unionAll(
        SELECTION_ACCESS_ALIASES,
        SELECTION_IDENTITY_ALIASES,
        Set.of("cubism.editor-model.update-manager.set-selection")
    );

    /** {@code ClearSelectedObjects}: empties the selection through the same verified member. */
    public static final Set<String> CLEAR_SELECTED_OBJECTS_REQUIRED_ALIASES = unionAll(
        SELECTION_ACCESS_ALIASES,
        Set.of("cubism.editor-model.update-manager.set-selection")
    );

    private static Set<String> unionAll(final Set<String>... sets) {
        final HashSet<String> values = new HashSet<>();
        for (final Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }

    private EditorEditSelectionSelectorContract() {
    }
}

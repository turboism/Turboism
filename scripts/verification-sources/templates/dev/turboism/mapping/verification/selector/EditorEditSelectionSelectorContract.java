package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for the selection family of the external-application editing
 * surface: {@code GetSelectedObjects}, {@code AddSelectedObjects}, and
 * {@code ClearSelectedObjects}.
 *
 * <p>{@code update-manager.selection-guid-list} is now statically verified on all three exact
 * host artifacts (the 5.2.03/5.3.02 records gained it by bytecode inspection in T5), so the
 * three rows below are the verified member sets the runtime gates on. The boolean semantics of
 * {@code update-manager.set-selection} remain unverified by host validation (T7): the rows stay
 * bound to the reviewed member set, and any member a record drops still fails closed.</p>
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
     * {@code GetSelectedObjects}: update-manager reach, the selection guid list, and the
     * guid-to-id translation members. Verified on all three exact artifacts; any member a
     * record drops still fails the row closed.
     */
    public static final Set<String> GET_SELECTED_OBJECTS_REQUIRED_ALIASES = unionAll(
        SELECTION_ACCESS_ALIASES,
        SELECTION_IDENTITY_ALIASES,
        Set.of("cubism.editor-model.update-manager.selection-guid-list")
    );

    /**
     * {@code AddSelectedObjects}: the read side of the union-write plus the verified
     * {@code setSelection} member. The {@code setSelection} boolean flag semantics are
     * documented as awaiting host validation (T7); the member set itself is verified.
     */
    public static final Set<String> ADD_SELECTED_OBJECTS_REQUIRED_ALIASES = unionAll(
        SELECTION_ACCESS_ALIASES,
        SELECTION_IDENTITY_ALIASES,
        Set.of(
            "cubism.editor-model.update-manager.selection-guid-list",
            "cubism.editor-model.update-manager.set-selection")
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

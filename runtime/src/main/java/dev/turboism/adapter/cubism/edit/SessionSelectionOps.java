package dev.turboism.adapter.cubism.edit;

import dev.turboism.mapping.verification.selector.EditorEditSelectionSelectorContract;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.SelectionOps;
import dev.turboism.sdk.cubism.id.ModelObjectId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Selection family routed through the session's verified member surface (spec 046, T3/T5):
 * {@code GetSelectedObjects}, {@code AddSelectedObjects}, and {@code ClearSelectedObjects}.
 *
 * <p>{@code GetSelectedObjects} reads {@code CEUpdateManager.getSelectionGuidList()} and
 * translates guids to object ids inside the bound model; the member is statically verified on
 * all three exact records (T5).
 * {@code AddSelectedObjects} implements the official "read current selection, union, write"
 * shape — it therefore requires the same guid-list member and stays rejected wherever the read
 * is unverified. {@code ClearSelectedObjects} writes an empty selection through the verified
 * {@code set-selection} member.</p>
 */
final class SessionSelectionOps implements SelectionOps {

    private final EditSessionOps ops;

    SessionSelectionOps(final EditSessionOps ops) {
        this.ops = Objects.requireNonNull(ops, "ops");
    }

    @Override
    public List<ModelObjectId> selectedObjects() throws EditSessionException {
        return ops.dispatch("GetSelectedObjects", access -> {
            ops.require(
                access,
                EditorEditSelectionSelectorContract.GET_SELECTED_OBJECTS_CAPABILITY_ID,
                EditorEditSelectionSelectorContract.GET_SELECTED_OBJECTS_REQUIRED_ALIASES,
                "GetSelectedObjects");
            return ops.selectedObjectIds(access);
        });
    }

    @Override
    public boolean addSelectedObjects(final AddSelectedObjects request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("AddSelectedObjects", access -> {
            // Union-write needs the current selection, so the read member must be admitted too.
            ops.require(
                access,
                EditorEditSelectionSelectorContract.ADD_SELECTED_OBJECTS_CAPABILITY_ID,
                union(
                    EditorEditSelectionSelectorContract.ADD_SELECTED_OBJECTS_REQUIRED_ALIASES,
                    Set.of("cubism.editor-model.update-manager.selection-guid-list")),
                "AddSelectedObjects");
            final ArrayList<Object> targets = new ArrayList<>();
            final Set<String> seen = new LinkedHashSet<>();
            for (final ModelObjectId id : ops.selectedObjectIds(access)) {
                seen.add(id.value());
            }
            for (final ModelObjectId id : request.ids()) {
                if (seen.add(id.value())) {
                    targets.add(ops.requireObjectSourceById(access, id));
                }
            }
            // Append the already-selected sources after the new ids: the write carries the
            // union, and re-adding an existing entry must not duplicate it.
            for (final ModelObjectId id : ops.selectedObjectIds(access)) {
                targets.add(ops.requireObjectSourceById(access, id));
            }
            ops.writeSelection(access, targets);
            return true;
        });
    }

    @Override
    public boolean clearSelectedObjects() throws EditSessionException {
        return ops.dispatch("ClearSelectedObjects", access -> {
            ops.require(
                access,
                EditorEditSelectionSelectorContract.CLEAR_SELECTED_OBJECTS_CAPABILITY_ID,
                EditorEditSelectionSelectorContract.CLEAR_SELECTED_OBJECTS_REQUIRED_ALIASES,
                "ClearSelectedObjects");
            ops.writeSelection(access, List.of());
            return true;
        });
    }

    private static Set<String> union(final Set<String> base, final Set<String> extra) {
        final java.util.HashSet<String> values = new java.util.HashSet<>(base);
        values.addAll(extra);
        return Set.copyOf(values);
    }
}

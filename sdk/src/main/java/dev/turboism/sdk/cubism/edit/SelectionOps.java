package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.List;
import java.util.Objects;

/**
 * Typed operations of the selection family of the editing surface: reading and mutating the
 * editor's current object selection.
 *
 * <p>Note: {@link #selectedObjects()} and {@link #addSelectedObjects(AddSelectedObjects)} are
 * declared for parity with the official API but currently lack verified host bindings; they fail
 * closed with {@link EditUnavailableException} on every supported editor version until the
 * verification records land.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface SelectionOps {

    /**
     * Reads the ids of the currently selected objects ({@code GetSelectedObjects}).
     *
     * @return the selected object ids, never {@code null}
     */
    List<ModelObjectId> selectedObjects() throws EditSessionException;

    /**
     * Adds objects to the current selection ({@code AddSelectedObjects}).
     *
     * @return {@code true} when the selection was updated
     */
    boolean addSelectedObjects(AddSelectedObjects request) throws EditSessionException;

    /**
     * Clears the current selection ({@code ClearSelectedObjects}).
     *
     * @return {@code true} when the selection was cleared
     */
    boolean clearSelectedObjects() throws EditSessionException;

    /** Returns a fail-closed implementation in which every operation is unavailable. */
    static SelectionOps unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements SelectionOps {
        INSTANCE;

        @Override
        public List<ModelObjectId> selectedObjects() throws EditSessionException {
            throw new EditUnavailableException("SelectionOps.selectedObjects");
        }

        @Override
        public boolean addSelectedObjects(final AddSelectedObjects request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("SelectionOps.addSelectedObjects");
        }

        @Override
        public boolean clearSelectedObjects() throws EditSessionException {
            throw new EditUnavailableException("SelectionOps.clearSelectedObjects");
        }
    }

    /** {@code AddSelectedObjects} request: the object ids to add to the selection. */
    record AddSelectedObjects(List<ModelObjectId> ids) {
        public AddSelectedObjects {
            ids = List.copyOf(Objects.requireNonNull(ids, "ids"));
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("ids must not be empty");
            }
        }
    }
}

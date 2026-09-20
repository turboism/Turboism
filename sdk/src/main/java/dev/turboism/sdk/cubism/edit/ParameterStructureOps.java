package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed operations of the parameter-structure family of the editing surface: reading the
 * parameter tree and adding, editing, deleting, or reordering parameters and parameter groups.
 */
public interface ParameterStructureOps {

    /**
     * Reads the whole parameter tree ({@code GetParameterStructure}).
     *
     * @return the root parameter group; parameters outside any group appear as its children
     */
    EditParameterGroupNode parameterStructure() throws EditSessionException;

    /** Adds a parameter ({@code AddParameter}); returns {@code true} on success. */
    boolean addParameter(AddParameter request) throws EditSessionException;

    /** Adds a parameter group ({@code AddParameterGroup}); returns {@code true} on success. */
    boolean addParameterGroup(AddParameterGroup request) throws EditSessionException;

    /** Edits one parameter ({@code EditParameter}); returns {@code true} on success. */
    boolean editParameter(EditParameter request) throws EditSessionException;

    /** Edits one parameter group ({@code EditParameterGroup}); returns {@code true} on success. */
    boolean editParameterGroup(EditParameterGroup request) throws EditSessionException;

    /** Deletes one parameter ({@code DeleteParameter}); returns {@code true} on success. */
    boolean deleteParameter(DeleteParameter request) throws EditSessionException;

    /** Deletes one parameter group ({@code DeleteParameterGroup}); returns {@code true} on success. */
    boolean deleteParameterGroup(DeleteParameterGroup request) throws EditSessionException;

    /**
     * Moves a parameter into a group ({@code MoveParameter}); returns {@code true} on success.
     */
    boolean moveParameter(MoveParameter request) throws EditSessionException;

    /**
     * Reorders a parameter group among its siblings ({@code MoveParameterGroup}); returns
     * {@code true} on success.
     */
    boolean moveParameterGroup(MoveParameterGroup request) throws EditSessionException;

    /** Returns a fail-closed implementation in which every operation is unavailable. */
    static ParameterStructureOps unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements ParameterStructureOps {
        INSTANCE;

        @Override
        public EditParameterGroupNode parameterStructure() throws EditSessionException {
            throw new EditUnavailableException("ParameterStructureOps.parameterStructure");
        }

        @Override
        public boolean addParameter(final AddParameter request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterStructureOps.addParameter");
        }

        @Override
        public boolean addParameterGroup(final AddParameterGroup request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterStructureOps.addParameterGroup");
        }

        @Override
        public boolean editParameter(final EditParameter request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterStructureOps.editParameter");
        }

        @Override
        public boolean editParameterGroup(final EditParameterGroup request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterStructureOps.editParameterGroup");
        }

        @Override
        public boolean deleteParameter(final DeleteParameter request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterStructureOps.deleteParameter");
        }

        @Override
        public boolean deleteParameterGroup(final DeleteParameterGroup request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterStructureOps.deleteParameterGroup");
        }

        @Override
        public boolean moveParameter(final MoveParameter request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterStructureOps.moveParameter");
        }

        @Override
        public boolean moveParameterGroup(final MoveParameterGroup request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterStructureOps.moveParameterGroup");
        }
    }

    /**
     * {@code AddParameter} request. All fields are optional; absent fields take editor defaults.
     * {@code group} places the parameter inside an existing group.
     */
    record AddParameter(
            Optional<String> name,
            Optional<ParameterId> id,
            Optional<ParameterGroupId> group,
            Optional<Double> min,
            Optional<Double> defaultValue,
            Optional<Double> max,
            boolean blendShape) {

        public AddParameter {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(defaultValue, "defaultValue");
            Objects.requireNonNull(max, "max");
            requireFinite(min, "min");
            requireFinite(defaultValue, "defaultValue");
            requireFinite(max, "max");
        }

        private static void requireFinite(final Optional<Double> value, final String field) {
            value.ifPresent(v -> {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException(field + " must be finite");
                }
            });
        }
    }

    /** {@code AddParameterGroup} request; absent fields take editor defaults. */
    record AddParameterGroup(Optional<String> name, Optional<ParameterGroupId> id) {
        public AddParameterGroup {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * {@code EditParameter} request: {@code id} selects the parameter; the remaining fields are
     * applied only when present. {@code newId} renames the identifier.
     */
    record EditParameter(
            ParameterId id,
            Optional<ParameterId> newId,
            Optional<String> name,
            Optional<Double> min,
            Optional<Double> defaultValue,
            Optional<Double> max,
            Optional<Boolean> repeat) {

        public EditParameter {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(newId, "newId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(defaultValue, "defaultValue");
            Objects.requireNonNull(max, "max");
            Objects.requireNonNull(repeat, "repeat");
        }
    }

    /** {@code EditParameterGroup} request: {@code id} selects the group; the rest apply when present. */
    record EditParameterGroup(
            ParameterGroupId id,
            Optional<ParameterGroupId> newId,
            Optional<String> name,
            Optional<EditLabelColor> labelColor) {

        public EditParameterGroup {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(newId, "newId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(labelColor, "labelColor");
        }
    }

    /** {@code DeleteParameter} request. */
    record DeleteParameter(ParameterId id) {
        public DeleteParameter {
            Objects.requireNonNull(id, "id");
        }
    }

    /** {@code DeleteParameterGroup} request. */
    record DeleteParameterGroup(ParameterGroupId id) {
        public DeleteParameterGroup {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * {@code MoveParameter} request: moves {@code id} into {@code group}, optionally at an
     * explicit child index.
     */
    record MoveParameter(ParameterId id, ParameterGroupId group, Optional<Integer> insertIndex) {
        public MoveParameter {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(insertIndex, "insertIndex");
            insertIndex.ifPresent(i -> {
                if (i < 0) {
                    throw new IllegalArgumentException("insertIndex must be non-negative");
                }
            });
        }
    }

    /** {@code MoveParameterGroup} request: reorders {@code id} to {@code insertIndex}. */
    record MoveParameterGroup(ParameterGroupId id, int insertIndex) {
        public MoveParameterGroup {
            Objects.requireNonNull(id, "id");
            if (insertIndex < 0) {
                throw new IllegalArgumentException("insertIndex must be non-negative");
            }
        }
    }
}

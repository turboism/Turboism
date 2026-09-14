package dev.turboism.sdk.cubism.model;


import java.util.List;
import java.util.Objects;

/**
 * Typed model-object automation surface shared by plugins, MCP, and other frontends.
 *
 * <p>Implementations use the active Editor authoring model as the write source of truth and fail
 * before mutation when the current host route is unavailable.</p>
 */
public interface ModelObjectService {

    /** Returns every model object in the active authoring model, in host order. */
    List<ModelObjectDescriptor> list();

    /**
     * Renames one model object.
     *
     * @param target the object to rename
     * @param name the new display name
     * @return the descriptor observed after the rename
     */
    ModelObjectDescriptor rename(ModelObjectReference target, String name);

    /**
     * Moves one model object under {@code parent} at {@code index}.
     *
     * @param target the object to move
     * @param parent the new parent reference
     * @param index position within the new parent's children; negative appends
     * @return the descriptor observed after the move
     */
    ModelObjectDescriptor reparent(
        ModelObjectReference target,
        ModelObjectReference parent,
        int index
    );

    /**
     * Creates one model object described by {@code request}.
     *
     * @return the descriptor observed after creation
     */
    ModelObjectDescriptor create(ModelObjectCreateRequest request);

    /**
     * Deletes one model object under {@code policy}.
     *
     * @param target the object to delete
     * @param policy how contained children are handled
     */
    void delete(ModelObjectReference target, ModelObjectDeletePolicy policy);

    /** Returns the fail-closed service whose calls all report {@code UNAVAILABLE}. */
    static ModelObjectService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements ModelObjectService {
        INSTANCE;

        @Override public List<ModelObjectDescriptor> list() {
            throw unavailable();
        }

        @Override public ModelObjectDescriptor rename(
            final ModelObjectReference target,
            final String name
        ) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(name, "name");
            throw unavailable();
        }

        @Override public ModelObjectDescriptor reparent(
            final ModelObjectReference target,
            final ModelObjectReference parent,
            final int index
        ) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(parent, "parent");
            throw unavailable();
        }

        @Override public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
            Objects.requireNonNull(request, "request");
            throw unavailable();
        }

        @Override public void delete(
            final ModelObjectReference target,
            final ModelObjectDeletePolicy policy
        ) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(policy, "policy");
            throw unavailable();
        }

        private static ModelObjectOperationException unavailable() {
            return new ModelObjectOperationException(
                ModelObjectOperationException.Code.UNAVAILABLE,
                "Model-object automation is unavailable"
            );
        }
    }
}

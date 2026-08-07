package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;

/**
 * Typed model-object automation surface shared by plugins, MCP, and other frontends.
 *
 * <p>Implementations use the active Editor authoring model as the write source of truth and fail
 * before mutation when the current host route is unavailable.</p>
 */
@PreviewApi
public interface ModelObjectService {

    List<ModelObjectDescriptor> list();

    ModelObjectDescriptor rename(ModelObjectReference target, String name);

    ModelObjectDescriptor create(ModelObjectCreateRequest request);

    void delete(ModelObjectReference target, ModelObjectDeletePolicy policy);

    static ModelObjectService unavailable() {
        return Unavailable.INSTANCE;
    }

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

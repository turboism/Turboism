package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorModelInstanceReadSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.InstanceRenderType;
import dev.turboism.sdk.cubism.model.ModelInstance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact, generation-bound Editor projection for model-instance reads.
 *
 * <p>Read-only: the decompiled host mutations ({@code createModelInstance},
 * {@code setCurrentInstance}, {@code setModelEditing}, {@code createRootPart},
 * {@code removeParameterControllableSource}) are plain field/list mutations
 * without any Undo path, so the write family fails closed.</p>
 */
final class EditorModelInstanceAccess {

    private static final String INSTANCE_CLASS_ALIAS = "cubism.editor-model.model-instance.class";
    private static final String RENDER_TYPE_ALIAS = "cubism.editor-model.model-instance.render-type";
    private static final String INSTANCES_ALIAS = "cubism.editor-model.model-source.model-instances";
    private static final String CURRENT_INSTANCE_ALIAS = "cubism.editor-model.model-source.current-instance";
    private static final String MODEL_EDITING_ALIAS = "cubism.editor-model.model-source.model-editing";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorModelInstanceAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    List<ModelInstance> modelInstances(final String identity, final Object source, final Object model) {
        requireAuthorization();
        modelGuard.requireCurrent(identity, model);
        final Object rawInstances = resolver.invoke(INSTANCES_ALIAS, source);
        if (!(rawInstances instanceof List<?> instances)) {
            throw unavailable("Editor model-instance list is unavailable.");
        }
        final List<ModelInstance> projections = new ArrayList<>(instances.size());
        for (Object instance : instances) {
            projections.add(project(instance));
        }
        return List.copyOf(projections);
    }

    Optional<ModelInstance> currentModelInstance(
        final String identity,
        final Object source,
        final Object model
    ) {
        requireAuthorization();
        modelGuard.requireCurrent(identity, model);
        final Object rawCurrent = resolver.invoke(CURRENT_INSTANCE_ALIAS, source);
        if (rawCurrent == null) {
            return Optional.empty();
        }
        final Object rawInstances = resolver.invoke(INSTANCES_ALIAS, source);
        if (rawInstances instanceof List<?> instances) {
            for (Object instance : instances) {
                if (instance == rawCurrent) {
                    return Optional.of(project(instance));
                }
            }
        }
        return Optional.of(project(rawCurrent));
    }

    boolean modelEditing(final String identity, final Object source, final Object model) {
        requireAuthorization();
        modelGuard.requireCurrent(identity, model);
        final Object rawEditing = resolver.invoke(MODEL_EDITING_ALIAS, source);
        if (!(rawEditing instanceof Boolean editing)) {
            throw unavailable("Editor model-editing state is invalid.");
        }
        return editing;
    }

    private ModelInstance project(final Object instance) {
        if (!resolver.isInstance(INSTANCE_CLASS_ALIAS, instance)) {
            throw unavailable("Editor model instance is invalid.");
        }
        final Object rawRenderType = resolver.invoke(RENDER_TYPE_ALIAS, instance);
        return new EditorModelInstance(renderType(rawRenderType));
    }

    private InstanceRenderType renderType(final Object rawType) {
        if (sameInstance(rawType, "cubism.editor-model.render-type.normal")) {
            return InstanceRenderType.NORMAL;
        }
        if (sameInstance(rawType, "cubism.editor-model.render-type.psd-export")) {
            return InstanceRenderType.PSD_EXPORT;
        }
        if (sameInstance(rawType, "cubism.editor-model.render-type.art-path")) {
            return InstanceRenderType.ART_PATH;
        }
        if (sameInstance(rawType, "cubism.editor-model.render-type.art-path-illegal")) {
            return InstanceRenderType.ART_PATH_ILLEGAL;
        }
        if (resolver.isExactCubismVersion(EditorModelInstanceReadSelectorContract.CUBISM_VERSION)
            && sameInstance(rawType, "cubism.editor-model.render-type.onion-skin-for-modeling")) {
            return InstanceRenderType.ONION_SKIN_FOR_MODELING;
        }
        throw unavailable("Editor model-instance render type is invalid.");
    }

    private boolean sameInstance(final Object value, final String fieldAlias) {
        return value != null && value == resolver.readStaticField(fieldAlias);
    }

    private void requireAuthorization() {
        if (!resolver.authorizesFeature(
            EditorModelInstanceReadSelectorContract.ADAPTER_SLICE_ID,
            EditorModelInstanceReadSelectorContract.CAPABILITY_ID,
            requiredAliases()
        )) {
            throw new UnsupportedOperationException(
                "Model-instance access is unavailable without exact verified host evidence."
            );
        }
    }

    private Set<String> requiredAliases() {
        if (resolver.isExactCubismVersion(EditorModelInstanceReadSelectorContract.CUBISM_VERSION)) {
            return EditorModelInstanceReadSelectorContract.REQUIRED_ALIASES;
        }
        final HashSet<String> aliases = new HashSet<>(
            EditorModelInstanceReadSelectorContract.REQUIRED_ALIASES
        );
        aliases.removeAll(EditorModelInstanceReadSelectorContract.ONION_SKIN_ALIASES);
        return Set.copyOf(aliases);
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    private record EditorModelInstance(InstanceRenderType renderType) implements ModelInstance {
        @Override public InstanceRenderType renderType() { return renderType; }
    }
}

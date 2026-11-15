package dev.turboism.adapter.cubism.optimization.scenehandler;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines the reviewed host classes and descriptors for scene mouse-handler traversal caching.
 *
 * <p>{@code CEViewContext.onInputEvent_exe} traverses every entity in the scene graph via
 * {@code GEntity.traverseAll()} and iterates every entity's {@code Components} collection on each
 * input event to collect {@code GMouseHandlerComponent} instances in depth-first order. Because
 * transient scene-graph overlay mutations during rendering rarely attach or detach mouse handlers,
 * the bridge caches the depth-first handler list per scene root and bumps its epoch only when an
 * entity or component mutation involves {@code GMouseHandlerComponent}.</p>
 */
public record SceneHandlerCacheTarget(HostArtifactDigest digest, String version) {

    /** Scene view context owner containing the input-event dispatch entry point. */
    public static final String VIEW_CONTEXT_OWNER = "com/live2d/cubism/view/context/CEViewContext";

    /** Input-event execution method on {@code CEViewContext}. */
    public static final String INPUT_EVENT_METHOD = "onInputEvent_exe";

    /** Descriptor of {@code CEViewContext.onInputEvent_exe(h)}. */
    public static final String INPUT_EVENT_DESCRIPTOR = "(Lcom/live2d/ui/event/h;)V";

    /** Scene entity owner. */
    public static final String ENTITY_OWNER = "com/live2d/graphics3d/entity/GEntity";

    /** Full subtree traversal method on {@code GEntity}. */
    public static final String TRAVERSE_ALL_METHOD = "traverseAll";

    /** Descriptor of {@code GEntity.traverseAll()}. */
    public static final String TRAVERSE_ALL_DESCRIPTOR = "()Ljava/lang/Iterable;";

    /** Entity child-collection accessor on {@code GEntity}. */
    public static final String GET_CHILDREN_METHOD = "getChildren";

    /** Descriptor of {@code GEntity.getChildren()}. */
    public static final String GET_CHILDREN_DESCRIPTOR = "()Lcom/live2d/graphics3d/entity/Entities;";

    /** Entity component-collection accessor on {@code GEntity}. */
    public static final String GET_COMPONENTS_METHOD = "getComponents";

    /** Descriptor of {@code GEntity.getComponents()}. */
    public static final String GET_COMPONENTS_DESCRIPTOR = "()Lcom/live2d/graphics3d/entity/Components;";

    /** Child-entity collection owner. */
    public static final String ENTITIES_OWNER = "com/live2d/graphics3d/entity/Entities";

    /** Backing list accessor on {@code Entities}. */
    public static final String ENTITIES_LIST_METHOD = "getList";

    /** Descriptor of {@code Entities.getList()}. */
    public static final String ENTITIES_LIST_DESCRIPTOR = "()Ljava/util/List;";

    /** Entity insertion method on {@code Entities}. */
    public static final String ENTITIES_ADD_METHOD = "add";

    /** Descriptor of {@code Entities.add(GEntity, int)}. */
    public static final String ENTITIES_ADD_DESCRIPTOR = "(Lcom/live2d/graphics3d/entity/GEntity;I)V";

    /** Entity removal method on {@code Entities}. */
    public static final String ENTITIES_REMOVE_METHOD = "remove";

    /** Descriptor of {@code Entities.remove(GEntity)}. */
    public static final String ENTITIES_REMOVE_DESCRIPTOR = "(Lcom/live2d/graphics3d/entity/GEntity;)V";

    /** Bulk clear method on {@code Entities}. */
    public static final String ENTITIES_REMOVE_ALL_METHOD = "removeAll";

    /** Descriptor of {@code Entities.removeAll()}. */
    public static final String ENTITIES_REMOVE_ALL_DESCRIPTOR = "()V";

    /** Indexed removal method on {@code Entities}. */
    public static final String ENTITIES_REMOVE_AT_METHOD = "removeAt";

    /** Descriptor of {@code Entities.removeAt(int)}. */
    public static final String ENTITIES_REMOVE_AT_DESCRIPTOR = "(I)V";

    /** Predicate removal method on {@code Entities}. */
    public static final String ENTITIES_REMOVE_IF_METHOD = "removeIf";

    /** Descriptor of {@code Entities.removeIf(Function1)}. */
    public static final String ENTITIES_REMOVE_IF_DESCRIPTOR = "(Lkotlin/jvm/functions/Function1;)V";

    /** Component collection owner. */
    public static final String COMPONENTS_OWNER = "com/live2d/graphics3d/entity/Components";

    /** Backing list accessor and mutator name on {@code Components}. */
    public static final String COMPONENTS_METHOD = "a";

    /** Descriptor of {@code Components.a()} returning the backing component list. */
    public static final String COMPONENTS_LIST_DESCRIPTOR = "()Ljava/util/List;";

    /** Descriptor of {@code Components.a(IGComponent, int)}. */
    public static final String COMPONENTS_ADD_DESCRIPTOR = "(Lcom/live2d/graphics3d/component/IGComponent;I)V";

    /** Descriptor of {@code Components.a(IGComponent)}. */
    public static final String COMPONENTS_REMOVE_DESCRIPTOR = "(Lcom/live2d/graphics3d/component/IGComponent;)V";

    /** Mouse-handler component owner collected by {@code CEViewContext.onInputEvent_exe}. */
    public static final String MOUSE_HANDLER_OWNER = "com/live2d/cubism/view/context/guiEntity/GMouseHandlerComponent";

    public SceneHandlerCacheTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /** Resolves the reviewed target for an admitted host artifact digest. */
    public static Optional<SceneHandlerCacheTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .map(version -> new SceneHandlerCacheTarget(digest, version));
    }

    /** Returns every reviewed target in version order. */
    public static List<SceneHandlerCacheTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    /** Returns the host methods read by {@link SceneHandlerCacheBridge}. */
    public List<Dep> dependencies() {
        return List.of(
                new Dep(ENTITY_OWNER, GET_COMPONENTS_METHOD, GET_COMPONENTS_DESCRIPTOR),
                new Dep(COMPONENTS_OWNER, COMPONENTS_METHOD, COMPONENTS_LIST_DESCRIPTOR),
                new Dep(ENTITY_OWNER, GET_CHILDREN_METHOD, GET_CHILDREN_DESCRIPTOR),
                new Dep(ENTITIES_OWNER, ENTITIES_LIST_METHOD, ENTITIES_LIST_DESCRIPTOR));
    }

    /** Identifies one reviewed dependency method verified before arming the weave. */
    public record Dep(String owner, String name, String descriptor) {
        public Dep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}

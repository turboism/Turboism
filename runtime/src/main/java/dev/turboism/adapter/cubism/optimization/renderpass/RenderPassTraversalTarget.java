package dev.turboism.adapter.cubism.optimization.renderpass;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reviewed render-system traversal and debug-label allocation targets across all admitted Cubism
 * Editor versions.
 *
 * <p>On Cubism 5.0.x–5.2.x the render system lives in {@code com/live2d/graphics3d/rendering/d}
 * and delegates scene traversal through {@code GEntity.traverse(Function2)}. On Cubism 5.3.x it
 * lives in {@code com/live2d/graphics3d/rendering/e} and recurses through
 * {@code e.a(GEntity, boolean, boolean, boolean, ArrayList)}. Across all reviewed versions,
 * traversal uses {@code Entities.iterator()}, reads {@code GEntity.getRenderer()} repeatedly per
 * entity, iterates sorted renderer lists with {@code Iterator}, and constructs {@code pre draw[i]}
 * and {@code post draw[i]} strings on every mesh draw call.</p>
 */
public record RenderPassTraversalTarget(
        HostArtifactDigest digest,
        String version,
        String renderSystemOwner,
        String renderImplDescriptor,
        String shaderHelperOwner,
        boolean hasRecursiveTraverseMethod,
        int expectedSites) {

    /** Reviewed Cubism versions sharing the render-pass traversal and draw-label shapes. */
    public static final Set<String> SUPPORTED_VERSIONS = ReviewedVersions.since("5.0.00");

    /** Render system internal class name on Cubism 5.0.x–5.2.x. */
    public static final String RENDER_SYSTEM_50 = "com/live2d/graphics3d/rendering/d";

    /** Render system internal class name on Cubism 5.3.x. */
    public static final String RENDER_SYSTEM_53 = "com/live2d/graphics3d/rendering/e";

    /** Private render-pass implementation method name across all reviewed versions. */
    public static final String RENDER_IMPL_METHOD = "b";

    /** Descriptor of {@code rendering/d.b} on Cubism 5.0.x–5.2.x. */
    public static final String RENDER_IMPL_DESCRIPTOR_50 = "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Z)V";

    /** Descriptor of {@code rendering/e.b} on Cubism 5.3.x. */
    public static final String RENDER_IMPL_DESCRIPTOR_53 = "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;ZZ)V";

    /** Recursive entity traversal method name on Cubism 5.3.x. */
    public static final String TRAVERSE_METHOD = "a";

    /** Descriptor of {@code rendering/e.a(GEntity, Z, Z, Z, ArrayList)}. */
    public static final String TRAVERSE_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/entity/GEntity;ZZZLjava/util/ArrayList;)V";

    /** Shader error helper owner on Cubism 5.0.x–5.1.x. */
    public static final String SHADER_HELPER_50 = "com/live2d/graphics3d/shader/w";

    /** Shader error helper owner on Cubism 5.2.x. */
    public static final String SHADER_HELPER_52 = "com/live2d/graphics3d/shader/y";

    /** Shader error helper owner on Cubism 5.3.x. */
    public static final String SHADER_HELPER_53 = "com/live2d/graphics3d/shader/A";

    /** Entity owner exposing child collections, enabled flag, and renderer cache. */
    public static final String ENTITY_OWNER = "com/live2d/graphics3d/entity/GEntity";

    /** Child collection owner exposing the backing {@code java.util.List}. */
    public static final String ENTITIES_OWNER = "com/live2d/graphics3d/entity/Entities";

    /** Base renderer component owner. */
    public static final String RENDERER_OWNER = "com/live2d/graphics3d/component/AGRenderer";

    /** Group renderer component owner. */
    public static final String GROUP_RENDERER_OWNER = "com/live2d/graphics3d/component/GGroupRenderer";

    /** Concrete {@code ArrayList} subclass used for the render list field. */
    public static final String CARRAY_LIST_OWNER = "com/live2d/type/CArrayList";

    /** Sorting-info component owner. */
    public static final String SORTING_INFO_OWNER = "com/live2d/graphics3d/component/a/b";

    /** Shader error helper method the rewritten traversal calls. */
    public static final String SHADER_DEFAULT_METHOD = "a";

    /**
     * Tail of the shader error helper descriptor every reviewed generation declares; the leading
     * parameter type is the generation's own shader helper owner.
     */
    private static final String SHADER_DEFAULT_DESCRIPTOR_TAIL =
            ";Lcom/jogamp/opengl/GL;Ljava/lang/String;ZILjava/lang/Object;)I";

    /** Expected rewrite site count on Cubism 5.0.x–5.2.x. */
    public static final int EXPECTED_SITES_50 = 8;

    /** Expected rewrite site count on Cubism 5.3.x. */
    public static final int EXPECTED_SITES_53 = 9;

    private static final String VERSION_PREFIX_53 = "5.3.";
    private static final String VERSION_PREFIX_52 = "5.2.";
    private static final String GET_CHILDREN_METHOD = "getChildren";
    private static final String GET_CHILDREN_DESCRIPTOR = "()Lcom/live2d/graphics3d/entity/Entities;";
    private static final String GET_ENABLED_METHOD = "getEnabled";
    private static final String BOOLEAN_GETTER_DESCRIPTOR = "()Z";
    private static final String GET_IGNORE_GROUP_METHOD = "getIgnoreGroupRenderer";
    private static final String GET_RENDERER_METHOD = "getRenderer";
    private static final String GET_RENDERER_DESCRIPTOR = "()Lcom/live2d/graphics3d/component/AGRenderer;";
    private static final String GET_LIST_METHOD = "getList";
    private static final String GET_LIST_DESCRIPTOR = "()Ljava/util/List;";
    private static final String GET_SORTING_INFO_METHOD = "getSortingInfo";
    private static final String GET_SORTING_INFO_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/GCamera;)Lcom/live2d/graphics3d/component/a/b;";

    /** Validates non-null metadata fields. */
    public RenderPassTraversalTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(renderSystemOwner, "renderSystemOwner");
        Objects.requireNonNull(renderImplDescriptor, "renderImplDescriptor");
        Objects.requireNonNull(shaderHelperOwner, "shaderHelperOwner");
        if (expectedSites <= 0) {
            throw new IllegalArgumentException("expectedSites must be positive: " + expectedSites);
        }
    }

    /**
     * Resolves the reviewed render-pass target for an admitted Cubism artifact digest.
     *
     * @param digest host JAR digest
     * @return reviewed target metadata, or empty when unreviewed
     */
    public static Optional<RenderPassTraversalTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .filter(SUPPORTED_VERSIONS::contains)
                .map(version -> targetForVersion(digest, version));
    }

    /**
     * Returns every reviewed target across all admitted Cubism releases in chronological order.
     *
     * @return immutable list of reviewed targets
     */
    public static List<RenderPassTraversalTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    /**
     * Returns the exact descriptor of the shader error helper this generation declares.
     *
     * @return the reviewed {@code a(Shader,String,boolean,int,Object) int} descriptor
     */
    public String shaderDefaultDescriptor() {
        return "(L" + shaderHelperOwner + SHADER_DEFAULT_DESCRIPTOR_TAIL;
    }

    /**
     * Returns host methods required by the rewritten render-pass bytecode.
     *
     * @return immutable dependency list verified before arming the transformer
     */
    public List<Dep> dependencies() {
        final List<Dep> deps = new ArrayList<>();
        deps.add(new Dep(ENTITY_OWNER, GET_CHILDREN_METHOD, GET_CHILDREN_DESCRIPTOR));
        deps.add(new Dep(ENTITY_OWNER, GET_ENABLED_METHOD, BOOLEAN_GETTER_DESCRIPTOR));
        deps.add(new Dep(ENTITY_OWNER, GET_RENDERER_METHOD, GET_RENDERER_DESCRIPTOR));
        deps.add(new Dep(ENTITIES_OWNER, GET_LIST_METHOD, GET_LIST_DESCRIPTOR));
        deps.add(new Dep(RENDERER_OWNER, GET_ENABLED_METHOD, BOOLEAN_GETTER_DESCRIPTOR));
        deps.add(new Dep(RENDERER_OWNER, GET_SORTING_INFO_METHOD, GET_SORTING_INFO_DESCRIPTOR));
        if (hasRecursiveTraverseMethod) {
            deps.add(new Dep(ENTITY_OWNER, GET_IGNORE_GROUP_METHOD, BOOLEAN_GETTER_DESCRIPTOR));
        }
        return List.copyOf(deps);
    }

    private static RenderPassTraversalTarget targetForVersion(final HostArtifactDigest digest, final String version) {
        if (version.startsWith(VERSION_PREFIX_53)) {
            return new RenderPassTraversalTarget(
                    digest,
                    version,
                    RENDER_SYSTEM_53,
                    RENDER_IMPL_DESCRIPTOR_53,
                    SHADER_HELPER_53,
                    true,
                    EXPECTED_SITES_53);
        }
        final String shaderHelper = version.startsWith(VERSION_PREFIX_52) ? SHADER_HELPER_52 : SHADER_HELPER_50;
        return new RenderPassTraversalTarget(
                digest, version, RENDER_SYSTEM_50, RENDER_IMPL_DESCRIPTOR_50, shaderHelper, false, EXPECTED_SITES_50);
    }

    /** Identifies a reviewed host member required by the rewritten bytecode. */
    public record Dep(String owner, String name, String descriptor) {
        /** Validates non-null dependency coordinates. */
        public Dep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}

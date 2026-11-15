package dev.turboism.adapter.cubism.optimization.extinterpsibling;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Defines reviewed host classes, methods, and descriptors for sibling extended-interpolation
 * update elision.
 *
 * <p>On all reviewed Cubism 5.0.00–5.3.04 builds, {@code extendedInterpolation.l.a} locates the
 * parent {@code CPartSource} of an edited object by scanning {@code getPartSourceSet()}, linearly
 * scans {@code getAllObjects()} for every sibling GUID, and invokes
 * {@code updateInterpolatedForms(true)} on every sibling extension. Admitted hosts resolve the
 * parent and siblings in constant time and pass {@code force = (sibling == target)} so unmodified
 * siblings use the host's own dirty-version check.</p>
 */
public record ExtendedInterpolationSiblingTarget(HostArtifactDigest digest, String version) {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.extendedInterpolationSiblingElision";

    /** Reviewed Cubism versions sharing the sibling extended-interpolation bytecode shape. */
    public static final Set<String> SUPPORTED_VERSIONS = ReviewedVersions.since("5.0.00");

    /** Internal name of the extended-interpolation coordinator class. */
    public static final String OWNER = "com/live2d/cubism/doc/model/interpolator/extendedInterpolation/l";

    /** Private sibling-update method name on {@code extendedInterpolation.l}. */
    public static final String TARGET_METHOD = "a";

    /** Descriptor of {@code extendedInterpolation.l.a(ACParameterControllableSource)}. */
    public static final String TARGET_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/ACParameterControllableSource;)V";

    /** Internal name of {@code ACParameterControllableSource}. */
    public static final String CONTROLLABLE_SOURCE_OWNER = "com/live2d/cubism/doc/model/ACParameterControllableSource";

    /** Internal name of {@code CModelSource}. */
    public static final String MODEL_SOURCE_OWNER = "com/live2d/cubism/doc/model/CModelSource";

    /** Internal name of {@code CPartSource}. */
    public static final String PART_SOURCE_OWNER = "com/live2d/cubism/doc/model/parts/CPartSource";

    /** Internal name of {@code CExtendedInterpolationExtension}. */
    public static final String EXTENSION_OWNER =
            "com/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension";

    /** Internal name of {@code Guid}. */
    public static final String GUID_OWNER = "com/live2d/type/Guid";

    /** Accessor name for the parent part GUID on {@code ACParameterControllableSource}. */
    public static final String GET_PARENT_GUID_METHOD = "getParentGuid";

    /** Descriptor of {@code getParentGuid()}. */
    public static final String GET_PARENT_GUID_DESCRIPTOR = "()Lcom/live2d/type/CPartGuid;";

    /** Accessor name for the owning {@code CModelSource}. */
    public static final String GET_MODEL_SOURCE_METHOD = "getModelSource";

    /** Descriptor of {@code getModelSource()}. */
    public static final String GET_MODEL_SOURCE_DESCRIPTOR = "()Lcom/live2d/cubism/doc/model/CModelSource;";

    /** Accessor name for object extensions on {@code ACParameterControllableSource}. */
    public static final String GET_EXTENSIONS_METHOD = "getExtensions";

    /** Zero-argument {@code List} getter descriptor. */
    public static final String LIST_GETTER_DESCRIPTOR = "()Ljava/util/List;";

    /** Constant-time part lookup method name on {@code CModelSource}. */
    public static final String GET_PART_SOURCE_METHOD = "getPartSource";

    /** Descriptor of {@code CModelSource.getPartSource(CPartGuid)}. */
    public static final String GET_PART_SOURCE_DESCRIPTOR =
            "(Lcom/live2d/type/CPartGuid;)Lcom/live2d/cubism/doc/model/parts/CPartSource;";

    /** Linear part-set getter name anchored on unmodified host bytecode. */
    public static final String GET_PART_SOURCE_SET_METHOD = "getPartSourceSet";

    /** Descriptor of {@code CModelSource.getPartSourceSet()}. */
    public static final String GET_PART_SOURCE_SET_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/parts/CPartSourceSet;";

    /** Linear all-objects getter name anchored on unmodified host bytecode. */
    public static final String GET_ALL_OBJECTS_METHOD = "getAllObjects";

    /** Constant-time object lookup method name on {@code CModelSource}. */
    public static final String GET_OBJECT_METHOD = "getObject";

    /** Descriptor of {@code CModelSource.getObject(Guid, boolean)}. */
    public static final String GET_OBJECT_DESCRIPTOR =
            "(Lcom/live2d/type/Guid;Z)Lcom/live2d/cubism/doc/model/ACParameterControllableSource;";

    /** Child GUID list accessor name on {@code CPartSource}. */
    public static final String GET_CHILD_GUIDS_METHOD = "getChildGuids";

    /** Extended-interpolation enabled flag accessor name. */
    public static final String IS_ENABLED_METHOD = "isExtendedInterpolationEnabled";

    /** Zero-argument boolean getter descriptor. */
    public static final String BOOLEAN_GETTER_DESCRIPTOR = "()Z";

    /** Extended-interpolation form refresh method name. */
    public static final String UPDATE_FORMS_METHOD = "updateInterpolatedForms";

    /** Descriptor of {@code CExtendedInterpolationExtension.updateInterpolatedForms(boolean)}. */
    public static final String UPDATE_FORMS_DESCRIPTOR = "(Z)Z";

    /** Validates non-null target coordinates. */
    public ExtendedInterpolationSiblingTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /**
     * Resolves the reviewed target for an admitted host artifact digest.
     *
     * @param digest host JAR digest
     * @return reviewed target metadata, or empty when unreviewed
     */
    public static Optional<ExtendedInterpolationSiblingTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .filter(SUPPORTED_VERSIONS::contains)
                .map(version -> new ExtendedInterpolationSiblingTarget(digest, version));
    }

    /**
     * Returns every reviewed target across all admitted Cubism releases in chronological order.
     *
     * @return immutable list of reviewed targets
     */
    public static List<ExtendedInterpolationSiblingTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    /**
     * Returns host methods required by the rewritten sibling-update bytecode.
     *
     * @return immutable dependency list verified before arming the transformer
     */
    public List<MethodDep> methodDependencies() {
        return List.of(
                new MethodDep(OWNER, TARGET_METHOD, TARGET_DESCRIPTOR),
                new MethodDep(CONTROLLABLE_SOURCE_OWNER, GET_PARENT_GUID_METHOD, GET_PARENT_GUID_DESCRIPTOR),
                new MethodDep(CONTROLLABLE_SOURCE_OWNER, GET_MODEL_SOURCE_METHOD, GET_MODEL_SOURCE_DESCRIPTOR),
                new MethodDep(CONTROLLABLE_SOURCE_OWNER, GET_EXTENSIONS_METHOD, LIST_GETTER_DESCRIPTOR),
                new MethodDep(MODEL_SOURCE_OWNER, GET_PART_SOURCE_METHOD, GET_PART_SOURCE_DESCRIPTOR),
                new MethodDep(MODEL_SOURCE_OWNER, GET_OBJECT_METHOD, GET_OBJECT_DESCRIPTOR),
                new MethodDep(PART_SOURCE_OWNER, GET_CHILD_GUIDS_METHOD, LIST_GETTER_DESCRIPTOR),
                new MethodDep(EXTENSION_OWNER, IS_ENABLED_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(EXTENSION_OWNER, UPDATE_FORMS_METHOD, UPDATE_FORMS_DESCRIPTOR));
    }

    /** Identifies one reviewed method dependency verified before retransformation. */
    public record MethodDep(String owner, String name, String descriptor) {
        /** Validates non-null dependency coordinates. */
        public MethodDep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}

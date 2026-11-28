package dev.turboism.adapter.cubism.optimization.physics;

import dev.turboism.adapter.cubism.optimization.TargetClassTable;
import dev.turboism.adapter.cubism.optimization.drag.EditDragResponsivenessTarget;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines reviewed host classes, methods, and descriptors for physics simulation acceleration.
 *
 * <p>Replaces boxed {@code kotlin.ranges.IntRange} iteration, {@code CArrayList} iterator objects,
 * linear parameter scans, and temporary {@code GVector2} allocations inside pendulum evaluation
 * loops while preserving exact IEEE-754 float order.</p>
 */
public record PhysicsAccelerationTarget(
        HostArtifactDigest digest,
        String version,
        String evaluatorOwner,
        String mathUtilsOwner,
        List<String> targetClasses) {

    /** Shared startup toggle property gating edit-mode acceleration hooks. */
    public static final String ENABLE_PROPERTY = EditDragResponsivenessTarget.ENABLE_PROPERTY;

    /** Explicit validation override property for isolated physics-acceleration tests. */
    public static final String VALIDATION_PROPERTY = "turboism.validation.physicsAcceleration";

    /** Number of rewritten class definitions required for admission. */
    public static final int EXPECTED_MATCHES = 1;

    /** Number of transformed class owners for restoration verification. */
    public static final int EXPECTED_OWNERS = 1;

    /** Number of rewritten methods inside the admitted evaluator class. */
    public static final int EXPECTED_TOTAL_SITES = 4;

    /** Target-class table base resource name. */
    public static final String TARGET_CLASSES_RESOURCE = "physics-acceleration";

    /** Legacy evaluator class internal name. */
    public static final String LEGACY_EVALUATOR_OWNER = "com/live2d/cubism/doc/gameData/physics/d";

    /** Current evaluator class internal name. */
    public static final String CURRENT_EVALUATOR_OWNER = "com/live2d/cubism/doc/gameData/physics/b";

    /** Legacy math utilities class internal name. */
    public static final String LEGACY_MATH_OWNER = "com/live2d/util/K";

    /** Current math utilities class internal name. */
    public static final String CURRENT_MATH_OWNER = "com/live2d/util/L";

    /** Internal name of {@code CPhysicsSettingsSource}. */
    public static final String SETTINGS_SOURCE_OWNER = "com/live2d/cubism/doc/gameData/physics/CPhysicsSettingsSource";

    /** Internal name of {@code CPhysicsVertex}. */
    public static final String VERTEX_OWNER = "com/live2d/cubism/doc/gameData/physics/CPhysicsVertex";

    /** Internal name of {@code CPhysicsInput}. */
    public static final String INPUT_OWNER = "com/live2d/cubism/doc/gameData/physics/CPhysicsInput";

    /** Internal name of {@code CPhysicsOutput}. */
    public static final String OUTPUT_OWNER = "com/live2d/cubism/doc/gameData/physics/CPhysicsOutput";

    /** Internal name of {@code CPhysicsController$a}. */
    public static final String CONTROLLER_COMPANION_OWNER =
            "com/live2d/cubism/doc/gameData/physics/CPhysicsController$a";

    /** Internal name of {@code CPhysicsController$CPhysicsSourceType}. */
    public static final String SOURCE_TYPE_OWNER =
            "com/live2d/cubism/doc/gameData/physics/CPhysicsController$CPhysicsSourceType";

    /** Internal name of {@code CParameterSet}. */
    public static final String PARAMETER_SET_OWNER = "com/live2d/cubism/doc/model/param/CParameterSet";

    /** Internal name of {@code CParameter}. */
    public static final String PARAMETER_OWNER = "com/live2d/cubism/doc/model/param/CParameter";

    /** Internal name of {@code CParameterId}. */
    public static final String PARAMETER_ID_OWNER = "com/live2d/cubism/doc/model/id/CParameterId";

    /** Internal name of the physics viewer mask helper. */
    public static final String VIEWER_MASK_OWNER = "com/live2d/cubism/doc/modeling/ui/viewer/c";

    /** Internal name of {@code GVector2}. */
    public static final String VECTOR2_OWNER = "com/live2d/graphics3d/type/GVector2";

    /** Internal name of {@code CArrayList}. */
    public static final String ARRAY_LIST_OWNER = "com/live2d/type/CArrayList";

    /** Internal name of {@code CParameterGuid}. */
    public static final String PARAM_GUID_OWNER = "com/live2d/type/CParameterGuid";

    /** Internal name of {@code CPhysicsDataGuid}. */
    public static final String PHYSICS_DATA_GUID_OWNER = "com/live2d/type/CPhysicsDataGuid";

    /** Hot evaluator method name shared across all four rewritten overloads. */
    public static final String TARGET_METHOD_NAME = "a";

    /** Descriptor of {@code evaluateSource} updating inputs, vertices, and outputs. */
    public static final String EVALUATE_SOURCE_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/gameData/physics/CPhysicsSettingsSource;"
                    + "Lcom/live2d/cubism/doc/model/param/CParameterSet;"
                    + "Lcom/live2d/graphics3d/type/GVector2;"
                    + "Lcom/live2d/graphics3d/type/GVector2;"
                    + "FLkotlin/jvm/functions/Function1;"
                    + "ZLjava/util/HashMap;"
                    + "Lcom/live2d/cubism/doc/modeling/ui/viewer/c;"
                    + "Ljava/util/ArrayList;)V";

    /** Descriptor of {@code stepVertices} advancing pendulum chains by {@code deltaTime}. */
    public static final String STEP_VERTICES_DESCRIPTOR =
            "(Lcom/live2d/type/CArrayList;Lcom/live2d/graphics3d/type/GVector2;F"
                    + "Lcom/live2d/graphics3d/type/GVector2;Lcom/live2d/graphics3d/type/GVector2;FF)V";

    /** Descriptor of {@code resetVertices} initializing pendulum rest state. */
    public static final String RESET_VERTICES_DESCRIPTOR =
            "(Lcom/live2d/type/CArrayList;Lcom/live2d/graphics3d/type/GVector2;)V";

    /** Descriptor of {@code findParameter} locating a parameter by GUID. */
    public static final String FIND_PARAMETER_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/model/param/CParameterSet;Lcom/live2d/type/CParameterGuid;)"
                    + "Lcom/live2d/cubism/doc/model/param/CParameter;";

    /** Descriptor of {@code applyInput} normalizing one input parameter. */
    public static final String APPLY_INPUT_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/param/CParameter;FFFZ)F";

    /** Descriptor of {@code applyOutput} clamping and assigning one output parameter. */
    public static final String APPLY_OUTPUT_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/model/param/CParameter;FLcom/live2d/cubism/doc/gameData/physics/CPhysicsOutput;Z)Z";

    private static final Map<String, List<String>> TARGET_TABLE = TargetClassTable.load(TARGET_CLASSES_RESOURCE);

    /**
     * Resolves the reviewed physics-acceleration target contract for {@code digest}.
     *
     * @param digest host artifact SHA-256 digest
     * @return resolved target contract, or empty when the artifact is unreviewed or omits required entries
     */
    public static Optional<PhysicsAccelerationTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        final Optional<String> version = ReviewedHostArtifacts.cubismVersionOf(digest);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        final List<String> classes = TARGET_TABLE.get(version.get());
        if (classes == null) {
            return Optional.empty();
        }
        final String evaluator = classes.contains(LEGACY_EVALUATOR_OWNER)
                ? LEGACY_EVALUATOR_OWNER
                : classes.contains(CURRENT_EVALUATOR_OWNER) ? CURRENT_EVALUATOR_OWNER : null;
        final String mathUtils = classes.contains(LEGACY_MATH_OWNER)
                ? LEGACY_MATH_OWNER
                : classes.contains(CURRENT_MATH_OWNER) ? CURRENT_MATH_OWNER : null;
        if (evaluator == null || mathUtils == null) {
            return Optional.empty();
        }
        return Optional.of(new PhysicsAccelerationTarget(digest, version.get(), evaluator, mathUtils, classes));
    }

    /**
     * Returns all target class names mapped for a reviewed Cubism version.
     *
     * @param version dotted Cubism release string
     * @return immutable list of reviewed class internal names
     */
    public static List<String> targetClasses(final String version) {
        return TargetClassTable.classes(TARGET_CLASSES_RESOURCE, version);
    }

    /**
     * Returns the full version-keyed target-class table map.
     *
     * @return immutable map from dotted version to target-class list
     */
    public static Map<String, List<String>> targetClassesByVersion() {
        return TARGET_TABLE;
    }
}

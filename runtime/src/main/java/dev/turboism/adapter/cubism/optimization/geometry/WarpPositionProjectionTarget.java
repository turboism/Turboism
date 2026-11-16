package dev.turboism.adapter.cubism.optimization.geometry;

import java.util.List;

/**
 * Reviewed host members the warp position-projection weave and its bridge consult.
 *
 * <p>The weave rewrites one consumer method, and its bridge reads the deformer form, the point
 * reference and the geometry helper that the rewritten body would have called. Every member below
 * is proven present before the weave is armed, so a host that renamed one fails closed instead of
 * installing a bridge that cannot run.</p>
 */
public final class WarpPositionProjectionTarget {

    /** Deformer form the rewritten consumer reads its positions from. */
    public static final String FORM_OWNER = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerForm";

    /** Geometry vector the projection writes into. */
    public static final String VECTOR_OWNER = "com/live2d/graphics3d/type/GVector2";

    /** Point reference the projection reads its source position from. */
    public static final String REFS_OWNER = "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef";

    /** Geometry helper whose interpolation the projection replaces. */
    public static final String HELPER_OWNER = "com/live2d/graphics3d/type/f";

    /** Form accessor returning the reviewed positions array. */
    public static final String GET_POSITIONS = "getPositions";

    /** {@code getPositions() float[]}. */
    public static final String GET_POSITIONS_DESCRIPTOR = "()[F";

    /** Form accessor returning every reviewed point reference. */
    public static final String GET_ALL_POINT_REF = "getAllPointRef";

    /** {@code getAllPointRef() List}. */
    public static final String GET_ALL_POINT_REF_DESCRIPTOR = "()Ljava/util/List;";

    /** Point-reference accessor returning its source position. */
    public static final String GET_POS = "getPos";

    /** {@code getPos() GVector2}. */
    public static final String GET_POS_DESCRIPTOR = "()Lcom/live2d/graphics3d/type/GVector2;";

    /** Vector X setter. */
    public static final String SET_X = "setX";

    /** Vector Y setter. */
    public static final String SET_Y = "setY";

    /** {@code setX(float) void} and {@code setY(float) void}. */
    public static final String SET_DESCRIPTOR = "(F)V";

    /** Geometry helper interpolation method name. */
    public static final String INTERPOLATE = "a";

    /** {@code a(float[],int,int,GVector2,int,Object) GVector2}. */
    public static final String INTERPOLATE_DESCRIPTOR =
            "([FIILcom/live2d/graphics3d/type/GVector2;ILjava/lang/Object;)Lcom/live2d/graphics3d/type/GVector2;";

    /** {@code a(float[],int,int,GVector2) GVector2}, the overload older generations declare. */
    public static final String INTERPOLATE_LEGACY_DESCRIPTOR =
            "([FIILcom/live2d/graphics3d/type/GVector2;)Lcom/live2d/graphics3d/type/GVector2;";

    /** Vector constructor name. */
    public static final String VECTOR_INIT = "<init>";

    /** {@code GVector2(float,float) void}. */
    public static final String VECTOR_INIT_DESCRIPTOR = "(FF)V";

    /** {@code GVector2() void}. */
    public static final String VECTOR_INIT_EMPTY_DESCRIPTOR = "()V";

    /** Point-reference index accessor name. */
    public static final String REF_READ = "a";

    /** {@code a() int}. */
    public static final String REF_READ_INDEX_DESCRIPTOR = "()I";

    private WarpPositionProjectionTarget() {}

    /**
     * The reviewed members the bridge reads, in resolution order.
     *
     * @return the reviewed member list
     */
    public static List<Member> bridgeMembers() {
        return List.of(
                new Member(FORM_OWNER, GET_POSITIONS, GET_POSITIONS_DESCRIPTOR),
                new Member(FORM_OWNER, GET_ALL_POINT_REF, GET_ALL_POINT_REF_DESCRIPTOR),
                new Member(REFS_OWNER, GET_POS, GET_POS_DESCRIPTOR),
                new Member(VECTOR_OWNER, SET_X, SET_DESCRIPTOR),
                new Member(VECTOR_OWNER, SET_Y, SET_DESCRIPTOR),
                new Member(HELPER_OWNER, INTERPOLATE, INTERPOLATE_DESCRIPTOR),
                new Member(HELPER_OWNER, INTERPOLATE, INTERPOLATE_LEGACY_DESCRIPTOR),
                new Member(VECTOR_OWNER, VECTOR_INIT, VECTOR_INIT_DESCRIPTOR),
                new Member(VECTOR_OWNER, VECTOR_INIT, VECTOR_INIT_EMPTY_DESCRIPTOR),
                new Member(REFS_OWNER, REF_READ, REF_READ_INDEX_DESCRIPTOR));
    }

    /**
     * One reviewed host member of the warp projection bridge.
     *
     * @param owner internal name of the declaring class
     * @param name the exact member name
     * @param descriptor the exact JVM descriptor
     */
    public record Member(String owner, String name, String descriptor) {}
}

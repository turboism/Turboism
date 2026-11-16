package dev.turboism.adapter.cubism.optimization.meshsetup;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.objectweb.asm.Opcodes;

/**
 * Reviewed host seam for the skipped-frame {@code GMesh2.setup} elision.
 *
 * <p>{@code GMesh2.setup(GFrame)} is called by {@code GMeshRenderer.render} for
 * every visible mesh on every paint. Its whole body is nine persistent-VBO
 * refresh calls: positions, normals, uvs, uvs2, uvs3, colors32, colors, alpha
 * and indices. The reviewed body has no other side effect. When the native
 * model update was skipped, the mesh arrays and their uploaded buffers are
 * still current, so the nine refreshes are redundant.</p>
 *
 * <p>The transformer only rewrites the exact reviewed method shape read from
 * the running official artifact. {@link #hasReviewedSetupShape(List)} admits a
 * body by inspecting its instruction tokens, so an added side effect or a
 * renamed accessor fails admission instead of running behind the elision. The
 * body was identical across all twenty reviewed 5.0.00 to 5.3.04 artifacts.</p>
 */
public record SkippedFrameMeshSetupElisionTarget(HostArtifactDigest digest, String version) {

    /** The final mesh class whose setup method is rewritten. */
    public static final String OWNER = "com/live2d/graphics3d/mesh/GMesh2";

    /** Reviewed setup method name. */
    public static final String METHOD = "setup";

    /** {@code setup(GFrame) void}. */
    public static final String DESCRIPTOR = "(Lcom/live2d/graphics3d/a;)V";

    /** Persistent buffer base class owning the protected name/dirty accessors. */
    public static final String BASE_OWNER = "com/live2d/graphics3d/mesh/a/a";

    /** Reviewed frame owner exposing {@code a()GL3}. */
    public static final String FRAME_OWNER = "com/live2d/graphics3d/a";

    /** Reviewed frame accessor used for GL-context validation. */
    public static final String FRAME_METHOD = "a";

    /** {@code a()GL3}. */
    public static final String FRAME_DESCRIPTOR = "()Lcom/jogamp/opengl/GL3;";

    /** Protected buffer-name accessor {@code i()IntBuffer}. */
    public static final String NAME_METHOD = "i";

    /** {@code i()IntBuffer}. */
    public static final String NAME_DESCRIPTOR = "()Ljava/nio/IntBuffer;";

    /** Protected dirty-flag accessor {@code k()Z}. */
    public static final String DIRTY_METHOD = "k";

    /** {@code k()Z}. */
    public static final String DIRTY_DESCRIPTOR = "()Z";

    /** Kotlin null-check owner called once on entry; it reads no state and has no side effect. */
    private static final String INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";

    /** Kotlin null-check method name. */
    private static final String INTRINSICS_METHOD = "checkNotNullParameter";

    /** {@code checkNotNullParameter(Object,String)void}. */
    private static final String INTRINSICS_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;)V";

    /** Persistent float-attribute wrapper declaring {@code a(float[])void}. */
    private static final String FLOAT_BUFFER_OWNER = "com/live2d/graphics3d/mesh/a/b";

    /** Persistent integer wrapper declaring {@code a(int[])void}. */
    private static final String INDEX_BUFFER_OWNER = "com/live2d/graphics3d/mesh/a/c";

    /** Reviewed upload method shared by both persistent buffer wrappers. */
    private static final String REFRESH_METHOD = "a";

    /** {@code a(float[])void}. */
    private static final String FLOAT_REFRESH_DESCRIPTOR = "([F)V";

    /** {@code a(int[])void}. */
    private static final String INDEX_REFRESH_DESCRIPTOR = "([I)V";

    /** {@code getPositionsBuffer()Lcom/live2d/graphics3d/mesh/a/b;}. */
    private static final String FLOAT_GETTER_DESCRIPTOR = "()L" + FLOAT_BUFFER_OWNER + ";";

    /** {@code getColors32Buffer()Lcom/live2d/graphics3d/mesh/a/c;}. */
    private static final String INDEX_GETTER_DESCRIPTOR = "()L" + INDEX_BUFFER_OWNER + ";";

    /** Reviewed access token: the setup method is {@code public} and carries no other flag. */
    private static final String REVIEWED_ACCESS_TOKEN = "access:" + Opcodes.ACC_PUBLIC;

    /** Reviewed terminal token: the body ends in its single {@code RETURN}. */
    private static final String RETURN_TOKEN = "op:" + Opcodes.RETURN;

    /** Call token prefix; the remainder is opcode, owner, name, descriptor and interface flag. */
    private static final String CALL_PREFIX = "call:";

    /** Operand prefixes the reviewed body may contain: a local load or a constant load. */
    private static final List<String> OPERAND_PREFIXES = List.of("var:", "ldc:");

    /** Field separator of a call token; the Base64 alphabet contains no colon. */
    private static final String TOKEN_SEPARATOR = ":";

    /** Field count of a call token: prefix, opcode, owner, name, descriptor and interface flag. */
    private static final int CALL_TOKEN_FIELDS = 6;

    /** Index of the opcode inside a split call token. */
    private static final int CALL_OPCODE_INDEX = 1;

    /** Index of the owner inside a split call token. */
    private static final int CALL_OWNER_INDEX = 2;

    /** Index of the method name inside a split call token. */
    private static final int CALL_METHOD_INDEX = 3;

    /** Index of the descriptor inside a split call token. */
    private static final int CALL_DESCRIPTOR_INDEX = 4;

    /** Index of the interface flag inside a split call token. */
    private static final int CALL_INTERFACE_INDEX = 5;

    /** Access token, at least one body token and the terminal return token. */
    private static final int MINIMUM_SHAPE_TOKENS = 3;

    /**
     * One reviewed refresh: read the buffer accessor, read the mesh array, then upload that array.
     *
     * @param getter the {@code GMesh2} buffer accessor
     * @param getterDescriptor the return descriptor of {@code getter}
     * @param values the {@code GMesh2} array accessor
     * @param valuesDescriptor the return descriptor of {@code values}
     * @param bufferOwner the persistent buffer owner declaring the upload
     * @param refreshDescriptor the upload descriptor
     */
    public record Refresh(
            String getter,
            String getterDescriptor,
            String values,
            String valuesDescriptor,
            String bufferOwner,
            String refreshDescriptor) {

        public Refresh {
            Objects.requireNonNull(getter, "getter");
            Objects.requireNonNull(getterDescriptor, "getterDescriptor");
            Objects.requireNonNull(values, "values");
            Objects.requireNonNull(valuesDescriptor, "valuesDescriptor");
            Objects.requireNonNull(bufferOwner, "bufferOwner");
            Objects.requireNonNull(refreshDescriptor, "refreshDescriptor");
        }
    }

    /** Every reviewed refresh, in setup visit order. */
    private static final List<Refresh> REFRESHES = List.of(
            new Refresh(
                    "getPositionsBuffer",
                    FLOAT_GETTER_DESCRIPTOR,
                    "getPositions",
                    "()[F",
                    FLOAT_BUFFER_OWNER,
                    FLOAT_REFRESH_DESCRIPTOR),
            new Refresh(
                    "getNormalsBuffer",
                    FLOAT_GETTER_DESCRIPTOR,
                    "getNormals",
                    "()[F",
                    FLOAT_BUFFER_OWNER,
                    FLOAT_REFRESH_DESCRIPTOR),
            new Refresh(
                    "getUvsBuffer",
                    FLOAT_GETTER_DESCRIPTOR,
                    "getUvs",
                    "()[F",
                    FLOAT_BUFFER_OWNER,
                    FLOAT_REFRESH_DESCRIPTOR),
            new Refresh(
                    "getUvs2Buffer",
                    FLOAT_GETTER_DESCRIPTOR,
                    "getUvs2",
                    "()[F",
                    FLOAT_BUFFER_OWNER,
                    FLOAT_REFRESH_DESCRIPTOR),
            new Refresh(
                    "getUvs3Buffer",
                    FLOAT_GETTER_DESCRIPTOR,
                    "getUvs3",
                    "()[F",
                    FLOAT_BUFFER_OWNER,
                    FLOAT_REFRESH_DESCRIPTOR),
            new Refresh(
                    "getColors32Buffer",
                    INDEX_GETTER_DESCRIPTOR,
                    "getColors32",
                    "()[I",
                    INDEX_BUFFER_OWNER,
                    INDEX_REFRESH_DESCRIPTOR),
            new Refresh(
                    "getColorsBuffer",
                    FLOAT_GETTER_DESCRIPTOR,
                    "getColors",
                    "()[F",
                    FLOAT_BUFFER_OWNER,
                    FLOAT_REFRESH_DESCRIPTOR),
            new Refresh(
                    "getAlphaBuffer",
                    FLOAT_GETTER_DESCRIPTOR,
                    "getAlphas",
                    "()[F",
                    FLOAT_BUFFER_OWNER,
                    FLOAT_REFRESH_DESCRIPTOR),
            new Refresh(
                    "getIndicesBuffer",
                    INDEX_GETTER_DESCRIPTOR,
                    "getIndices",
                    "()[I",
                    INDEX_BUFFER_OWNER,
                    INDEX_REFRESH_DESCRIPTOR));

    /** Every buffer getter called by the reviewed setup body, in visit order. */
    public static final List<String> BUFFER_GETTERS =
            REFRESHES.stream().map(Refresh::getter).toList();

    /**
     * Returns every reviewed refresh in setup visit order.
     *
     * <p>Callers that must reproduce the reviewed body, such as admission tests, read this table
     * instead of restating the accessor names and descriptors.</p>
     *
     * @return the immutable reviewed refresh table
     */
    public static List<Refresh> refreshes() {
        return REFRESHES;
    }

    /** The exact reviewed call sequence; the setup body must contain nothing else. */
    private static final List<Call> REVIEWED_CALLS = reviewedCalls();

    /** Every reviewed target, oldest supported version first. */
    private static final List<SkippedFrameMeshSetupElisionTarget> TARGETS = ReviewedHostArtifacts.all().stream()
            .flatMap(
                    digest -> ReviewedHostArtifacts.cubismVersionOf(digest)
                            .map(version -> new SkippedFrameMeshSetupElisionTarget(digest, version))
                            .stream())
            .toList();

    public SkippedFrameMeshSetupElisionTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /**
     * Returns whether the observed setup body is the reviewed, version-compatible shape.
     *
     * <p>The gate inspects the instruction tokens directly. The body must be public, end in its
     * single {@code RETURN}, load only locals and constants, and call exactly the reviewed
     * accessors: the null check, the frame accessor and nine persistent-buffer refreshes in visit
     * order. Any other call, field access or branch fails admission, so an unreviewed side effect
     * never runs behind the elision.</p>
     *
     * @param shape instruction tokens of the observed method, or {@code null} when it is absent
     * @return whether the body is the reviewed shape
     */
    public static boolean hasReviewedSetupShape(final List<String> shape) {
        if (shape == null || shape.size() < MINIMUM_SHAPE_TOKENS) {
            return false;
        }
        if (!REVIEWED_ACCESS_TOKEN.equals(shape.get(0)) || !RETURN_TOKEN.equals(shape.get(shape.size() - 1))) {
            return false;
        }
        final List<Call> calls = new ArrayList<>();
        for (final String token : shape.subList(1, shape.size() - 1)) {
            if (token.startsWith(CALL_PREFIX)) {
                final Call call = Call.parse(token);
                if (call == null) {
                    return false;
                }
                calls.add(call);
            } else if (!isOperand(token)) {
                return false;
            }
        }
        return calls.equals(REVIEWED_CALLS);
    }

    /** The reviewed target for a host artifact digest, or empty when unsupported. */
    public static Optional<SkippedFrameMeshSetupElisionTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return TARGETS.stream().filter(target -> target.digest().equals(digest)).findFirst();
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<SkippedFrameMeshSetupElisionTarget> all() {
        return TARGETS;
    }

    /** Returns whether the token is a side-effect-free local or constant load. */
    private static boolean isOperand(final String token) {
        for (final String prefix : OPERAND_PREFIXES) {
            if (token.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Builds the exact reviewed call sequence in visit order. */
    private static List<Call> reviewedCalls() {
        final List<Call> calls = new ArrayList<>();
        calls.add(new Call(Opcodes.INVOKESTATIC, INTRINSICS_OWNER, INTRINSICS_METHOD, INTRINSICS_DESCRIPTOR));
        calls.add(new Call(Opcodes.INVOKEVIRTUAL, FRAME_OWNER, FRAME_METHOD, FRAME_DESCRIPTOR));
        for (final Refresh refresh : REFRESHES) {
            calls.add(new Call(Opcodes.INVOKEVIRTUAL, OWNER, refresh.getter(), refresh.getterDescriptor()));
            calls.add(new Call(Opcodes.INVOKEVIRTUAL, OWNER, refresh.values(), refresh.valuesDescriptor()));
            calls.add(new Call(
                    Opcodes.INVOKEVIRTUAL, refresh.bufferOwner(), REFRESH_METHOD, refresh.refreshDescriptor()));
        }
        return List.copyOf(calls);
    }

    /**
     * One decoded invocation of the observed body.
     *
     * <p>{@code ReviewedMethodShape} renders a call as its opcode, Base64-encoded UTF-8 owner, name
     * and descriptor, then the interface flag. This record restores those operands so the gate
     * compares them as text instead of as opaque tokens.</p>
     */
    private record Call(int opcode, String owner, String name, String descriptor) {

        /** Returns the decoded call, or {@code null} when the token is malformed or an interface call. */
        static Call parse(final String token) {
            final String[] fields = token.split(TOKEN_SEPARATOR, -1);
            if (fields.length != CALL_TOKEN_FIELDS || !Boolean.FALSE.toString().equals(fields[CALL_INTERFACE_INDEX])) {
                return null;
            }
            try {
                return new Call(
                        Integer.parseInt(fields[CALL_OPCODE_INDEX]),
                        decodeText(fields[CALL_OWNER_INDEX]),
                        decodeText(fields[CALL_METHOD_INDEX]),
                        decodeText(fields[CALL_DESCRIPTOR_INDEX]));
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }

        /** Restores one Base64 UTF-8 operand. */
        private static String decodeText(final String encoded) {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
    }
}

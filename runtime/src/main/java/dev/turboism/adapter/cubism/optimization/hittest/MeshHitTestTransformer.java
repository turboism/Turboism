package dev.turboism.adapter.cubism.optimization.hittest;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites {@code com.live2d.graphics3d.mesh.a.contains(GVector2, int[])} to evaluate 2D bounds
 * directly on {@code GMesh2} and {@code GMesh3} vertex arrays without heap allocations.
 */
public final class MeshHitTestTransformer implements ClassFileTransformer {

    /** Number of rewritten injection sites expected on {@code com.live2d.graphics3d.mesh.a}. */
    public static final int EXPECTED_SITES = 1;

    private static final int LOCAL_THIS = 0;
    private static final int LOCAL_POINT = 1;
    private static final int LOCAL_OUT_INDICES = 2;
    private static final int LOCAL_STEP = 3;
    private static final int LOCAL_POSITIONS = 4;
    private static final int LOCAL_LIMIT = 5;
    private static final int LOCAL_MIN_X = 6;
    private static final int LOCAL_MAX_X = 7;
    private static final int LOCAL_MIN_Y = 8;
    private static final int LOCAL_MAX_Y = 9;
    private static final int LOCAL_INDEX = 10;
    private static final int LOCAL_X = 11;
    private static final int LOCAL_Y = 12;
    private static final int LOCAL_MARGIN = 13;

    private static final int STRIDE_2D = 2;
    private static final int STRIDE_3D = 3;
    private static final int EVEN_ALIGNMENT_MASK = -2;
    private static final int Y_COMPONENT_OFFSET = 1;

    private static final float INITIAL_MIN_FLOAT = Float.MAX_VALUE;
    private static final float INITIAL_MAX_FLOAT = -Float.MAX_VALUE;

    private static final String ERROR_LOADER_OR_SOURCE = "unexpected loader or code source for ";
    private static final String ERROR_METHOD_SHAPE = "mesh contains method shape mismatch";
    private static final String ERROR_SITE_COUNT = "mesh contains site count mismatch: ";
    private static final String ERROR_ABSENT_REFERENCE = "mesh contains absent from reference";
    private static final String ERROR_SUBCLASS_SHAPE = "unexpected getBounds contract on ";
    private static final String ERROR_BOUNDS_MARGIN = "unexpected GBounds margin helper contract";

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> containsShape;
    private final float boundsMargin;

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures and verifies reviewed shapes for {@code mesh/a}, {@code GMesh2}, {@code GMesh3},
     * {@code GBounds}, and the bounds-tolerance helper class.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param meshBaseReference reference bytes of {@code com/live2d/graphics3d/mesh/a}
     * @param gmesh2Reference reference bytes of {@code GMesh2}
     * @param gmesh3Reference reference bytes of {@code GMesh3}
     * @param boundsReference reference bytes of {@code GBounds}
     * @param helperReference reference bytes of the utility class supplying the bounds tolerance
     * @param target reviewed target metadata
     */
    public MeshHitTestTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] meshBaseReference,
            final byte[] gmesh2Reference,
            final byte[] gmesh3Reference,
            final byte[] boundsReference,
            final byte[] helperReference,
            final MeshHitTestTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(meshBaseReference, "meshBaseReference");
        Objects.requireNonNull(gmesh2Reference, "gmesh2Reference");
        Objects.requireNonNull(gmesh3Reference, "gmesh3Reference");
        Objects.requireNonNull(boundsReference, "boundsReference");
        Objects.requireNonNull(helperReference, "helperReference");
        Objects.requireNonNull(target, "target");

        containsShape = ReviewedMethodShape.read(
                meshBaseReference,
                MeshHitTestTarget.OWNER,
                MeshHitTestTarget.CONTAINS_METHOD,
                MeshHitTestTarget.CONTAINS_DESCRIPTOR);
        if (containsShape == null) {
            throw new IllegalArgumentException(ERROR_ABSENT_REFERENCE);
        }
        verifySubclassGetBounds(gmesh2Reference, MeshHitTestTarget.GMESH2_OWNER, STRIDE_2D);
        verifySubclassGetBounds(gmesh3Reference, MeshHitTestTarget.GMESH3_OWNER, STRIDE_3D);
        final String helperOwner = resolveBoundsHelperOwner(boundsReference);
        boundsMargin = verifyBoundsMarginConstant(helperReference, helperOwner);
    }

    /**
     * Resolves the internal name of the utility class supplying {@code GBounds} default margin.
     *
     * @param boundsReference reference bytes of {@code GBounds}
     * @return internal class name of the utility helper
     */
    public static String resolveBoundsHelperOwner(final byte[] boundsReference) {
        Objects.requireNonNull(boundsReference, "boundsReference");
        final String[] found = new String[1];
        new ClassReader(boundsReference).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                if (!MeshHitTestTarget.BOUNDS_CONTAINS_DEFAULT_METHOD.equals(name)
                        || !MeshHitTestTarget.BOUNDS_CONTAINS_DEFAULT_DESCRIPTOR.equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String methodName,
                            final String methodDesc,
                            final boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.startsWith(MeshHitTestTarget.UTIL_PACKAGE_PREFIX)
                                && MeshHitTestTarget.TOLERANCE_GETTER_METHOD.equals(methodName)
                                && MeshHitTestTarget.FLOAT_GETTER_DESCRIPTOR.equals(methodDesc)) {
                            found[0] = owner;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (found[0] == null) {
            throw new IllegalArgumentException(ERROR_BOUNDS_MARGIN);
        }
        return found[0];
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten classes. */
    public int matches() {
        return matches;
    }

    /** Returns the number of rewritten hit-test sites. */
    public int sites() {
        return sites;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null || !MeshHitTestTarget.OWNER.equals(name)) {
            return null;
        }
        if (actualLoader != loader || !matchesArtifactSource(domain)) {
            failure = ERROR_LOADER_OR_SOURCE + name;
            return null;
        }
        if (!containsShape.equals(ReviewedMethodShape.read(
                bytes,
                MeshHitTestTarget.OWNER,
                MeshHitTestTarget.CONTAINS_METHOD,
                MeshHitTestTarget.CONTAINS_DESCRIPTOR))) {
            failure = ERROR_METHOD_SHAPE;
            return null;
        }
        try {
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.computeFrames(reader, loader);
            final int[] localSites = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String methodName,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor mv =
                                    super.visitMethod(access, methodName, descriptor, signature, exceptions);
                            if (!MeshHitTestTarget.CONTAINS_METHOD.equals(methodName)
                                    || !MeshHitTestTarget.CONTAINS_DESCRIPTOR.equals(descriptor)) {
                                return mv;
                            }
                            return wrapContains(mv, boundsMargin, localSites);
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (localSites[0] != EXPECTED_SITES) {
                failure = ERROR_SITE_COUNT + localSites[0];
                return null;
            }
            final byte[] output = writer.toByteArray();
            matches++;
            sites += localSites[0];
            return output;
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private boolean matchesArtifactSource(final ProtectionDomain domain) {
        if (domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null) {
            return false;
        }
        try {
            return Path.of(domain.getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize()
                    .equals(artifact);
        } catch (final Exception ignored) {
            return false;
        }
    }

    private static void verifySubclassGetBounds(
            final byte[] classBytes, final String owner, final int expectedStride) {
        final boolean[] sawGetPositions = {false};
        final boolean[] sawBoundsInit = {false};
        final int[] capturedStride = {-1};
        final int[] extraCalls = {0};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                if (!MeshHitTestTarget.GET_BOUNDS_METHOD.equals(name)
                        || !MeshHitTestTarget.GET_BOUNDS_DESCRIPTOR.equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private int lastIntConst = -1;

                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.ICONST_2) {
                            lastIntConst = STRIDE_2D;
                        } else if (opcode == Opcodes.ICONST_3) {
                            lastIntConst = STRIDE_3D;
                        } else {
                            lastIntConst = -1;
                        }
                    }

                    @Override
                    public void visitMethodInsn(
                            final int opcode,
                            final String callOwner,
                            final String callName,
                            final String callDesc,
                            final boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(callOwner)
                                && MeshHitTestTarget.GET_POSITIONS_METHOD.equals(callName)
                                && MeshHitTestTarget.GET_POSITIONS_DESCRIPTOR.equals(callDesc)) {
                            sawGetPositions[0] = true;
                            return;
                        }
                        if (opcode == Opcodes.INVOKESPECIAL
                                && MeshHitTestTarget.BOUNDS_OWNER.equals(callOwner)
                                && MeshHitTestTarget.INIT_METHOD.equals(callName)
                                && MeshHitTestTarget.BOUNDS_ARRAY_INIT_DESCRIPTOR.equals(callDesc)) {
                            sawBoundsInit[0] = true;
                            capturedStride[0] = lastIntConst;
                            return;
                        }
                        extraCalls[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!sawGetPositions[0]
                || !sawBoundsInit[0]
                || capturedStride[0] != expectedStride
                || extraCalls[0] != 0) {
            throw new IllegalArgumentException(ERROR_SUBCLASS_SHAPE + owner);
        }
    }

    private static float verifyBoundsMarginConstant(final byte[] helperBytes, final String helperOwner) {
        final String[] fieldName = new String[1];
        final Float[] clinitValue = new Float[1];
        new ClassReader(helperBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                if (MeshHitTestTarget.TOLERANCE_GETTER_METHOD.equals(name)
                        && MeshHitTestTarget.FLOAT_GETTER_DESCRIPTOR.equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(
                                final int opcode, final String owner, final String field, final String fieldDesc) {
                            if (opcode == Opcodes.GETSTATIC && helperOwner.equals(owner)) {
                                fieldName[0] = field;
                            }
                        }
                    };
                }
                return null;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (fieldName[0] == null) {
            throw new IllegalArgumentException(ERROR_BOUNDS_MARGIN);
        }
        new ClassReader(helperBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                if (!MeshHitTestTarget.CLINIT_METHOD.equals(name)
                        || !MeshHitTestTarget.VOID_DESCRIPTOR.equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private Float lastFloatLdc;

                    @Override
                    public void visitLdcInsn(final Object value) {
                        lastFloatLdc = value instanceof Float f ? f : null;
                    }

                    @Override
                    public void visitFieldInsn(
                            final int opcode, final String owner, final String field, final String fieldDesc) {
                        if (opcode == Opcodes.PUTSTATIC
                                && helperOwner.equals(owner)
                                && fieldName[0].equals(field)
                                && lastFloatLdc != null) {
                            clinitValue[0] = lastFloatLdc;
                        }
                        lastFloatLdc = null;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (clinitValue[0] == null
                || Float.floatToRawIntBits(clinitValue[0])
                        != Float.floatToRawIntBits(MeshHitTestTarget.REVIEWED_BOUNDS_MARGIN)) {
            throw new IllegalArgumentException(ERROR_BOUNDS_MARGIN);
        }
        return clinitValue[0];
    }

    private static MethodVisitor wrapContains(
            final MethodVisitor downstream, final float boundsMargin, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, downstream) {
            private boolean injected;

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean isInterface) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                if (!injected
                        && opcode == Opcodes.INVOKESTATIC
                        && MeshHitTestTarget.KOTLIN_INTRINSICS_OWNER.equals(owner)
                        && MeshHitTestTarget.CHECK_NOT_NULL_PARAMETER_METHOD.equals(name)
                        && MeshHitTestTarget.CHECK_NOT_NULL_PARAMETER_DESCRIPTOR.equals(descriptor)) {
                    injected = true;
                    localSites[0]++;
                    emitBoundsFastPath(super.mv, boundsMargin);
                }
            }
        };
    }

    private static void emitBoundsFastPath(final MethodVisitor mv, final float boundsMargin) {
        final Label checkGMesh3 = new Label();
        final Label stepReady = new Label();
        final Label stride3Limit = new Label();
        final Label limitReady = new Label();
        final Label loopStart = new Label();
        final Label loopEnd = new Label();
        final Label notMinX = new Label();
        final Label notMaxX = new Label();
        final Label notMinY = new Label();
        final Label notMaxY = new Label();
        final Label outsideBounds = new Label();
        final Label fallback = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_THIS);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, MeshHitTestTarget.GMESH2_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, checkGMesh3);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_STEP);
        mv.visitJumpInsn(Opcodes.GOTO, stepReady);

        mv.visitLabel(checkGMesh3);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_THIS);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, MeshHitTestTarget.GMESH3_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, fallback);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_STEP);

        mv.visitLabel(stepReady);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_THIS);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                MeshHitTestTarget.OWNER,
                MeshHitTestTarget.GET_POSITIONS_METHOD,
                MeshHitTestTarget.GET_POSITIONS_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_POSITIONS);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_POSITIONS);
        mv.visitJumpInsn(Opcodes.IFNULL, fallback);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_POSITIONS);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_STEP);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, stride3Limit);
        mv.visitIntInsn(Opcodes.BIPUSH, EVEN_ALIGNMENT_MASK);
        mv.visitInsn(Opcodes.IAND);
        mv.visitJumpInsn(Opcodes.GOTO, limitReady);

        mv.visitLabel(stride3Limit);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.IDIV);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.IMUL);

        mv.visitLabel(limitReady);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_LIMIT);

        mv.visitLdcInsn(INITIAL_MIN_FLOAT);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_MIN_X);
        mv.visitLdcInsn(INITIAL_MAX_FLOAT);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_MAX_X);
        mv.visitLdcInsn(INITIAL_MIN_FLOAT);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_MIN_Y);
        mv.visitLdcInsn(INITIAL_MAX_FLOAT);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_MAX_Y);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_INDEX);

        mv.visitLabel(loopStart);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_LIMIT);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_POSITIONS);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX);
        mv.visitInsn(Opcodes.FALOAD);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_X);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_POSITIONS);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX);
        mv.visitIntInsn(Opcodes.BIPUSH, Y_COMPONENT_OFFSET);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.FALOAD);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_Y);

        // Uses FCMPG for < and FCMPL for > to match GBounds NaN handling bit-for-bit.
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_X);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MIN_X);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, notMinX);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_X);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_MIN_X);

        mv.visitLabel(notMinX);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_X);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MAX_X);
        mv.visitInsn(Opcodes.FCMPL);
        mv.visitJumpInsn(Opcodes.IFLE, notMaxX);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_X);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_MAX_X);

        mv.visitLabel(notMaxX);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MIN_Y);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, notMinY);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_MIN_Y);

        mv.visitLabel(notMinY);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MAX_Y);
        mv.visitInsn(Opcodes.FCMPL);
        mv.visitJumpInsn(Opcodes.IFLE, notMaxY);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_MAX_Y);

        mv.visitLabel(notMaxY);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_INDEX);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_STEP);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_INDEX);
        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        mv.visitLabel(loopEnd);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_POINT);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                MeshHitTestTarget.VECTOR2_OWNER,
                MeshHitTestTarget.GET_X_METHOD,
                MeshHitTestTarget.FLOAT_GETTER_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_X);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_POINT);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                MeshHitTestTarget.VECTOR2_OWNER,
                MeshHitTestTarget.GET_Y_METHOD,
                MeshHitTestTarget.FLOAT_GETTER_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_Y);
        mv.visitLdcInsn(boundsMargin);
        mv.visitVarInsn(Opcodes.FSTORE, LOCAL_MARGIN);

        // FCMPG produces +1 on NaN so IFGE routes NaN coordinates to outsideBounds, matching GBounds.a.
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MIN_X);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MARGIN);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_X);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, outsideBounds);

        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_X);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MAX_X);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MARGIN);
        mv.visitInsn(Opcodes.FADD);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, outsideBounds);

        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MIN_Y);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MARGIN);
        mv.visitInsn(Opcodes.FSUB);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, outsideBounds);

        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_Y);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MAX_Y);
        mv.visitVarInsn(Opcodes.FLOAD, LOCAL_MARGIN);
        mv.visitInsn(Opcodes.FADD);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, outsideBounds);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_THIS);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_POINT);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_OUT_INDICES);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                MeshHitTestTarget.OWNER,
                MeshHitTestTarget.CONTAINS_IN_MESH_METHOD,
                MeshHitTestTarget.CONTAINS_DESCRIPTOR,
                false);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(outsideBounds);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(fallback);
    }
}

package dev.turboism.adapter.cubism.optimization.animation;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites admitted Cubism animation evaluation and timeline/Graph Editor UI classes across all
 * reviewed builds while failing closed on any unexpected class bytes.
 */
public final class AnimationAccelerationTransformer implements ClassFileTransformer {

    private static final String FAILURE_SOURCE_MISMATCH = "animation acceleration source is not the reviewed artifact";
    private static final String FAILURE_METHOD_ABSENT = "reviewed animation method absent";
    private static final String FAILURE_SHAPE_MISMATCH = "animation method shape mismatch";
    private static final String FAILURE_NOT_APPLIED = "animation acceleration fast path was not applied";
    private static final String FAILURE_LOCATOR_SEPARATOR = " at ";
    private static final String NO_FRAMES_MARKER = "<no frames>";

    private static final String LIST_OWNER = "java/util/List";
    private static final String ARRAY_LIST_OWNER = "java/util/ArrayList";
    private static final String ITERATOR_OWNER = "java/util/Iterator";
    private static final String METHOD_SIZE = "size";
    private static final String METHOD_HAS_NEXT = "hasNext";
    private static final String DESC_INT_VOID = "()I";
    private static final String DESC_BOOL_VOID = "()Z";
    private static final String METHOD_GET_DISPLAY_FRAME = "getDisplayFrame";
    private static final String DESC_GET_DISPLAY_FRAME = "(I)I";
    private static final String MAKE_CONCAT = "makeConcatWithConstants";
    private static final String RECIPE_NEG_ZERO = "-0:\u0001";
    private static final String RECIPE_SEC_SUB = "\u0001:\u0001";
    private static final String RECIPE_FRAME = "\u0001";
    private static final String DESC_STR_TO_STR = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String DESC_INT_STR_TO_STR = "(ILjava/lang/String;)Ljava/lang/String;";
    private static final String DESC_INT_TO_STR = "(I)Ljava/lang/String;";
    private static final String FIELD_TRACK_CHILDREN = "trackChildren";
    private static final String FIELD_BEZIER_HANDLE_COLOR = "color_BezierHandle";
    private static final String METHOD_VH_D = "d";
    private static final String METHOD_INIT = "<init>";
    private static final String DESC_VOID_INT = "(I)V";
    private static final String FMT_PAD5 = "%05d";
    private static final String FMT_TIMECODE_NEG = "-%02d:%02d:";
    private static final String METHOD_TIMELINE_FRAME_B = "b";
    private static final String METHOD_TIMELINE_FRAME_C = "c";
    private static final String CORE_TIMELINE_OWNER = "com/live2d/cubism/doc/animation/movie/core/b";
    private static final String GRAPH_POINT_COLOR_PREFIX = "drawCurve$getPointColor";
    private static final String GRAPH_TEXT_COLOR_PREFIX = "drawCurve$getPointTextColor";
    private static final String LOG_INVOKE_METHOD = "c";
    private static final int DOUBLE_SLOT_PAD = 2;
    private static final int DRAW_CURVE_FALLBACK_DF_OFFSET = 42;
    private static final int DRAW_CURVE_MAX_TEMP_NEEDED = 45;
    private static final int EXPECTED_FORMAT_NEG_SUBFRAME_CALLS = 4;

    private final ClassLoader loader;
    private final Path artifact;
    private final AnimationAccelerationTarget target;
    private final Map<String, byte[]> references;
    private final List<String> formAnimShape;
    private final List<String> sceneParamShape;
    private final List<String> groupTestImplShape;
    private final List<String> groupForFaShape;
    private final List<String> modelTrackShape;
    private final List<String> movieInfoSubframeShape;
    private final List<String> utilsTimecodeShapeBytes;
    private final List<String> headerPaintShape;
    private final List<String> graphPaintShape;
    private final List<String> graphDrawCurveShapeBytes;

    private final boolean movieInfoNegateSubframe;
    private final AnimationMethodEmitters.UtilsTimecodeShape utilsTimecodeShape;
    private final AnimationMethodEmitters.GraphDrawCurveShape graphDrawCurveShape;

    private volatile String failure;
    private volatile int formAnimMatches;
    private volatile int sceneMatches;
    private volatile int groupMatches;
    private volatile int modelMatches;
    private volatile int movieInfoMatches;
    private volatile int utilsMatches;
    private volatile int headerMatches;
    private volatile int graphMatches;
    private volatile int totalSites;

    /**
     * Constructs an animation acceleration transformer validated against reference host class bytes.
     *
     * @param loader permitted host class loader
     * @param artifact normalized path to the admitted host JAR
     * @param references unmodified reference bytecode keyed by internal class name
     * @param target resolved animation target metadata
     */
    public AnimationAccelerationTransformer(
            final ClassLoader loader,
            final Path artifact,
            final Map<String, byte[]> references,
            final AnimationAccelerationTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.references = Map.copyOf(Objects.requireNonNull(references, "references"));
        this.target = Objects.requireNonNull(target, "target");

        formAnimShape = requireShape(
                AnimationAccelerationTarget.FORM_ANIMATION_SET_OWNER,
                AnimationAccelerationTarget.updateTrackInstanceMethod(),
                AnimationAccelerationTarget.updateTrackInstanceDescriptor());
        sceneParamShape = target.hasSceneParamCtrlReference()
                ? requireShape(
                        AnimationAccelerationTarget.SCENE_INSTANCE_OWNER,
                        AnimationAccelerationTarget.updateAllParamCtrlRefMethod(),
                        target.updateAllParamCtrlRefDescriptor())
                : null;
        groupTestImplShape = requireShape(
                AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER,
                AnimationAccelerationTarget.updateTrackInstanceTestImplMethod(),
                target.updateTrackInstanceTestImplDescriptor());
        groupForFaShape = requireShape(
                AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER,
                AnimationAccelerationTarget.updateTrackInstanceForFaMethod(),
                target.updateTrackInstanceForFaDescriptor());
        modelTrackShape = requireShape(
                AnimationAccelerationTarget.TRACK_MODEL_INSTANCE_OWNER,
                AnimationAccelerationTarget.updateTrackInstanceTestImplMethod(),
                target.updateTrackInstanceTestImplDescriptor());
        movieInfoSubframeShape = requireShape(
                AnimationAccelerationTarget.MOVIE_INFO_OWNER,
                AnimationAccelerationTarget.getDisplaySubframeMethod(),
                AnimationAccelerationTarget.getDisplaySubframeDescriptor());
        headerPaintShape = requireShape(
                AnimationAccelerationTarget.HEADER_PANEL_OWNER,
                AnimationAccelerationTarget.paintExeMethod(),
                AnimationAccelerationTarget.paintExeDescriptor());
        graphPaintShape = requireShape(
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                AnimationAccelerationTarget.paintExeMethod(),
                AnimationAccelerationTarget.paintExeDescriptor());
        graphDrawCurveShapeBytes = requireShape(
                AnimationAccelerationTarget.GRAPH_EDITOR_OWNER,
                AnimationAccelerationTarget.drawCurveMethod(),
                AnimationAccelerationTarget.drawCurveDescriptor());

        movieInfoNegateSubframe =
                detectSubframeNegation(requireReference(AnimationAccelerationTarget.MOVIE_INFO_OWNER));
        utilsTimecodeShape =
                detectUtilsTimecodeShape(requireReference(target.timelineUtilsOwner()), target.timelineUtilsOwner());
        utilsTimecodeShapeBytes = requireShape(target.timelineUtilsOwner(), utilsTimecodeShape.methodName(), "()V");
        graphDrawCurveShape =
                detectGraphDrawCurveShape(requireReference(AnimationAccelerationTarget.GRAPH_EDITOR_OWNER));
    }

    /** Returns the most recent refusal reason recorded during transformation, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns rewritten match count ({@code 0} or {@code 1}) for one owner class internal name. */
    public int matchesFor(final String internalName) {
        if (AnimationAccelerationTarget.FORM_ANIMATION_SET_OWNER.equals(internalName)) {
            return formAnimMatches;
        }
        if (AnimationAccelerationTarget.SCENE_INSTANCE_OWNER.equals(internalName)) {
            return sceneMatches;
        }
        if (AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER.equals(internalName)) {
            return groupMatches;
        }
        if (AnimationAccelerationTarget.TRACK_MODEL_INSTANCE_OWNER.equals(internalName)) {
            return modelMatches;
        }
        if (AnimationAccelerationTarget.MOVIE_INFO_OWNER.equals(internalName)) {
            return movieInfoMatches;
        }
        if (target.timelineUtilsOwner().equals(internalName)) {
            return utilsMatches;
        }
        if (AnimationAccelerationTarget.HEADER_PANEL_OWNER.equals(internalName)) {
            return headerMatches;
        }
        if (AnimationAccelerationTarget.GRAPH_EDITOR_OWNER.equals(internalName)) {
            return graphMatches;
        }
        return 0;
    }

    /** Returns expected match count for one owner class internal name. */
    public int expectedMatchesFor(final String internalName) {
        if (AnimationAccelerationTarget.SCENE_INSTANCE_OWNER.equals(internalName)) {
            return target.hasSceneParamCtrlReference() ? 1 : 0;
        }
        return target.rewrittenOwners().contains(internalName) ? 1 : 0;
    }

    /** Returns total rewritten method sites across transformed owners. */
    public int sites() {
        return totalSites;
    }

    /** Returns total expected rewritten method sites for this admitted build. */
    public int expectedTotalSites() {
        return target.hasSceneParamCtrlReference() ? 10 : 9;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || bytes == null || !target.rewrittenOwners().contains(name)) {
            return null;
        }
        try {
            if (!HostCodeSource.matchesArtifact(domain, artifact)) {
                failure = FAILURE_SOURCE_MISMATCH;
                return null;
            }
            if (!matchesExpectedShapes(name, bytes)) {
                return null;
            }
            final byte[] rewritten = rewriteOwner(name, bytes);
            if (rewritten == null) {
                return null;
            }
            recordMatch(name);
            return rewritten;
        } catch (final Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure =
                    rejected + FAILURE_LOCATOR_SEPARATOR + (trace.length == 0 ? NO_FRAMES_MARKER : trace[0].toString());
            return null;
        }
    }

    private byte[] rewriteOwner(final String owner, final byte[] bytes) {
        final ClassReader reader = HostClassWriters.newClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final int[] applied = {0};
        final int expectedSitesForOwner = expectedSitesForOwner(owner);
        final ClassVisitor visitor =
                HostClassWriters.preservingClassVersion(bytes, new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor delegate =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        return adaptMethod(owner, name, descriptor, delegate, bytes, applied);
                    }
                });
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (applied[0] != expectedSitesForOwner) {
            failure = FAILURE_NOT_APPLIED + " (" + owner + ": " + applied[0] + "/" + expectedSitesForOwner + ")";
            return null;
        }
        totalSites += applied[0];
        return writer.toByteArray();
    }

    private MethodVisitor adaptMethod(
            final String owner,
            final String name,
            final String descriptor,
            final MethodVisitor delegate,
            final byte[] ownerBytes,
            final int[] applied) {
        if (AnimationAccelerationTarget.FORM_ANIMATION_SET_OWNER.equals(owner)
                && AnimationAccelerationTarget.updateTrackInstanceMethod().equals(name)
                && AnimationAccelerationTarget.updateTrackInstanceDescriptor().equals(descriptor)) {
            return new FormAnimationSetMethodVisitor(delegate, target, applied);
        }
        if (AnimationAccelerationTarget.SCENE_INSTANCE_OWNER.equals(owner)
                && target.hasSceneParamCtrlReference()
                && AnimationAccelerationTarget.updateAllParamCtrlRefMethod().equals(name)
                && target.updateAllParamCtrlRefDescriptor().equals(descriptor)) {
            return new SceneParamCtrlMethodVisitor(delegate, target, applied);
        }
        if (AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER.equals(owner)) {
            if (AnimationAccelerationTarget.updateTrackInstanceTestImplMethod().equals(name)
                    && target.updateTrackInstanceTestImplDescriptor().equals(descriptor)) {
                final int baseLocal = maxLocalIndex(ownerBytes, owner, name, descriptor) + DOUBLE_SLOT_PAD;
                return new GroupTrackMethodVisitor(delegate, target, false, baseLocal, applied);
            }
            if (AnimationAccelerationTarget.updateTrackInstanceForFaMethod().equals(name)
                    && target.updateTrackInstanceForFaDescriptor().equals(descriptor)) {
                final int baseLocal = maxLocalIndex(ownerBytes, owner, name, descriptor) + DOUBLE_SLOT_PAD;
                return new GroupTrackMethodVisitor(delegate, target, true, baseLocal, applied);
            }
        }
        if (AnimationAccelerationTarget.TRACK_MODEL_INSTANCE_OWNER.equals(owner)
                && AnimationAccelerationTarget.updateTrackInstanceTestImplMethod()
                        .equals(name)
                && target.updateTrackInstanceTestImplDescriptor().equals(descriptor)) {
            final int baseLocal = maxLocalIndex(ownerBytes, owner, name, descriptor) + DOUBLE_SLOT_PAD;
            return new Live2DModelTrackMethodVisitor(delegate, baseLocal, applied);
        }
        if (AnimationAccelerationTarget.MOVIE_INFO_OWNER.equals(owner)
                && AnimationAccelerationTarget.getDisplaySubframeMethod().equals(name)
                && AnimationAccelerationTarget.getDisplaySubframeDescriptor().equals(descriptor)) {
            return new MovieInfoSubframeMethodVisitor(delegate, movieInfoNegateSubframe, applied);
        }
        if (target.timelineUtilsOwner().equals(owner)
                && utilsTimecodeShape.methodName().equals(name)
                && "()V".equals(descriptor)) {
            return new UtilsTimecodeMethodVisitor(delegate, utilsTimecodeShape, applied);
        }
        if (AnimationAccelerationTarget.HEADER_PANEL_OWNER.equals(owner)
                && AnimationAccelerationTarget.paintExeMethod().equals(name)
                && AnimationAccelerationTarget.paintExeDescriptor().equals(descriptor)) {
            final int baseLocal = maxLocalIndex(ownerBytes, owner, name, descriptor) + DOUBLE_SLOT_PAD;
            return new HeaderRowEditMethodVisitor(delegate, baseLocal, applied);
        }
        if (AnimationAccelerationTarget.GRAPH_EDITOR_OWNER.equals(owner)) {
            if (AnimationAccelerationTarget.paintExeMethod().equals(name)
                    && AnimationAccelerationTarget.paintExeDescriptor().equals(descriptor)) {
                final int baseLocal = maxLocalIndex(ownerBytes, owner, name, descriptor) + DOUBLE_SLOT_PAD;
                return new GraphPaintMethodVisitor(delegate, baseLocal, applied);
            }
            if (AnimationAccelerationTarget.drawCurveMethod().equals(name)
                    && AnimationAccelerationTarget.drawCurveDescriptor().equals(descriptor)) {
                final int baseLocal = maxLocalIndex(ownerBytes, owner, name, descriptor) + DOUBLE_SLOT_PAD;
                return new GraphDrawCurveMethodVisitor(delegate, target, graphDrawCurveShape, baseLocal, applied);
            }
        }
        return delegate;
    }

    private int expectedSitesForOwner(final String owner) {
        if (AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER.equals(owner)
                || AnimationAccelerationTarget.GRAPH_EDITOR_OWNER.equals(owner)) {
            return 2;
        }
        return 1;
    }

    private void recordMatch(final String owner) {
        if (AnimationAccelerationTarget.FORM_ANIMATION_SET_OWNER.equals(owner)) {
            formAnimMatches++;
        } else if (AnimationAccelerationTarget.SCENE_INSTANCE_OWNER.equals(owner)) {
            sceneMatches++;
        } else if (AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER.equals(owner)) {
            groupMatches++;
        } else if (AnimationAccelerationTarget.TRACK_MODEL_INSTANCE_OWNER.equals(owner)) {
            modelMatches++;
        } else if (AnimationAccelerationTarget.MOVIE_INFO_OWNER.equals(owner)) {
            movieInfoMatches++;
        } else if (target.timelineUtilsOwner().equals(owner)) {
            utilsMatches++;
        } else if (AnimationAccelerationTarget.HEADER_PANEL_OWNER.equals(owner)) {
            headerMatches++;
        } else if (AnimationAccelerationTarget.GRAPH_EDITOR_OWNER.equals(owner)) {
            graphMatches++;
        }
    }

    private boolean matchesExpectedShapes(final String owner, final byte[] bytes) {
        if (AnimationAccelerationTarget.FORM_ANIMATION_SET_OWNER.equals(owner)) {
            return matchesShape(
                    bytes,
                    owner,
                    AnimationAccelerationTarget.updateTrackInstanceMethod(),
                    AnimationAccelerationTarget.updateTrackInstanceDescriptor(),
                    formAnimShape);
        }
        if (AnimationAccelerationTarget.SCENE_INSTANCE_OWNER.equals(owner)) {
            return !target.hasSceneParamCtrlReference()
                    || matchesShape(
                            bytes,
                            owner,
                            AnimationAccelerationTarget.updateAllParamCtrlRefMethod(),
                            target.updateAllParamCtrlRefDescriptor(),
                            sceneParamShape);
        }
        if (AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER.equals(owner)) {
            return matchesShape(
                            bytes,
                            owner,
                            AnimationAccelerationTarget.updateTrackInstanceTestImplMethod(),
                            target.updateTrackInstanceTestImplDescriptor(),
                            groupTestImplShape)
                    && matchesShape(
                            bytes,
                            owner,
                            AnimationAccelerationTarget.updateTrackInstanceForFaMethod(),
                            target.updateTrackInstanceForFaDescriptor(),
                            groupForFaShape);
        }
        if (AnimationAccelerationTarget.TRACK_MODEL_INSTANCE_OWNER.equals(owner)) {
            return matchesShape(
                    bytes,
                    owner,
                    AnimationAccelerationTarget.updateTrackInstanceTestImplMethod(),
                    target.updateTrackInstanceTestImplDescriptor(),
                    modelTrackShape);
        }
        if (AnimationAccelerationTarget.MOVIE_INFO_OWNER.equals(owner)) {
            return matchesShape(
                    bytes,
                    owner,
                    AnimationAccelerationTarget.getDisplaySubframeMethod(),
                    AnimationAccelerationTarget.getDisplaySubframeDescriptor(),
                    movieInfoSubframeShape);
        }
        if (target.timelineUtilsOwner().equals(owner)) {
            return matchesShape(bytes, owner, utilsTimecodeShape.methodName(), "()V", utilsTimecodeShapeBytes);
        }
        if (AnimationAccelerationTarget.HEADER_PANEL_OWNER.equals(owner)) {
            return matchesShape(
                    bytes,
                    owner,
                    AnimationAccelerationTarget.paintExeMethod(),
                    AnimationAccelerationTarget.paintExeDescriptor(),
                    headerPaintShape);
        }
        if (AnimationAccelerationTarget.GRAPH_EDITOR_OWNER.equals(owner)) {
            return matchesShape(
                            bytes,
                            owner,
                            AnimationAccelerationTarget.paintExeMethod(),
                            AnimationAccelerationTarget.paintExeDescriptor(),
                            graphPaintShape)
                    && matchesShape(
                            bytes,
                            owner,
                            AnimationAccelerationTarget.drawCurveMethod(),
                            AnimationAccelerationTarget.drawCurveDescriptor(),
                            graphDrawCurveShapeBytes);
        }
        return false;
    }

    private boolean matchesShape(
            final byte[] bytes,
            final String owner,
            final String method,
            final String descriptor,
            final List<String> expected) {
        final List<String> actual = ReviewedMethodShape.read(bytes, owner, method, descriptor);
        if (actual == null) {
            failure = FAILURE_METHOD_ABSENT + ": " + owner + "." + method + descriptor;
            return false;
        }
        if (!expected.equals(actual)) {
            failure = FAILURE_SHAPE_MISMATCH + ": " + owner + "." + method + descriptor;
            return false;
        }
        return true;
    }

    private byte[] requireReference(final String owner) {
        final byte[] bytes = references.get(owner);
        if (bytes == null) {
            throw new IllegalArgumentException(FAILURE_METHOD_ABSENT + ": " + owner);
        }
        return bytes;
    }

    private List<String> requireShape(final String owner, final String method, final String descriptor) {
        final List<String> shape = ReviewedMethodShape.read(requireReference(owner), owner, method, descriptor);
        if (shape == null) {
            throw new IllegalArgumentException(FAILURE_METHOD_ABSENT + ": " + owner + "." + method + descriptor);
        }
        return shape;
    }

    private static boolean detectSubframeNegation(final byte[] movieInfoBytes) {
        final int[] formatCalls = {0};
        final ClassReader reader = HostClassWriters.newClassReader(movieInfoBytes);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (!AnimationAccelerationTarget.getDisplaySubframeMethod()
                                        .equals(name)
                                || !AnimationAccelerationTarget.getDisplaySubframeDescriptor()
                                        .equals(descriptor)) {
                            return null;
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if ("java/lang/String".equals(owner) && "format".equals(mName)) {
                                    formatCalls[0]++;
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return formatCalls[0] >= EXPECTED_FORMAT_NEG_SUBFRAME_CALLS;
    }

    private static AnimationMethodEmitters.UtilsTimecodeShape detectUtilsTimecodeShape(
            final byte[] utilsBytes, final String utilsOwner) {
        final AnimationMethodEmitters.UtilsTimecodeShape[] found = {null};
        final ClassReader reader = HostClassWriters.newClassReader(utilsBytes);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (!"()V".equals(descriptor)) {
                            return null;
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            boolean sawSubframe;
                            boolean sawPad5;
                            boolean sawNeg;
                            String sceneMethod;
                            String frameMethod;
                            String logOwner;
                            String lambdaOwner;

                            @Override
                            public void visitLdcInsn(final Object value) {
                                if (FMT_PAD5.equals(value)) {
                                    sawPad5 = true;
                                } else if (FMT_TIMECODE_NEG.equals(value)) {
                                    sawNeg = true;
                                }
                            }

                            @Override
                            public void visitTypeInsn(final int opcode, final String type) {
                                if (opcode == Opcodes.NEW
                                        && type.startsWith("com/live2d/cubism/view/palette/timeline/Utils/")) {
                                    lambdaOwner = type;
                                }
                            }

                            @Override
                            public void visitFieldInsn(
                                    final int opcode, final String owner, final String fName, final String fDesc) {
                                if (opcode == Opcodes.GETSTATIC && owner.startsWith("com/live2d/util/")) {
                                    logOwner = owner;
                                }
                            }

                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (AnimationAccelerationTarget.MOVIE_INFO_OWNER.equals(owner)
                                        && AnimationAccelerationTarget.getDisplaySubframeMethod()
                                                .equals(mName)) {
                                    sawSubframe = true;
                                } else if (utilsOwner.equals(owner) && mDesc.endsWith("CSceneSource;")) {
                                    sceneMethod = mName;
                                } else if (CORE_TIMELINE_OWNER.equals(owner)
                                        && DESC_INT_VOID.equals(mDesc)
                                        && (METHOD_TIMELINE_FRAME_B.equals(mName)
                                                || METHOD_TIMELINE_FRAME_C.equals(mName))) {
                                    frameMethod = mName;
                                }
                            }

                            @Override
                            public void visitEnd() {
                                if (sawSubframe
                                        && sawPad5
                                        && sceneMethod != null
                                        && frameMethod != null
                                        && logOwner != null
                                        && lambdaOwner != null) {
                                    found[0] = new AnimationMethodEmitters.UtilsTimecodeShape(
                                            utilsOwner, name, sceneMethod, frameMethod, sawNeg, logOwner, lambdaOwner);
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (found[0] == null) {
            throw new IllegalArgumentException(FAILURE_METHOD_ABSENT + ": " + utilsOwner + " timecode formatter");
        }
        return found[0];
    }

    private static AnimationMethodEmitters.GraphDrawCurveShape detectGraphDrawCurveShape(final byte[] graphBytes) {
        final String[] colorDescs = {null, null};
        final ClassReader reader = HostClassWriters.newClassReader(graphBytes);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (!AnimationAccelerationTarget.drawCurveMethod().equals(name)
                                || !AnimationAccelerationTarget.drawCurveDescriptor()
                                        .equals(descriptor)) {
                            return null;
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (AnimationAccelerationTarget.GRAPH_EDITOR_OWNER.equals(owner)) {
                                    if (GRAPH_POINT_COLOR_PREFIX.equals(mName)) {
                                        colorDescs[0] = mDesc;
                                    } else if (GRAPH_TEXT_COLOR_PREFIX.equals(mName)) {
                                        colorDescs[1] = mDesc;
                                    }
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (colorDescs[0] == null || colorDescs[1] == null) {
            throw new IllegalArgumentException(FAILURE_METHOD_ABSENT + ": GraphEditor helper descriptors");
        }
        return new AnimationMethodEmitters.GraphDrawCurveShape(colorDescs[0], colorDescs[1]);
    }

    private static int maxLocalIndex(
            final byte[] bytes, final String owner, final String method, final String descriptor) {
        final int[] max = {16};
        final ClassReader reader = HostClassWriters.newClassReader(bytes);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String mDesc,
                            final String signature,
                            final String[] exceptions) {
                        if (!method.equals(name) || !descriptor.equals(mDesc)) {
                            return null;
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitVarInsn(final int opcode, final int varIndex) {
                                final int width = (opcode == Opcodes.DLOAD
                                                || opcode == Opcodes.DSTORE
                                                || opcode == Opcodes.LLOAD
                                                || opcode == Opcodes.LSTORE)
                                        ? 2
                                        : 1;
                                max[0] = Math.max(max[0], varIndex + width);
                            }

                            @Override
                            public void visitIincInsn(final int varIndex, final int increment) {
                                max[0] = Math.max(max[0], varIndex + 1);
                            }

                            @Override
                            public void visitMaxs(final int maxStack, final int maxLocals) {
                                max[0] = Math.max(max[0], maxLocals);
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return max[0];
    }

    private static final class FormAnimationSetMethodVisitor extends MethodVisitor {
        private final AnimationAccelerationTarget target;
        private final int[] applied;
        private Label origTryStart;
        private boolean injected;

        FormAnimationSetMethodVisitor(
                final MethodVisitor delegate, final AnimationAccelerationTarget target, final int[] applied) {
            super(Opcodes.ASM9, delegate);
            this.target = target;
            this.applied = applied;
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            if (type == null && origTryStart == null) {
                origTryStart = start;
            }
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        public void visitLabel(final Label label) {
            if (!injected && label == origTryStart) {
                injected = true;
                final Label fallback = new Label();
                AnimationMethodEmitters.emitFormAnimationFastPath(mv, target, fallback);
                mv.visitLabel(fallback);
                applied[0]++;
            }
            super.visitLabel(label);
        }
    }

    private static final class SceneParamCtrlMethodVisitor extends MethodVisitor {
        private final AnimationAccelerationTarget target;
        private final int[] applied;
        private boolean sawAthrow;
        private boolean injected;

        SceneParamCtrlMethodVisitor(
                final MethodVisitor delegate, final AnimationAccelerationTarget target, final int[] applied) {
            super(Opcodes.ASM9, delegate);
            this.target = target;
            this.applied = applied;
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.ATHROW) {
                sawAthrow = true;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (sawAthrow && !injected && opcode == Opcodes.ALOAD && varIndex == 0) {
                injected = true;
                final Label fallback = new Label();
                AnimationMethodEmitters.emitSceneParamCtrlFastPath(mv, target, fallback);
                mv.visitLabel(fallback);
                applied[0]++;
            }
            super.visitVarInsn(opcode, varIndex);
        }
    }

    private static final class GroupTrackMethodVisitor extends MethodVisitor {
        private final AnimationAccelerationTarget target;
        private final boolean forFa;
        private final int baseLocal;
        private final int[] applied;
        private boolean injected;
        private boolean sawHasNext;
        private Label origExitLabel;
        private Label fastExitBridge;
        private boolean exitBridged;

        GroupTrackMethodVisitor(
                final MethodVisitor delegate,
                final AnimationAccelerationTarget target,
                final boolean forFa,
                final int baseLocal,
                final int[] applied) {
            super(Opcodes.ASM9, delegate);
            this.target = target;
            this.forFa = forFa;
            this.baseLocal = baseLocal;
            this.applied = applied;
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            if (!injected
                    && opcode == Opcodes.GETFIELD
                    && AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER.equals(owner)
                    && FIELD_TRACK_CHILDREN.equals(name)) {
                injected = true;
                fastExitBridge = new Label();
                AnimationMethodEmitters.emitGroupIndexedLoop(mv, target, forFa, baseLocal, fastExitBridge);
            }
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            if (injected && !exitBridged && ITERATOR_OWNER.equals(owner) && METHOD_HAS_NEXT.equals(name)) {
                sawHasNext = true;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (sawHasNext && opcode == Opcodes.IFEQ && origExitLabel == null) {
                origExitLabel = label;
                sawHasNext = false;
            }
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            super.visitLabel(label);
            if (injected && !exitBridged && label == origExitLabel) {
                exitBridged = true;
                mv.visitLabel(fastExitBridge);
                applied[0]++;
            }
        }
    }

    private static final class Live2DModelTrackMethodVisitor extends MethodVisitor {
        private final int baseLocal;
        private final int[] applied;
        private int state;
        private int steppedVar;

        Live2DModelTrackMethodVisitor(final MethodVisitor delegate, final int baseLocal, final int[] applied) {
            super(Opcodes.ASM9, delegate);
            this.baseLocal = baseLocal;
            this.applied = applied;
        }

        private void flush() {
            if (state == 0) {
                return;
            }
            super.visitTypeInsn(Opcodes.NEW, AnimationAccelerationTarget.FRAME_TIME_OWNER);
            if (state >= 2) {
                super.visitInsn(Opcodes.DUP);
            }
            if (state >= 3) {
                super.visitVarInsn(Opcodes.ILOAD, steppedVar);
            }
            if (state >= 4) {
                super.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        AnimationAccelerationTarget.FRAME_TIME_OWNER,
                        METHOD_INIT,
                        DESC_VOID_INT,
                        false);
            }
            state = 0;
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (state == 0 && opcode == Opcodes.NEW && AnimationAccelerationTarget.FRAME_TIME_OWNER.equals(type)) {
                state = 1;
                return;
            }
            flush();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (state == 1 && opcode == Opcodes.DUP) {
                state = 2;
                return;
            }
            flush();
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (state == 2 && opcode == Opcodes.ILOAD) {
                steppedVar = varIndex;
                state = 3;
                return;
            }
            flush();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            if (state == 3
                    && opcode == Opcodes.INVOKESPECIAL
                    && AnimationAccelerationTarget.FRAME_TIME_OWNER.equals(owner)
                    && METHOD_INIT.equals(name)
                    && DESC_VOID_INT.equals(descriptor)) {
                state = 4;
                return;
            }
            if (state == 4
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && AnimationAccelerationTarget.VISUAL_HANDLER_OWNER.equals(owner)
                    && METHOD_VH_D.equals(name)) {
                state = 0;
                AnimationMethodEmitters.emitVisualHandlerCall(mv, steppedVar, baseLocal);
                applied[0]++;
                return;
            }
            flush();
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            flush();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            flush();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            flush();
            super.visitLabel(label);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            flush();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            flush();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            flush();
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {
            flush();
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            flush();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            flush();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            flush();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }
    }

    private static final class MovieInfoSubframeMethodVisitor extends MethodVisitor {
        private final boolean negateNegative;
        private final int[] applied;

        MovieInfoSubframeMethodVisitor(
                final MethodVisitor delegate, final boolean negateNegative, final int[] applied) {
            super(Opcodes.ASM9, delegate);
            this.negateNegative = negateNegative;
            this.applied = applied;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallback = new Label();
            AnimationMethodEmitters.emitSubframeFastPath(mv, negateNegative, fallback);
            mv.visitLabel(fallback);
            applied[0]++;
        }
    }

    private static final class UtilsTimecodeMethodVisitor extends MethodVisitor {
        private final AnimationMethodEmitters.UtilsTimecodeShape shape;
        private final int[] applied;

        UtilsTimecodeMethodVisitor(
                final MethodVisitor delegate,
                final AnimationMethodEmitters.UtilsTimecodeShape shape,
                final int[] applied) {
            super(Opcodes.ASM9, delegate);
            this.shape = shape;
            this.applied = applied;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallback = new Label();
            AnimationMethodEmitters.emitTimelineStatusTimecodeFastPath(mv, shape, fallback);
            mv.visitLabel(fallback);
            applied[0]++;
        }
    }

    private static final class HeaderRowEditMethodVisitor extends MethodVisitor {
        private final int baseLocal;
        private final int[] applied;
        private boolean justCalledDisplayFrame;
        private boolean counted;

        HeaderRowEditMethodVisitor(final MethodVisitor delegate, final int baseLocal, final int[] applied) {
            super(Opcodes.ASM9, delegate);
            this.baseLocal = baseLocal;
            this.applied = applied;
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            justCalledDisplayFrame = AnimationAccelerationTarget.MOVIE_INFO_OWNER.equals(owner)
                    && METHOD_GET_DISPLAY_FRAME.equals(name)
                    && DESC_GET_DISPLAY_FRAME.equals(descriptor);
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {
            final boolean prevDisplayFrame = justCalledDisplayFrame;
            justCalledDisplayFrame = false;
            if (MAKE_CONCAT.equals(name)
                    && bootstrapMethodArguments.length >= 1
                    && bootstrapMethodArguments[0] instanceof String recipe) {
                if (DESC_STR_TO_STR.equals(descriptor) && RECIPE_NEG_ZERO.equals(recipe)) {
                    AnimationMethodEmitters.emitRulerNegZeroConcat(
                            mv,
                            baseLocal,
                            () -> super.visitInvokeDynamicInsn(
                                    name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments));
                    markApplied();
                    return;
                }
                if (DESC_INT_STR_TO_STR.equals(descriptor) && RECIPE_SEC_SUB.equals(recipe)) {
                    AnimationMethodEmitters.emitRulerSecSubConcat(
                            mv,
                            baseLocal,
                            baseLocal + 1,
                            () -> super.visitInvokeDynamicInsn(
                                    name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments));
                    markApplied();
                    return;
                }
                if (prevDisplayFrame && DESC_INT_TO_STR.equals(descriptor) && RECIPE_FRAME.equals(recipe)) {
                    AnimationMethodEmitters.emitRulerFrameConcat(
                            mv,
                            baseLocal,
                            () -> super.visitInvokeDynamicInsn(
                                    name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments));
                    markApplied();
                    return;
                }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        private void markApplied() {
            if (!counted) {
                counted = true;
                applied[0]++;
            }
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            justCalledDisplayFrame = false;
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitInsn(final int opcode) {
            justCalledDisplayFrame = false;
            super.visitInsn(opcode);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            justCalledDisplayFrame = false;
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            justCalledDisplayFrame = false;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            justCalledDisplayFrame = false;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            justCalledDisplayFrame = false;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            justCalledDisplayFrame = false;
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            justCalledDisplayFrame = false;
            super.visitLabel(label);
        }
    }

    private static final class GraphPaintMethodVisitor extends MethodVisitor {
        private final int baseLocal;
        private final int[] applied;
        private boolean counted;

        GraphPaintMethodVisitor(final MethodVisitor delegate, final int baseLocal, final int[] applied) {
            super(Opcodes.ASM9, delegate);
            this.baseLocal = baseLocal;
            this.applied = applied;
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && AnimationMethodEmitters.DECIMAL_FORMAT_OWNER.equals(owner)
                    && AnimationMethodEmitters.DECIMAL_FORMAT_METHOD.equals(name)
                    && AnimationMethodEmitters.DECIMAL_FORMAT_DESC.equals(descriptor)) {
                AnimationMethodEmitters.emitDecimalFormatCall(mv, baseLocal, baseLocal + 1);
                if (!counted) {
                    counted = true;
                    applied[0]++;
                }
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }
    }

    private static final class GraphDrawCurveMethodVisitor extends MethodVisitor {
        private final AnimationAccelerationTarget target;
        private final AnimationMethodEmitters.GraphDrawCurveShape shape;
        private final int baseLocal;
        private final int[] applied;
        private boolean sawBezierColor;
        private boolean enteredBlock;
        private int listSizeCalls;
        private boolean awaitExitJump;
        private Label origDotExitLabel;
        private Label fastDotExitBridge;
        private boolean bridged;

        GraphDrawCurveMethodVisitor(
                final MethodVisitor delegate,
                final AnimationAccelerationTarget target,
                final AnimationMethodEmitters.GraphDrawCurveShape shape,
                final int baseLocal,
                final int[] applied) {
            super(Opcodes.ASM9, delegate);
            this.target = target;
            this.shape = shape;
            this.baseLocal = baseLocal;
            this.applied = applied;
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            if (opcode == Opcodes.GETSTATIC
                    && AnimationAccelerationTarget.GRAPH_EDITOR_OWNER.equals(owner)
                    && FIELD_BEZIER_HANDLE_COLOR.equals(name)) {
                sawBezierColor = true;
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (sawBezierColor && !enteredBlock && opcode == Opcodes.NEW && ARRAY_LIST_OWNER.equals(type)) {
                enteredBlock = true;
                final Label fallbackStart = new Label();
                fastDotExitBridge = new Label();
                AnimationMethodEmitters.emitActiveGate(mv, fallbackStart);
                AnimationMethodEmitters.emitGraphCurveCulledFastPath(mv, target, shape, baseLocal, fastDotExitBridge);
                mv.visitLabel(fallbackStart);
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && AnimationMethodEmitters.DECIMAL_FORMAT_OWNER.equals(owner)
                    && AnimationMethodEmitters.DECIMAL_FORMAT_METHOD.equals(name)
                    && AnimationMethodEmitters.DECIMAL_FORMAT_DESC.equals(descriptor)) {
                AnimationMethodEmitters.emitDecimalFormatCall(
                        mv, baseLocal + DRAW_CURVE_FALLBACK_DF_OFFSET, baseLocal + DRAW_CURVE_FALLBACK_DF_OFFSET + 1);
                return;
            }
            if (enteredBlock
                    && !bridged
                    && opcode == Opcodes.INVOKEINTERFACE
                    && LIST_OWNER.equals(owner)
                    && METHOD_SIZE.equals(name)
                    && DESC_INT_VOID.equals(descriptor)) {
                listSizeCalls++;
                if (listSizeCalls == 2) {
                    awaitExitJump = true;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (awaitExitJump && opcode == Opcodes.IF_ICMPGE && origDotExitLabel == null) {
                origDotExitLabel = label;
                awaitExitJump = false;
            }
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            super.visitLabel(label);
            if (enteredBlock && !bridged && label == origDotExitLabel) {
                bridged = true;
                mv.visitLabel(fastDotExitBridge);
                applied[0]++;
            }
        }
    }
}

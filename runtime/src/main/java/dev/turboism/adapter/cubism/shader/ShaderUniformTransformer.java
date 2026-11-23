package dev.turboism.adapter.cubism.shader;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.TargetClassTable;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * First-definition-only hook for the exact reviewed host shaders. Hash admission never resolves host classes.
 *
 * <p>This transformer owns the host's single material uniform query seam in
 * {@code preDraw_exe} and is registered retransformation-incapable, so its output
 * becomes the baseline every later retransformation starts from. It also replaces
 * per-draw {@code entrySet().iterator()} and {@code optionalTextures.forEach(...)}
 * allocations inside {@code preDraw_exe} with pooled entry iteration via
 * {@link NativeShaderUniformBridge}. The verified uniform-location cache transforms
 * the uniform-query seam afterwards against {@link #composedShape(byte[])}.</p>
 */
public final class ShaderUniformTransformer implements ClassFileTransformer {
    public static final String OWNER = "com/live2d/graphics3d/shader/GShader";
    /** Internal name of the bridge that owns the rewritten uniform-query seam. */
    public static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/shader/NativeShaderUniformBridge";
    /** Bridge method this rewrite substitutes for the host's uniform query. */
    public static final String BRIDGE_LOOKUP = "lookup";
    /** Descriptor of {@link #BRIDGE_LOOKUP}: {@code (gl, program, name, shader) -> location}. */
    public static final String BRIDGE_LOOKUP_DESCRIPTOR = "(Ljava/lang/Object;ILjava/lang/String;Ljava/lang/Object;)I";

    static final String DRAW_METHOD = "preDraw_exe";
    static final String DRAW_DESC =
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/graphics3d/material/GMaterial;Lcom/live2d/graphics3d/type/GMatrix44;)V";
    static final String GL_OWNER = "com/jogamp/opengl/GL3";
    static final String QUERY_METHOD = "glGetUniformLocation";
    static final String QUERY_DESC = "(ILjava/lang/String;)I";
    static final String[] LIFECYCLE_METHODS = {"init_exe", "setGlProgramID"};
    static final String[] LIFECYCLE_DESCS = {"(Lcom/live2d/graphics3d/a;)V", "(I)V"};

    private static final String BRIDGE_BORROW_METHOD = "borrowMapEntries";
    private static final String BRIDGE_BORROW_DESC = "(Ljava/util/HashMap;)[Ljava/util/Map$Entry;";
    private static final String BRIDGE_RELEASE_METHOD = "releaseMapEntries";
    private static final String BRIDGE_RELEASE_DESC = "([Ljava/util/Map$Entry;)V";

    private static final String GMATERIAL_OWNER = "com/live2d/graphics3d/material/GMaterial";
    private static final String GET_UNIFORM_MAP_METHOD = "getUniformMap$core";
    private static final String GET_OPTIONAL_TEXTURES_METHOD = "getOptionalTextures$core";
    private static final String HASH_MAP_GETTER_DESC = "()Ljava/util/HashMap;";
    private static final String HASH_MAP_OWNER = "java/util/HashMap";
    private static final String HASH_MAP_ENTRY_SET_METHOD = "entrySet";
    private static final String HASH_MAP_ENTRY_SET_DESC = "()Ljava/util/Set;";
    private static final String HASH_MAP_SIZE_METHOD = "size";
    private static final String HASH_MAP_SIZE_DESC = "()I";
    private static final String HASH_MAP_FOR_EACH_METHOD = "forEach";
    private static final String HASH_MAP_FOR_EACH_DESC = "(Ljava/util/function/BiConsumer;)V";
    private static final String INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";
    private static final String CHECK_NOT_NULL_METHOD = "checkNotNullExpressionValue";
    private static final String CHECK_NOT_NULL_DESC = "(Ljava/lang/Object;Ljava/lang/String;)V";
    private static final String ITERABLE_OWNER = "java/lang/Iterable";
    private static final String ITERATOR_METHOD = "iterator";
    private static final String ITERATOR_METHOD_DESC = "()Ljava/util/Iterator;";
    private static final String ITERATOR_OWNER = "java/util/Iterator";
    private static final String HAS_NEXT_METHOD = "hasNext";
    private static final String HAS_NEXT_DESC = "()Z";
    private static final String NEXT_METHOD = "next";
    private static final String NEXT_DESC = "()Ljava/lang/Object;";
    private static final String MAP_ENTRY_OWNER = "java/util/Map$Entry";
    private static final String MAP_ENTRY_GET_KEY_METHOD = "getKey";
    private static final String MAP_ENTRY_GET_VALUE_METHOD = "getValue";
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String SHADER_PACKAGE_PREFIX = "com/live2d/graphics3d/shader/";
    private static final String TEXTURE_LAMBDA_INIT_DESC =
            "(Lcom/live2d/graphics3d/shader/GShader;Lcom/live2d/graphics3d/a;Lcom/jogamp/opengl/GL3;)V";
    private static final String FUNCTION2_OWNER = "kotlin/jvm/functions/Function2";
    private static final String BI_CONSUMER_ACCEPT = "accept";
    private static final String BI_CONSUMER_INDY_DESC =
            "(Lkotlin/jvm/functions/Function2;)Ljava/util/function/BiConsumer;";
    private static final String GTEXTURE_OWNER = "com/live2d/graphics3d/texture/GTexture";
    private static final String STRING_OWNER = "java/lang/String";
    private static final String BIND_TEXTURE_METHOD = "bindTexture";
    private static final String BIND_TEXTURE_DESC =
            "(Lcom/live2d/graphics3d/a;Lcom/jogamp/opengl/GL3;Lcom/live2d/graphics3d/texture/GTexture;Ljava/lang/String;)V";

    /**
     * Reviewed GShader target class per admitted Cubism version. Admission is per
     * version: the whole-artifact digest resolves the version, and the rewrite gate
     * then verifies the exact {@code preDraw_exe} body it substitutes.
     */
    private static final Map<String, List<String>> REVIEWED_CLASSES = TargetClassTable.load("shader-uniform");

    /**
     * Records a definition this rewrite actually patched. A later transformer in
     * the retransform chain consults it so a body carrying <em>this</em> reviewed
     * rewrite can be told apart from foreign bytecode drift; the record alone
     * widens nothing, because the composed shape the consumer admits is derived
     * from {@link #patch(byte[])} over attested reference bytes.
     *
     * <p>The record is keyed by the defining loader in a weak map, so a loader that
     * the host unloads is collectable again. The bootstrap loader is a legal key.</p>
     */
    private static final Map<ClassLoader, Set<String>> PATCHED = Collections.synchronizedMap(new WeakHashMap<>());

    private final Consumer<String> diagnostic;
    private volatile boolean patched;

    public ShaderUniformTransformer(Consumer<String> diagnostic) {
        this.diagnostic = java.util.Objects.requireNonNull(diagnostic);
    }

    /**
     * Records that a definition supplied by {@code loader} carries this rewrite.
     * Called by the transform itself; exposed so retransform-chain consumers can
     * be exercised against a composed baseline in tests.
     *
     * @param loader the defining loader of the patched definition
     * @param owner the patched class internal name
     */
    public static void markPatched(ClassLoader loader, String owner) {
        final String name = java.util.Objects.requireNonNull(owner, "owner");
        synchronized (PATCHED) {
            PATCHED.computeIfAbsent(loader, ignored -> new LinkedHashSet<>()).add(name);
        }
    }

    /**
     * Drops a patch record. A real definition is never reverted — removing the
     * transformer leaves the already-defined class patched — so production code
     * keeps the record for the process lifetime.
     *
     * @param loader the defining loader of the patched definition
     * @param owner the patched class internal name
     */
    public static void clearPatched(ClassLoader loader, String owner) {
        final String name = java.util.Objects.requireNonNull(owner, "owner");
        synchronized (PATCHED) {
            final Set<String> owners = PATCHED.get(loader);
            if (owners == null) {
                return;
            }
            owners.remove(name);
            if (owners.isEmpty()) {
                PATCHED.remove(loader);
            }
        }
    }

    /**
     * @param loader the defining loader to test
     * @param owner the class internal name to test
     * @return {@code true} when this rewrite patched that definition
     */
    public static boolean isPatched(ClassLoader loader, String owner) {
        final String name = java.util.Objects.requireNonNull(owner, "owner");
        synchronized (PATCHED) {
            final Set<String> owners = PATCHED.get(loader);
            return owners != null && owners.contains(name);
        }
    }

    /**
     * Returns the exact reviewed {@code preDraw_exe} shape this rewrite produces
     * on the official body — the bytes a later transformer in the retransform
     * chain observes, because this transformer is registered
     * retransformation-incapable and its output becomes the retransform baseline.
     * Computed by running this rewrite over attested reference bytes, never by
     * trusting observed chain output.
     *
     * @param reference official reference bytes of the reviewed shader class
     * @return the composed instruction shape, or {@code null} when the rewrite cannot run
     */
    public static List<String> composedShape(byte[] reference) {
        byte[] composed = composedDefinition(reference);
        return composed == null ? null : ReviewedMethodShape.read(composed, OWNER, DRAW_METHOD, DRAW_DESC);
    }

    /**
     * Returns the exact bytes this rewrite produces on an official definition.
     * Derivation only — no host class is resolved and no admission is implied;
     * {@link #transform} remains the single admitting entry point. Consumers use
     * it to reproduce the retransformation baseline this hook leaves behind.
     *
     * @param reference official reference bytes of the reviewed shader class
     * @return the rewritten definition, or {@code null} when the rewrite cannot run
     */
    public static byte[] composedDefinition(byte[] reference) {
        try {
            return patch(reference);
        } catch (Exception | LinkageError rejected) {
            return null;
        }
    }

    /**
     * @return {@code true} when the exact reviewed shader definition was transformed
     */
    public boolean patched() {
        return patched;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> prior, ProtectionDomain domain, byte[] bytes) {
        if (!OWNER.equals(name)) return null;
        if (prior != null || bytes == null || domain == null || domain.getCodeSource() == null) {
            report("DECLINED_DEFINITION");
            return null;
        }
        try {
            Path artifactPath = Path.of(domain.getCodeSource().getLocation().toURI());
            HostArtifactDigest artifact = HostArtifactDigest.cached(artifactPath);
            if (!admits(artifact)) {
                report("DECLINED_ARTIFACT_HASH");
                return null;
            }
            byte[] result = patch(bytes);
            patched = true;
            markPatched(loader, OWNER);
            NativeShaderUniformBridge.patched(true);
            report("PATCHED_"
                    + ReviewedHostArtifacts.cubismVersionOf(artifact)
                            .orElseThrow()
                            .replace(".", ""));
            return result;
        } catch (Exception | LinkageError rejected) {
            report("DECLINED_SHAPE_OR_SOURCE");
            return null;
        }
    }

    /**
     * Returns the reviewed GShader target-class table, keyed by Cubism version.
     *
     * @return the immutable {@code version -> target class names} table
     */
    public static Map<String, List<String>> targetClasses() {
        return REVIEWED_CLASSES;
    }

    static boolean admits(HostArtifactDigest artifact) {
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
                .map(ShaderUniformTransformer::admitsVersion)
                .orElse(false);
    }

    static boolean admitsVersion(String version) {
        final List<String> targets = REVIEWED_CLASSES.get(version);
        return targets != null && targets.contains(OWNER);
    }

    static byte[] patch(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().equals(OWNER)) throw new IllegalArgumentException("shader owner");
        // Derive uniform frames identically for both transform() and composedShape() without host classloading.
        ClassWriter writer = HostClassWriters.newFrameWriter(reader, null);
        int[] count = {0, 0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                        boolean drawing = name.equals(DRAW_METHOD) && descriptor.equals(DRAW_DESC);
                        boolean lifecycle = (name.equals(LIFECYCLE_METHODS[0]) && descriptor.equals(LIFECYCLE_DESCS[0]))
                                || (name.equals(LIFECYCLE_METHODS[1]) && descriptor.equals(LIFECYCLE_DESCS[1]));
                        if (drawing) {
                            return new DrawMethodRewriter(delegate, count);
                        }
                        if (lifecycle) {
                            return new LifecycleVisitor(delegate, count);
                        }
                        return delegate;
                    }
                },
                ClassReader.SKIP_FRAMES);
        if (count[0] != 1 || count[1] != 2)
            throw new IllegalArgumentException("shader hook cardinality " + java.util.Arrays.toString(count));
        return writer.toByteArray();
    }

    private void report(String code) {
        FatalErrors.reportSafely(diagnostic, "SHADER_UNIFORM_" + code);
    }

    private static final class LifecycleVisitor extends MethodVisitor {
        private final int[] count;

        LifecycleVisitor(final MethodVisitor delegate, final int[] count) {
            super(Opcodes.ASM9, delegate);
            this.count = count;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            count[1]++;
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, "invalidate", "(Ljava/lang/Object;)V", false);
        }
    }

    /**
     * Rewrites uniform-query call sites and replaces per-draw iterator/lambda allocations in
     * {@code GShader.preDraw_exe} when exact Kotlin loop shapes appear.
     */
    private static final class DrawMethodRewriter extends MethodVisitor {
        private enum Mode {
            IDLE,
            UNIFORM_HEADER,
            TEXTURE_LAMBDA
        }

        private final int[] count;
        private final List<Op> buffer = new ArrayList<>();
        private final List<TryCatchSpec> deferredHandlers = new ArrayList<>();
        private Mode mode = Mode.IDLE;
        private int step;

        private int iterableSlot = -1;
        private int dummySlot = -1;
        private int iteratorSlot = -1;
        private int rawEntrySlot = -1;
        private int entrySlot = -1;
        private Label loopTop;
        private Label loopEnd;
        private ActiveUniformLoop activeUniformLoop;

        private String textureLambdaOwner;
        private int textureShaderSlot = -1;
        private int textureCtxSlot = -1;
        private int textureGlSlot = -1;

        DrawMethodRewriter(final MethodVisitor delegate, final int[] count) {
            super(Opcodes.ASM9, delegate);
            this.count = count;
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            deferredHandlers.add(new TryCatchSpec(start, end, handler, type));
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            for (final TryCatchSpec handler : deferredHandlers) {
                super.visitTryCatchBlock(handler.start, handler.end, handler.handler, handler.type);
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (mode == Mode.UNIFORM_HEADER) {
                buffer.add(mv -> mv.visitInsn(opcode));
                advanceUniformInsn(opcode);
                return;
            }
            if (mode == Mode.TEXTURE_LAMBDA) {
                buffer.add(mv -> mv.visitInsn(opcode));
                if (step == 1 && opcode == Opcodes.DUP) {
                    step = 2;
                } else {
                    flushBuffer();
                }
                return;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            if (mode != Mode.IDLE) {
                flushBuffer();
            }
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (mode == Mode.UNIFORM_HEADER) {
                buffer.add(mv -> mv.visitVarInsn(opcode, varIndex));
                advanceUniformVar(opcode, varIndex);
                return;
            }
            if (mode == Mode.TEXTURE_LAMBDA) {
                buffer.add(mv -> mv.visitVarInsn(opcode, varIndex));
                advanceTextureVar(opcode, varIndex);
                return;
            }
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (mode == Mode.UNIFORM_HEADER) {
                buffer.add(mv -> mv.visitTypeInsn(opcode, type));
                advanceUniformType(opcode, type);
                return;
            }
            if (mode == Mode.TEXTURE_LAMBDA) {
                buffer.add(mv -> mv.visitTypeInsn(opcode, type));
                advanceTextureType(opcode, type);
                return;
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            if (mode != Mode.IDLE) {
                flushBuffer();
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            if (mode == Mode.UNIFORM_HEADER) {
                buffer.add(mv -> mv.visitMethodInsn(opcode, owner, name, descriptor, itf));
                advanceUniformMethod(opcode, owner, name, descriptor, itf);
                return;
            }
            if (mode == Mode.TEXTURE_LAMBDA) {
                buffer.add(mv -> mv.visitMethodInsn(opcode, owner, name, descriptor, itf));
                advanceTextureMethod(opcode, owner, name, descriptor, itf);
                return;
            }
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals(GL_OWNER)
                    && name.equals(QUERY_METHOD)
                    && descriptor.equals(QUERY_DESC)) {
                count[0]++;
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC, BRIDGE_OWNER, BRIDGE_LOOKUP, BRIDGE_LOOKUP_DESCRIPTOR, false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            if (activeUniformLoop == null
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && owner.equals(GMATERIAL_OWNER)
                    && name.equals(GET_UNIFORM_MAP_METHOD)
                    && descriptor.equals(HASH_MAP_GETTER_DESC)) {
                mode = Mode.UNIFORM_HEADER;
                step = 0;
            } else if (iterableSlot >= 0
                    && activeUniformLoop == null
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && owner.equals(GMATERIAL_OWNER)
                    && name.equals(GET_OPTIONAL_TEXTURES_METHOD)
                    && descriptor.equals(HASH_MAP_GETTER_DESC)) {
                mode = Mode.TEXTURE_LAMBDA;
                step = 0;
            }
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {
            if (mode == Mode.TEXTURE_LAMBDA) {
                buffer.add(mv ->
                        mv.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments));
                if (step == 7 && BI_CONSUMER_ACCEPT.equals(name) && BI_CONSUMER_INDY_DESC.equals(descriptor)) {
                    step = 8;
                } else {
                    flushBuffer();
                }
                return;
            }
            if (mode != Mode.IDLE) {
                flushBuffer();
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (mode == Mode.UNIFORM_HEADER) {
                buffer.add(mv -> mv.visitJumpInsn(opcode, label));
                if (step == 14 && opcode == Opcodes.IFEQ) {
                    loopEnd = label;
                    step = 15;
                } else {
                    flushBuffer();
                }
                return;
            }
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            if (mode == Mode.UNIFORM_HEADER) {
                buffer.add(mv -> mv.visitLabel(label));
                if (step == 11) {
                    loopTop = label;
                    step = 12;
                } else {
                    flushBuffer();
                }
                return;
            }
            if (mode == Mode.TEXTURE_LAMBDA) {
                flushBuffer();
            }
            if (activeUniformLoop != null && label == activeUniformLoop.loopEnd) {
                emitUniformLoopEnd();
                return;
            }
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            if (mode == Mode.UNIFORM_HEADER) {
                buffer.add(mv -> mv.visitLdcInsn(value));
                if (step == 2 && "".equals(value)) {
                    step = 3;
                } else {
                    flushBuffer();
                }
                return;
            }
            if (mode != Mode.IDLE) {
                flushBuffer();
            }
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            if (mode != Mode.IDLE) {
                flushBuffer();
            }
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            if (mode != Mode.IDLE) {
                flushBuffer();
            }
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            if (mode != Mode.IDLE) {
                flushBuffer();
            }
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            if (mode != Mode.IDLE) {
                flushBuffer();
            }
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitLineNumber(final int line, final Label start) {
            if (mode != Mode.IDLE) {
                buffer.add(mv -> mv.visitLineNumber(line, start));
                return;
            }
            super.visitLineNumber(line, start);
        }

        private void flushBuffer() {
            mode = Mode.IDLE;
            for (final Op op : buffer) {
                op.emit(mv);
            }
            buffer.clear();
        }

        private void advanceUniformMethod(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            if (step == 0
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && owner.equals(HASH_MAP_OWNER)
                    && name.equals(HASH_MAP_ENTRY_SET_METHOD)
                    && desc.equals(HASH_MAP_ENTRY_SET_DESC)) {
                step = 1;
            } else if (step == 3
                    && opcode == Opcodes.INVOKESTATIC
                    && owner.equals(INTRINSICS_OWNER)
                    && name.equals(CHECK_NOT_NULL_METHOD)
                    && desc.equals(CHECK_NOT_NULL_DESC)) {
                step = 4;
            } else if (step == 9
                    && itf
                    && opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals(ITERABLE_OWNER)
                    && name.equals(ITERATOR_METHOD)
                    && desc.equals(ITERATOR_METHOD_DESC)) {
                step = 10;
            } else if (step == 13
                    && itf
                    && opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals(ITERATOR_OWNER)
                    && name.equals(HAS_NEXT_METHOD)
                    && desc.equals(HAS_NEXT_DESC)) {
                step = 14;
            } else if (step == 16
                    && itf
                    && opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals(ITERATOR_OWNER)
                    && name.equals(NEXT_METHOD)
                    && desc.equals(NEXT_DESC)) {
                step = 17;
            } else {
                flushBuffer();
            }
        }

        private void advanceUniformInsn(final int opcode) {
            if (step == 1 && opcode == Opcodes.DUP) {
                step = 2;
            } else if (step == 6 && opcode == Opcodes.ICONST_0) {
                step = 7;
            } else {
                flushBuffer();
            }
        }

        private void advanceUniformType(final int opcode, final String type) {
            if ((step == 1 || step == 4) && opcode == Opcodes.CHECKCAST && ITERABLE_OWNER.equals(type)) {
                step = 5;
            } else if (step == 19 && opcode == Opcodes.CHECKCAST && MAP_ENTRY_OWNER.equals(type)) {
                step = 20;
            } else {
                flushBuffer();
            }
        }

        private void advanceUniformVar(final int opcode, final int varIndex) {
            if ((step == 1 || step == 4 || step == 5) && opcode == Opcodes.ASTORE) {
                iterableSlot = varIndex;
                step = 6;
            } else if (step == 7 && opcode == Opcodes.ISTORE) {
                dummySlot = varIndex;
                step = 8;
            } else if (step == 8 && opcode == Opcodes.ALOAD && varIndex == iterableSlot) {
                step = 9;
            } else if (step == 10 && opcode == Opcodes.ASTORE) {
                iteratorSlot = varIndex;
                step = 11;
            } else if (step == 12 && opcode == Opcodes.ALOAD && varIndex == iteratorSlot) {
                step = 13;
            } else if (step == 15 && opcode == Opcodes.ALOAD && varIndex == iteratorSlot) {
                step = 16;
            } else if (step == 17 && opcode == Opcodes.ASTORE) {
                rawEntrySlot = varIndex;
                step = 18;
            } else if (step == 18 && opcode == Opcodes.ALOAD && varIndex == rawEntrySlot) {
                step = 19;
            } else if (step == 20 && opcode == Opcodes.ASTORE) {
                entrySlot = varIndex;
                emitUniformLoopStart();
            } else {
                flushBuffer();
            }
        }

        private void emitUniformLoopStart() {
            buffer.clear();
            mode = Mode.IDLE;

            final Label hasEntries = new Label();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label tryHandler = new Label();
            final Label popAndRelease = new Label();
            final Label afterLoop = new Label();

            activeUniformLoop = new ActiveUniformLoop(loopEnd, tryEnd, tryHandler, afterLoop, popAndRelease);

            // Stack has HashMap from getUniformMap$core(). Skip bridge calls when empty.
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, HASH_MAP_OWNER, HASH_MAP_SIZE_METHOD, HASH_MAP_SIZE_DESC, false);
            super.visitJumpInsn(Opcodes.IFNE, hasEntries);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, afterLoop);

            super.visitLabel(hasEntries);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, BRIDGE_BORROW_METHOD, BRIDGE_BORROW_DESC, false);
            super.visitVarInsn(Opcodes.ASTORE, iterableSlot);

            super.visitTryCatchBlock(tryStart, tryEnd, tryHandler, null);
            super.visitLabel(tryStart);
            super.visitInsn(Opcodes.ICONST_M1);
            super.visitVarInsn(Opcodes.ISTORE, dummySlot);
            super.visitVarInsn(Opcodes.ALOAD, iterableSlot);
            super.visitInsn(Opcodes.ARRAYLENGTH);
            super.visitVarInsn(Opcodes.ISTORE, iteratorSlot);

            // Reuse original loopTop so body continue edges advance the array index automatically.
            super.visitLabel(loopTop);
            super.visitIincInsn(dummySlot, 1);
            super.visitVarInsn(Opcodes.ILOAD, dummySlot);
            super.visitVarInsn(Opcodes.ILOAD, iteratorSlot);
            super.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);
            super.visitVarInsn(Opcodes.ALOAD, iterableSlot);
            super.visitVarInsn(Opcodes.ILOAD, dummySlot);
            super.visitInsn(Opcodes.AALOAD);
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, popAndRelease);
            super.visitVarInsn(Opcodes.ASTORE, entrySlot);
        }

        private void emitUniformLoopEnd() {
            final ActiveUniformLoop loop = activeUniformLoop;
            activeUniformLoop = null;

            super.visitJumpInsn(Opcodes.GOTO, loop.loopEnd);
            super.visitLabel(loop.popAndRelease);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(loop.loopEnd);
            super.visitLabel(loop.tryEnd);
            super.visitVarInsn(Opcodes.ALOAD, iterableSlot);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, BRIDGE_OWNER, BRIDGE_RELEASE_METHOD, BRIDGE_RELEASE_DESC, false);
            super.visitJumpInsn(Opcodes.GOTO, loop.afterLoop);

            super.visitLabel(loop.tryHandler);
            super.visitVarInsn(Opcodes.ASTORE, rawEntrySlot);
            super.visitVarInsn(Opcodes.ALOAD, iterableSlot);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, BRIDGE_OWNER, BRIDGE_RELEASE_METHOD, BRIDGE_RELEASE_DESC, false);
            super.visitVarInsn(Opcodes.ALOAD, rawEntrySlot);
            super.visitInsn(Opcodes.ATHROW);

            super.visitLabel(loop.afterLoop);
        }

        private void advanceTextureType(final int opcode, final String type) {
            if (step == 0 && opcode == Opcodes.NEW && type.startsWith(SHADER_PACKAGE_PREFIX)) {
                textureLambdaOwner = type;
                step = 1;
            } else if (step == 6 && opcode == Opcodes.CHECKCAST && FUNCTION2_OWNER.equals(type)) {
                step = 7;
            } else {
                flushBuffer();
            }
        }

        private void advanceTextureVar(final int opcode, final int varIndex) {
            if (opcode != Opcodes.ALOAD) {
                flushBuffer();
                return;
            }
            if (step == 2) {
                textureShaderSlot = varIndex;
                step = 3;
            } else if (step == 3) {
                textureCtxSlot = varIndex;
                step = 4;
            } else if (step == 4) {
                textureGlSlot = varIndex;
                step = 5;
            } else {
                flushBuffer();
            }
        }

        private void advanceTextureMethod(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            if (step == 5
                    && opcode == Opcodes.INVOKESPECIAL
                    && owner.equals(textureLambdaOwner)
                    && name.equals(CONSTRUCTOR_NAME)
                    && desc.equals(TEXTURE_LAMBDA_INIT_DESC)) {
                step = 6;
            } else if (step == 8
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && !itf
                    && owner.equals(HASH_MAP_OWNER)
                    && name.equals(HASH_MAP_FOR_EACH_METHOD)
                    && desc.equals(HASH_MAP_FOR_EACH_DESC)) {
                emitTextureLoop();
            } else {
                flushBuffer();
            }
        }

        private void emitTextureLoop() {
            buffer.clear();
            mode = Mode.IDLE;

            final Label hasEntries = new Label();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label tryHandler = new Label();
            final Label texLoopTop = new Label();
            final Label popAndRelease = new Label();
            final Label releaseBlock = new Label();
            final Label afterLoop = new Label();

            // Stack has HashMap from getOptionalTextures$core(). Skip bridge calls when empty.
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, HASH_MAP_OWNER, HASH_MAP_SIZE_METHOD, HASH_MAP_SIZE_DESC, false);
            super.visitJumpInsn(Opcodes.IFNE, hasEntries);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, afterLoop);

            super.visitLabel(hasEntries);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, BRIDGE_BORROW_METHOD, BRIDGE_BORROW_DESC, false);
            super.visitVarInsn(Opcodes.ASTORE, iterableSlot);

            super.visitTryCatchBlock(tryStart, tryEnd, tryHandler, null);
            super.visitLabel(tryStart);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ISTORE, dummySlot);
            super.visitVarInsn(Opcodes.ALOAD, iterableSlot);
            super.visitInsn(Opcodes.ARRAYLENGTH);
            super.visitVarInsn(Opcodes.ISTORE, iteratorSlot);

            super.visitLabel(texLoopTop);
            super.visitVarInsn(Opcodes.ILOAD, dummySlot);
            super.visitVarInsn(Opcodes.ILOAD, iteratorSlot);
            super.visitJumpInsn(Opcodes.IF_ICMPGE, releaseBlock);
            super.visitVarInsn(Opcodes.ALOAD, iterableSlot);
            super.visitVarInsn(Opcodes.ILOAD, dummySlot);
            super.visitInsn(Opcodes.AALOAD);
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, popAndRelease);
            super.visitVarInsn(Opcodes.ASTORE, entrySlot);

            super.visitVarInsn(Opcodes.ALOAD, textureShaderSlot);
            super.visitVarInsn(Opcodes.ALOAD, textureCtxSlot);
            super.visitVarInsn(Opcodes.ALOAD, textureGlSlot);
            super.visitVarInsn(Opcodes.ALOAD, entrySlot);
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, MAP_ENTRY_OWNER, MAP_ENTRY_GET_VALUE_METHOD, NEXT_DESC, true);
            super.visitTypeInsn(Opcodes.CHECKCAST, GTEXTURE_OWNER);
            super.visitVarInsn(Opcodes.ALOAD, entrySlot);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, MAP_ENTRY_OWNER, MAP_ENTRY_GET_KEY_METHOD, NEXT_DESC, true);
            super.visitTypeInsn(Opcodes.CHECKCAST, STRING_OWNER);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, BIND_TEXTURE_METHOD, BIND_TEXTURE_DESC, false);

            super.visitIincInsn(dummySlot, 1);
            super.visitJumpInsn(Opcodes.GOTO, texLoopTop);

            super.visitLabel(popAndRelease);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(releaseBlock);
            super.visitLabel(tryEnd);
            super.visitVarInsn(Opcodes.ALOAD, iterableSlot);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, BRIDGE_OWNER, BRIDGE_RELEASE_METHOD, BRIDGE_RELEASE_DESC, false);
            super.visitJumpInsn(Opcodes.GOTO, afterLoop);

            super.visitLabel(tryHandler);
            super.visitVarInsn(Opcodes.ASTORE, rawEntrySlot);
            super.visitVarInsn(Opcodes.ALOAD, iterableSlot);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, BRIDGE_OWNER, BRIDGE_RELEASE_METHOD, BRIDGE_RELEASE_DESC, false);
            super.visitVarInsn(Opcodes.ALOAD, rawEntrySlot);
            super.visitInsn(Opcodes.ATHROW);

            super.visitLabel(afterLoop);
        }

        private interface Op {
            void emit(MethodVisitor mv);
        }

        private record ActiveUniformLoop(
                Label loopEnd, Label tryEnd, Label tryHandler, Label afterLoop, Label popAndRelease) {}

        private record TryCatchSpec(Label start, Label end, Label handler, String type) {}
    }
}

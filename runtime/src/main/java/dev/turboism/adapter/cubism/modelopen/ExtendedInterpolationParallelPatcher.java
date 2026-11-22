package dev.turboism.adapter.cubism.modelopen;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ICONST_3;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IF_ICMPNE;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.RETURN;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Parallelizes initial {@code CExtendedInterpolationExtension.setupFormStructure} computation during
 * first-time {@code CModelSource.setup()} without modifying {@code CModelSource.class}.
 *
 * <p>Triggers parallel computation at the tail of {@code CGameMotionSet.a(CModelSource)}, elides
 * already-computed extensions in {@code CExtendedInterpolationExtension.setupFormStructure$default},
 * and silences Cubism's non-thread-safe stopwatch utility ({@code com/live2d/util/{g,h}}) on worker
 * threads.</p>
 */
final class ExtendedInterpolationParallelPatcher {

    static final String GAME_MOTION_SET = "com/live2d/cubism/doc/gameData/motions/CGameMotionSet";
    static final String EXT_INTERP_EXTENSION =
            "com/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension";
    private static final String UTIL_PREFIX = "com/live2d/util/";

    private static final String MOTION_SETUP_NAME = "a";
    private static final String MOTION_SETUP_DESC = "(Lcom/live2d/cubism/doc/model/CModelSource;)V";
    private static final String SETUP_DEFAULT_NAME = "setupFormStructure$default";
    private static final String SETUP_DEFAULT_DESC =
            "(Lcom/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension;"
                    + "ZZILjava/lang/Object;)V";
    private static final String DECIMAL_FORMAT_DESC = "Ljava/text/DecimalFormat;";
    private static final String HASHMAP_DESC = "Ljava/util/HashMap;";
    private static final String STOPWATCH_START_DESC = "(Ljava/lang/String;)V";
    private static final String STOPWATCH_END_DESC = "(Ljava/lang/String;JJ)V";

    private static final String BRIDGE_INTERNAL = Type.getInternalName(ExtendedInterpolationParallelBridge.class);
    private static final String PRECOMPUTE_METHOD = "precomputeForFirstSetup";
    private static final String CONSUME_METHOD = "consumePrecomputed";
    private static final String IS_WORKER_METHOD = "isWorkerThread";
    private static final String OBJECT_VOID_DESC = "(Ljava/lang/Object;)V";
    private static final String OBJECT_BOOL_DESC = "(Ljava/lang/Object;)Z";
    private static final String BOOL_DESC = "()Z";

    private ExtendedInterpolationParallelPatcher() {}

    static boolean isSupportedTarget(final String internalName) {
        if (GAME_MOTION_SET.equals(internalName) || EXT_INTERP_EXTENSION.equals(internalName)) {
            return true;
        }
        return internalName != null
                && internalName.startsWith(UTIL_PREFIX)
                && internalName.length() == UTIL_PREFIX.length() + 1;
    }

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (GAME_MOTION_SET.equals(internalName)) {
            return patchGameMotionSet(loader, classfileBuffer);
        }
        if (EXT_INTERP_EXTENSION.equals(internalName)) {
            return patchExtendedInterpolationExtension(loader, classfileBuffer);
        }
        if (internalName != null
                && internalName.startsWith(UTIL_PREFIX)
                && internalName.length() == UTIL_PREFIX.length() + 1) {
            return patchStopwatchUtil(loader, classfileBuffer);
        }
        return null;
    }

    private static byte[] patchGameMotionSet(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, BRIDGE_INTERNAL)
                || !ModelOpenClassWriters.hasDeclaredMethod(reader, MOTION_SETUP_NAME, MOTION_SETUP_DESC)) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!MOTION_SETUP_NAME.equals(name) || !MOTION_SETUP_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitInsn(final int opcode) {
                                if (opcode == RETURN) {
                                    patched[0] = true;
                                    super.visitVarInsn(ALOAD, 1);
                                    super.visitMethodInsn(
                                            INVOKESTATIC, BRIDGE_INTERNAL, PRECOMPUTE_METHOD, OBJECT_VOID_DESC, false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchExtendedInterpolationExtension(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, BRIDGE_INTERNAL)
                || !ModelOpenClassWriters.hasDeclaredMethod(reader, SETUP_DEFAULT_NAME, SETUP_DEFAULT_DESC)) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!SETUP_DEFAULT_NAME.equals(name) || !SETUP_DEFAULT_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                patched[0] = true;
                                final Label proceed = new Label();
                                super.visitVarInsn(ILOAD, 3);
                                super.visitInsn(ICONST_3);
                                super.visitJumpInsn(IF_ICMPNE, proceed);
                                super.visitVarInsn(ALOAD, 0);
                                super.visitMethodInsn(
                                        INVOKESTATIC, BRIDGE_INTERNAL, CONSUME_METHOD, OBJECT_BOOL_DESC, false);
                                super.visitJumpInsn(IFEQ, proceed);
                                super.visitInsn(RETURN);
                                super.visitLabel(proceed);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchStopwatchUtil(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        final StopwatchScan scan = new StopwatchScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        final boolean isStartOrEnd =
                                ("a".equals(name) || "b".equals(name)) && STOPWATCH_START_DESC.equals(desc);
                        final boolean isThresholdEnd = "a".equals(name) && STOPWATCH_END_DESC.equals(desc);
                        if (!isStartOrEnd && !isThresholdEnd) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                patched[0] = true;
                                final Label proceed = new Label();
                                super.visitMethodInsn(
                                        INVOKESTATIC, BRIDGE_INTERNAL, IS_WORKER_METHOD, BOOL_DESC, false);
                                super.visitJumpInsn(IFEQ, proceed);
                                super.visitInsn(RETURN);
                                super.visitLabel(proceed);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static final class StopwatchScan extends ClassVisitor {
        private boolean hasDecimalFormat;
        private boolean hasHashMap;
        private boolean hasStartMethod;
        private boolean hasThresholdEndMethod;
        private boolean alreadyPatched;

        StopwatchScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasDecimalFormat && hasHashMap && hasStartMethod && hasThresholdEndMethod;
        }

        @Override
        public FieldVisitor visitField(
                final int access, final String name, final String desc, final String sig, final Object value) {
            if (DECIMAL_FORMAT_DESC.equals(desc)) {
                hasDecimalFormat = true;
            } else if (HASHMAP_DESC.equals(desc)) {
                hasHashMap = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if ("a".equals(name) && STOPWATCH_START_DESC.equals(desc)) {
                hasStartMethod = true;
            } else if ("a".equals(name) && STOPWATCH_END_DESC.equals(desc)) {
                hasThresholdEndMethod = true;
            }
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (BRIDGE_INTERNAL.equals(owner)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }
}

package dev.turboism.adapter.cubism.modelopen;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.DUP2;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.POP2;
import static org.objectweb.asm.Opcodes.RETURN;

import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches document load boundaries, {@code CEUpdateManager.updateParameter}, and Parameter Palette
 * listener calls to coalesce redundant post-load structure rebuilds while keeping
 * {@code controller.a(Z)V} untouched for {@code ParameterRowRepaintTransformer}.
 */
final class ParameterPaletteCoalescePatcher {

    static final String UPDATE_MANAGER = "com/live2d/cubism/CEUpdateManager";
    static final String DOC_LOAD_CONTROLLER = "com/live2d/cubism/appCtrlImpl/O";
    static final String PARAM_PALETTE_PREFIX = "com/live2d/cubism/view/palette/parameter/";

    private static final Set<String> CONTROLLER_OWNERS = Set.of(
            "com/live2d/cubism/view/palette/parameter/k",
            "com/live2d/cubism/view/palette/parameter/m",
            "com/live2d/cubism/view/palette/parameter/s");

    private static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/modelopen/ParameterPaletteCoalesceBridge";
    private static final String UPDATE_PARAM_NAME = "updateParameter";
    private static final String UPDATE_PARAM_DESC = "(Z)V";
    private static final String DOC_LOAD_NAME = "a";
    private static final String DOC_LOAD_DESC_500 =
            "(Lcom/live2d/cubism/CEAppCtrl;Ljava/io/File;ZLcom/live2d/util/a/a;)V";
    private static final String DOC_LOAD_DESC_MODERN =
            "(Lcom/live2d/cubism/CEAppCtrl;Ljava/io/File;ZLcom/live2d/util/a/a;Z)V";
    private static final String CONTROLLER_UPDATE_NAME = "a";
    private static final String CONTROLLER_UPDATE_DESC = "(Z)V";

    private ParameterPaletteCoalescePatcher() {}

    static boolean isSupportedTarget(final String internalName) {
        return UPDATE_MANAGER.equals(internalName)
                || DOC_LOAD_CONTROLLER.equals(internalName)
                || (internalName != null && internalName.startsWith(PARAM_PALETTE_PREFIX));
    }

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (UPDATE_MANAGER.equals(internalName)) {
            return patchUpdateManager(loader, classfileBuffer);
        }
        if (DOC_LOAD_CONTROLLER.equals(internalName)) {
            return patchDocLoadController(loader, classfileBuffer);
        }
        if (internalName != null && internalName.startsWith(PARAM_PALETTE_PREFIX)) {
            return patchPaletteListenerCallers(internalName, loader, classfileBuffer);
        }
        return null;
    }

    private static byte[] patchUpdateManager(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, BRIDGE_OWNER)) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String desc,
                    final String signature,
                    final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!UPDATE_PARAM_NAME.equals(name) || !UPDATE_PARAM_DESC.equals(desc)) {
                    return mv;
                }
                return new MethodVisitor(ASM9, mv) {
                    private boolean rewroteForceJump;

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        final Label continueLabel = new Label();
                        super.visitVarInsn(ALOAD, 0);
                        super.visitVarInsn(ILOAD, 1);
                        super.visitMethodInsn(
                                INVOKESTATIC,
                                BRIDGE_OWNER,
                                "tryInterceptUpdateParameter",
                                "(Ljava/lang/Object;Z)Z",
                                false);
                        super.visitJumpInsn(IFEQ, continueLabel);
                        super.visitInsn(RETURN);
                        super.visitLabel(continueLabel);
                    }

                    @Override
                    public void visitJumpInsn(final int opcode, final Label label) {
                        if (!rewroteForceJump && opcode == IFEQ) {
                            rewroteForceJump = true;
                            final Label normalBranch = new Label();
                            super.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, "isDirectFlushActive", "()Z", false);
                            super.visitJumpInsn(IFEQ, normalBranch);
                            super.visitInsn(POP);
                            super.visitJumpInsn(GOTO, label);
                            super.visitLabel(normalBranch);
                            super.visitJumpInsn(IFEQ, label);
                            return;
                        }
                        super.visitJumpInsn(opcode, label);
                    }

                    @Override
                    public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String mName,
                            final String mDesc,
                            final boolean isInterface) {
                        if (opcode == INVOKEVIRTUAL
                                && CONTROLLER_OWNERS.contains(owner)
                                && CONTROLLER_UPDATE_NAME.equals(mName)
                                && CONTROLLER_UPDATE_DESC.equals(mDesc)) {
                            patched[0] = true;
                            super.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, "effectiveForceArgument", "(Z)Z", false);
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchDocLoadController(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, BRIDGE_OWNER)) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String desc,
                    final String signature,
                    final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!DOC_LOAD_NAME.equals(name)
                        || (!DOC_LOAD_DESC_MODERN.equals(desc) && !DOC_LOAD_DESC_500.equals(desc))) {
                    return mv;
                }
                patched[0] = true;
                return new MethodVisitor(ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, "beginDocumentLoad", "()V", false);
                    }

                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == RETURN || opcode == ATHROW) {
                            super.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, "endDocumentLoad", "()V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchPaletteListenerCallers(
            final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, BRIDGE_OWNER)) {
            return null;
        }
        // Scan first so non-calling UI widgets in palette/parameter/ avoid frame rewriting overhead.
        final boolean[] hasTargetCall = new boolean[1];
        final boolean isController = CONTROLLER_OWNERS.contains(internalName);
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (isController && !isSafeControllerDeferMethod(name)) {
                            return null;
                        }
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (opcode == INVOKEVIRTUAL
                                        && CONTROLLER_OWNERS.contains(owner)
                                        && CONTROLLER_UPDATE_NAME.equals(mName)
                                        && CONTROLLER_UPDATE_DESC.equals(mDesc)) {
                                    hasTargetCall[0] = true;
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!hasTargetCall[0]) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String desc,
                    final String signature,
                    final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (isController && !isSafeControllerDeferMethod(name)) {
                    return mv;
                }
                return new MethodVisitor(ASM9, mv) {
                    @Override
                    public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String mName,
                            final String mDesc,
                            final boolean isInterface) {
                        if (opcode == INVOKEVIRTUAL
                                && CONTROLLER_OWNERS.contains(owner)
                                && CONTROLLER_UPDATE_NAME.equals(mName)
                                && CONTROLLER_UPDATE_DESC.equals(mDesc)) {
                            patched[0] = true;
                            final Label callOriginal = new Label();
                            final Label callDone = new Label();
                            super.visitInsn(DUP2);
                            super.visitMethodInsn(
                                    INVOKESTATIC,
                                    BRIDGE_OWNER,
                                    "tryDeferControllerUpdate",
                                    "(Ljava/lang/Object;Z)Z",
                                    false);
                            super.visitJumpInsn(IFEQ, callOriginal);
                            super.visitInsn(POP2);
                            super.visitJumpInsn(GOTO, callDone);
                            super.visitLabel(callOriginal);
                            super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                            super.visitLabel(callDone);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static boolean isSafeControllerDeferMethod(final String methodName) {
        // Leave a(Z)V untouched for ParameterRowRepaintTransformer; synchronous row actions R/S/b stay immediate.
        return "ae".equals(methodName) || "c".equals(methodName);
    }
}

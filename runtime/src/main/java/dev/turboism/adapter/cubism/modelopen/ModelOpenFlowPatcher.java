package dev.turboism.adapter.cubism.modelopen;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches model-open lifecycle classes to trigger background PNG pre-decoding on first setup and
 * safely elide the redundant immediate second {@code CModelSource.setup()} during document open.
 */
final class ModelOpenFlowPatcher {

    static final String MODEL_SOURCE = "com/live2d/cubism/doc/model/CModelSource";
    static final String MODEL_SOURCE_COMPANION = "com/live2d/cubism/doc/model/CModelSource$a";
    static final String DOCUMENT_LOADER = "com/live2d/cubism/doc/a/b/b";

    private static final String SETUP_METHOD_NAME = "setup";
    private static final String SETUP_METHOD_DESC = "()V";
    private static final String NATIVE_SETUP_NAME = "turboism$nativeSetup";
    private static final String FIELD_SETUP_FLAG = "setupFlag";
    private static final String FIELD_TEXTURE_MANAGER = "textureManager";
    private static final String TEXTURE_MANAGER_DESC = "Lcom/live2d/cubism/doc/model/texture/CTextureManager;";

    private static final String COMPANION_LOAD_NAME = "a";
    private static final String COMPANION_LOAD_DESC = "(Ljava/io/File;Z)Lcom/live2d/cubism/doc/model/CModelSource;";
    private static final String DOC_LOAD_NAME = "b";
    private static final String DOC_LOAD_DESC = "(Ljava/io/File;)V";

    private static final String IMAGE_DECODE_BRIDGE = "dev/turboism/adapter/cubism/modelopen/ImageDecodeBridge";
    private static final String PREDECODE_METHOD = "scheduleTextureManagerPredecode";
    private static final String OBJECT_VOID_DESC = "(Ljava/lang/Object;)V";

    private static final String FLOW_BRIDGE = "dev/turboism/adapter/cubism/modelopen/ModelOpenFlowBridge";
    private static final String BEGIN_FACTORY_OPEN = "beginFactoryOpen";
    private static final String MARK_FACTORY_COMPLETED = "markFactorySetupCompleted";
    private static final String CONSUME_FACTORY_COMPLETED = "consumeFactorySetupCompleted";
    private static final String OBJECT_BOOL_DESC = "(Ljava/lang/Object;)Z";

    private ModelOpenFlowPatcher() {}

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (MODEL_SOURCE.equals(internalName)) {
            return patchModelSource(loader, classfileBuffer);
        }
        if (MODEL_SOURCE_COMPANION.equals(internalName)) {
            return patchModelSourceCompanion(loader, classfileBuffer);
        }
        if (DOCUMENT_LOADER.equals(internalName)) {
            return patchDocumentLoader(loader, classfileBuffer);
        }
        return null;
    }

    private static byte[] patchModelSource(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.hasDeclaredMethod(reader, NATIVE_SETUP_NAME, SETUP_METHOD_DESC)
                || !ModelOpenClassWriters.hasDeclaredMethod(reader, SETUP_METHOD_NAME, SETUP_METHOD_DESC)) {
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
                if (SETUP_METHOD_NAME.equals(name) && SETUP_METHOD_DESC.equals(desc)) {
                    patched[0] = true;
                    emitModelSourceSetupWrapper(super.visitMethod(access, name, desc, signature, exceptions));
                    return super.visitMethod(ACC_PRIVATE | ACC_FINAL, NATIVE_SETUP_NAME, desc, signature, exceptions);
                }
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static void emitModelSourceSetupWrapper(final MethodVisitor mv) {
        final Label skipPredecode = new Label();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, MODEL_SOURCE, FIELD_SETUP_FLAG, "Z");
        mv.visitJumpInsn(IFNE, skipPredecode);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, MODEL_SOURCE, FIELD_TEXTURE_MANAGER, TEXTURE_MANAGER_DESC);
        mv.visitMethodInsn(INVOKESTATIC, IMAGE_DECODE_BRIDGE, PREDECODE_METHOD, OBJECT_VOID_DESC, false);
        mv.visitLabel(skipPredecode);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, MODEL_SOURCE, NATIVE_SETUP_NAME, SETUP_METHOD_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] patchModelSourceCompanion(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, FLOW_BRIDGE)) {
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
                if (!COMPANION_LOAD_NAME.equals(name) || !COMPANION_LOAD_DESC.equals(desc)) {
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
                                && MODEL_SOURCE.equals(owner)
                                && SETUP_METHOD_NAME.equals(mName)
                                && SETUP_METHOD_DESC.equals(mDesc)) {
                            patched[0] = true;
                            super.visitInsn(DUP);
                            super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                            super.visitMethodInsn(
                                    INVOKESTATIC, FLOW_BRIDGE, MARK_FACTORY_COMPLETED, OBJECT_VOID_DESC, false);
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

    private static byte[] patchDocumentLoader(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.callsOwner(reader, FLOW_BRIDGE)) {
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
                if (!DOC_LOAD_NAME.equals(name) || !DOC_LOAD_DESC.equals(desc)) {
                    return mv;
                }
                return new MethodVisitor(ASM9, mv) {
                    private boolean sawFactoryCall;

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(INVOKESTATIC, FLOW_BRIDGE, BEGIN_FACTORY_OPEN, "()V", false);
                    }

                    @Override
                    public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String mName,
                            final String mDesc,
                            final boolean isInterface) {
                        if (opcode == INVOKESTATIC && MODEL_SOURCE_COMPANION.equals(owner)) {
                            this.sawFactoryCall = true;
                        } else if (this.sawFactoryCall
                                && opcode == INVOKEVIRTUAL
                                && MODEL_SOURCE.equals(owner)
                                && SETUP_METHOD_NAME.equals(mName)
                                && SETUP_METHOD_DESC.equals(mDesc)) {
                            this.sawFactoryCall = false;
                            patched[0] = true;
                            final Label skipSetup = new Label();
                            final Label afterSetup = new Label();
                            super.visitInsn(DUP);
                            super.visitMethodInsn(
                                    INVOKESTATIC, FLOW_BRIDGE, CONSUME_FACTORY_COMPLETED, OBJECT_BOOL_DESC, false);
                            super.visitJumpInsn(IFNE, skipSetup);
                            super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                            super.visitJumpInsn(GOTO, afterSetup);
                            super.visitLabel(skipSetup);
                            super.visitInsn(POP);
                            super.visitLabel(afterSetup);
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
}

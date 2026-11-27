package dev.turboism.adapter.cubism.modelopen;

import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.POP;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches model-open lifecycle callers to trigger background PNG pre-decoding after factory setup
 * and elide the redundant immediate second {@code CModelSource.setup()} during document open.
 */
final class ModelOpenFlowPatcher {

    static final String MODEL_SOURCE = "com/live2d/cubism/doc/model/CModelSource";
    static final String MODEL_SOURCE_COMPANION = "com/live2d/cubism/doc/model/CModelSource$a";
    static final String DOCUMENT_LOADER = "com/live2d/cubism/doc/a/b/b";

    private static final String SETUP_METHOD_NAME = "setup";
    private static final String SETUP_METHOD_DESC = "()V";

    private static final String COMPANION_LOAD_NAME = "a";
    private static final String COMPANION_LOAD_DESC = "(Ljava/io/File;Z)Lcom/live2d/cubism/doc/model/CModelSource;";
    private static final String DOC_LOAD_NAME = "b";
    private static final String DOC_LOAD_DESC = "(Ljava/io/File;)V";

    private static final String OBJECT_VOID_DESC = "(Ljava/lang/Object;)V";

    private static final String FLOW_BRIDGE = "dev/turboism/adapter/cubism/modelopen/ModelOpenFlowBridge";
    private static final String BEGIN_FACTORY_OPEN = "beginFactoryOpen";
    private static final String END_DOCUMENT_LOAD = "endDocumentLoad";
    private static final String MARK_FACTORY_COMPLETED = "markFactorySetupCompleted";
    private static final String CONSUME_FACTORY_COMPLETED = "consumeFactorySetupCompleted";
    private static final String OBJECT_BOOL_DESC = "(Ljava/lang/Object;)Z";

    private ModelOpenFlowPatcher() {}

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (MODEL_SOURCE_COMPANION.equals(internalName)) {
            return patchModelSourceCompanion(loader, classfileBuffer);
        }
        if (DOCUMENT_LOADER.equals(internalName)) {
            return patchDocumentLoader(loader, classfileBuffer);
        }
        return null;
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
                    public void visitInsn(final int opcode) {
                        if (opcode == org.objectweb.asm.Opcodes.RETURN) {
                            super.visitMethodInsn(INVOKESTATIC, FLOW_BRIDGE, END_DOCUMENT_LOAD, "()V", false);
                        }
                        super.visitInsn(opcode);
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

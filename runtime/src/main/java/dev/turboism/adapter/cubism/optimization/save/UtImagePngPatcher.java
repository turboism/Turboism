package dev.turboism.adapter.cubism.optimization.save;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.IRETURN;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches {@code jp/noids/graphics/UtImage.saveImage_exe} to route supported lossless PNG writes
 * through {@link ImageSaveFastPngSupport}, coexisting with read-path patches on {@code UtImage}.
 */
public final class UtImagePngPatcher {

    public static final String TARGET = "jp/noids/graphics/UtImage";

    private static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/optimization/save/ImageSaveFastPngSupport";
    private static final String TRY_SAVE_PNG_NAME = "trySavePng";
    private static final String TRY_SAVE_PNG_DESC =
            "(Ljava/awt/image/BufferedImage;Ljava/lang/Object;Ljava/lang/String;)Z";

    private static final String SAVE_IMAGE_EXE_NAME = "saveImage_exe";
    private static final String SAVE_IMAGE_EXE_DESC =
            "(Ljava/awt/image/BufferedImage;Ljava/lang/Object;Ljava/lang/String;FLjp/noids/framework/e/c;)Z";

    private UtImagePngPatcher() {}

    /**
     * Prepends fast PNG dispatch to {@code UtImage.saveImage_exe} when not yet patched.
     *
     * @param internalName JVM internal class name
     * @param loader target host class loader, nullable
     * @param classfileBuffer input class bytes
     * @return patched class bytes, or {@code null} when not applicable or already patched
     */
    public static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!TARGET.equals(internalName) || classfileBuffer == null) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (SavePerfClassWriters.callsOwner(reader, BRIDGE_OWNER)
                || !SavePerfClassWriters.hasDeclaredMethod(reader, SAVE_IMAGE_EXE_NAME, SAVE_IMAGE_EXE_DESC)) {
            return null;
        }
        final ClassWriter writer = SavePerfClassWriters.newFrameWriter(reader, loader);
        final boolean[] modified = new boolean[1];
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
                        if (!SAVE_IMAGE_EXE_NAME.equals(name) || !SAVE_IMAGE_EXE_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                final Label fallback = new Label();
                                super.visitVarInsn(ALOAD, 0);
                                super.visitVarInsn(ALOAD, 1);
                                super.visitVarInsn(ALOAD, 2);
                                super.visitMethodInsn(
                                        INVOKESTATIC, BRIDGE_OWNER, TRY_SAVE_PNG_NAME, TRY_SAVE_PNG_DESC, false);
                                super.visitJumpInsn(IFEQ, fallback);
                                super.visitInsn(ICONST_1);
                                super.visitInsn(IRETURN);
                                super.visitLabel(fallback);
                                modified[0] = true;
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return modified[0] ? writer.toByteArray() : null;
    }
}

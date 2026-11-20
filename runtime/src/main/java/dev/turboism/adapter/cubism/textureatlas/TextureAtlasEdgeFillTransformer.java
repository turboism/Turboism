package dev.turboism.adapter.cubism.textureatlas;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Rewrites {@code jp/noids/graphics/h.a(BufferedImage, int)} to execute cache-friendly row-major edge fill.
 *
 * <p>Falls back directly to the native method body whenever the underlying raster buffer is not
 * {@code DataBufferInt}. Transformation is idempotent across chained instrumentation runs.</p>
 */
public final class TextureAtlasEdgeFillTransformer implements ClassFileTransformer, Opcodes {

    public static final String TARGET = "jp/noids/graphics/h";
    private static final String EDGE_FILL_METHOD = "a";
    private static final String EDGE_FILL_DESC = "(Ljava/awt/image/BufferedImage;I)V";
    private static final String HELPER_INTERNAL = Type.getInternalName(TextureAtlasEdgeFillDelegate.class);
    private static final String HELPER_METHOD = "tryEdgeFill";
    private static final String HELPER_DESC = "(Ljava/awt/image/BufferedImage;I)Z";
    private static final String BUFFER_HELPER = "jp/noids/graphics/f";
    private static final String BUFFER_EXTRACT = "c";
    private static final int FALLBACK_GREY_PIXEL = 0x0080_8080;

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        return transform(null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (classfileBuffer == null || classBeingRedefined != null || !TARGET.equals(className)) {
            return null;
        }
        return patch(classfileBuffer);
    }

    /** Patches {@code jp/noids/graphics/h} bytecode if it matches the reviewed structure. */
    public static byte[] patch(final byte[] bytes) {
        if (bytes == null) return null;
        final ClassReader reader = new ClassReader(bytes);
        if (!TARGET.equals(reader.getClassName())) return null;
        final ShapeScan scan = new ShapeScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new PatchVisitor(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static final class ShapeScan extends ClassVisitor {
        private boolean inTarget;
        private boolean alreadyPatched;
        private int methodHits;
        private int bufferExtractCalls;
        private int greyFillConstants;

        ShapeScan() {
            super(ASM9);
        }

        boolean matches() {
            return methodHits == 1 && bufferExtractCalls == 1 && greyFillConstants == 1;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            inTarget = (access & ACC_STATIC) != 0 && EDGE_FILL_METHOD.equals(name) && EDGE_FILL_DESC.equals(desc);
            if (!inTarget) return null;
            methodHits++;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode, final String owner, final String m, final String d, final boolean itf) {
                    if (opcode == INVOKESTATIC && BUFFER_HELPER.equals(owner) && BUFFER_EXTRACT.equals(m)) {
                        bufferExtractCalls++;
                    }
                    if (opcode == INVOKESTATIC && HELPER_INTERNAL.equals(owner) && HELPER_METHOD.equals(m)) {
                        alreadyPatched = true;
                    }
                }

                @Override
                public void visitLdcInsn(final Object value) {
                    if (Integer.valueOf(FALLBACK_GREY_PIXEL).equals(value)) {
                        greyFillConstants++;
                    }
                }
            };
        }
    }

    private static final class PatchVisitor extends ClassVisitor {
        PatchVisitor(final ClassWriter writer) {
            super(ASM9, writer);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
            if ((access & ACC_STATIC) == 0 || !EDGE_FILL_METHOD.equals(name) || !EDGE_FILL_DESC.equals(desc)) {
                return output;
            }
            return new MethodVisitor(ASM9, output) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    final Label fallback = new Label();
                    super.visitVarInsn(ALOAD, 0);
                    super.visitVarInsn(ILOAD, 1);
                    super.visitMethodInsn(INVOKESTATIC, HELPER_INTERNAL, HELPER_METHOD, HELPER_DESC, false);
                    super.visitJumpInsn(IFEQ, fallback);
                    super.visitInsn(RETURN);
                    super.visitLabel(fallback);
                    super.visitFrame(
                            F_NEW, 2, new Object[] {"java/awt/image/BufferedImage", INTEGER}, 0, new Object[0]);
                }
            };
        }
    }
}

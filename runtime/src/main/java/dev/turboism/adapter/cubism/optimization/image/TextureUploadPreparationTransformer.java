package dev.turboism.adapter.cubism.optimization.image;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Substitutes only the image argument of one reviewed native texture-creation call. */
public final class TextureUploadPreparationTransformer implements ClassFileTransformer {
    /** Exact 5.3.02 native texture factory. */
    public static final String TARGET = "com/live2d/graphics3d/shader/A";
    /** The profiled factory overload; the other overload remains untouched. */
    public static final String METHOD_DESCRIPTOR = "(Lcom/live2d/graphics3d/a;Lcom/live2d/graphics/CWritableImage;ILjava/lang/String;)Lkotlin/Pair;";
    private static final String CALLEE = "com/jogamp/opengl/util/texture/awt/AWTTextureIO";
    private static final String CALL_DESCRIPTOR = "(Lcom/jogamp/opengl/GLProfile;Ljava/awt/image/BufferedImage;Z)Lcom/jogamp/opengl/util/texture/Texture;";
    private final ClassLoader expectedLoader;
    private final Path artifact;
    private final List<String> reviewedShape;
    private volatile int matches;
    private volatile String failure;
    private volatile String beforeSha256;

    /** Reference class bytes must come from the installer's digest-pinned official artifact. */
    public TextureUploadPreparationTransformer(ClassLoader loader, Path artifact, byte[] reference) {
        expectedLoader = loader;
        this.artifact = artifact == null ? null : artifact.toAbsolutePath().normalize();
        reviewedShape = ReviewedMethodShape.read(reference, TARGET, "a", METHOD_DESCRIPTOR);
        if (reviewedShape == null) throw new IllegalArgumentException("reviewed texture factory absent");
    }

    @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                      ProtectionDomain domain, byte[] bytes) {
        if (loader != expectedLoader || !TARGET.equals(name) || bytes == null) return null;
        try {
            if (artifact != null && !artifact.equals(Path.of(domain.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) return null;
            if (!reviewedShape.equals(ReviewedMethodShape.read(bytes, TARGET, "a", METHOD_DESCRIPTOR))) {
                failure = "native texture factory body differs from reviewed artifact"; return null;
            }
            int[] sites = {0}, locals = {0};
            ClassReader reader = new ClassReader(bytes);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (!name.equals("a") || !desc.equals(METHOD_DESCRIPTOR)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int op, String owner, String method, String descriptor, boolean itf) { if (call(op, owner, method, descriptor)) sites[0]++; }
                        @Override public void visitMaxs(int stack, int localCount) { locals[0] = localCount; }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
            if (sites[0] != 1) {failure = "native texture factory call-site count differs"; return null;}
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return expectedLoader; }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    MethodVisitor original = super.visitMethod(access, name, desc, signature, exceptions);
                    return name.equals("a") && desc.equals(METHOD_DESCRIPTOR) ? new Preparation(original, locals[0]) : original;
                }
            }, ClassReader.EXPAND_FRAMES);
            byte[] output = writer.toByteArray();
            if (beforeSha256 == null) beforeSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            matches++;
            return output;
        } catch (Exception | LinkageError rejected) { failure = rejected.toString(); return null; }
    }

    /** Successful exact-owner transformations. */
    public int matches() { return matches; }
    /** First native input hash used for verified restoration. */
    public String beforeSha256() { return beforeSha256; }
    /** Last admission/transform failure. */
    public String failure() { return failure; }

    private static boolean call(int op, String owner, String name, String descriptor) {
        return op == Opcodes.INVOKESTATIC && owner.equals(CALLEE) && name.equals("newTexture") && descriptor.equals(CALL_DESCRIPTOR);
    }

    private static final class Preparation extends MethodVisitor {
        final int base;
        final List<Handler> handlers = new ArrayList<>();
        Preparation(MethodVisitor original, int base) {super(Opcodes.ASM9, original); this.base = base;}
        @Override public void visitTryCatchBlock(Label start, Label end, Label target, String type) {handlers.add(new Handler(start, end, target, type));}
        @Override public void visitMethodInsn(int op, String owner, String name, String descriptor, boolean itf) {
            if (!call(op, owner, name, descriptor)) {super.visitMethodInsn(op, owner, name, descriptor, itf); return;}
            // Keep the original profile/mipmap inputs and the original native call itself.
            super.visitVarInsn(Opcodes.ISTORE, base);
            super.visitVarInsn(Opcodes.ASTORE, base + 1);
            super.visitVarInsn(Opcodes.ASTORE, base + 2);
            Label start = new Label(), end = new Label(), handler = new Label(), discard = new Label(), invoke = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(TextureUploadPreparationBridge.CALLBACK_PROPERTY);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            super.visitInsn(Opcodes.DUP); super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BiFunction");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction");
            super.visitVarInsn(Opcodes.ALOAD, base + 1); super.visitVarInsn(Opcodes.ALOAD, base + 2);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            super.visitInsn(Opcodes.DUP); super.visitTypeInsn(Opcodes.INSTANCEOF, "java/awt/image/BufferedImage");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/awt/image/BufferedImage"); super.visitVarInsn(Opcodes.ASTORE, base + 3);
            for (String dimension : List.of("getWidth", "getHeight")) {
                super.visitVarInsn(Opcodes.ALOAD, base + 3);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/awt/image/BufferedImage", dimension, "()I", false);
                super.visitVarInsn(Opcodes.ALOAD, base + 1);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/awt/image/BufferedImage", dimension, "()I", false);
                super.visitJumpInsn(Opcodes.IF_ICMPNE, end);
            }
            super.visitVarInsn(Opcodes.ALOAD, base + 3); super.visitVarInsn(Opcodes.ASTORE, base + 1);
            super.visitLabel(end); super.visitJumpInsn(Opcodes.GOTO, invoke);
            super.visitLabel(discard); super.visitInsn(Opcodes.POP); super.visitJumpInsn(Opcodes.GOTO, invoke);
            super.visitLabel(handler); super.visitInsn(Opcodes.POP);
            super.visitLabel(invoke);
            super.visitVarInsn(Opcodes.ALOAD, base + 2); super.visitVarInsn(Opcodes.ALOAD, base + 1); super.visitVarInsn(Opcodes.ILOAD, base);
            super.visitMethodInsn(op, owner, name, descriptor, itf);
        }
        @Override public void visitMaxs(int stack, int locals) {
            for (Handler handler : handlers) super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(), handler.type());
            super.visitMaxs(stack, Math.max(locals, base + 4));
        }
    }
    private record Handler(Label start, Label end, Label target, String type) { }
}

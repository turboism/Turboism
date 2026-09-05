package dev.turboism.adapter.cubism.optimization.image;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exact-host PNG call-site substitution. Native streams, counters, resource ownership and
 * disposal remain untouched. The host bytecode references only JDK callback interfaces.
 */
public final class ImageArchiveReuseTransformer implements ClassFileTransformer {
    private static final String RESOURCE = "com/live2d/graphics/CImageResource";
    private static final String IMAGE = "com/live2d/graphics/CWritableImage";
    private static final String KEY = "turboism.image-archive-reuse.callback";
    private final ClassLoader expectedLoader;
    private final Path artifact;
    private final AtomicInteger matches = new AtomicInteger();
    private volatile String beforeSha256;
    private volatile String failure;

    /** Creates a transformer scoped to an attested host loader and artifact. */
    public ImageArchiveReuseTransformer(final ClassLoader loader, final Path artifact) {
        expectedLoader = loader;
        this.artifact = artifact == null ? null : artifact.toAbsolutePath().normalize();
    }

    @Override
    public byte[] transform(final Module module, final ClassLoader loader, final String name,
                            final Class<?> redefining, final ProtectionDomain domain, final byte[] bytes) {
        if (loader != expectedLoader || !RESOURCE.equals(name) || bytes == null) return null;
        if (artifact != null) {
            try {
                if (!artifact.equals(Path.of(domain.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) return null;
            } catch (Exception rejected) { return null; }
        }
        try {
            final ClassReader reader = new ClassReader(bytes);
            final int[] counts = new int[4];
            final int[] locals = new int[2];
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                    int target = target(method, desc);
                    if (target < 0) return null;
                    if ((access & (Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_STATIC)) != Opcodes.ACC_SYNCHRONIZED) return null;
                    counts[target]++;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int opcode,String owner,String field,String descriptor) {
                            if (target == 0 && opcode == Opcodes.PUTFIELD && owner.equals(RESOURCE)
                                && field.equals("image") && descriptor.equals("L"+IMAGE+";")) counts[2]++;
                        }
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf) {
                            if (target == 1 && isEncoder(opcode,owner,method,descriptor)) counts[3]++;
                        }
                        @Override public void visitMaxs(int stack,int localCount) { locals[target] = localCount; }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
            for (int count : counts) if (count != 1) {
                failure = "unexpected decode/archive call-site shape"; return null;
            }
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return expectedLoader; }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                    var delegate = super.visitMethod(access,method,desc,signature,exceptions);
                    int target = target(method,desc);
                    return target < 0 ? delegate : new Augmentation(delegate,target,locals[target]);
                }
            }, ClassReader.EXPAND_FRAMES);
            final byte[] output = writer.toByteArray();
            if (beforeSha256 == null) beforeSha256 = sha256(bytes);
            matches.incrementAndGet();
            return output;
        } catch (RuntimeException | LinkageError rejected) {
            failure = rejected.getClass().getName();
            return null;
        }
    }

    /** Number of successfully transformed exact owner definitions. */
    public int matches() { return matches.get(); }
    /** Hash of the first exact owner input bytes, for restoration verification. */
    public String beforeSha256() { return beforeSha256; }
    /** Last transformation failure, or null when there was none. */
    public String failure() { return failure; }

    private static int target(String method,String descriptor) {
        if (method.equals("getImage_exe") && descriptor.equals("()L"+IMAGE+";")) return 0;
        if (method.equals("archive") && descriptor.equals("()V")) return 1;
        return -1;
    }
    private static boolean isEncoder(int opcode,String owner,String method,String descriptor) {
        return opcode == Opcodes.INVOKEVIRTUAL && owner.equals(IMAGE)
            && method.equals("writeImageAsPng") && descriptor.equals("(Ljava/io/OutputStream;)V");
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static final class Augmentation extends MethodVisitor {
        private final int target;
        private final int base;
        private final List<Handler> originalHandlers = new ArrayList<>();
        Augmentation(MethodVisitor delegate,int target,int base) { super(Opcodes.ASM9,delegate);this.target=target;this.base=base; }

        @Override public void visitTryCatchBlock(Label start,Label end,Label handler,String type) {
            // The new callback-only guards must precede the native outer catch entries.
            originalHandlers.add(new Handler(start,end,handler,type));
        }
        @Override public void visitFieldInsn(int opcode,String owner,String name,String descriptor) {
            super.visitFieldInsn(opcode,owner,name,descriptor);
            if (target == 0 && opcode == Opcodes.PUTFIELD && owner.equals(RESOURCE)
                && name.equals("image") && descriptor.equals("L"+IMAGE+";")) callback(false);
        }
        @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf) {
            if (target != 1 || !isEncoder(opcode,owner,name,descriptor)) {
                super.visitMethodInsn(opcode,owner,name,descriptor,itf); return;
            }
            super.visitVarInsn(Opcodes.ASTORE,base);     // original output stream
            super.visitVarInsn(Opcodes.ASTORE,base+1);   // original image
            super.visitInsn(Opcodes.ACONST_NULL); super.visitVarInsn(Opcodes.ASTORE,base+2);
            callback(true);
            final Label nativeEncoder = new Label(), done = new Label();
            super.visitVarInsn(Opcodes.ALOAD,base+2); super.visitJumpInsn(Opcodes.IFNULL,nativeEncoder);
            super.visitVarInsn(Opcodes.ALOAD,base); super.visitVarInsn(Opcodes.ALOAD,base+2);
            // Outside the callback guard: IO errors retain the original native handling.
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/io/OutputStream","write","([B)V",false);
            super.visitJumpInsn(Opcodes.GOTO,done);
            super.visitLabel(nativeEncoder); super.visitVarInsn(Opcodes.ALOAD,base+1); super.visitVarInsn(Opcodes.ALOAD,base);
            super.visitMethodInsn(opcode,owner,name,descriptor,itf); super.visitLabel(done);
        }

        private void callback(boolean archive) {
            final Label start=new Label(),end=new Label(),handler=new Label(),notCallback=new Label(),done=new Label();
            super.visitTryCatchBlock(start,end,handler,"java/lang/Throwable"); super.visitLabel(start);
            super.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/System","getProperties","()Ljava/util/Properties;",false);
            super.visitLdcInsn(KEY); super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/util/Properties","get","(Ljava/lang/Object;)Ljava/lang/Object;",false);
            super.visitInsn(Opcodes.DUP);super.visitTypeInsn(Opcodes.INSTANCEOF,"java/util/function/BiFunction");super.visitJumpInsn(Opcodes.IFEQ,notCallback);
            super.visitTypeInsn(Opcodes.CHECKCAST,"java/util/function/BiFunction");super.visitVarInsn(Opcodes.ALOAD,0);
            if (archive) super.visitVarInsn(Opcodes.ALOAD,base+1); else super.visitInsn(Opcodes.ACONST_NULL);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE,"java/util/function/BiFunction","apply","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",true);
            if (archive) {
                super.visitInsn(Opcodes.DUP);super.visitTypeInsn(Opcodes.INSTANCEOF,"[B");super.visitJumpInsn(Opcodes.IFEQ,notCallback);
                super.visitTypeInsn(Opcodes.CHECKCAST,"[B");super.visitVarInsn(Opcodes.ASTORE,base+2);super.visitJumpInsn(Opcodes.GOTO,end);
            } else { super.visitInsn(Opcodes.POP);super.visitJumpInsn(Opcodes.GOTO,end); }
            super.visitLabel(notCallback);super.visitInsn(Opcodes.POP);super.visitLabel(end);super.visitJumpInsn(Opcodes.GOTO,done);
            super.visitLabel(handler);super.visitInsn(Opcodes.POP);super.visitLabel(done);
        }
        @Override public void visitMaxs(int stack,int locals) {
            for (Handler h : originalHandlers) super.visitTryCatchBlock(h.start(),h.end(),h.handler(),h.type());
            super.visitMaxs(stack,Math.max(locals,base+3));
        }
    }
    private record Handler(Label start,Label end,Label handler,String type) { }
}

package dev.turboism.adapter.cubism.optimization.uniform;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Inserts typed lookup/observation callbacks at the reviewed material-uniform query.
 *
 * <p>This does not install itself or establish cache validity. A verified installer
 * must admit the host artifact, and the callback owner must provide frame/context
 * and program-lifecycle coverage before enabling reuse. Absent/failed callbacks
 * retain the original query and its exception behavior. Other GL calls, the class
 * hierarchy, fields and method signatures remain unchanged.</p>
 */
public final class UniformLocationCallSiteTransformer implements ClassFileTransformer {
    /** Typed {@code (Object,int,String)int} lookup; values below minus one mean miss. */
    public static final String LOOKUP_PROPERTY = "turboism.uniform-location.lookup";
    /** Typed {@code (Object,int,String,int)void} native-result observation. */
    public static final String RECORD_PROPERTY = "turboism.uniform-location.record";
    /** Exact internal name of the shader containing the reviewed query. */
    public static final String OWNER = "com/live2d/graphics3d/shader/GShader";
    /** Reviewed method name, not a global GL interception point. */
    public static final String METHOD = "preDraw_exe";
    /** Reviewed method descriptor used by the verified installer. */
    public static final String DESCRIPTOR = "(Lcom/live2d/graphics3d/a;"
        + "Lcom/live2d/graphics3d/material/GMaterial;Lcom/live2d/graphics3d/type/GMatrix44;)V";
    private static final String GL = "com/jogamp/opengl/GL3";
    private static final String QUERY = "glGetUniformLocation";
    private static final String QUERY_DESCRIPTOR = "(ILjava/lang/String;)I";
    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private final int localBase;
    private volatile String failure, beforeSha256;
    private volatile int matches;

    /**
     * Creates an inert transformer for the exact loader and reference query shape.
     *
     * @param loader the loader allowed to supply the shader class
     * @param artifact the expected code-source path (digest admission belongs to the installer)
     * @param reference official reference bytes for the admitted shader class
     * @throws IllegalArgumentException if the reviewed method or sole query site is absent
     */
    public UniformLocationCallSiteTransformer(ClassLoader loader, Path artifact, byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        shape = ReviewedMethodShape.read(reference, OWNER, METHOD, DESCRIPTOR);
        if (shape == null) throw new IllegalArgumentException("reviewed shader method absent");
        int[] inspected = {-1, 0};
        new ClassReader(reference).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                         String desc, boolean itf) {
                        if (query(opcode, owner, method, desc, itf)) inspected[1]++;
                    }
                    @Override public void visitMaxs(int stack, int locals) { inspected[0] = locals; }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        if (inspected[0] < 4 || inspected[1] != 1) {
            throw new IllegalArgumentException("expected one reviewed material query, observed=" + inspected[1]);
        }
        localBase = inspected[0];
    }

    /** Returns the most recent transform rejection, or null. */
    public String failure() { return failure; }
    /** Returns the number of successfully rewritten class observations. */
    public int matches() { return matches; }
    /** Returns the first pre-rewrite class digest for installer restoration checks. */
    public String beforeSha256() { return beforeSha256; }

    @Override public byte[] transform(Module module, ClassLoader actualLoader, String name,
                                      Class<?> type, ProtectionDomain domain, byte[] bytes) {
        if (actualLoader != loader || !OWNER.equals(name) || bytes == null) return null;
        try {
            if (domain == null || domain.getCodeSource() == null
                || !artifact.equals(Path.of(domain.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) {
                failure = "shader query source mismatch";
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(bytes, OWNER, METHOD, DESCRIPTOR))) {
                failure = "shader query method shape mismatch";
                return null;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                           String signature, String[] exceptions) {
                    MethodVisitor original = super.visitMethod(access, method, descriptor, signature, exceptions);
                    return METHOD.equals(method) && DESCRIPTOR.equals(descriptor)
                        ? new QueryVisitor(original, localBase) : original;
                }
            }, ClassReader.EXPAND_FRAMES);
            byte[] result = writer.toByteArray();
            if (beforeSha256 == null) beforeSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
            matches++;
            return result;
        } catch (Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private static boolean query(int opcode, String owner, String name, String desc, boolean itf) {
        return opcode == Opcodes.INVOKEINTERFACE && itf && GL.equals(owner)
            && QUERY.equals(name) && QUERY_DESCRIPTOR.equals(desc);
    }

    private static final class QueryVisitor extends MethodVisitor {
        private final int gl, program, name, result;
        private final List<Handler> nativeHandlers = new ArrayList<>();
        QueryVisitor(MethodVisitor visitor, int base) {
            super(Opcodes.ASM9, visitor);
            gl = base; program = base + 1; name = base + 2; result = base + 3;
        }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            // Callback guards must precede native handlers, otherwise host catch blocks
            // could consume a callback failure before the original query can run.
            nativeHandlers.add(new Handler(start, end, handler, type));
        }
        @Override public void visitMaxs(int stack, int locals) {
            for (Handler handler : nativeHandlers) {
                super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(), handler.type());
            }
            super.visitMaxs(stack, Math.max(locals, result + 1));
        }
        @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
            if (!query(opcode, owner, method, desc, itf)) {
                super.visitMethodInsn(opcode, owner, method, desc, itf);
                return;
            }
            // The attested call site's operand stack contains exactly GL, program, name.
            // New slots start after all original locals; original temporaries remain intact.
            super.visitVarInsn(Opcodes.ASTORE, name);
            super.visitVarInsn(Opcodes.ISTORE, program);
            super.visitVarInsn(Opcodes.ASTORE, gl);
            Label nativeQuery = new Label(), done = new Label();
            super.visitVarInsn(Opcodes.ALOAD, gl);
            super.visitJumpInsn(Opcodes.IFNULL, nativeQuery);
            super.visitVarInsn(Opcodes.ILOAD, program);
            super.visitJumpInsn(Opcodes.IFLE, nativeQuery);
            super.visitVarInsn(Opcodes.ALOAD, name);
            super.visitJumpInsn(Opcodes.IFNULL, nativeQuery);
            lookup(nativeQuery, done);
            super.visitLabel(nativeQuery);
            arguments();
            // Intentionally outside both callback try blocks: do not catch or retry
            // the native operation, and do not alter an existing native exception.
            super.visitMethodInsn(opcode, owner, method, desc, itf);
            super.visitVarInsn(Opcodes.ISTORE, result);
            observe();
            super.visitLabel(done);
            super.visitVarInsn(Opcodes.ILOAD, result);
        }
        private void arguments() {
            super.visitVarInsn(Opcodes.ALOAD, gl);
            super.visitVarInsn(Opcodes.ILOAD, program);
            super.visitVarInsn(Opcodes.ALOAD, name);
        }
        private void property(String key) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(key);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        }
        private void lookup(Label nativeQuery, Label done) {
            Label start = new Label(), end = new Label(), failure = new Label(), discard = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            super.visitLabel(start);
            property(LOOKUP_PROPERTY);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/invoke/MethodHandle");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/invoke/MethodHandle");
            arguments();
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "(Ljava/lang/Object;ILjava/lang/String;)I", false);
            super.visitVarInsn(Opcodes.ISTORE, result);
            super.visitLabel(end);
            super.visitVarInsn(Opcodes.ILOAD, result);
            super.visitInsn(Opcodes.ICONST_M1);
            super.visitJumpInsn(Opcodes.IF_ICMPGE, done);
            super.visitJumpInsn(Opcodes.GOTO, nativeQuery);
            super.visitLabel(discard); super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, nativeQuery);
            super.visitLabel(failure); super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, nativeQuery);
        }
        private void observe() {
            Label start = new Label(), end = new Label(), failure = new Label(), discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            super.visitLabel(start);
            property(RECORD_PROPERTY);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/invoke/MethodHandle");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/invoke/MethodHandle");
            arguments(); super.visitVarInsn(Opcodes.ILOAD, result);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "(Ljava/lang/Object;ILjava/lang/String;I)V", false);
            super.visitLabel(end); super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard); super.visitInsn(Opcodes.POP); super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failure); super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }
    }
    private record Handler(Label start, Label end, Label target, String type) { }
}

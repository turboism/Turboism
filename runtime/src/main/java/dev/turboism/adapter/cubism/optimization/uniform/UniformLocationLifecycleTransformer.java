package dev.turboism.adapter.cubism.optimization.uniform;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Observes render ownership, native errors and concrete JOGL program mutations.
 * Only method bodies are changed. Every callback is guarded; native operations,
 * return values and failures remain outside the observer's exception guards.
 */
public final class UniformLocationLifecycleTransformer implements ClassFileTransformer {
    /** The fixed, individually attested class and method families. */
    public enum Role {
        /** Render scope with cleanup on both normal and exceptional exit. */
        FRAME("com/live2d/cubism/view/gl/SGFramework/g", Map.of("render3d", "(Lcom/live2d/graphics3d/a;)V")),
        /** Existing native shader error observation, not an additional query. */
        ERROR("com/live2d/graphics3d/shader/A", Map.of("a", "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I")),
        /** Concrete core and ARB program lifecycle operations used by this host. */
        MUTATIONS("jogamp/opengl/gl4/GL4bcImpl", Map.of("glLinkProgram", "(I)V", "glDeleteProgram", "(I)V", "glProgramBinary", "(IILjava/nio/Buffer;I)V", "glLinkProgramARB", "(J)V", "glDeleteObjectARB", "(J)V")),
        /** Other bundled implementation capable of mutating a shared GLSL program. */
        MUTATIONS_ES("jogamp/opengl/es3/GLES3Impl", Map.of("glLinkProgram", "(I)V", "glDeleteProgram", "(I)V", "glProgramBinary", "(IILjava/nio/Buffer;I)V"));
        private final String owner;
        private final Map<String, String> methods;
        Role(String owner, Map<String, String> methods) { this.owner = owner; this.methods = methods; }
        /** Returns the reviewed class internal name. */
        public String owner() { return owner; }
        /** Returns the fixed target method descriptors. */
        public Map<String, String> methods() { return methods; }
        /** Whether this role needs begin/finally-end program mutation accounting. */
        public boolean programMutations() { return this == MUTATIONS || this == MUTATIONS_ES; }
    }
    private final ClassLoader loader;
    private final Path artifact;
    private final Role role;
    private final Map<String, List<String>> shapes = new HashMap<>();
    private final Map<String, Integer> locals = new HashMap<>();
    private volatile String failure, beforeSha256;
    private volatile int matches;
    private Runnable onRejection = () -> { };

    /**
     * Creates an inert companion transform from official reference bytes.
     * @param loader the exact defining loader
     * @param artifact the exact code-source path, digest admitted by the installer
     * @param reference the official class bytes
     * @param role the fixed lifecycle family
     * @throws IllegalArgumentException if a required body or error-query site is absent
     */
    public UniformLocationLifecycleTransformer(ClassLoader loader, Path artifact, byte[] reference, Role role) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.role = Objects.requireNonNull(role, "role");
        for (var target : role.methods.entrySet()) {
            List<String> shape = ReviewedMethodShape.read(reference, role.owner, target.getKey(), target.getValue());
            if (shape == null) throw new IllegalArgumentException("lifecycle method absent: " + target.getKey());
            shapes.put(target.getKey(), shape);
        }
        int[] errorCalls = {0};
        new ClassReader(reference).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!descriptor.equals(role.methods.get(name))) return null;
                if ((access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) != 0) {
                    throw new IllegalArgumentException("unsupported lifecycle access");
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if (errorQuery(opcode, owner, method, desc, itf)) errorCalls[0]++;
                    }
                    @Override public void visitMaxs(int stack, int maxLocals) { locals.put(name, maxLocals); }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        if (locals.size() != shapes.size() || (role == Role.ERROR && errorCalls[0] != 1)) {
            throw new IllegalArgumentException("incomplete lifecycle method bodies");
        }
    }
    /**
     * Verifies that every bundled public GLSL program-mutating implementation is covered.
     * @param jar the separately digest-attested bundled JOGL reference archive
     * @throws java.io.IOException when a reference class cannot be read
     * @throws IllegalArgumentException when the concrete writer inventory differs
     */
    public static void verifyMutationInventory(java.util.jar.JarFile jar) throws java.io.IOException {
        java.util.Set<String> expected = new java.util.HashSet<>(), observed = new java.util.HashSet<>();
        for (var role : Role.values()) if (role.programMutations()) {
            role.methods().forEach((name, descriptor) -> expected.add(role.owner() + ":" + name + descriptor));
        }
        java.util.Set<String> names = java.util.Set.of("glLinkProgram", "glDeleteProgram", "glProgramBinary",
            "glLinkProgramARB", "glDeleteObjectARB", "glProgramBinaryOES");
        for (var entry : java.util.Collections.list(jar.entries())) {
            if (!entry.getName().startsWith("jogamp/opengl/") || !entry.getName().endsWith("Impl.class")) continue;
            byte[] bytes;
            try (var input = jar.getInputStream(entry)) { bytes = input.readAllBytes(); }
            String owner = entry.getName().substring(0, entry.getName().length() - 6);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                            String signature, String[] exceptions) {
                    if (names.contains(name) && (access & Opcodes.ACC_ABSTRACT) == 0) {
                        observed.add(owner + ":" + name + descriptor);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        }
        if (!expected.equals(observed)) throw new IllegalArgumentException("incomplete JOGL shared program mutation coverage");
    }

    /** Registers a fail-closed action before installing this transformer. */
    public void onRejection(Runnable action) { onRejection = Objects.requireNonNull(action, "action"); }
    /** Returns the latest rejection, or null. */
    public String failure() { return failure; }
    /** Returns successful class rewrites, not target method counts. */
    public int matches() { return matches; }
    /** Returns the initial pre-rewrite class digest for restoration verification. */
    public String beforeSha256() { return beforeSha256; }

    @Override public byte[] transform(Module module, ClassLoader actualLoader, String name, Class<?> type,
                                      ProtectionDomain domain, byte[] bytes) {
        if (actualLoader != loader || !role.owner.equals(name) || bytes == null) return null;
        try {
            if (domain == null || domain.getCodeSource() == null || !artifact.equals(
                Path.of(domain.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("lifecycle artifact mismatch");
            }
            for (var target : role.methods.entrySet()) if (!shapes.get(target.getKey()).equals(
                ReviewedMethodShape.read(bytes, role.owner, target.getKey(), target.getValue()))) {
                throw new IllegalArgumentException("lifecycle method shape mismatch: " + target.getKey());
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                    MethodVisitor original = super.visitMethod(access, method, desc, signature, exceptions);
                    return desc.equals(role.methods.get(method)) ? new Observer(original, role, locals.get(method)) : original;
                }
            }, ClassReader.EXPAND_FRAMES);
            byte[] changed = writer.toByteArray();
            if (beforeSha256 == null) beforeSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            matches++;
            return changed;
        } catch (Exception | LinkageError problem) {
            failure = problem.toString();
            onRejection.run();
            return null;
        }
    }
    private static boolean errorQuery(int opcode, String owner, String method, String desc, boolean itf) {
        return opcode == Opcodes.INVOKEINTERFACE && itf && owner.equals("com/jogamp/opengl/GL")
            && method.equals("glGetError") && desc.equals("()I");
    }
    private record Handler(Label start, Label end, Label target, String type) { }
    private static final class Observer extends MethodVisitor {
        private final Role role;
        private final int base;
        private final List<Handler> originalHandlers = new ArrayList<>();
        private final Label bodyStart = new Label(), bodyEnd = new Label(), exceptionalExit = new Label();
        Observer(MethodVisitor visitor, Role role, int base) { super(Opcodes.ASM9, visitor); this.role = role; this.base = base; }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            originalHandlers.add(new Handler(start, end, handler, type));
        }
        @Override public void visitCode() {
            super.visitCode();
            if (role == Role.FRAME || role.programMutations()) {
                super.visitInsn(Opcodes.LCONST_0); super.visitVarInsn(Opcodes.LSTORE, base);
                if (role == Role.FRAME) {
                    guarded(UniformLocationHookBridge.BEGIN_PROPERTY, "(Ljava/lang/Object;)J",
                        () -> super.visitVarInsn(Opcodes.ALOAD, 1), () -> super.visitVarInsn(Opcodes.LSTORE, base));
                } else {
                    guarded(UniformLocationHookBridge.MUTATION_BEGIN_PROPERTY, "()J",
                        () -> { }, () -> super.visitVarInsn(Opcodes.LSTORE, base));
                }
                super.visitLabel(bodyStart);
            }
        }
        @Override public void visitInsn(int opcode) {
            if ((role == Role.FRAME || role.programMutations()) && opcode == Opcodes.RETURN) endFrame();
            super.visitInsn(opcode);
        }
        @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
            if (role != Role.ERROR || !errorQuery(opcode, owner, method, desc, itf)) {
                super.visitMethodInsn(opcode, owner, method, desc, itf); return;
            }
            super.visitVarInsn(Opcodes.ASTORE, base);
            super.visitVarInsn(Opcodes.ALOAD, base);
            super.visitMethodInsn(opcode, owner, method, desc, itf);
            super.visitVarInsn(Opcodes.ISTORE, base + 1);
            guarded(UniformLocationHookBridge.ERROR_PROPERTY, "(Ljava/lang/Object;I)V", () -> {
                super.visitVarInsn(Opcodes.ALOAD, base); super.visitVarInsn(Opcodes.ILOAD, base + 1);
            }, () -> { });
            super.visitVarInsn(Opcodes.ILOAD, base + 1);
        }
        private void endFrame() {
            guarded(role == Role.FRAME ? UniformLocationHookBridge.END_PROPERTY
                : UniformLocationHookBridge.MUTATION_END_PROPERTY,
                "(J)V", () -> super.visitVarInsn(Opcodes.LLOAD, base), () -> { });
        }
        @Override public void visitMaxs(int stack, int localCount) {
            if (role == Role.FRAME || role.programMutations()) {
                super.visitLabel(bodyEnd); super.visitLabel(exceptionalExit);
                super.visitVarInsn(Opcodes.ASTORE, base + 2);
                endFrame();
                super.visitVarInsn(Opcodes.ALOAD, base + 2); super.visitInsn(Opcodes.ATHROW);
            }
            for (Handler handler : originalHandlers) super.visitTryCatchBlock(handler.start, handler.end, handler.target, handler.type);
            if (role == Role.FRAME || role.programMutations()) super.visitTryCatchBlock(bodyStart, bodyEnd, exceptionalExit, null);
            super.visitMaxs(stack, Math.max(localCount, base + 3));
        }
        private void guarded(String property, String signature, Runnable arguments, Runnable consumeResult) {
            Label start = new Label(), end = new Label(), failure = new Label(), discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(property);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            super.visitInsn(Opcodes.DUP); super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/invoke/MethodHandle");
            super.visitJumpInsn(Opcodes.IFEQ, discard); super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/invoke/MethodHandle");
            arguments.run();
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", signature, false);
            consumeResult.run();
            super.visitLabel(end); super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard); super.visitInsn(Opcodes.POP); super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failure); super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }
    }
}

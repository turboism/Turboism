package dev.turboism.validation.texture;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.*;

/** Test-only observation of swallowed admission exceptions; never changes a decision or launches a host. */
public final class HostAdmissionDiagnostic {
    private static final String OWNER = "dev/turboism/adapter/host/HostSession";
    static final String KEY = "dev.turboism.validation.atlas.admission-exception";
    private static Path output;

    public static void install(Path directory, Instrumentation instrumentation) {
        output = directory.resolve("host-admission-exceptions.txt");
        System.getProperties().put(KEY, (java.util.function.Consumer<Throwable>) HostAdmissionDiagnostic::record);
        marker(directory, "installed");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(Module module, ClassLoader loader, String name,
                    Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                if (!OWNER.equals(name)) return null;
                marker(directory, "transform loader=" + loader);
                return observe(bytes);
            }
        }, true);
        try {
            for (Class<?> type : instrumentation.getAllLoadedClasses())
                if (type.getName().equals(OWNER.replace('/', '.'))) instrumentation.retransformClasses(type);
        } catch (Exception failure) { throw new IllegalStateException("Admission observation unavailable", failure); }
    }

    static byte[] observe(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    final Set<Label> handlers = new HashSet<>();
                    @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        if (type != null && (type.equals("java/lang/Throwable")
                                || type.equals("java/lang/Exception") || type.equals("java/lang/RuntimeException")))
                            handlers.add(handler);
                        super.visitTryCatchBlock(start, end, handler, type);
                    }
                    boolean pending;
                    @Override public void visitLabel(Label label) {
                        super.visitLabel(label);
                        pending = handlers.contains(label);
                    }
                    @Override public void visitVarInsn(int opcode, int variable) {
                        // javac catch entry stores the one exception after its stack-map frame.
                        if (pending && opcode == Opcodes.ASTORE) {
                            pending = false;
                            // Bootstrap-only bridge: runtime loaders need not see this probe class.
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                            super.visitLdcInsn(KEY);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                            super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
                        }
                        super.visitVarInsn(opcode, variable);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void marker(Path directory, String text) {
        try { Files.writeString(directory.resolve("host-admission-observer.txt"), text + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Exception failure) { throw new IllegalStateException("Cannot record observer installation", failure); }
    }

    public static synchronized void record(Throwable failure) {
        if (output == null) return;
        try {
            var text = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(text));
            Files.writeString(output, "[atlas-admission-diagnostic]\n" + text,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { /* Observation must not change admission or cleanup behavior. */ }
    }
}

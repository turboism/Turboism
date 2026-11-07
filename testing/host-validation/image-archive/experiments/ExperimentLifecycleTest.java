package dev.turboism.validation.experiments;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Real JVM regression for removal + retransformation, without a Cubism process. */
public final class ExperimentLifecycleTest {
    static Instrumentation instrumentation;
    public static final class Victim { public static int value() { return 17; } }
    private ExperimentLifecycleTest() {}
    public static void main(String[] args) throws Exception {
        if (Victim.value() != 17 || instrumentation == null) throw new AssertionError("selftest agent absent");
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (!name.equals(Victim.class.getName().replace('.', '/'))) return null;
                var reader = new ClassReader(bytes); var writer = new ClassWriter(reader, 0);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                        var original = super.visitMethod(a, n, d, s, e);
                        if (!n.equals("value")) return original;
                        return new MethodVisitor(Opcodes.ASM9, original) {
                            @Override public void visitIntInsn(int op, int value) { super.visitIntInsn(op, value == 17 ? 19 : value); }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        };
        instrumentation.addTransformer(transformer, true);
        instrumentation.retransformClasses(Victim.class);
        if (Victim.value() != 19) throw new AssertionError("test weave inactive");
        instrumentation.removeTransformer(transformer);
        instrumentation.retransformClasses(Victim.class);
        if (Victim.value() != 17) throw new AssertionError("test weave not restored");
        System.out.println("ExperimentLifecycleTest PASS");
    }
}

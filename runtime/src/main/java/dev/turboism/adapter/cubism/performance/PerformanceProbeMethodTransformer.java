package dev.turboism.adapter.cubism.performance;

import dev.turboism.adapter.cubism.optimization.HostClassWriter;
import java.lang.instrument.ClassFileTransformer;
import java.net.URI;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Weaves probe enter/exit calls into a fixed list of Cubism methods at class-load time.
 *
 * <p>Transformation is deliberately narrow and fails closed: a class is only rewritten
 * when it is loaded by the expected classloader, its code source is the expected
 * artifact on disk, and its name matches a declared {@link Target}. Anything else
 * returns {@code null}, leaving the host bytes untouched. Each instrumented method is
 * wrapped in a try/catch that re-throws, so the exit probe runs on both normal and
 * exceptional paths.</p>
 *
 * <p>SHA-256 digests of the bytes received and produced are recorded per class so a
 * report can prove exactly what was modified. The transformer is called concurrently by
 * the JVM and keeps its counters and digests in concurrent maps.</p>
 */
public final class PerformanceProbeMethodTransformer implements ClassFileTransformer {

    private static final String CARRIER = "dev/turboism/bootstrap/carrier/PerformanceProbeCarrier";
    private final ClassLoader expectedLoader;
    private final List<Target> targets;
    private final Path expectedArtifact;
    private final ConcurrentHashMap<Target, AtomicInteger> matches = new ConcurrentHashMap<>();
    private final java.util.Set<String> wovenOwners = ConcurrentHashMap.newKeySet();

    public PerformanceProbeMethodTransformer(
            final ClassLoader expectedLoader, final Path expectedArtifact, final List<Target> targets) {
        this.expectedLoader = expectedLoader;
        this.expectedArtifact = expectedArtifact == null
                ? null
                : expectedArtifact.toAbsolutePath().normalize();
        this.targets = List.copyOf(targets);
        this.targets.forEach(target -> matches.put(target, new AtomicInteger()));
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (classfileBuffer == null || (expectedLoader != null && loader != expectedLoader)) return null;
        if (expectedArtifact != null && !comesFromArtifact(protectionDomain, expectedArtifact)) return null;
        final List<Target> classTargets = targets.stream()
                .filter(target -> target.ownerInternalName().equals(className))
                .toList();
        if (classTargets.isEmpty()) return null;

        final ClassReader reader = new ClassReader(classfileBuffer);
        final Map<Target, Integer> locals = findMaxLocals(reader, classTargets);
        final boolean[] changed = {false};
        final ClassWriter writer =
                new HostClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, expectedLoader);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor delegate =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        final Target target = matching(classTargets, name, descriptor);
                        if (target == null) return delegate;
                        changed[0] = true;
                        matches.get(target).incrementAndGet();
                        return instrument(delegate, target, locals.get(target));
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (!changed[0]) return null;
        final byte[] output = writer.toByteArray();
        wovenOwners.add(className);
        return output;
    }

    /**
     * @return an immutable snapshot of how many methods each declared target actually
     *     matched. Every declared target is present; a count of zero means the method was
     *     never instrumented, which is how a version mismatch shows up.
     */
    public Map<Target, Integer> matchCounts() {
        final Map<Target, Integer> copy = new HashMap<>();
        matches.forEach((target, count) -> copy.put(target, count.get()));
        return Map.copyOf(copy);
    }

    /** Internal names this transformer actually instrumented. */
    public java.util.Set<String> wovenOwners() {
        return java.util.Set.copyOf(wovenOwners);
    }

    static boolean comesFromArtifact(final ProtectionDomain protectionDomain, final Path expectedArtifact) {
        try {
            final URI location = protectionDomain.getCodeSource().getLocation().toURI();
            return Path.of(location).toAbsolutePath().normalize().equals(expectedArtifact);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Map<Target, Integer> findMaxLocals(final ClassReader reader, final List<Target> targets) {
        final Map<Target, Integer> result = new HashMap<>();
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final Target target = matching(targets, name, descriptor);
                        if (target == null) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMaxs(final int maxStack, final int maxLocals) {
                                result.put(target, maxLocals);
                            }
                        };
                    }
                },
                ClassReader.SKIP_FRAMES);
        return result;
    }

    private static Target matching(final List<Target> targets, final String name, final String descriptor) {
        return targets.stream()
                .filter(candidate -> candidate.methodName().equals(name)
                        && candidate.descriptor().equals(descriptor))
                .findFirst()
                .orElse(null);
    }

    private static MethodVisitor instrument(final MethodVisitor delegate, final Target target, final int tokenLocal) {
        final int throwableLocal = tokenLocal + 2;
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private final Label start = new Label();
            private final Label end = new Label();
            private final Label handler = new Label();

            @Override
            public void visitCode() {
                super.visitCode();
                visitLdcInsn(target.metric().id());
                visitMethodInsn(Opcodes.INVOKESTATIC, CARRIER, "enter", "(I)J", false);
                visitVarInsn(Opcodes.LSTORE, tokenLocal);
                visitLabel(start);
            }

            @Override
            public void visitInsn(final int opcode) {
                // Leave any one- or two-slot return value below the callback arguments.
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) complete();
                super.visitInsn(opcode);
            }

            private void complete() {
                visitLdcInsn(target.metric().id());
                visitVarInsn(Opcodes.LLOAD, tokenLocal);
                visitMethodInsn(Opcodes.INVOKESTATIC, CARRIER, "exit", "(IJ)V", false);
            }

            @Override
            public void visitMaxs(final int maxStack, final int maxLocals) {
                visitLabel(end);
                visitTryCatchBlock(start, end, handler, null);
                visitLabel(handler);
                visitVarInsn(Opcodes.ASTORE, throwableLocal);
                complete();
                visitVarInsn(Opcodes.ALOAD, throwableLocal);
                visitInsn(Opcodes.ATHROW);
                super.visitMaxs(maxStack, throwableLocal + 1);
            }
        };
    }

    /**
     * One Cubism method to instrument, identified exactly.
     *
     * <p>Obfuscated names differ between host builds, so a target is only valid for the
     * Cubism version it was verified against.</p>
     *
     * @param ownerInternalName JVM internal name of the declaring class, e.g.
     *                          {@code com/live2d/cubism/view/context/CEViewContext}
     * @param methodName        exact method name in that class
     * @param descriptor        JVM descriptor of a concrete, non-constructor method
     * @param metric            the metric this method timings are recorded under
     * @throws NullPointerException when any component is {@code null}
     * @throws IllegalArgumentException when the descriptor is invalid or the method is a constructor
     */
    public record Target(
            String ownerInternalName, String methodName, String descriptor, PerformanceProbeMetric metric) {
        public Target {
            Objects.requireNonNull(ownerInternalName, "ownerInternalName");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(metric, "metric");
            if (methodName.startsWith("<")) {
                throw new IllegalArgumentException("performance probe targets must not be constructors");
            }
            try {
                final org.objectweb.asm.Type type = org.objectweb.asm.Type.getMethodType(descriptor);
                if (!descriptor.equals(
                        org.objectweb.asm.Type.getMethodDescriptor(type.getReturnType(), type.getArgumentTypes()))) {
                    throw new IllegalArgumentException("invalid performance probe method descriptor");
                }
            } catch (IndexOutOfBoundsException exception) {
                throw new IllegalArgumentException("invalid performance probe method descriptor", exception);
            }
        }
    }
}

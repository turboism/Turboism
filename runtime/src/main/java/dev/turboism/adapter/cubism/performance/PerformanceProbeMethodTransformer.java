package dev.turboism.adapter.cubism.performance;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.net.URI;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PerformanceProbeMethodTransformer implements ClassFileTransformer {

    private static final String CARRIER =
        "dev/turboism/bootstrap/carrier/PerformanceProbeCarrier";
    private final ClassLoader expectedLoader;
    private final List<Target> targets;
    private final Path expectedArtifact;
    private final ConcurrentHashMap<Target, AtomicInteger> matches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> beforeSha256 = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> instrumentedSha256 = new ConcurrentHashMap<>();

    public PerformanceProbeMethodTransformer(
        final ClassLoader expectedLoader,
        final Path expectedArtifact,
        final List<Target> targets
    ) {
        this.expectedLoader = expectedLoader;
        this.expectedArtifact = expectedArtifact == null ? null : expectedArtifact.toAbsolutePath().normalize();
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
        final byte[] classfileBuffer
    ) {
        if (classfileBuffer == null || (expectedLoader != null && loader != expectedLoader)) return null;
        if (expectedArtifact != null && !comesFromArtifact(protectionDomain, expectedArtifact)) return null;
        final List<Target> classTargets = targets.stream()
            .filter(target -> target.ownerInternalName().equals(className))
            .toList();
        if (classTargets.isEmpty()) return null;

        final ClassReader reader = new ClassReader(classfileBuffer);
        final Map<Target, Integer> locals = findMaxLocals(reader, classTargets);
        final boolean[] changed = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                final Target target = matching(classTargets, name, descriptor);
                if (target == null) return delegate;
                changed[0] = true;
                matches.get(target).incrementAndGet();
                return instrument(delegate, target, locals.get(target));
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!changed[0]) return null;
        final byte[] output = writer.toByteArray();
        beforeSha256.putIfAbsent(className, sha256(classfileBuffer));
        instrumentedSha256.putIfAbsent(className, sha256(output));
        return output;
    }

    public Map<Target, Integer> matchCounts() {
        final Map<Target, Integer> copy = new HashMap<>();
        matches.forEach((target, count) -> copy.put(target, count.get()));
        return Map.copyOf(copy);
    }

    /** SHA-256 of the actual input bytes this transformer received, keyed by owner internal name. */
    public Map<String, String> beforeSha256() {
        return Map.copyOf(beforeSha256);
    }

    /** SHA-256 of the actual bytes this transformer returned, keyed by owner internal name. */
    public Map<String, String> instrumentedSha256() {
        return Map.copyOf(instrumentedSha256);
    }

    static boolean comesFromArtifact(final ProtectionDomain protectionDomain, final Path expectedArtifact) {
        try {
            final URI location = protectionDomain.getCodeSource().getLocation().toURI();
            return Path.of(location).toAbsolutePath().normalize().equals(expectedArtifact);
        } catch (Exception ignored) {
            return false;
        }
    }

    static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<Target, Integer> findMaxLocals(
        final ClassReader reader,
        final List<Target> targets
    ) {
        final Map<Target, Integer> result = new HashMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final Target target = matching(targets, name, descriptor);
                if (target == null) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMaxs(final int maxStack, final int maxLocals) {
                        result.put(target, maxLocals);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return result;
    }

    private static Target matching(final List<Target> targets, final String name, final String descriptor) {
        return targets.stream()
            .filter(candidate -> candidate.methodName().equals(name)
                && candidate.descriptor().equals(descriptor))
            .findFirst().orElse(null);
    }

    private static MethodVisitor instrument(
        final MethodVisitor delegate,
        final Target target,
        final int tokenLocal
    ) {
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
                if (opcode == Opcodes.RETURN) complete();
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

    public record Target(
        String ownerInternalName,
        String methodName,
        String descriptor,
        PerformanceProbeMetric metric
    ) {
        public Target {
            Objects.requireNonNull(ownerInternalName, "ownerInternalName");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(metric, "metric");
            if (!descriptor.endsWith(")V")) {
                throw new IllegalArgumentException("performance probe targets must return void");
            }
        }
    }
}

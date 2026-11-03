package dev.turboism.adapter.cubism.optimization.inspector;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
 * Replaces the single {@code CModelSource.updateModelInstances()} call in reviewed inspector
 * visual-property setters with a guarded shader-only refresh.
 *
 * <p>Each reviewed method must still match the official artifact's instruction, branch and
 * handler shape, and must contain exactly one full-rebuild call. At that call site the woven
 * bytecode parks the {@code CModelSource} in a fresh local, consults a
 * {@link java.util.function.Predicate} from {@link System#getProperties()}, and either skips the
 * rebuild or re-pushes the source and executes the original call. Any missing slot, wrong type,
 * thrown callback or shape mismatch leaves the native call in place.</p>
 */
public final class InspectorPropertyBypassTransformer implements ClassFileTransformer {

    private final ClassLoader loader;
    private final Path artifact;
    private final Map<String, ClassSpec> specs;
    private final Map<String, String> beforeSha256 = new ConcurrentHashMap<>();
    private final AtomicInteger matches = new AtomicInteger();
    private volatile String failure;

    /**
     * @param loader admitted host loader
     * @param artifact exact official artifact used for code-source attestation
     * @param references official class bytes keyed by internal owner name
     */
    public InspectorPropertyBypassTransformer(
            final ClassLoader loader, final Path artifact, final Map<String, byte[]> references) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        final Map<String, byte[]> copied = new LinkedHashMap<>();
        for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
            final byte[] reference = references.get(seam.owner());
            if (reference == null) {
                throw new IllegalArgumentException("inspector bypass reference absent: " + seam.owner());
            }
            copied.put(seam.owner(), reference.clone());
        }
        final Map<String, ClassSpec> built = new LinkedHashMap<>();
        for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
            final byte[] reference = copied.get(seam.owner());
            final List<String> shape =
                    ReviewedMethodShape.read(reference, seam.owner(), seam.method(), seam.descriptor());
            if (shape == null || updateCallCount(reference, seam) != 1) {
                throw new IllegalArgumentException(
                        "reviewed inspector seam absent or not a single rebuild: " + seam.owner());
            }
            final int slot = freeLocalSlot(reference, seam.method(), seam.descriptor());
            built.put(seam.owner(), new ClassSpec(seam, shape, slot));
        }
        specs = Map.copyOf(built);
    }

    /** SHA-256 of each rewritten class before this transformer touched it. */
    public Map<String, String> beforeSha256() {
        return Map.copyOf(beforeSha256);
    }

    /** Rewritten classes observed so far. */
    public int matches() {
        return matches.get();
    }

    /** Most recent rejection, or null when none has occurred. */
    public String failure() {
        return failure;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || name == null || bytes == null) return null;
        final ClassSpec spec = specs.get(name);
        if (spec == null) return null;
        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failure = "inspector seam source is not the reviewed artifact: " + name;
                return null;
            }
            final var seam = spec.seam();
            if (!spec.shape().equals(ReviewedMethodShape.read(bytes, name, seam.method(), seam.descriptor()))) {
                failure = "inspector seam shape mismatch: " + name + "." + seam.method();
                return null;
            }
            if (updateCallCount(bytes, seam) != 1) {
                failure = "inspector seam no longer has one rebuild call: " + name + "." + seam.method();
                return null;
            }

            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader;
                }
            };
            final int[] rewritten = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String method,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor visitor =
                                    super.visitMethod(access, method, descriptor, signature, exceptions);
                            if (!seam.method().equals(method)
                                    || !seam.descriptor().equals(descriptor)) {
                                return visitor;
                            }
                            return new GuardVisitor(visitor, spec.slot(), rewritten);
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (rewritten[0] != 1) {
                failure = "inspector seam cardinality mismatch: " + name;
                return null;
            }
            beforeSha256.putIfAbsent(
                    name,
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            matches.incrementAndGet();
            return writer.toByteArray();
        } catch (Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private record ClassSpec(InspectorPropertyBypassTarget.Seam seam, List<String> shape, int slot) {}

    /** Intercepts only the reviewed full-rebuild call inside its reviewed setter. */
    private static final class GuardVisitor extends MethodVisitor {
        private final int slot;
        private final int[] rewritten;
        private boolean emittingGuard;

        GuardVisitor(final MethodVisitor visitor, final int slot, final int[] rewritten) {
            super(Opcodes.ASM9, visitor);
            this.slot = slot;
            this.rewritten = rewritten;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String method,
                final String descriptor,
                final boolean isInterface) {
            if (!emittingGuard
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && InspectorPropertyBypassTarget.SOURCE_OWNER.equals(owner)
                    && InspectorPropertyBypassTarget.UPDATE_METHOD.equals(method)
                    && InspectorPropertyBypassTarget.UPDATE_DESCRIPTOR.equals(descriptor)
                    && !isInterface) {
                emittingGuard = true;
                try {
                    emitGuard(this, slot);
                } finally {
                    emittingGuard = false;
                }
                rewritten[0]++;
                return;
            }
            super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
        }
    }

    /**
     * Emits the guarded replacement. The original source is parked in {@code slot}; a callback
     * returning true leaves the rebuild out, anything else restores the exact native call.
     */
    private static void emitGuard(final MethodVisitor out, final int slot) {
        final Label tryStart = new Label(), tryEnd = new Label(), handler = new Label();
        final Label notPredicate = new Label(), nativeCall = new Label(), done = new Label();
        out.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        out.visitVarInsn(Opcodes.ASTORE, slot);
        out.visitLabel(tryStart);
        out.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        out.visitLdcInsn(InspectorPropertyBypassBridge.BYPASS_CALLBACK_PROPERTY);
        out.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        out.visitInsn(Opcodes.DUP);
        out.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Predicate");
        out.visitJumpInsn(Opcodes.IFEQ, notPredicate);
        out.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate");
        out.visitVarInsn(Opcodes.ALOAD, 0);
        out.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", "(Ljava/lang/Object;)Z", true);
        out.visitJumpInsn(Opcodes.IFEQ, nativeCall);
        out.visitJumpInsn(Opcodes.GOTO, done);
        out.visitLabel(notPredicate);
        out.visitInsn(Opcodes.POP);
        out.visitLabel(tryEnd);
        out.visitLabel(nativeCall);
        out.visitVarInsn(Opcodes.ALOAD, slot);
        out.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                InspectorPropertyBypassTarget.SOURCE_OWNER,
                InspectorPropertyBypassTarget.UPDATE_METHOD,
                InspectorPropertyBypassTarget.UPDATE_DESCRIPTOR,
                false);
        out.visitJumpInsn(Opcodes.GOTO, done);
        out.visitLabel(handler);
        out.visitInsn(Opcodes.POP);
        out.visitJumpInsn(Opcodes.GOTO, nativeCall);
        out.visitLabel(done);
    }

    private static int updateCallCount(final byte[] classFile, final InspectorPropertyBypassTarget.Seam seam) {
        final int[] calls = {0};
        new ClassReader(classFile)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String method,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!seam.method().equals(method)
                                        || !seam.descriptor().equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String name,
                                            final String desc,
                                            final boolean isInterface) {
                                        if (opcode == Opcodes.INVOKEVIRTUAL
                                                && InspectorPropertyBypassTarget.SOURCE_OWNER.equals(owner)
                                                && InspectorPropertyBypassTarget.UPDATE_METHOD.equals(name)
                                                && InspectorPropertyBypassTarget.UPDATE_DESCRIPTOR.equals(desc)
                                                && !isInterface) {
                                            calls[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls[0];
    }

    /** @return a local slot the reviewed setter method never touches */
    private static int freeLocalSlot(final byte[] classFile, final String methodName, final String descriptor) {
        final int[] max = {0};
        new ClassReader(classFile)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!methodName.equals(name) || !descriptor.equals(desc)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitVarInsn(final int opcode, final int var) {
                                        if (var > max[0]) max[0] = var;
                                    }

                                    @Override
                                    public void visitIincInsn(final int var, final int increment) {
                                        if (var > max[0]) max[0] = var;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return max[0] + 1;
    }
}

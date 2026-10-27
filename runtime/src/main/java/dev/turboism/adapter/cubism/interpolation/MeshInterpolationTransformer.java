package dev.turboism.adapter.cubism.interpolation;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.function.Consumer;
import org.objectweb.asm.*;

/** First-definition-only hook. Hash admission does not resolve any host class. */
public final class MeshInterpolationTransformer implements ClassFileTransformer {
    public static final String OWNER = NativeMeshInterpolationBridge.EXTENSION.replace('.', '/');
    public static final String CLASS_SHA256 = "399a05605ebb2e837434e5b8be7fa84c4cb5d24adc09f7e5546214471380e39f";
    private static final java.util.Map<String, java.util.Map<String, String>> REVIEWED_CLASS_SHA256 =
            dev.turboism.adapter.cubism.optimization.ClassPinTable.load("mesh-interpolation");
    private static final java.util.Set<String> ADMITTED_CLASS_SHA256S =
            java.util.Collections.unmodifiableSet(REVIEWED_CLASS_SHA256.values().stream()
                    .map(m -> m.get(OWNER))
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet()));

    static final String METHOD = "updateInterpolatedForms_common";
    static final String DESC =
            "(Ljava/util/List;Ljava/util/List;Ljava/util/List;Lcom/live2d/cubism/doc/model/interpolator/extendedInterpolation/ExtendedInterpolationType;F)V";
    private final Consumer<String> diagnostic;
    private volatile boolean patched;
    private volatile boolean serializerPatched;
    private volatile boolean propertyPatched;
    private final boolean projectSerializedCache;
    private final boolean optimizeInterpolation;

    public MeshInterpolationTransformer(Consumer<String> diagnostic) {
        this(diagnostic, false);
    }

    public MeshInterpolationTransformer(Consumer<String> diagnostic, boolean projectSerializedCache) {
        this(diagnostic, projectSerializedCache, true);
    }

    public MeshInterpolationTransformer(
            Consumer<String> diagnostic, boolean projectSerializedCache, boolean optimizeInterpolation) {
        this.diagnostic = java.util.Objects.requireNonNull(diagnostic);
        this.projectSerializedCache = projectSerializedCache;
        this.optimizeInterpolation = optimizeInterpolation;
    }
    /**
     * @return {@code true} when the pinned interpolation extension was transformed
     */
    public boolean patched() {
        return patched;
    }
    /**
     * @return {@code true} when the admitted serializer and property reads were transformed
     */
    public boolean serializationPatched() {
        return projectSerializedCache && serializerPatched && propertyPatched;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> prior, ProtectionDomain domain, byte[] bytes) {
        boolean serializer = projectSerializedCache && DerivedCacheSerializationPatch.OWNER.equals(name);
        boolean property = projectSerializedCache && DerivedCacheSerializationPatch.PROPERTY_OWNER.equals(name);
        if (!(optimizeInterpolation && OWNER.equals(name)) && !serializer && !property) return null;
        if (prior != null || bytes == null || domain == null || domain.getCodeSource() == null) {
            report("DECLINED_DEFINITION");
            return null;
        }
        try {
            String classDigest = digest(bytes);
            if (serializer) {
                if (!classDigest.equals(DerivedCacheSerializationPatch.SHA256)) {
                    report("DECLINED_CLASS_HASH");
                    return null;
                }
            } else if (property) {
                if (!classDigest.equals(DerivedCacheSerializationPatch.PROPERTY_SHA256)) {
                    report("DECLINED_CLASS_HASH");
                    return null;
                }
            } else if (!ADMITTED_CLASS_SHA256S.contains(classDigest)) {
                report("DECLINED_CLASS_HASH");
                return null;
            }
            Path artifact = Path.of(domain.getCodeSource().getLocation().toURI());
            dev.turboism.mapping.verification.HostArtifactDigest hostDigest =
                    NativeMeshInterpolationBridge.hostArtifactDigest(artifact);
            var cubismVersion = ReviewedHostArtifacts.cubismVersionOf(hostDigest);
            if (cubismVersion.isEmpty()) {
                report("DECLINED_ARTIFACT_HASH");
                return null;
            }
            String version = cubismVersion.get();
            if (!serializer && !property) {
                var pins = REVIEWED_CLASS_SHA256.get(version);
                String expected = pins == null ? null : pins.get(OWNER);
                if (!classDigest.equals(expected)) {
                    report("DECLINED_CLASS_HASH");
                    return null;
                }
            }
            String artifactTag = version.replace(".", "");
            byte[] result = serializer
                    ? DerivedCacheSerializationPatch.serializer(bytes)
                    : property ? DerivedCacheSerializationPatch.property(bytes) : patch(bytes);
            if (serializer) serializerPatched = true;
            else if (property) propertyPatched = true;
            else patched = true;
            if (serializationPatched()) DerivedCacheSerializationBridge.ready(true);
            report(
                    serializer
                            ? "SERIALIZER_SCOPED_" + artifactTag
                            : property ? "SERIALIZER_FIELD_READ_SCOPED_" + artifactTag : "PATCHED_" + artifactTag);
            return result;
        } catch (Exception | LinkageError rejected) {
            report("DECLINED_SHAPE_OR_SOURCE");
            return null;
        }
    }

    static String digest(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static byte[] patch(byte[] bytes) {
        ClassReader reader = new ClassReader(StructureLookupPatch.patch(bytes));
        if (!reader.getClassName().equals(OWNER)) throw new IllegalArgumentException("owner");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] matches = {0}, inspections = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (name.equals("updateInterpolatedForms_exe") && descriptor.equals("(Z)Z")) {
                            return new MethodVisitor(Opcodes.ASM9, delegate) {
                                @Override
                                public void visitMethodInsn(
                                        int op, String owner, String method, String desc, boolean itf) {
                                    if (op == Opcodes.INVOKEVIRTUAL
                                            && owner.equals(OWNER)
                                            && method.equals("getAllExtendedKeyforms")
                                            && desc.equals("()Ljava/util/List;")) {
                                        inspections[0]++;
                                        // [extension] -> [normal, morph] -> [first nonempty bank].
                                        // The next native firstOrNull sees exactly the same form.
                                        visitInsn(Opcodes.DUP);
                                        super.visitMethodInsn(
                                                op,
                                                owner,
                                                "getNormalExtendedKeyforms",
                                                "()Lcom/live2d/type/CArrayList;",
                                                false);
                                        visitInsn(Opcodes.SWAP);
                                        super.visitMethodInsn(
                                                op,
                                                owner,
                                                "getMorphTargetExtendedKeyforms",
                                                "()Lcom/live2d/type/CArrayList;",
                                                false);
                                        super.visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                "dev/turboism/adapter/cubism/interpolation/NativeMeshInterpolationBridge",
                                                "firstInspectionBank",
                                                "(Ljava/util/List;Ljava/util/List;)Ljava/util/List;",
                                                false);
                                        return;
                                    }
                                    super.visitMethodInsn(op, owner, method, desc, itf);
                                }
                            };
                        }
                        if (!name.equals(METHOD) || !descriptor.equals(DESC)) return delegate;
                        if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
                            throw new IllegalArgumentException("method access");
                        matches[0]++;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                for (int slot = 0; slot <= 4; slot++) visitVarInsn(Opcodes.ALOAD, slot);
                                visitVarInsn(Opcodes.FLOAD, 5);
                                visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        "dev/turboism/adapter/cubism/interpolation/NativeMeshInterpolationBridge",
                                        "tryUpdate",
                                        "(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/lang/Object;F)Z",
                                        false);
                                Label official = new Label();
                                visitJumpInsn(Opcodes.IFEQ, official);
                                visitInsn(Opcodes.RETURN);
                                visitLabel(official);
                                visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                        };
                    }
                },
                0);
        if (matches[0] != 1) throw new IllegalArgumentException("method cardinality " + matches[0]);
        if (inspections[0] != 1) throw new IllegalArgumentException("first inspection cardinality " + inspections[0]);
        return writer.toByteArray();
    }

    private void report(String code) {
        try {
            diagnostic.accept("MESH_INTERPOLATION_" + code);
        } catch (RuntimeException ignored) {
        }
    }
}

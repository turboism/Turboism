package dev.turboism.adapter.cubism.shader;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.objectweb.asm.*;

/**
 * First-definition-only hook for the exact reviewed host shaders. Hash admission never resolves host classes.
 *
 * <p>This transformer owns the host's single material uniform query seam in
 * {@code preDraw_exe} and is registered retransformation-incapable, so its output
 * becomes the baseline every later retransformation starts from. The verified
 * uniform-location cache transforms the same seam afterwards; it admits the exact
 * {@link #composedShape(byte[])} this rewrite produces, takes the seam over (its
 * frame cache carries the program-mutation coverage a per-shader cache cannot) and
 * leaves {@link NativeShaderUniformBridge}'s own cache unused for that definition.
 * Where the cache is absent or inadmissible this hook keeps the seam.</p>
 */
public final class ShaderUniformTransformer implements ClassFileTransformer {
    public static final String OWNER = "com/live2d/graphics3d/shader/GShader";
    /** Internal name of the bridge that owns the rewritten uniform-query seam. */
    public static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/shader/NativeShaderUniformBridge";
    /** Bridge method this rewrite substitutes for the host's uniform query. */
    public static final String BRIDGE_LOOKUP = "lookup";
    /** Descriptor of {@link #BRIDGE_LOOKUP}: {@code (gl, program, name, shader) -> location}. */
    public static final String BRIDGE_LOOKUP_DESCRIPTOR = "(Ljava/lang/Object;ILjava/lang/String;Ljava/lang/Object;)I";

    static final String DRAW_METHOD = "preDraw_exe";
    static final String DRAW_DESC =
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/graphics3d/material/GMaterial;Lcom/live2d/graphics3d/type/GMatrix44;)V";
    static final String GL_OWNER = "com/jogamp/opengl/GL3";
    static final String QUERY_METHOD = "glGetUniformLocation";
    static final String QUERY_DESC = "(ILjava/lang/String;)I";
    static final String[] LIFECYCLE_METHODS = {"init_exe", "setGlProgramID"};
    static final String[] LIFECYCLE_DESCS = {"(Lcom/live2d/graphics3d/a;)V", "(I)V"};
    /**
     * Reviewed GShader class digests per admitted Cubism version. Admission is
     * exact and per version: the class bytes must equal the digest pinned for the
     * version the whole-artifact digest resolves to, so an identical class shared
     * by several versions never admits an artifact that was not reviewed.
     */
    private static final Map<String, Map<String, String>> REVIEWED_CLASSES = ClassPinTable.load("shader-uniform");

    /**
     * Records a definition this rewrite actually patched. A later transformer in
     * the retransform chain consults it so a body carrying <em>this</em> reviewed
     * rewrite can be told apart from foreign bytecode drift; the record alone
     * widens nothing, because the composed shape the consumer admits is derived
     * from {@link #patch(byte[])} over attested reference bytes.
     */
    private record Patched(ClassLoader loader, String owner) {}

    private static final Set<Patched> PATCHED = ConcurrentHashMap.newKeySet();
    private final Consumer<String> diagnostic;
    private volatile boolean patched;

    public ShaderUniformTransformer(Consumer<String> diagnostic) {
        this.diagnostic = java.util.Objects.requireNonNull(diagnostic);
    }

    /**
     * Records that a definition supplied by {@code loader} carries this rewrite.
     * Called by the transform itself; exposed so retransform-chain consumers can
     * be exercised against a composed baseline in tests.
     *
     * @param loader the defining loader of the patched definition
     * @param owner the patched class internal name
     */
    public static void markPatched(ClassLoader loader, String owner) {
        PATCHED.add(new Patched(loader, java.util.Objects.requireNonNull(owner, "owner")));
    }

    /**
     * Drops a patch record. A real definition is never reverted — removing the
     * transformer leaves the already-defined class patched — so production code
     * keeps the record for the process lifetime.
     *
     * @param loader the defining loader of the patched definition
     * @param owner the patched class internal name
     */
    public static void clearPatched(ClassLoader loader, String owner) {
        PATCHED.remove(new Patched(loader, java.util.Objects.requireNonNull(owner, "owner")));
    }

    /**
     * @param loader the defining loader to test
     * @param owner the class internal name to test
     * @return {@code true} when this rewrite patched that definition
     */
    public static boolean isPatched(ClassLoader loader, String owner) {
        return PATCHED.contains(new Patched(loader, java.util.Objects.requireNonNull(owner, "owner")));
    }

    /**
     * Returns the exact reviewed {@code preDraw_exe} shape this rewrite produces
     * on the official body — the bytes a later transformer in the retransform
     * chain observes, because this transformer is registered
     * retransformation-incapable and its output becomes the retransform baseline.
     * Computed by running this rewrite over attested reference bytes, never by
     * trusting observed chain output.
     *
     * @param reference official reference bytes of the reviewed shader class
     * @return the composed instruction shape, or {@code null} when the rewrite cannot run
     */
    public static List<String> composedShape(byte[] reference) {
        byte[] composed = composedDefinition(reference);
        return composed == null ? null : ReviewedMethodShape.read(composed, OWNER, DRAW_METHOD, DRAW_DESC);
    }

    /**
     * Returns the exact bytes this rewrite produces on an official definition.
     * Derivation only — no host class is resolved and no admission is implied;
     * {@link #transform} remains the single admitting entry point. Consumers use
     * it to reproduce the retransformation baseline this hook leaves behind.
     *
     * @param reference official reference bytes of the reviewed shader class
     * @return the rewritten definition, or {@code null} when the rewrite cannot run
     */
    public static byte[] composedDefinition(byte[] reference) {
        try {
            return patch(reference);
        } catch (Exception | LinkageError rejected) {
            return null;
        }
    }

    /**
     * @return {@code true} when the exact reviewed shader definition was transformed
     */
    public boolean patched() {
        return patched;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> prior, ProtectionDomain domain, byte[] bytes) {
        if (!OWNER.equals(name)) return null;
        if (prior != null || bytes == null || domain == null || domain.getCodeSource() == null) {
            report("DECLINED_DEFINITION");
            return null;
        }
        try {
            String classSha = digest(bytes);
            if (!reviewedClassDigest(classSha)) {
                report("DECLINED_CLASS_HASH");
                return null;
            }
            Path artifactPath = Path.of(domain.getCodeSource().getLocation().toURI());
            HostArtifactDigest artifact = HostArtifactDigest.from(artifactPath);
            if (!admits(classSha, artifact)) {
                report("DECLINED_ARTIFACT_HASH");
                return null;
            }
            byte[] result = patch(bytes);
            patched = true;
            markPatched(loader, OWNER);
            NativeShaderUniformBridge.patched(true);
            report("PATCHED_"
                    + ReviewedHostArtifacts.cubismVersionOf(artifact)
                            .orElseThrow()
                            .replace(".", ""));
            return result;
        } catch (Exception | LinkageError rejected) {
            report("DECLINED_SHAPE_OR_SOURCE");
            return null;
        }
    }

    static String digest(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Returns the reviewed GShader digest table, keyed by Cubism version.
     *
     * @return the immutable {@code version -> class -> sha256} pin table
     */
    public static Map<String, Map<String, String>> reviewedClassSha256() {
        return REVIEWED_CLASSES;
    }

    static boolean reviewedClassDigest(String classSha256) {
        for (Map<String, String> block : REVIEWED_CLASSES.values())
            if (classSha256.equals(block.get(OWNER))) return true;
        return false;
    }

    static boolean admits(String classSha256, HostArtifactDigest artifact) {
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
                .map(version -> admitsVersion(classSha256, version))
                .orElse(false);
    }

    static boolean admitsVersion(String classSha256, String version) {
        Map<String, String> block = REVIEWED_CLASSES.get(version);
        return block != null && classSha256.equals(block.get(OWNER));
    }

    static byte[] patch(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().equals(OWNER)) throw new IllegalArgumentException("shader owner");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] count = {0, 0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                        boolean drawing = name.equals(DRAW_METHOD) && descriptor.equals(DRAW_DESC);
                        boolean lifecycle = (name.equals(LIFECYCLE_METHODS[0]) && descriptor.equals(LIFECYCLE_DESCS[0]))
                                || (name.equals(LIFECYCLE_METHODS[1]) && descriptor.equals(LIFECYCLE_DESCS[1]));
                        if (!drawing && !lifecycle) return delegate;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                if (lifecycle) {
                                    count[1]++;
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            BRIDGE_OWNER,
                                            "invalidate",
                                            "(Ljava/lang/Object;)V",
                                            false);
                                }
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode, String owner, String method, String descriptor, boolean itf) {
                                if (drawing
                                        && opcode == Opcodes.INVOKEINTERFACE
                                        && owner.equals(GL_OWNER)
                                        && method.equals(QUERY_METHOD)
                                        && descriptor.equals(QUERY_DESC)) {
                                    count[0]++;
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            BRIDGE_OWNER,
                                            BRIDGE_LOOKUP,
                                            BRIDGE_LOOKUP_DESCRIPTOR,
                                            false);
                                } else super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                            }
                        };
                    }
                },
                0);
        if (count[0] != 1 || count[1] != 2)
            throw new IllegalArgumentException("shader hook cardinality " + java.util.Arrays.toString(count));
        return writer.toByteArray();
    }

    private void report(String code) {
        try {
            diagnostic.accept("SHADER_UNIFORM_" + code);
        } catch (RuntimeException ignored) {
        }
    }
}

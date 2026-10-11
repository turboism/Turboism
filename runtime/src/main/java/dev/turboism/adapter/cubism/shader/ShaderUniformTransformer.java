package dev.turboism.adapter.cubism.shader;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.objectweb.asm.*;

/** First-definition-only hook for the exact reviewed host shaders. Hash admission never resolves host classes. */
public final class ShaderUniformTransformer implements ClassFileTransformer {
    public static final String OWNER = "com/live2d/graphics3d/shader/GShader";
    /** Reviewed GShader bytes shared by the 5.3.02 and 5.3.03 host artifacts. */
    public static final String CLASS_SHA256 = "efed635454ed364f26f7aa1673c602cba35b00b4396ba03dee631dfc6450b6e0";
    /** Reviewed GShader bytes of the 5.2.03 host artifact. */
    public static final String CLASS_SHA256_5_2 = "172952b8b422f3b6b0610bc614672cad7259e33e5186e5128401dd40dadef7da";

    static final String DRAW_METHOD = "preDraw_exe";
    static final String DRAW_DESC =
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/graphics3d/material/GMaterial;Lcom/live2d/graphics3d/type/GMatrix44;)V";
    static final String GL_OWNER = "com/jogamp/opengl/GL3";
    static final String QUERY_METHOD = "glGetUniformLocation";
    static final String QUERY_DESC = "(ILjava/lang/String;)I";
    static final String[] LIFECYCLE_METHODS = {"init_exe", "setGlProgramID"};
    static final String[] LIFECYCLE_DESCS = {"(Lcom/live2d/graphics3d/a;)V", "(I)V"};
    static final String HELPER = "dev/turboism/adapter/cubism/shader/NativeShaderUniformBridge";
    private static final Map<String, List<HostArtifactDigest>> REVIEWED_CLASSES = Map.of(
            CLASS_SHA256, List.of(ReviewedHostArtifacts.CUBISM_5_3_02, ReviewedHostArtifacts.CUBISM_5_3_03),
            CLASS_SHA256_5_2, List.of(ReviewedHostArtifacts.CUBISM_5_2_03));
    private final Consumer<String> diagnostic;
    private volatile boolean patched;

    public ShaderUniformTransformer(Consumer<String> diagnostic) {
        this.diagnostic = java.util.Objects.requireNonNull(diagnostic);
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
            if (!REVIEWED_CLASSES.containsKey(classSha)) {
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

    static boolean admits(String classSha256, HostArtifactDigest artifact) {
        return REVIEWED_CLASSES.getOrDefault(classSha256, List.of()).contains(artifact);
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
                                            Opcodes.INVOKESTATIC, HELPER, "invalidate", "(Ljava/lang/Object;)V", false);
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
                                            HELPER,
                                            "lookup",
                                            "(Ljava/lang/Object;ILjava/lang/String;Ljava/lang/Object;)I",
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

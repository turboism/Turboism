package dev.turboism.diagnostic;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType;
import org.objectweb.asm.*;

/** Isolated JVM diagnostic. Never install this agent into the user's Editor. */
public final class CatInterpolationDiagnosticAgent {
    public static boolean enabled;
    public static long accepted, refused;
    public static boolean transformed;
    private static final String OWNER = "com/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension";
    private static final String DESC = "(Ljava/util/List;Ljava/util/List;Ljava/util/List;Lcom/live2d/cubism/doc/model/interpolator/extendedInterpolation/ExtendedInterpolationType;F)V";

    public static boolean tryFast(List<? extends ACForm> target, List<? extends ACForm> source,
            List<CExtendedInterpolationExtension.b> keys, ExtendedInterpolationType type, float scale) {
        if (!enabled || type != ExtendedInterpolationType.ELLIPSE) return false;
        if (FastMeshKernelProof.fast(target, source, keys, type, scale)) { accepted++; return true; }
        refused++;
        return false;
    }

    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> prior,
                    ProtectionDomain domain, byte[] bytes) {
                if (!OWNER.equals(name)) return null;
                try {
                    Path artifact = Path.of(domain.getCodeSource().getLocation().toURI());
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
                    if (!digest.equals("988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"))
                        throw new IllegalStateException("unreviewed official artifact");
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    int[] matches = {0};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                            MethodVisitor original = super.visitMethod(access, method, desc, signature, exceptions);
                            if (!method.equals("updateInterpolatedForms_common") || !desc.equals(DESC)) return original;
                            matches[0]++;
                            return new MethodVisitor(Opcodes.ASM9, original) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    for (int slot = 1; slot <= 4; slot++) visitVarInsn(Opcodes.ALOAD, slot);
                                    visitVarInsn(Opcodes.FLOAD, 5);
                                    visitMethodInsn(Opcodes.INVOKESTATIC, "dev/turboism/diagnostic/CatInterpolationDiagnosticAgent", "tryFast", DESC.substring(0, DESC.length()-1) + "Z", false);
                                    Label fallback = new Label();
                                    visitJumpInsn(Opcodes.IFEQ, fallback);
                                    visitInsn(Opcodes.RETURN);
                                    visitLabel(fallback);
                                    visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                }
                            };
                        }
                    }, 0);
                    if (matches[0] != 1) throw new IllegalStateException("method cardinality " + matches[0]);
                    transformed = true;
                    return writer.toByteArray();
                } catch (Exception failure) { throw new IllegalStateException("diagnostic transformation refused", failure); }
            }
        });
    }
}

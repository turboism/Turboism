package dev.turboism.adapter.cubism.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

final class ShaderUniformTransformerTest {
    static final String OWNER = ShaderUniformTransformer.OWNER;
    static final String DRAW = ShaderUniformTransformer.DRAW_METHOD;
    static final String DRAW_DESC = ShaderUniformTransformer.DRAW_DESC;

    static byte[] fixture(String owner, int drawCalls, boolean lifecycleA, boolean lifecycleB) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor draw = writer.visitMethod(Opcodes.ACC_PUBLIC, DRAW, DRAW_DESC, null, null);
        draw.visitCode();
        for (int i = 0; i < drawCalls; i++) {
            draw.visitInsn(Opcodes.ACONST_NULL);
            draw.visitTypeInsn(Opcodes.CHECKCAST, ShaderUniformTransformer.GL_OWNER);
            draw.visitInsn(Opcodes.ICONST_0);
            draw.visitLdcInsn("u");
            draw.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    ShaderUniformTransformer.GL_OWNER,
                    ShaderUniformTransformer.QUERY_METHOD,
                    ShaderUniformTransformer.QUERY_DESC,
                    true);
            draw.visitInsn(Opcodes.POP);
        }
        draw.visitInsn(Opcodes.RETURN);
        draw.visitMaxs(4, 4);
        draw.visitEnd();
        if (lifecycleA) {
            MethodVisitor init = writer.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    ShaderUniformTransformer.LIFECYCLE_METHODS[0],
                    ShaderUniformTransformer.LIFECYCLE_DESCS[0],
                    null,
                    null);
            init.visitCode();
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
        }
        if (lifecycleB) {
            MethodVisitor program = writer.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    ShaderUniformTransformer.LIFECYCLE_METHODS[1],
                    ShaderUniformTransformer.LIFECYCLE_DESCS[1],
                    null,
                    null);
            program.visitCode();
            program.visitInsn(Opcodes.RETURN);
            program.visitMaxs(1, 2);
            program.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void patchRewritesSingleDrawQueryAndAddsTwoLifecycleInvalidations() {
        byte[] patched = ShaderUniformTransformer.patch(fixture(OWNER, 1, true, true));
        int[] count = new int[4];
        new ClassReader(patched)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String signature, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String method, String descriptor, boolean itf) {
                                        if (owner.equals(ShaderUniformTransformer.BRIDGE_OWNER)) {
                                            if (method.equals("lookup")) count[0]++;
                                            if (method.equals("invalidate")) count[1]++;
                                        }
                                        if (method.equals(ShaderUniformTransformer.QUERY_METHOD)) count[2]++;
                                        if (method.equals("<init>")) count[3]++;
                                    }
                                };
                            }
                        },
                        0);
        assertEquals(1, count[0]);
        assertEquals(2, count[1]);
        assertEquals(0, count[2], "native query must be fully delegated to the bridge");
    }

    @Test
    void wrongOwnerMissingDrawOrExtraCallsCannotPatch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderUniformTransformer.patch(fixture("other/Owner", 1, true, true)));
        assertThrows(
                IllegalArgumentException.class, () -> ShaderUniformTransformer.patch(fixture(OWNER, 0, true, true)));
        assertThrows(
                IllegalArgumentException.class, () -> ShaderUniformTransformer.patch(fixture(OWNER, 2, true, true)));
        assertThrows(
                IllegalArgumentException.class, () -> ShaderUniformTransformer.patch(fixture(OWNER, 1, false, true)));
        assertThrows(
                IllegalArgumentException.class, () -> ShaderUniformTransformer.patch(fixture(OWNER, 1, true, false)));
    }

    @Test
    void unreviewedBytesAndRedefinitionsAreNeverTransformed() throws Exception {
        var reports = new ArrayList<String>();
        var transformer = new ShaderUniformTransformer(reports::add);
        byte[] bytes = fixture(OWNER, 1, true, true);
        var source = new java.security.CodeSource(
                java.net.URI.create("file:/tmp/unreviewed.jar").toURL(), (java.security.cert.Certificate[]) null);
        var domain = new java.security.ProtectionDomain(source, null);
        // An unreachable or unreviewed artifact declines before any class bytes are rewritten.
        assertNull(transformer.transform(getClass().getClassLoader(), OWNER, null, domain, bytes));
        assertEquals(List.of("SHADER_UNIFORM_DECLINED_SHAPE_OR_SOURCE"), reports);
        assertFalse(transformer.patched());
        assertNull(transformer.transform(getClass().getClassLoader(), OWNER, Object.class, domain, bytes));
        assertEquals(List.of("SHADER_UNIFORM_DECLINED_SHAPE_OR_SOURCE", "SHADER_UNIFORM_DECLINED_DEFINITION"), reports);
    }

    @Test
    void unrelatedClassesPassThroughUntouched() {
        var transformer = new ShaderUniformTransformer(ignored -> {});
        assertNull(transformer.transform(getClass().getClassLoader(), "unrelated/Class", null, null, new byte[0]));
        assertFalse(transformer.patched());
    }

    @Test
    void everyAdmittedVersionDeclaresTheShaderOwner() {
        var targets = ShaderUniformTransformer.reviewedClassSha256();
        assertEquals(
                dev.turboism.mapping.verification.ReviewedHostArtifacts.reviewedCubismVersions().stream()
                        .sorted()
                        .toList(),
                targets.keySet().stream().sorted().toList(),
                "the hook must declare every adapted host version");
        for (var entry : targets.entrySet()) {
            assertEquals(
                    java.util.List.of(ShaderUniformTransformer.OWNER),
                    entry.getValue(),
                    entry.getKey() + " must declare the reviewed shader owner");
            assertTrue(ShaderUniformTransformer.admitsVersion(entry.getKey()));
        }
        assertFalse(ShaderUniformTransformer.admitsVersion("5.9.99"));
    }

    @Test
    void reviewedArtifactsResolveTheirDeclaredOwnerAndRejectForeignArtifacts() {
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_3_02));
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_3_04));
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_2_03));
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_2_00));
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_2_02));
        assertFalse(ShaderUniformTransformer.admits(
                new dev.turboism.mapping.verification.HostArtifactDigest(1L, "0".repeat(64))));
    }

    @Test
    void patchRecordsAreLoaderAndOwnerExact() {
        var loader = new java.net.URLClassLoader(new java.net.URL[0], null);
        assertFalse(ShaderUniformTransformer.isPatched(loader, OWNER));
        ShaderUniformTransformer.markPatched(loader, OWNER);
        assertTrue(ShaderUniformTransformer.isPatched(loader, OWNER));
        assertFalse(ShaderUniformTransformer.isPatched(getClass().getClassLoader(), OWNER));
        assertFalse(ShaderUniformTransformer.isPatched(loader, "other/Owner"));
        ShaderUniformTransformer.clearPatched(loader, OWNER);
        assertFalse(ShaderUniformTransformer.isPatched(loader, OWNER));
    }

    @Test
    void composedShapeIsTheRewrittenBodyAndNullForUnpatchableBytes() {
        byte[] official = fixture(OWNER, 1, true, true);
        var composed = ShaderUniformTransformer.composedShape(official);
        assertNotNull(composed);
        assertEquals(
                dev.turboism.adapter.cubism.optimization.ReviewedMethodShape.read(
                        ShaderUniformTransformer.patch(official), OWNER, DRAW, DRAW_DESC),
                composed);
        assertNotEquals(
                dev.turboism.adapter.cubism.optimization.ReviewedMethodShape.read(official, OWNER, DRAW, DRAW_DESC),
                composed,
                "the composed shape must differ from the official one");
        assertNull(ShaderUniformTransformer.composedShape(fixture(OWNER, 2, true, true)));
        assertNull(ShaderUniformTransformer.composedShape(fixture("other/Owner", 1, true, true)));
    }
}

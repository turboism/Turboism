package dev.turboism.adapter.cubism.interpolation;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

final class MeshInterpolationTransformerTest {
    static byte[] fixture(String owner, String method, String descriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method, descriptor, null, null);
        mv.visitCode(); mv.visitLdcInsn("official-body"); mv.visitInsn(Opcodes.POP); mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 6); mv.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void patchAddsConditionalBridgeAndPreservesOfficialBody() {
        byte[] bytes = fixture(MeshInterpolationTransformer.OWNER, MeshInterpolationTransformer.METHOD, MeshInterpolationTransformer.DESC);
        byte[] patched = MeshInterpolationTransformer.patch(bytes);
        var calls = new ArrayList<String>(); var literals = new ArrayList<Object>(); var jumps = new ArrayList<Integer>();
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean iface) { calls.add(owner+"."+method+descriptor); }
                    @Override public void visitLdcInsn(Object value) { literals.add(value); }
                    @Override public void visitJumpInsn(int opcode, Label label) { jumps.add(opcode); }
                };
            }
        }, 0);
        assertEquals(1, calls.size());
        assertEquals("dev/turboism/adapter/cubism/interpolation/NativeMeshInterpolationBridge.tryUpdate(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/lang/Object;F)Z", calls.get(0));
        assertEquals(java.util.List.of(Opcodes.IFEQ), jumps);
        assertEquals(java.util.List.of("official-body"), literals);
    }

    @Test void wrongOwnerMethodOrDescriptorCannotPatch() {
        assertThrows(IllegalArgumentException.class, () -> MeshInterpolationTransformer.patch(fixture("other/Owner", MeshInterpolationTransformer.METHOD, MeshInterpolationTransformer.DESC)));
        assertThrows(IllegalArgumentException.class, () -> MeshInterpolationTransformer.patch(fixture(MeshInterpolationTransformer.OWNER, "other", MeshInterpolationTransformer.DESC)));
        assertThrows(IllegalArgumentException.class, () -> MeshInterpolationTransformer.patch(fixture(MeshInterpolationTransformer.OWNER, MeshInterpolationTransformer.METHOD, "()V")));
    }

    @Test void unreviewedBytesAndRedefinitionsAreNeverTransformed() throws Exception {
        var reports = new ArrayList<String>();
        var transformer = new MeshInterpolationTransformer(reports::add);
        byte[] bytes = fixture(MeshInterpolationTransformer.OWNER, MeshInterpolationTransformer.METHOD, MeshInterpolationTransformer.DESC);
        var source = new java.security.CodeSource(java.net.URI.create("file:/tmp/unreviewed.jar").toURL(), (java.security.cert.Certificate[])null);
        var domain = new java.security.ProtectionDomain(source, null);
        assertNull(transformer.transform(getClass().getClassLoader(), MeshInterpolationTransformer.OWNER, null, domain, bytes));
        assertEquals(java.util.List.of("MESH_INTERPOLATION_DECLINED_CLASS_HASH"), reports);
        assertFalse(transformer.patched());
        assertNull(transformer.transform(getClass().getClassLoader(), MeshInterpolationTransformer.OWNER, Object.class, domain, bytes));
    }
}

package dev.turboism.adapter.cubism.mesh;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for the triangulation hash fix.
 *
 * <p>The fixture is generated at test time with the reviewed class name and shape, so the test
 * exercises the real selector and the real patch without depending on, or loading, any official
 * class.</p>
 */
final class MeshTriangulationHashTransformerTest {

    @Test
    void patchesTheReviewedShapeAndKeepsHashesPermutationInvariant() throws Exception {
        final byte[] fixture = fixtureClassBytes();
        final String digest = MeshTriangulationHashTransformer.sha256(fixture);
        final MeshTriangulationHashTransformer transformer =
            new MeshTriangulationHashTransformer(digest);

        final byte[] patched = transformer.transform(
            null, MeshTriangulationHashTransformer.TARGET_INTERNAL_NAME, null, null, fixture);

        assertNotNull(patched, "the reviewed shape must be patched");
        assertEquals(MeshTriangulationHashTransformer.Outcome.PATCHED, transformer.outcome());

        final Loader loader = new Loader(patched);
        final Class<?> point = Class.forName(
            "com.live2d.graphics3d.editableMesh.triangulation.TriPoint", true, loader);
        final Class<?> triple = Class.forName(
            "com.live2d.graphics3d.editableMesh.triangulation.l", true, loader);

        // The constant hash collapsed every instance into one value; the fix must not.
        final Set<Integer> hashes = new HashSet<>();
        for (int index = 0; index < 32; index++) {
            hashes.add(hashOf(newTriple(triple, point, index, index * 2.0f)));
        }
        assertEquals(32, hashes.size(), "distinct coordinates must produce distinct hashes");

        // The host's equals accepts all six corner orders, so the hash must ignore the order too:
        // the same three points arranged differently must hash identically.
        final Constructor<?> pointConstructor =
            point.getConstructor(float.class, float.class, int.class);
        final Object first = pointConstructor.newInstance(1.0f, 2.0f, 1);
        final Object second = pointConstructor.newInstance(3.0f, 4.0f, 2);
        final Object third = pointConstructor.newInstance(5.0f, 6.0f, 3);
        final Constructor<?> tripleConstructor = triple.getConstructor(point, point, point);
        final int forward = tripleConstructor.newInstance(first, second, third).hashCode();
        final int rotated = tripleConstructor.newInstance(third, first, second).hashCode();
        final int swapped = tripleConstructor.newInstance(first, third, second).hashCode();
        assertEquals(forward, rotated, "a rotated corner order must hash identically");
        assertEquals(forward, swapped, "a swapped corner order must hash identically");
    }

    @Test
    void refusesClassesWhoseBytesAreNotTheReviewedDigest() {
        final MeshTriangulationHashTransformer transformer = new MeshTriangulationHashTransformer();
        final byte[] fixture = fixtureClassBytes();

        assertNull(transformer.transform(null,
            MeshTriangulationHashTransformer.TARGET_INTERNAL_NAME, null, null, fixture));
        assertEquals(MeshTriangulationHashTransformer.Outcome.HASH_MISMATCH,
            transformer.outcome());
    }

    @Test
    void ignoresEveryOtherClassAndNullBytes() {
        final MeshTriangulationHashTransformer transformer = new MeshTriangulationHashTransformer();

        assertNull(transformer.transform(null, "com/example/Other", null, null,
            fixtureClassBytes()));
        assertNull(transformer.transform(null,
            MeshTriangulationHashTransformer.TARGET_INTERNAL_NAME, null, null, null));
        assertEquals(MeshTriangulationHashTransformer.Outcome.NONE, transformer.outcome());
    }

    @Test
    void refusesTheReviewedDigestWhenTheShapeIsUnexpected() {
        // Same pinned digest, but the class no longer has the reviewed constant-return body.
        final byte[] unexpected = new ClassWriter(0).toByteArray();
        final MeshTriangulationHashTransformer transformer =
            new MeshTriangulationHashTransformer(
                MeshTriangulationHashTransformer.sha256(unexpected));

        assertNull(transformer.transform(null,
            MeshTriangulationHashTransformer.TARGET_INTERNAL_NAME, null, null, unexpected));
        assertTrue(transformer.outcome() == MeshTriangulationHashTransformer.Outcome.SHAPE_REJECTED
                || transformer.outcome() == MeshTriangulationHashTransformer.Outcome.HASH_MISMATCH,
            "an unexpected shape must be reported, never silently patched");
    }

    private static int hashOf(final Object instance) throws Exception {
        final Method hashCode = instance.getClass().getMethod("hashCode");
        return (int) hashCode.invoke(instance);
    }

    private static Object newTriple(final Class<?> triple, final Class<?> point, final int seed,
                                    final float base) throws Exception {
        final Constructor<?> pointConstructor =
            point.getConstructor(float.class, float.class, int.class);
        final Object first = pointConstructor.newInstance(base, base * 3.0f, seed);
        final Object second = pointConstructor.newInstance(base + 1.0f, base * 3.0f + 1.0f, seed + 1);
        final Object third = pointConstructor.newInstance(base + 2.0f, base * 3.0f + 2.0f, seed + 2);
        return triple.getConstructor(point, point, point).newInstance(first, second, third);
    }

    /** Minimal child loader so the patched bytes replace the constant hash in one JVM. */
    private static final class Loader extends ClassLoader {
        private final byte[] triple;

        Loader(final byte[] triple) {
            super(MeshTriangulationHashTransformerTest.class.getClassLoader());
            this.triple = triple;
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            if (name.endsWith(".l")) {
                return defineClass(name, triple, 0, triple.length);
            }
            if (name.endsWith(".TriPoint")) {
                return defineClass(name, pointBytes(), 0, pointBytes().length);
            }
            return super.findClass(name);
        }
    }

    /** The reviewed class name and shape, with the defective constant hash. */
    private static byte[] fixtureClassBytes() {
        final String internal =
            "com/live2d/graphics3d/editableMesh/triangulation/l";
        final String point = "com/live2d/graphics3d/editableMesh/triangulation/TriPoint";
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internal, null,
            "java/lang/Object", null);
        for (final String field : new String[] {"a", "b", "c"}) {
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, field, "L" + point + ";",
                null, null).visitEnd();
        }
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
            "(L" + point + ";L" + point + ";L" + point + ";)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V",
            false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, internal, "a", "L" + point + ";");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 2);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, internal, "b", "L" + point + ";");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 3);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, internal, "c", "L" + point + ";");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 4);
        constructor.visitEnd();
        final MethodVisitor hashCode = writer.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I",
            null, null);
        hashCode.visitCode();
        hashCode.visitInsn(Opcodes.ICONST_0);
        hashCode.visitInsn(Opcodes.IRETURN);
        hashCode.visitMaxs(1, 1);
        hashCode.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** The point type the fixture references: equals on coordinates, hash mixed with the index. */
    private static byte[] pointBytes() {
        final String internal =
            "com/live2d/graphics3d/editableMesh/triangulation/TriPoint";
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internal, null,
            "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "x", "F", null, null)
            .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "y", "F", null, null)
            .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "index", "I", null, null)
            .visitEnd();
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
            "(FFI)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V",
            false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.FLOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, internal, "x", "F");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.FLOAD, 2);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, internal, "y", "F");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 3);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, internal, "index", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 4);
        constructor.visitEnd();
        for (final String[] accessor : new String[][] {{"getX", "F", "x"}, {"getY", "F", "y"},
            {"getIndex", "I", "index"}}) {
            final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, accessor[0],
                "()" + accessor[1], null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, internal, accessor[2], accessor[1]);
            method.visitInsn(accessor[1].equals("F") ? Opcodes.FRETURN : Opcodes.IRETURN);
            method.visitMaxs(1, 1);
            method.visitEnd();
        }
        final MethodVisitor hashCode = writer.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I",
            null, null);
        hashCode.visitCode();
        hashCode.visitVarInsn(Opcodes.ALOAD, 0);
        hashCode.visitFieldInsn(Opcodes.GETFIELD, internal, "index", "I");
        hashCode.visitIntInsn(Opcodes.BIPUSH, 31);
        hashCode.visitInsn(Opcodes.IMUL);
        hashCode.visitVarInsn(Opcodes.ALOAD, 0);
        hashCode.visitFieldInsn(Opcodes.GETFIELD, internal, "x", "F");
        hashCode.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "hashCode", "(F)I",
            false);
        hashCode.visitInsn(Opcodes.IADD);
        hashCode.visitInsn(Opcodes.IRETURN);
        hashCode.visitMaxs(2, 1);
        hashCode.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}

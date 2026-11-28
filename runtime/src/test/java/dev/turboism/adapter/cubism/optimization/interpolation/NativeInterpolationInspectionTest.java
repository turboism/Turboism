package dev.turboism.adapter.cubism.optimization.interpolation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class NativeInterpolationInspectionTest {
    private static final String ISOLATED_FORM_NAME = "com.example.isolated.IsolatedArtMeshForm";
    private static final String ISOLATED_VECTOR_NAME = "com.example.isolated.IsolatedVector2";
    private static final float EXPECTED_X = 1.25f;
    private static final float EXPECTED_Y = -2.5f;

    @Test
    void directLambdaBindingsDeclineChildClassLoaderHostTypesAndBindSameLoaderTypes() throws Exception {
        byte[] formBytes = buildIsolatedFormClass(ISOLATED_FORM_NAME.replace('.', '/'));
        byte[] vectorBytes = buildIsolatedVectorClass(ISOLATED_VECTOR_NAME.replace('.', '/'));

        var childLoader = new ByteArrayClassLoader(NativeMeshInterpolationBridge.class.getClassLoader());
        Class<?> childFormClass = childLoader.define(ISOLATED_FORM_NAME, formBytes);
        Class<?> childVectorClass = childLoader.define(ISOLATED_VECTOR_NAME, vectorBytes);

        // Host types loaded in a child classloader are invisible to the bridge classloader.
        assertNull(NativeMeshInterpolationBridge.Access.directUpdate(childFormClass));
        assertNull(NativeMeshInterpolationBridge.Access.bindVectorSetter(childVectorClass));
        assertNull(NativeMeshInterpolationBridge.Access.bindFloatGetter(childVectorClass, "getX"));

        var sameLoaderForm = new SameLoaderForm();
        Consumer<Object> directUpdated = NativeMeshInterpolationBridge.Access.directUpdate(SameLoaderForm.class);
        assertNotNull(directUpdated);
        directUpdated.accept(sameLoaderForm);
        assertEquals(1, sameLoaderForm.updates);

        var sameLoaderVector = new SameLoaderVector();
        NativeMeshInterpolationBridge.VectorSetter setter =
                NativeMeshInterpolationBridge.Access.bindVectorSetter(SameLoaderVector.class);
        NativeMeshInterpolationBridge.FloatGetter getX =
                NativeMeshInterpolationBridge.Access.bindFloatGetter(SameLoaderVector.class, "getX");
        NativeMeshInterpolationBridge.FloatGetter getY =
                NativeMeshInterpolationBridge.Access.bindFloatGetter(SameLoaderVector.class, "getY");
        assertNotNull(setter);
        assertNotNull(getX);
        assertNotNull(getY);
        setter.set(sameLoaderVector, EXPECTED_X, EXPECTED_Y);
        assertEquals(EXPECTED_X, getX.get(sameLoaderVector));
        assertEquals(EXPECTED_Y, getY.get(sameLoaderVector));
    }

    public static final class SameLoaderForm {
        int updates;

        public void setUpdated() {
            updates++;
        }
    }

    public static final class SameLoaderVector {
        private float x;
        private float y;

        public void set(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }
    }

    private static byte[] buildIsolatedFormClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "updates", "I", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor setUpdated = cw.visitMethod(Opcodes.ACC_PUBLIC, "setUpdated", "()V", null, null);
        setUpdated.visitCode();
        setUpdated.visitVarInsn(Opcodes.ALOAD, 0);
        setUpdated.visitInsn(Opcodes.DUP);
        setUpdated.visitFieldInsn(Opcodes.GETFIELD, internalName, "updates", "I");
        setUpdated.visitInsn(Opcodes.ICONST_1);
        setUpdated.visitInsn(Opcodes.IADD);
        setUpdated.visitFieldInsn(Opcodes.PUTFIELD, internalName, "updates", "I");
        setUpdated.visitInsn(Opcodes.RETURN);
        setUpdated.visitMaxs(0, 0);
        setUpdated.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildIsolatedVectorClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "x", "F", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "y", "F", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor set = cw.visitMethod(Opcodes.ACC_PUBLIC, "set", "(FF)V", null, null);
        set.visitCode();
        set.visitVarInsn(Opcodes.ALOAD, 0);
        set.visitVarInsn(Opcodes.FLOAD, 1);
        set.visitFieldInsn(Opcodes.PUTFIELD, internalName, "x", "F");
        set.visitVarInsn(Opcodes.ALOAD, 0);
        set.visitVarInsn(Opcodes.FLOAD, 2);
        set.visitFieldInsn(Opcodes.PUTFIELD, internalName, "y", "F");
        set.visitInsn(Opcodes.RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        MethodVisitor getX = cw.visitMethod(Opcodes.ACC_PUBLIC, "getX", "()F", null, null);
        getX.visitCode();
        getX.visitVarInsn(Opcodes.ALOAD, 0);
        getX.visitFieldInsn(Opcodes.GETFIELD, internalName, "x", "F");
        getX.visitInsn(Opcodes.FRETURN);
        getX.visitMaxs(0, 0);
        getX.visitEnd();

        MethodVisitor getY = cw.visitMethod(Opcodes.ACC_PUBLIC, "getY", "()F", null, null);
        getY.visitCode();
        getY.visitVarInsn(Opcodes.ALOAD, 0);
        getY.visitFieldInsn(Opcodes.GETFIELD, internalName, "y", "F");
        getY.visitInsn(Opcodes.FRETURN);
        getY.visitMaxs(0, 0);
        getY.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        ByteArrayClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    void nonemptyNormalBankWithNullFirstElementDoesNotSelectMorph() {
        NativeMeshInterpolationBridge.enabled(true);
        try {
            var normal = java.util.Arrays.asList((Object) null);
            assertSame(normal, NativeMeshInterpolationBridge.firstInspectionBank(normal, List.of(new Object())));
        } finally {
            NativeMeshInterpolationBridge.enabled(false);
        }
    }

    @Test
    void firstElementMatchesNativeConcatenationForNormalMorphAndEmptyBanks() {
        NativeMeshInterpolationBridge.enabled(true);
        try {
            Object normalForm = new Object(), morphForm = new Object();
            for (List<?> normal : List.of(List.of(), List.of(normalForm))) {
                for (List<?> morph : List.of(List.of(), List.of(morphForm))) {
                    List<?> selected = NativeMeshInterpolationBridge.firstInspectionBank(normal, morph);
                    Object expected = !normal.isEmpty() ? normal.get(0) : morph.isEmpty() ? null : morph.get(0);
                    assertSame(expected, selected.isEmpty() ? null : selected.get(0));
                    assertSame(normal.isEmpty() ? morph : normal, selected);
                }
            }
        } finally {
            NativeMeshInterpolationBridge.enabled(false);
        }
    }

    @Test
    void disabledInstallationKeepsFullNativeBankOrderAndSnapshotSemantics() {
        NativeMeshInterpolationBridge.enabled(false);
        var normal = new java.util.ArrayList<>(List.of(new Object()));
        var morph = List.of(new Object());
        List<?> selected = NativeMeshInterpolationBridge.firstInspectionBank(normal, morph);
        normal.clear();
        assertEquals(2, selected.size());
        assertSame(morph.get(0), selected.get(1));
    }
}

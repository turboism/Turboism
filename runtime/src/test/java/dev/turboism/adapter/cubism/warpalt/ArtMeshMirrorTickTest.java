package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Regression test suite for rectangle-selection authorized behavior during mirror ticks.
 * Verifies that NativeArtMeshSymmetryBridge and NativeWarpAltMirrorBridge never invoke
 * selection-mutating or diagnostic event-pack methods when mirror is disabled or lacks
 * ArtMesh participants, correctly maintain and clear CURRENT state, and enforce typed,
 * pure read-only accessor bytecode verification on native action managers.
 */
final class ArtMeshMirrorTickTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        NativeArtMeshSymmetryBridge.service().resetSession();
        NativeWarpAltMirrorBridge.uninstall();
    }

    @Test
    void mirrorOffInvokesZeroMethodsOnHostilePack() {
        final HostilePack pack = new HostilePack();

        // 1. ArtMesh symmetry mode is 0 (off), bridge not installed
        NativeArtMeshSymmetryBridge.setMode(0);
        NativeArtMeshSymmetryBridge.beginTick(pack);
        assertEquals(0, pack.abCalls);
        assertEquals(0, pack.avCalls);
        assertEquals(0, pack.awCalls);
        assertEquals(0, pack.axCalls);

        // 2. WarpAltMirror bridge installed with enabled = false
        NativeWarpAltMirrorBridge.install(false);
        NativeArtMeshSymmetryBridge.beginTick(pack);
        assertEquals(0, pack.abCalls);
        assertEquals(0, pack.avCalls);
        assertEquals(0, pack.awCalls);
        assertEquals(0, pack.axCalls);

        // 3. ArtMesh mode set to 2, but bridge remains inactive
        NativeArtMeshSymmetryBridge.setMode(2);
        NativeArtMeshSymmetryBridge.beginTick(pack);
        assertEquals(0, pack.abCalls);
        assertEquals(0, pack.avCalls);
        assertEquals(0, pack.awCalls);
        assertEquals(0, pack.axCalls);
    }

    @Test
    void noArtMeshParticipantsInvokesZeroMethodsOnHostilePack() {
        final HostilePack pack = new HostilePack();
        NativeWarpAltMirrorBridge.install(true);
        NativeArtMeshSymmetryBridge.setMode(2);

        // Case A: Zero participants active
        assertFalse(NativeArtMeshSymmetryBridge.service().hasParticipants());
        NativeArtMeshSymmetryBridge.beginTick(pack);
        assertEquals(0, pack.abCalls);
        assertEquals(0, pack.avCalls);
        assertEquals(0, pack.awCalls);
        assertEquals(0, pack.axCalls);

        // Case B: Warp-only participant active (moveParticipation), but no ArtMesh participants
        final var warpRegistration =
                NativeWarpAltMirrorBridge.moveParticipation().participate();
        try {
            assertTrue(NativeWarpAltMirrorBridge.moveParticipation().hasParticipants());
            assertFalse(NativeArtMeshSymmetryBridge.service().hasParticipants());
            NativeArtMeshSymmetryBridge.beginTick(pack);
            assertEquals(0, pack.abCalls);
            assertEquals(0, pack.avCalls);
            assertEquals(0, pack.awCalls);
            assertEquals(0, pack.axCalls);
        } finally {
            warpRegistration.close();
        }
    }

    @Test
    void preexistingCurrentClearedWhenDisabledOrInvalidManager() throws Exception {
        final HostilePack pack = new HostilePack();

        // Disabled condition: preexisting CURRENT must be cleared to null without invoking pack
        seedCurrentState();
        assertNotNull(getCurrentState());
        NativeArtMeshSymmetryBridge.beginTick(pack);
        assertNull(getCurrentState());
        assertEquals(0, pack.abCalls);

        // Enabled condition: valid mode, active bridge and registered ArtMesh participant
        NativeWarpAltMirrorBridge.install(true);
        NativeArtMeshSymmetryBridge.setMode(2);
        final var meshRegistration = NativeArtMeshSymmetryBridge.service().participate();
        try {
            // Null manager clears preexisting CURRENT
            seedCurrentState();
            assertNotNull(getCurrentState());
            NativeArtMeshSymmetryBridge.beginTick(null);
            assertNull(getCurrentState());

            // Manager of invalid / unrelated type clears preexisting CURRENT
            seedCurrentState();
            assertNotNull(getCurrentState());
            NativeArtMeshSymmetryBridge.beginTick(new Object());
            assertNull(getCurrentState());
        } finally {
            meshRegistration.close();
        }
    }

    @Test
    void sharedDiagMouseActionWhenDisabledDoesNotProbeEventPackMethods() {
        final HostilePack pack = new HostilePack();
        NativeWarpAltMirrorBridge.install(false);

        NativeWarpAltMirrorBridge.diagMouseAction(pack, pack);
        assertEquals(0, pack.abCalls);
        assertEquals(0, pack.avCalls);
        assertEquals(0, pack.awCalls);
        assertEquals(0, pack.axCalls);

        NativeWarpAltMirrorBridge.diagMouseAction(null, pack);
        assertEquals(0, pack.abCalls);
        assertEquals(0, pack.avCalls);
        assertEquals(0, pack.awCalls);
        assertEquals(0, pack.axCalls);
    }

    @Test
    void enabledPositivePathWithExactClassNames() throws Exception {
        final DynamicClassLoader loader = new DynamicClassLoader(getClass().getClassLoader());
        final Class<?> viewContextClass =
                loader.define("com.live2d.cubism.view.context.CEViewContext", makeCEViewContextBytes());
        final Class<?> modelingViewClass =
                loader.define("com.live2d.cubism.view.context.CEViewContext_ModelingView", makeModelingViewBytes());
        final Class<?> editModeClass =
                loader.define("com.live2d.cubism.doc.modeling.CModelingEditMode_Main", makeModelingEditModeBytes());
        final Class<?> modelClass = loader.define("com.live2d.cubism.doc.model.ModelStub", makeModelStubBytes());
        final Class<?> managerClass = loader.define(
                "com.live2d.cubism.view.context.actionManager.CEActionManager", makeValidActionManagerBytes());

        final Object model = modelClass.getDeclaredConstructor().newInstance();
        final Object editMode =
                editModeClass.getDeclaredConstructor(Object.class).newInstance(model);
        final Object view =
                modelingViewClass.getDeclaredConstructor(Object.class).newInstance(editMode);
        final Object manager =
                managerClass.getDeclaredConstructor(viewContextClass).newInstance(view);

        NativeWarpAltMirrorBridge.install(true);
        NativeArtMeshSymmetryBridge.setMode(2);
        final var meshRegistration = NativeArtMeshSymmetryBridge.service().participate();
        try {
            // NativeWarpAltMirrorBridge.actionView returns exact view context
            final Object resolvedView = NativeWarpAltMirrorBridge.actionView(manager);
            assertSame(view, resolvedView);

            // NativeArtMeshSymmetryBridge.beginTick initializes CURRENT, binds view, clears written
            NativeArtMeshSymmetryBridge.beginTick(manager);
            final Object state = getCurrentState();
            assertNotNull(state);
            assertSame(view, getViewFromState(state));

            final Map<float[], Set<Integer>> written = getWrittenFromState(state);
            assertTrue(written.isEmpty());

            // Add dirty entry and verify beginTick clears it on subsequent tick
            written.put(new float[] {1f, 2f}, new HashSet<>(Set.of(0, 1)));
            assertFalse(written.isEmpty());

            NativeArtMeshSymmetryBridge.beginTick(manager);
            assertTrue(written.isEmpty());
        } finally {
            meshRegistration.close();
        }
    }

    @Test
    void hostileWrongReturnGetterNeverInvokedAndClearsCurrent() throws Exception {
        final DynamicClassLoader loader = new DynamicClassLoader(getClass().getClassLoader());
        final Class<?> hostileManagerClass = loader.define(
                "com.live2d.cubism.view.context.actionManager.CEActionManager", makeHostileVoidActionManagerBytes());
        final Object hostileManager =
                hostileManagerClass.getDeclaredConstructor().newInstance();

        NativeWarpAltMirrorBridge.install(true);
        NativeArtMeshSymmetryBridge.setMode(2);
        final var meshRegistration = NativeArtMeshSymmetryBridge.service().participate();
        try {
            seedCurrentState();
            assertNotNull(getCurrentState());

            NativeArtMeshSymmetryBridge.beginTick(hostileManager);

            final Field invocationsField = hostileManagerClass.getField("invocations");
            assertEquals(0, invocationsField.getInt(null), "wrong-return getter must never be invoked");
            assertNull(getCurrentState(), "stale CURRENT must be cleared when actionView rejects manager");
        } finally {
            meshRegistration.close();
        }
    }

    @Test
    void hostileClassLoaderMismatchGetterNeverInvokedAndClearsCurrent() throws Exception {
        final DynamicClassLoader parentLoader =
                new DynamicClassLoader(getClass().getClassLoader());
        final Class<?> parentViewContextClass =
                parentLoader.define("com.live2d.cubism.view.context.CEViewContext", makeCEViewContextBytes());

        // Child loader defines CEActionManager whose return type resolves to parentLoader's CEViewContext
        final DynamicClassLoader childLoader = new DynamicClassLoader(parentLoader);
        final Class<?> managerClass = childLoader.define(
                "com.live2d.cubism.view.context.actionManager.CEActionManager", makeValidActionManagerBytes());
        final Object managerInstance = managerClass
                .getDeclaredConstructor(parentViewContextClass)
                .newInstance(parentViewContextClass.getDeclaredConstructor().newInstance());

        NativeWarpAltMirrorBridge.install(true);
        NativeArtMeshSymmetryBridge.setMode(2);
        final var meshRegistration = NativeArtMeshSymmetryBridge.service().participate();
        try {
            seedCurrentState();
            assertNotNull(getCurrentState());

            // Because managerClass.getClassLoader() != parentViewContextClass.getClassLoader(), view access is rejected
            NativeArtMeshSymmetryBridge.beginTick(managerInstance);
            assertNull(getCurrentState(), "loader mismatch must reject manager and clear CURRENT");
        } finally {
            meshRegistration.close();
        }
    }

    @Test
    void bytecodeVerificationRequiresPureGetter() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();

        // 1. Pure read-only getter: ALOAD 0; GETFIELD ...; ARETURN;
        final byte[] validBytes = makeValidActionManagerBytes();
        assertTrue(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(validBytes), profile));

        // 2. Mutating getter: side effect before field read
        final byte[] mutatingBytes = makeMutatingActionManagerBytes();
        assertFalse(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(mutatingBytes), profile));

        // 3. Non-final getter
        final byte[] nonFinalBytes = makeActionManagerWithModifiers(Opcodes.ACC_PUBLIC);
        assertFalse(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(nonFinalBytes), profile));

        // 4. Static getter
        final byte[] staticBytes =
                makeActionManagerWithModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC);
        assertFalse(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(staticBytes), profile));

        // 5. Wrong return descriptor (void)
        final byte[] wrongDescBytes = makeHostileVoidActionManagerBytes();
        assertFalse(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(wrongDescBytes), profile));
    }

    // --- Helper classes and dynamic bytecode generators ---

    public static final class HostilePack {
        int abCalls;
        int avCalls;
        int awCalls;
        int axCalls;

        public void ab() {
            abCalls++;
            throw new AssertionError("ab() side-effect method was called unexpectedly");
        }

        public boolean av() {
            avCalls++;
            throw new AssertionError("av() method was called unexpectedly");
        }

        public boolean aw() {
            awCalls++;
            throw new AssertionError("aw() method was called unexpectedly");
        }

        public boolean ax() {
            axCalls++;
            throw new AssertionError("ax() method was called unexpectedly");
        }
    }

    static final class DynamicClassLoader extends ClassLoader {
        DynamicClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] b) {
            return defineClass(name, b, 0, b.length);
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> currentThreadLocal() throws ReflectiveOperationException {
        final Field field = NativeArtMeshSymmetryBridge.class.getDeclaredField("CURRENT");
        field.setAccessible(true);
        return (ThreadLocal<Object>) field.get(null);
    }

    private static void seedCurrentState() throws ReflectiveOperationException {
        final Class<?> stateClass =
                Class.forName("dev.turboism.adapter.cubism.warpalt.NativeArtMeshSymmetryBridge$State");
        final Constructor<?> constructor = stateClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        final Object state = constructor.newInstance();
        currentThreadLocal().set(state);
    }

    private static Object getCurrentState() throws ReflectiveOperationException {
        return currentThreadLocal().get();
    }

    private static Object getViewFromState(final Object state) throws ReflectiveOperationException {
        final Field field = state.getClass().getDeclaredField("view");
        field.setAccessible(true);
        final java.lang.ref.WeakReference<?> ref = (java.lang.ref.WeakReference<?>) field.get(state);
        return ref == null ? null : ref.get();
    }

    @SuppressWarnings("unchecked")
    private static Map<float[], Set<Integer>> getWrittenFromState(final Object state)
            throws ReflectiveOperationException {
        final Field field = state.getClass().getDeclaredField("written");
        field.setAccessible(true);
        return (Map<float[], Set<Integer>>) field.get(state);
    }

    private static byte[] makeCEViewContextBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/live2d/cubism/view/context/CEViewContext",
                null,
                "java/lang/Object",
                null);

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeModelingViewBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/live2d/cubism/view/context/CEViewContext_ModelingView",
                null,
                "com/live2d/cubism/view/context/CEViewContext",
                null);
        cw.visitField(Opcodes.ACC_PUBLIC, "editMode", "Ljava/lang/Object;", null, null)
                .visitEnd();

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "com/live2d/cubism/view/context/CEViewContext", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(
                Opcodes.PUTFIELD,
                "com/live2d/cubism/view/context/CEViewContext_ModelingView",
                "editMode",
                "Ljava/lang/Object;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 2);
        init.visitEnd();

        final MethodVisitor getEdit =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "getCurrentEditMode", "()Ljava/lang/Object;", null, null);
        getEdit.visitCode();
        getEdit.visitVarInsn(Opcodes.ALOAD, 0);
        getEdit.visitFieldInsn(
                Opcodes.GETFIELD,
                "com/live2d/cubism/view/context/CEViewContext_ModelingView",
                "editMode",
                "Ljava/lang/Object;");
        getEdit.visitInsn(Opcodes.ARETURN);
        getEdit.visitMaxs(1, 1);
        getEdit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeModelingEditModeBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/live2d/cubism/doc/modeling/CModelingEditMode_Main",
                null,
                "java/lang/Object",
                null);
        cw.visitField(Opcodes.ACC_PUBLIC, "model", "Ljava/lang/Object;", null, null)
                .visitEnd();

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(
                Opcodes.PUTFIELD,
                "com/live2d/cubism/doc/modeling/CModelingEditMode_Main",
                "model",
                "Ljava/lang/Object;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 2);
        init.visitEnd();

        final MethodVisitor getModel =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "getCurrentModel", "()Ljava/lang/Object;", null, null);
        getModel.visitCode();
        getModel.visitVarInsn(Opcodes.ALOAD, 0);
        getModel.visitFieldInsn(
                Opcodes.GETFIELD,
                "com/live2d/cubism/doc/modeling/CModelingEditMode_Main",
                "model",
                "Ljava/lang/Object;");
        getModel.visitInsn(Opcodes.ARETURN);
        getModel.visitMaxs(1, 1);
        getModel.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeModelStubBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/live2d/cubism/doc/model/ModelStub",
                null,
                "java/lang/Object",
                null);

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        final MethodVisitor getSource =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "getSource", "()Ljava/lang/Object;", null, null);
        getSource.visitCode();
        getSource.visitLdcInsn("stub-source-key");
        getSource.visitInsn(Opcodes.ARETURN);
        getSource.visitMaxs(1, 1);
        getSource.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeValidActionManagerBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/live2d/cubism/view/context/actionManager/CEActionManager",
                null,
                "java/lang/Object",
                null);
        cw.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                        "viewContext",
                        "Lcom/live2d/cubism/view/context/CEViewContext;",
                        null,
                        null)
                .visitEnd();

        final MethodVisitor init = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(Lcom/live2d/cubism/view/context/CEViewContext;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(
                Opcodes.PUTFIELD,
                "com/live2d/cubism/view/context/actionManager/CEActionManager",
                "viewContext",
                "Lcom/live2d/cubism/view/context/CEViewContext;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 2);
        init.visitEnd();

        final MethodVisitor getView = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "getViewContext",
                "()Lcom/live2d/cubism/view/context/CEViewContext;",
                null,
                null);
        getView.visitCode();
        getView.visitVarInsn(Opcodes.ALOAD, 0);
        getView.visitFieldInsn(
                Opcodes.GETFIELD,
                "com/live2d/cubism/view/context/actionManager/CEActionManager",
                "viewContext",
                "Lcom/live2d/cubism/view/context/CEViewContext;");
        getView.visitInsn(Opcodes.ARETURN);
        getView.visitMaxs(1, 1);
        getView.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeHostileVoidActionManagerBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/live2d/cubism/view/context/actionManager/CEActionManager",
                null,
                "java/lang/Object",
                null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invocations", "I", null, null)
                .visitEnd();

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        final MethodVisitor getView =
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "getViewContext", "()V", null, null);
        getView.visitCode();
        getView.visitFieldInsn(
                Opcodes.GETSTATIC, "com/live2d/cubism/view/context/actionManager/CEActionManager", "invocations", "I");
        getView.visitInsn(Opcodes.ICONST_1);
        getView.visitInsn(Opcodes.IADD);
        getView.visitFieldInsn(
                Opcodes.PUTSTATIC, "com/live2d/cubism/view/context/actionManager/CEActionManager", "invocations", "I");
        getView.visitInsn(Opcodes.RETURN);
        getView.visitMaxs(2, 1);
        getView.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeMutatingActionManagerBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/live2d/cubism/view/context/actionManager/CEActionManager",
                null,
                "java/lang/Object",
                null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sideEffect", "I", null, null)
                .visitEnd();
        cw.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                        "viewContext",
                        "Lcom/live2d/cubism/view/context/CEViewContext;",
                        null,
                        null)
                .visitEnd();

        final MethodVisitor getView = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "getViewContext",
                "()Lcom/live2d/cubism/view/context/CEViewContext;",
                null,
                null);
        getView.visitCode();
        getView.visitInsn(Opcodes.ICONST_1);
        getView.visitFieldInsn(
                Opcodes.PUTSTATIC, "com/live2d/cubism/view/context/actionManager/CEActionManager", "sideEffect", "I");
        getView.visitVarInsn(Opcodes.ALOAD, 0);
        getView.visitFieldInsn(
                Opcodes.GETFIELD,
                "com/live2d/cubism/view/context/actionManager/CEActionManager",
                "viewContext",
                "Lcom/live2d/cubism/view/context/CEViewContext;");
        getView.visitInsn(Opcodes.ARETURN);
        getView.visitMaxs(2, 1);
        getView.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeActionManagerWithModifiers(int methodAccess) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/live2d/cubism/view/context/actionManager/CEActionManager",
                null,
                "java/lang/Object",
                null);
        cw.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                        "viewContext",
                        "Lcom/live2d/cubism/view/context/CEViewContext;",
                        null,
                        null)
                .visitEnd();

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        final MethodVisitor getView = cw.visitMethod(
                methodAccess, "getViewContext", "()Lcom/live2d/cubism/view/context/CEViewContext;", null, null);
        getView.visitCode();
        if ((methodAccess & Opcodes.ACC_STATIC) == 0) {
            getView.visitVarInsn(Opcodes.ALOAD, 0);
            getView.visitFieldInsn(
                    Opcodes.GETFIELD,
                    "com/live2d/cubism/view/context/actionManager/CEActionManager",
                    "viewContext",
                    "Lcom/live2d/cubism/view/context/CEViewContext;");
        } else {
            getView.visitInsn(Opcodes.ACONST_NULL);
        }
        getView.visitInsn(Opcodes.ARETURN);
        getView.visitMaxs(1, 1);
        getView.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}

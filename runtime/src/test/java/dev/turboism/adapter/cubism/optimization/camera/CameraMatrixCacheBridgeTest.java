package dev.turboism.adapter.cubism.optimization.camera;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class CameraMatrixCacheBridgeTest {

    public static final class StubMatrix {}

    public static final class StubVector3 {
        private float x;
        private float y;
        private float z;

        StubVector3(final float x, final float y, final float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }

        public float getZ() {
            return z;
        }

        void set(final float nx, final float ny, final float nz) {
            this.x = nx;
            this.y = ny;
            this.z = nz;
        }
    }

    public static class StubEntity {
        private final StubEntity parentEntity;
        private final StubTransform transform;

        StubEntity(final StubEntity parentEntity, final StubTransform transform) {
            this.parentEntity = parentEntity;
            this.transform = transform;
        }

        public StubEntity getParentEntity() {
            return parentEntity;
        }

        public StubTransform getTransform() {
            return transform;
        }
    }

    public static final class StubCamera extends StubEntity {
        StubCamera(final StubEntity parentEntity, final StubTransform transform) {
            super(parentEntity, transform);
        }
    }

    public static final class StubTransform {
        private StubEntity entity;
        private final StubVector3 position;
        private final StubVector3 eulerAngles;
        private final StubVector3 scale;
        private StubMatrix internalStaticMatrixLocalToParent;

        StubTransform(final StubVector3 position, final StubVector3 eulerAngles, final StubVector3 scale) {
            this.position = position;
            this.eulerAngles = eulerAngles;
            this.scale = scale;
        }

        void attach(final StubEntity owner) {
            this.entity = owner;
        }

        public StubEntity getEntity() {
            return entity;
        }

        public StubVector3 getPosition() {
            return position;
        }

        public StubVector3 getEulerAngles() {
            return eulerAngles;
        }

        public StubVector3 getScale() {
            return scale;
        }

        void setStaticMatrix(final StubMatrix matrix) {
            this.internalStaticMatrixLocalToParent = matrix;
        }
    }

    private static final class CubismNamedLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        CubismNamedLoader(final Map<String, byte[]> classes) {
            super(CameraMatrixCacheBridgeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @AfterEach
    void cleanupProperties() {
        final Properties properties = System.getProperties();
        properties.remove(CameraMatrixCacheBridge.GET_PROPERTY);
        properties.remove(CameraMatrixCacheBridge.PUT_PROPERTY);
        properties.remove(CameraMatrixCacheBridge.STATS_PROPERTY);
    }

    @Test
    void compiledReaderHandlesCacheHitsMutationsStaticMatrixAndDepthLimit() throws Exception {
        final ClassLoader stubLoader = buildHostShapedLoader();
        final Class<?> cameraCls = stubLoader.loadClass(CameraMatrixCacheTarget.CAMERA_OWNER.replace('/', '.'));
        final Class<?> transformCls = stubLoader.loadClass(CameraMatrixCacheTarget.TRANSFORM_OWNER.replace('/', '.'));
        final Class<?> entityCls = stubLoader.loadClass(CameraMatrixCacheTarget.ENTITY_OWNER.replace('/', '.'));
        final Class<?> vecCls = stubLoader.loadClass(CameraMatrixCacheTarget.VECTOR3_OWNER.replace('/', '.'));
        final Class<?> matCls = stubLoader.loadClass("com.live2d.graphics3d.type.GMatrix44");

        final Object pos =
                vecCls.getConstructor(float.class, float.class, float.class).newInstance(1f, 2f, 3f);
        final Object rot =
                vecCls.getConstructor(float.class, float.class, float.class).newInstance(0f, 0f, 0f);
        final Object scl =
                vecCls.getConstructor(float.class, float.class, float.class).newInstance(1f, 1f, 1f);
        final Object parentTx =
                transformCls.getConstructor(vecCls, vecCls, vecCls).newInstance(pos, rot, scl);
        final Object parent = entityCls.getConstructor(entityCls, transformCls).newInstance(null, parentTx);
        transformCls.getMethod("attach", entityCls).invoke(parentTx, parent);

        final Object camPos =
                vecCls.getConstructor(float.class, float.class, float.class).newInstance(4f, 5f, 6f);
        final Object camTx = transformCls.getConstructor(vecCls, vecCls, vecCls).newInstance(camPos, rot, scl);
        final Object camera = cameraCls.getConstructor(entityCls, transformCls).newInstance(parent, camTx);
        transformCls.getMethod("attach", entityCls).invoke(camTx, camera);

        try (CameraMatrixCacheBridge bridge = new CameraMatrixCacheBridge(stubLoader)) {
            assertTrue(bridge.usesCompiledReader(), "public host types must compile the monomorphic ASM reader");
            bridge.install();
            @SuppressWarnings("unchecked")
            final Function<Object, Object> getter =
                    (Function<Object, Object>) System.getProperties().get(CameraMatrixCacheBridge.GET_PROPERTY);
            @SuppressWarnings("unchecked")
            final BiFunction<Object, Object, Object> setter = (BiFunction<Object, Object, Object>)
                    System.getProperties().get(CameraMatrixCacheBridge.PUT_PROPERTY);
            assertNotNull(getter);
            assertNotNull(setter);

            final Object firstMatrix = matCls.getConstructor().newInstance();
            assertNull(getter.apply(camera));
            assertSame(firstMatrix, setter.apply(camera, firstMatrix));
            assertSame(firstMatrix, getter.apply(camera));

            vecCls.getMethod("set", float.class, float.class, float.class).invoke(camPos, 9f, 5f, 6f);
            assertNull(getter.apply(camera), "position bit change must invalidate cached camera matrix");

            final Object secondMatrix = matCls.getConstructor().newInstance();
            setter.apply(camera, secondMatrix);
            assertNotSame(firstMatrix, getter.apply(camera));
            assertSame(secondMatrix, getter.apply(camera));

            transformCls
                    .getMethod("setStaticMatrix", matCls)
                    .invoke(parentTx, matCls.getConstructor().newInstance());
            assertNull(getter.apply(camera), "non-null internalStaticMatrixLocalToParent must bypass cache");
        }
    }

    @Test
    void compiledReaderCompilesAgainstRealCubismJarWhenPresent() throws Exception {
        final String configuredJar = System.getProperty("turboism.test.cubism5305Jar", "");
        final Path cubismJar = !configuredJar.isBlank()
                ? Path.of(configuredJar)
                : Path.of(
                        System.getProperty("user.home"),
                        ".local",
                        "share",
                        "cubism-reference",
                        "5.3.05",
                        "proton",
                        "pfx",
                        "drive_c",
                        "Program Files",
                        "Live2D Cubism 5.3",
                        "app",
                        "lib",
                        "Live2D_Cubism.jar");
        final Path libDir = cubismJar.getParent();
        assumeTrue(Files.isRegularFile(cubismJar) && libDir != null, "Cubism 5.3.05 reference lib directory absent");
        final java.util.List<URL> urls = new java.util.ArrayList<>();
        try (var jars = Files.walk(libDir, 2)) {
            for (final Path jar :
                    jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
                urls.add(jar.toUri().toURL());
            }
        }
        try (URLClassLoader cubismLoader =
                        new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
                CameraMatrixCacheBridge bridge = new CameraMatrixCacheBridge(cubismLoader)) {
            assertTrue(
                    bridge.usesCompiledReader(),
                    "real Cubism GCamera/GTransform/GEntity/GVector3 must use compiled hierarchy reader");
        }
    }

    private static ClassLoader buildHostShapedLoader() throws Exception {
        final String vecOwner = CameraMatrixCacheTarget.VECTOR3_OWNER;
        final String entityOwner = CameraMatrixCacheTarget.ENTITY_OWNER;
        final String transformOwner = CameraMatrixCacheTarget.TRANSFORM_OWNER;
        final String cameraOwner = CameraMatrixCacheTarget.CAMERA_OWNER;
        final String matOwner = "com/live2d/graphics3d/type/GMatrix44";
        final java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        classes.put(matOwner.replace('/', '.'), buildMatrixBytes(matOwner));
        classes.put(vecOwner.replace('/', '.'), buildVectorBytes(vecOwner));
        classes.put(entityOwner.replace('/', '.'), buildEntityBytes(entityOwner, transformOwner));
        classes.put(
                transformOwner.replace('/', '.'), buildTransformBytes(transformOwner, entityOwner, vecOwner, matOwner));
        classes.put(cameraOwner.replace('/', '.'), buildCameraBytes(cameraOwner, entityOwner, transformOwner));
        return new CubismNamedLoader(classes);
    }

    private static byte[] buildMatrixBytes(final String owner) {
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                owner,
                null,
                "java/lang/Object",
                null);
        final var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildVectorBytes(final String owner) {
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                owner,
                null,
                "java/lang/Object",
                null);
        for (final String f : new String[] {"x", "y", "z"}) {
            cw.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, f, "F", null, null)
                    .visitEnd();
        }
        final var init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(FFF)V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.FLOAD, 1);
        init.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "x", "F");
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.FLOAD, 2);
        init.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "y", "F");
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.FLOAD, 3);
        init.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "z", "F");
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final var set = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "set", "(FFF)V", null, null);
        set.visitCode();
        set.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        set.visitVarInsn(org.objectweb.asm.Opcodes.FLOAD, 1);
        set.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "x", "F");
        set.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        set.visitVarInsn(org.objectweb.asm.Opcodes.FLOAD, 2);
        set.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "y", "F");
        set.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        set.visitVarInsn(org.objectweb.asm.Opcodes.FLOAD, 3);
        set.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "z", "F");
        set.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        for (final String[] m : new String[][] {{"getX", "x"}, {"getY", "y"}, {"getZ", "z"}}) {
            final var mv = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                    m[0],
                    "()F",
                    null,
                    null);
            mv.visitCode();
            mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            mv.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, owner, m[1], "F");
            mv.visitInsn(org.objectweb.asm.Opcodes.FRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildEntityBytes(final String entityOwner, final String transformOwner) {
        final String eDesc = "L" + entityOwner + ";";
        final String tDesc = "L" + transformOwner + ";";
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                entityOwner,
                null,
                "java/lang/Object",
                null);
        cw.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, "parentEntity", eDesc, null, null)
                .visitEnd();
        cw.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, "transform", tDesc, null, null)
                .visitEnd();
        final var init =
                cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(" + eDesc + tDesc + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        init.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, entityOwner, "parentEntity", eDesc);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
        init.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, entityOwner, "transform", tDesc);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final var getP = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                "getParentEntity",
                "()" + eDesc,
                null,
                null);
        getP.visitCode();
        getP.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        getP.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, entityOwner, "parentEntity", eDesc);
        getP.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        getP.visitMaxs(0, 0);
        getP.visitEnd();

        final var getT = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "getTransform", "()" + tDesc, null, null);
        getT.visitCode();
        getT.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        getT.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, entityOwner, "transform", tDesc);
        getT.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        getT.visitMaxs(0, 0);
        getT.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildTransformBytes(
            final String owner, final String entityOwner, final String vecOwner, final String matOwner) {
        final String eDesc = "L" + entityOwner + ";";
        final String vDesc = "L" + vecOwner + ";";
        final String mDesc = "L" + matOwner + ";";
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                owner,
                null,
                "java/lang/Object",
                null);
        cw.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, "entity", eDesc, null, null)
                .visitEnd();
        cw.visitField(
                        org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_FINAL,
                        "position",
                        vDesc,
                        null,
                        null)
                .visitEnd();
        cw.visitField(
                        org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_FINAL,
                        "eulerAngles",
                        vDesc,
                        null,
                        null)
                .visitEnd();
        cw.visitField(
                        org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_FINAL,
                        "scale",
                        vDesc,
                        null,
                        null)
                .visitEnd();
        cw.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, "internalStaticMatrixLocalToParent", mDesc, null, null)
                .visitEnd();
        final var init = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(" + vDesc + vDesc + vDesc + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        init.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "position", vDesc);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
        init.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "eulerAngles", vDesc);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 3);
        init.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "scale", vDesc);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final var attach =
                cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "attach", "(" + eDesc + ")V", null, null);
        attach.visitCode();
        attach.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        attach.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        attach.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "entity", eDesc);
        attach.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        attach.visitMaxs(0, 0);
        attach.visitEnd();

        final var setStatic =
                cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "setStaticMatrix", "(" + mDesc + ")V", null, null);
        setStatic.visitCode();
        setStatic.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        setStatic.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        setStatic.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, owner, "internalStaticMatrixLocalToParent", mDesc);
        setStatic.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        setStatic.visitMaxs(0, 0);
        setStatic.visitEnd();

        final var getE = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "getEntity", "()" + eDesc, null, null);
        getE.visitCode();
        getE.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        getE.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, owner, "entity", eDesc);
        getE.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        getE.visitMaxs(0, 0);
        getE.visitEnd();

        for (final String[] m :
                new String[][] {{"getPosition", "position"}, {"getEulerAngles", "eulerAngles"}, {"getScale", "scale"}
                }) {
            final var mv = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                    m[0],
                    "()" + vDesc,
                    null,
                    null);
            mv.visitCode();
            mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            mv.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, owner, m[1], vDesc);
            mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildCameraBytes(
            final String cameraOwner, final String entityOwner, final String transformOwner) {
        final String eDesc = "L" + entityOwner + ";";
        final String tDesc = "L" + transformOwner + ";";
        final var cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                cameraOwner,
                null,
                entityOwner,
                null);
        final var init =
                cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(" + eDesc + tDesc + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
        init.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKESPECIAL, entityOwner, "<init>", "(" + eDesc + tDesc + ")V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

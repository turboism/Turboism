package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies {@link DeserializeGcElisionPatcher}, {@link DeserializeReflectFastPatcher}, and
 * {@link DeserializeReflectFastBridge} across synthetic contracts and official Cubism JARs.
 */
final class DeserializeFastOptimizationTest {

    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JAR_5305_REL =
            ".local/share/cubism-reference/5.3.05/proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/"
                    + JAR_NAME;
    private static final String VERSION_5305 = "5.3.05";
    private static final String CLASS_SUFFIX = ".class";
    private static final String BRIDGE_OWNER =
            "dev/turboism/adapter/cubism/optimization/modelopen/DeserializeReflectFastBridge";
    private static final String SYSTEM_OWNER = "java/lang/System";
    private static final String GC_NAME = "gc";
    private static final String VOID_DESC = "()V";
    private static final String CLASS_OWNER = "java/lang/Class";
    private static final String NEW_INSTANCE_NAME = "newInstance";
    private static final String CLASS_NEW_INSTANCE_DESC = "()Ljava/lang/Object;";
    private static final String DESERIALIZE_FROM_FILE = "deserializeFromFile";
    private static final String DESERIALIZE_FROM_BYTES = "deserializeFromBytes";
    private static final String READ_PROPERTY_NAME = "readProperty";
    private static final String CREATE_INSTANCE_NAME = "createInstance";
    private static final String TRY_SET_MUTABLE_NAME = "trySetMutableProperty";
    private static final String TRY_SET_FIELD_NAME = "trySetBackingField";
    private static final int EXPECTED_RAW_GC_CALLS = 4;
    private static final int CUSTOM_SETTER_INPUT = 7;
    private static final int CUSTOM_SETTER_MULTIPLIER = 3;
    private static final int FIELD_ASSIGN_VALUE = 42;
    private static final float FLOAT_VALUE = 1.5f;
    private static final String FINAL_FIELD_VALUE = "updated-final";

    @Test
    void patchesRealHostJarsAndIsIdempotent() throws Exception {
        final List<Path> jars = discoverHostJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Cubism host JARs present under ~/cubism-host-artifacts");

        for (final Path jarPath : jars) {
            final String version = versionLabel(jarPath);
            try (JarFile jar = new JarFile(jarPath.toFile(), false);
                    URLClassLoader hostLoader = new URLClassLoader(
                            new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                verifyGcElisionOnHostJar(jar, hostLoader, version);
                verifyPropertySetOnHostJar(jar, hostLoader, version);
                verifyClassSerializerOnHostJar(jar, hostLoader, version);
            }
        }
    }

    @Test
    void patchersFailClosedOnUnrelatedOrMismatchedClasses() {
        final byte[] emptyClass = buildMinimalClass("com/example/Dummy");
        assertNull(DeserializeGcElisionPatcher.tryPatch("com/example/Dummy", null, emptyClass));
        assertNull(DeserializeGcElisionPatcher.tryPatch(
                DeserializeGcElisionPatcher.TARGET_INTERNAL_NAME, null, emptyClass));

        assertFalse(DeserializeReflectFastPatcher.isSupportedTarget("com/example/Dummy"));
        assertTrue(DeserializeReflectFastPatcher.isSupportedTarget(
                DeserializeReflectFastPatcher.PROPERTY_SET_INTERNAL_NAME));
        assertTrue(DeserializeReflectFastPatcher.isSupportedTarget(
                DeserializeReflectFastPatcher.CLASS_SERIALIZER_INTERNAL_NAME));
        assertNull(DeserializeReflectFastPatcher.tryPatch("com/example/Dummy", null, emptyClass));
        assertNull(DeserializeReflectFastPatcher.tryPatch(
                DeserializeReflectFastPatcher.PROPERTY_SET_INTERNAL_NAME, null, emptyClass));
        assertNull(DeserializeReflectFastPatcher.tryPatch(
                DeserializeReflectFastPatcher.CLASS_SERIALIZER_INTERNAL_NAME, null, emptyClass));
    }

    @Test
    void bridgeInvokesCustomSetterWithoutBypassingToField() throws Throwable {
        final StubKotlinReflectLoader loader = new StubKotlinReflectLoader();
        final Object propWithCustomSetter = loader.newPropertyToken("custom");
        final Object propFieldOnly = loader.newPropertyToken("fieldOnly");
        final Object propThrowingSetter = loader.newPropertyToken("throwing");

        final Method customSetter = SampleBean.class.getDeclaredMethod("setScaled", int.class);
        final Method throwingSetter = SampleBean.class.getDeclaredMethod("setExploding", String.class);
        final Field scaledField = SampleBean.class.getDeclaredField("scaled");
        final Field directField = SampleBean.class.getDeclaredField("directFloat");

        StubKotlinReflectRegistry.registerSetter(propWithCustomSetter, customSetter);
        StubKotlinReflectRegistry.registerSetter(propThrowingSetter, throwingSetter);
        StubKotlinReflectRegistry.registerField(propFieldOnly, directField);

        final SampleBean bean = new SampleBean();
        final Object propSetKeyCustom = new Object();
        final Object propSetKeyField = new Object();
        final Object propSetKeyThrow = new Object();

        assertTrue(DeserializeReflectFastBridge.trySetMutableProperty(
                propSetKeyCustom, propWithCustomSetter, scaledField, bean, CUSTOM_SETTER_INPUT));
        assertEquals(CUSTOM_SETTER_INPUT * CUSTOM_SETTER_MULTIPLIER, bean.scaled);
        assertEquals(1, bean.setterInvocations);

        // Incompatible types and null-for-primitive fall back cleanly.
        assertFalse(DeserializeReflectFastBridge.trySetMutableProperty(
                propSetKeyCustom, propWithCustomSetter, scaledField, bean, "wrong-type"));
        assertFalse(DeserializeReflectFastBridge.trySetMutableProperty(
                propSetKeyCustom, propWithCustomSetter, scaledField, bean, null));

        // Null Java setter uses backing field directly.
        assertTrue(DeserializeReflectFastBridge.trySetMutableProperty(
                propSetKeyField, propFieldOnly, directField, bean, FLOAT_VALUE));
        assertEquals(FLOAT_VALUE, bean.directFloat);

        // Exceptions thrown by custom setters propagate unwrapped.
        final IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> DeserializeReflectFastBridge.trySetMutableProperty(
                        propSetKeyThrow, propThrowingSetter, null, bean, "boom"));
        assertEquals("boom", thrown.getMessage());

        // Without ReflectJvmMapping on the property's class loader, fail closed to preserve custom setters.
        assertFalse(DeserializeReflectFastBridge.trySetMutableProperty(
                new Object(), new Object(), directField, bean, FLOAT_VALUE));
    }

    @Test
    void bridgeSetsMutableAndFinalBackingFields() throws Throwable {
        final SampleBean bean = new SampleBean();
        final Field mutableField = SampleBean.class.getDeclaredField("scaled");
        final Field finalField = SampleBean.class.getDeclaredField("immutableTag");

        assertTrue(
                DeserializeReflectFastBridge.trySetBackingField(new Object(), mutableField, bean, FIELD_ASSIGN_VALUE));
        assertEquals(FIELD_ASSIGN_VALUE, bean.scaled);

        assertTrue(DeserializeReflectFastBridge.trySetBackingField(new Object(), finalField, bean, FINAL_FIELD_VALUE));
        assertEquals(FINAL_FIELD_VALUE, bean.immutableTag);

        assertFalse(DeserializeReflectFastBridge.trySetBackingField(new Object(), mutableField, bean, "not-an-int"));
    }

    @Test
    void bridgeNewInstanceMatchesClassNewInstanceSemantics() throws Throwable {
        final Object first = DeserializeReflectFastBridge.newInstance(PublicNoArgBean.class);
        final Object second = DeserializeReflectFastBridge.newInstance(PublicNoArgBean.class);
        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second);
        assertEquals(PublicNoArgBean.class, first.getClass());

        final IllegalStateException ctorError = assertThrows(
                IllegalStateException.class, () -> DeserializeReflectFastBridge.newInstance(ThrowingCtorBean.class));
        assertEquals("ctor-failed", ctorError.getMessage());

        assertThrows(InstantiationException.class, () -> DeserializeReflectFastBridge.newInstance(AbstractBean.class));
        assertThrows(
                IllegalAccessException.class, () -> DeserializeReflectFastBridge.newInstance(PrivateCtorBean.class));
    }

    private static void verifyGcElisionOnHostJar(final JarFile jar, final ClassLoader hostLoader, final String version)
            throws IOException {
        final byte[] raw = readClassBytes(jar, DeserializeGcElisionPatcher.TARGET_INTERNAL_NAME);
        assertEquals(
                EXPECTED_RAW_GC_CALLS,
                countGcCallsInDeserializeMethods(raw),
                "Expected 4 System.gc() calls in raw UUSerialize for " + version);

        final byte[] patched =
                DeserializeGcElisionPatcher.tryPatch(DeserializeGcElisionPatcher.TARGET_INTERNAL_NAME, hostLoader, raw);
        assertNotNull(patched, "Expected UUSerialize to be patched for " + version);
        assertEquals(
                0,
                countGcCallsInDeserializeMethods(patched),
                "Expected 0 System.gc() calls in patched UUSerialize for " + version);
        assertNull(
                DeserializeGcElisionPatcher.tryPatch(
                        DeserializeGcElisionPatcher.TARGET_INTERNAL_NAME, hostLoader, patched),
                "Expected UUSerialize patcher to be idempotent for " + version);
    }

    private static void verifyPropertySetOnHostJar(
            final JarFile jar, final ClassLoader hostLoader, final String version) throws IOException {
        final byte[] raw = readClassBytes(jar, DeserializeReflectFastPatcher.PROPERTY_SET_INTERNAL_NAME);
        final byte[] patched = DeserializeReflectFastPatcher.tryPatch(
                DeserializeReflectFastPatcher.PROPERTY_SET_INTERNAL_NAME, hostLoader, raw);
        assertNotNull(patched, "Expected PropertySet to be patched for " + version);
        assertTrue(
                methodCallsTarget(patched, READ_PROPERTY_NAME, INVOKESTATIC, BRIDGE_OWNER, TRY_SET_MUTABLE_NAME),
                "Expected trySetMutableProperty call in PropertySet for " + version);
        assertTrue(
                methodCallsTarget(patched, READ_PROPERTY_NAME, INVOKESTATIC, BRIDGE_OWNER, TRY_SET_FIELD_NAME),
                "Expected trySetBackingField call in PropertySet for " + version);
        assertTrue(
                methodCallsTarget(
                        patched,
                        READ_PROPERTY_NAME,
                        INVOKEINTERFACE,
                        "kotlin/reflect/KMutableProperty1$Setter",
                        "call"),
                "Expected Kotlin setter fallback preserved in PropertySet for " + version);
        assertNull(
                DeserializeReflectFastPatcher.tryPatch(
                        DeserializeReflectFastPatcher.PROPERTY_SET_INTERNAL_NAME, hostLoader, patched),
                "Expected PropertySet patcher to be idempotent for " + version);
    }

    private static void verifyClassSerializerOnHostJar(
            final JarFile jar, final ClassLoader hostLoader, final String version) throws IOException {
        final byte[] raw = readClassBytes(jar, DeserializeReflectFastPatcher.CLASS_SERIALIZER_INTERNAL_NAME);
        final byte[] patched = DeserializeReflectFastPatcher.tryPatch(
                DeserializeReflectFastPatcher.CLASS_SERIALIZER_INTERNAL_NAME, hostLoader, raw);
        assertNotNull(patched, "Expected ClassSerializer to be patched for " + version);
        assertTrue(
                methodCallsTarget(patched, CREATE_INSTANCE_NAME, INVOKESTATIC, BRIDGE_OWNER, NEW_INSTANCE_NAME),
                "Expected bridge newInstance call in ClassSerializer for " + version);
        assertFalse(
                methodCallsTarget(patched, CREATE_INSTANCE_NAME, INVOKEVIRTUAL, CLASS_OWNER, NEW_INSTANCE_NAME),
                "Expected Class.newInstance() removed from ClassSerializer for " + version);
        assertNull(
                DeserializeReflectFastPatcher.tryPatch(
                        DeserializeReflectFastPatcher.CLASS_SERIALIZER_INTERNAL_NAME, hostLoader, patched),
                "Expected ClassSerializer patcher to be idempotent for " + version);
    }

    private static int countGcCallsInDeserializeMethods(final byte[] bytecode) {
        final int[] count = new int[1];
        new ClassReader(bytecode)
                .accept(
                        new ClassVisitor(ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!DESERIALIZE_FROM_FILE.equals(name) && !DESERIALIZE_FROM_BYTES.equals(name)) {
                                    return null;
                                }
                                return new MethodVisitor(ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String methodName,
                                            final String methodDesc,
                                            final boolean isInterface) {
                                        if (opcode == INVOKESTATIC
                                                && SYSTEM_OWNER.equals(owner)
                                                && GC_NAME.equals(methodName)
                                                && VOID_DESC.equals(methodDesc)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static boolean methodCallsTarget(
            final byte[] bytecode,
            final String enclosingMethod,
            final int expectedOpcode,
            final String expectedOwner,
            final String expectedName) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytecode)
                .accept(
                        new ClassVisitor(ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!enclosingMethod.equals(name)) {
                                    return null;
                                }
                                return new MethodVisitor(ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String methodName,
                                            final String methodDesc,
                                            final boolean isInterface) {
                                        if (opcode == expectedOpcode
                                                && expectedOwner.equals(owner)
                                                && expectedName.equals(methodName)) {
                                            found[0] = true;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static byte[] readClassBytes(final JarFile jar, final String internalName) throws IOException {
        final JarEntry entry = jar.getJarEntry(internalName + CLASS_SUFFIX);
        assertNotNull(entry, "Missing class entry: " + internalName);
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static List<Path> discoverHostJars() throws IOException {
        final Path home = Path.of(System.getProperty("user.home"));
        final Path artifactsDir = home.resolve(ARTIFACTS_DIR_NAME);
        final List<Path> found = new ArrayList<>();
        if (Files.isDirectory(artifactsDir)) {
            try (var stream = Files.list(artifactsDir)) {
                stream.filter(Files::isDirectory)
                        .sorted(Comparator.naturalOrder())
                        .forEach(dir -> {
                            final Path jar = dir.resolve(JAR_NAME);
                            if (Files.isRegularFile(jar)) {
                                found.add(jar);
                            }
                        });
            }
        }
        final Path jar5305 = home.resolve(JAR_5305_REL);
        if (Files.isRegularFile(jar5305)) {
            found.add(jar5305);
        }
        return found;
    }

    private static String versionLabel(final Path jarPath) {
        final String parentName = jarPath.getParent().getFileName().toString();
        return "lib".equals(parentName) ? VERSION_5305 : parentName;
    }

    private static byte[] buildMinimalClass(final String internalName) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", VOID_DESC, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", VOID_DESC, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Public test bean for {@link DeserializeReflectFastBridge} property and field tests. */
    public static final class SampleBean {
        private int scaled;
        private int setterInvocations;
        private float directFloat;
        private final String immutableTag;

        public SampleBean() {
            this.immutableTag = "initial";
        }

        void setScaled(final int value) {
            this.setterInvocations++;
            this.scaled = value * CUSTOM_SETTER_MULTIPLIER;
        }

        void setExploding(final String message) {
            throw new IllegalStateException(message);
        }
    }

    /** Public concrete bean with public no-arg constructor. */
    public static final class PublicNoArgBean {
        public PublicNoArgBean() {}
    }

    /** Public bean whose no-arg constructor throws an exception. */
    public static final class ThrowingCtorBean {
        public ThrowingCtorBean() {
            throw new IllegalStateException("ctor-failed");
        }
    }

    /** Abstract bean verifying {@code InstantiationException} fallback. */
    public abstract static class AbstractBean {
        public AbstractBean() {}
    }

    /** Bean with private constructor verifying {@code IllegalAccessException} fallback. */
    public static final class PrivateCtorBean {
        private PrivateCtorBean() {}
    }

    /** Registry backing the test-local {@code ReflectJvmMapping} stub. */
    public static final class StubKotlinReflectRegistry {
        private static final Map<Object, Method> SETTERS = new ConcurrentHashMap<>();
        private static final Map<Object, Field> FIELDS = new ConcurrentHashMap<>();

        private StubKotlinReflectRegistry() {}

        static void registerSetter(final Object property, final Method setter) {
            SETTERS.put(property, setter);
        }

        static void registerField(final Object property, final Field field) {
            FIELDS.put(property, field);
        }

        public static Method lookupSetter(final Object property) {
            return SETTERS.get(property);
        }

        public static Field lookupField(final Object property) {
            return FIELDS.get(property);
        }
    }

    /**
     * Defines synthetic {@code kotlin.reflect.jvm.ReflectJvmMapping} and property token classes
     * inside an isolated loader so {@link DeserializeReflectFastBridge} can exercise its Kotlin
     * reflection path without adding {@code kotlin-reflect} to test dependencies.
     */
    private static final class StubKotlinReflectLoader extends ClassLoader {
        private static final String MAPPING_NAME = "kotlin.reflect.jvm.ReflectJvmMapping";
        private static final String MAPPING_INTERNAL = "kotlin/reflect/jvm/ReflectJvmMapping";
        private static final String TOKEN_NAME = "dev.turboism.test.StubKPropertyToken";
        private static final String TOKEN_INTERNAL = "dev/turboism/test/StubKPropertyToken";
        private static final String REGISTRY_INTERNAL =
                "dev/turboism/adapter/cubism/optimization/modelopen/DeserializeFastOptimizationTest$StubKotlinReflectRegistry";

        private final Class<?> tokenClass;

        StubKotlinReflectLoader() {
            super(DeserializeFastOptimizationTest.class.getClassLoader());
            final byte[] mappingBytes = buildReflectJvmMappingStub();
            defineClass(MAPPING_NAME, mappingBytes, 0, mappingBytes.length);
            final byte[] tokenBytes = buildMinimalClass(TOKEN_INTERNAL);
            this.tokenClass = defineClass(TOKEN_NAME, tokenBytes, 0, tokenBytes.length);
        }

        Object newPropertyToken(final String ignoredLabel) throws Exception {
            return tokenClass.getDeclaredConstructor().newInstance();
        }

        private static byte[] buildReflectJvmMappingStub() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, MAPPING_INTERNAL, null, "java/lang/Object", null);

            final MethodVisitor getSetter = cw.visitMethod(
                    ACC_PUBLIC | ACC_STATIC,
                    "getJavaSetter",
                    "(Ljava/lang/Object;)Ljava/lang/reflect/Method;",
                    null,
                    null);
            getSetter.visitCode();
            getSetter.visitVarInsn(ALOAD, 0);
            getSetter.visitMethodInsn(
                    INVOKESTATIC,
                    REGISTRY_INTERNAL,
                    "lookupSetter",
                    "(Ljava/lang/Object;)Ljava/lang/reflect/Method;",
                    false);
            getSetter.visitInsn(ARETURN);
            getSetter.visitMaxs(0, 0);
            getSetter.visitEnd();

            final MethodVisitor getField = cw.visitMethod(
                    ACC_PUBLIC | ACC_STATIC,
                    "getJavaField",
                    "(Ljava/lang/Object;)Ljava/lang/reflect/Field;",
                    null,
                    null);
            getField.visitCode();
            getField.visitVarInsn(ALOAD, 0);
            getField.visitMethodInsn(
                    INVOKESTATIC,
                    REGISTRY_INTERNAL,
                    "lookupField",
                    "(Ljava/lang/Object;)Ljava/lang/reflect/Field;",
                    false);
            getField.visitInsn(ARETURN);
            getField.visitMaxs(0, 0);
            getField.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }
    }
}

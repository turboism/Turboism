package dev.turboism.adapter.cubism.optimization.extendedinterpolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.objectweb.asm.Opcodes.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

final class ExtendedInterpolationSetupBridgeTest {

    private static final String MODEL_SOURCE = "com/live2d/cubism/doc/model/CModelSource";
    private static final String OWNER = "com/live2d/cubism/doc/model/ACParameterControllableSource";
    private static final String FORM = "com/live2d/cubism/doc/model/ACForm";
    private static final String GRID = "com/live2d/cubism/doc/model/interpolator/KeyformGridSource";
    private static final String BINDING = "com/live2d/cubism/doc/model/interpolator/KeyformBindingSource";
    private static final String MORPH =
            "com/live2d/cubism/doc/model/morphTarget/extendedInterpolation/ExtendedKeyFormMorphTargetSet";
    private static final String EXTENSION = ExtendedInterpolationSetupTarget.EXTENSION;

    private Path artifact;
    private FixtureLoader loader;
    private ExtendedInterpolationSetupBridge bridge;
    private String definingOwner;

    private static final class FixtureLoader extends ClassLoader {
        private final ProtectionDomain domain;

        FixtureLoader(final Path artifact) throws Exception {
            super(ExtendedInterpolationSetupBridgeTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }

        Class<?> type(final String name) throws ClassNotFoundException {
            return Class.forName(name.replace('/', '.'), true, this);
        }

        Object construct(final String name, final String descriptor, final Object... args) throws Exception {
            final Class<?> type = type(name);
            for (final var constructor : type.getDeclaredConstructors()) {
                if (org.objectweb.asm.Type.getConstructorDescriptor(constructor).equals(descriptor)) {
                    return constructor.newInstance(args);
                }
            }
            throw new NoSuchMethodException(name + descriptor);
        }

        void setStatic(final String name, final String field, final Object value) throws Exception {
            type(name).getField(field).set(null, value);
        }

        Object getStatic(final String name, final String field) throws Exception {
            return type(name).getField(field).get(null);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        artifact = Files.createTempFile("incremental-setup-bridge-test", ".jar");
        loader = new FixtureLoader(artifact);
        defineFixtures();
        bridge = new ExtendedInterpolationSetupBridge(loader);
        bridge.install();
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.close();
        ExtendedInterpolationSetupBridge.clearNativeEditIntent();
        System.getProperties().remove(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY);
        System.getProperties().remove(ExtendedInterpolationSetupBridge.STATS_PROPERTY);
        try {
            Files.deleteIfExists(artifact);
        } catch (Exception ignored) {
        }
    }

    @Test
    void withoutEditIntentTheForcedRebuildIsKept() throws Exception {
        primeUnchanged();
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0, updates());
    }

    @Test
    void generalIncrementalModeAllowsFingerprintProvenExtensionsWithoutIntent() throws Exception {
        primeUnchanged();
        try {
            System.setProperty(ExtendedInterpolationSetupBridge.GENERAL_INCREMENTAL_PROPERTY, "true");
            assertEquals(Boolean.TRUE, consult(currentExtension()));
            assertEquals(1, updates());
        } finally {
            System.clearProperty(ExtendedInterpolationSetupBridge.GENERAL_INCREMENTAL_PROPERTY);
        }
    }

    @Test
    void disabledExtensionFallsBack() throws Exception {
        primeUnchanged();
        loader.setStatic(EXTENSION, "enabled", Boolean.FALSE);
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0, updates());
    }

    @Test
    void unprimedExtensionFallsBack() throws Exception {
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0, updates());
    }

    @Test
    void changedFormVersionFallsBack() throws Exception {
        primeUnchanged();
        loader.setStatic(EXTENSION, "versions", Map.of(guid("form"), 99));
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0, updates());
    }

    @Test
    void changedBindingsFallBack() throws Exception {
        primeUnchanged();
        loader.setStatic(
                OWNER,
                "keyformGridSource",
                loader.construct(GRID, "(Ljava/util/List;)V", List.of(binding("ParamOther", 2.0f))));
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0, updates());
    }

    @Test
    void pendingMorphTargetStructureFallsBack() throws Exception {
        primeUnchanged();
        loader.setStatic(EXTENSION, "morphTargets", loader.construct(MORPH, "(Z)V", Boolean.TRUE));
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0, updates());
    }

    @Test
    void unchangedFormStateRunsTheIncrementalUpdate() throws Exception {
        primeUnchanged();
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.TRUE, consult(currentExtension()));
        assertEquals(1, updates());
        assertEquals(0, lastForce());
        final Map<String, Long> stats = bridge.snapshot();
        assertEquals(1L, stats.get("incremental"));
        assertEquals(0L, stats.get("fallback.changed"));
    }

    @Test
    void incrementalRebuildIsCountedAsUnexpected() throws Exception {
        primeUnchanged();
        loader.setStatic(EXTENSION, "rebuilds", Boolean.TRUE);
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.TRUE, consult(currentExtension()));
        assertEquals(1L, bridge.snapshot().get("unexpectedRebuilds"));
    }

    @Test
    void thrownIncrementalUpdateFallsBack() throws Exception {
        primeUnchanged();
        loader.setStatic(EXTENSION, "throwsOnUpdate", Boolean.TRUE);
        ExtendedInterpolationSetupBridge.noteNativeEditIntent();
        assertEquals(Boolean.FALSE, consult(currentExtension()));
        assertEquals(0L, bridge.snapshot().get("incremental"));
        assertEquals(1L, bridge.snapshot().get("failures"));
    }

    @Test
    void installsAndRemovesItsConsultSlot() {
        assertInstanceOf(
                java.util.function.Function.class,
                System.getProperties().get(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY));
        assertInstanceOf(
                java.util.function.Supplier.class,
                System.getProperties().get(ExtendedInterpolationSetupBridge.STATS_PROPERTY));
        bridge.close();
        assertEquals(null, System.getProperties().get(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY));
        assertEquals(null, System.getProperties().get(ExtendedInterpolationSetupBridge.STATS_PROPERTY));
    }

    private Object consult(final Object extension) throws Exception {
        final var method = ExtendedInterpolationSetupBridge.class.getDeclaredMethod("onConsult", Object.class);
        method.setAccessible(true);
        return method.invoke(bridge, extension);
    }

    private Object currentExtension() throws Exception {
        return loader.construct(EXTENSION, "()V");
    }

    private int updates() throws Exception {
        return ((Number) loader.getStatic(EXTENSION, "updates")).intValue();
    }

    private int lastForce() throws Exception {
        return ((Number) loader.getStatic(EXTENSION, "lastForce")).intValue();
    }

    private void primeUnchanged() throws Exception {
        final Object form = loader.construct(FORM, "(Ljava/lang/Object;I)V", guid("form"), 4);
        final Object binding = binding("ParamAngleX", 0.5f);
        loader.setStatic(OWNER, "keyforms", List.of(form));
        loader.setStatic(OWNER, "keyformGridSource", loader.construct(GRID, "(Ljava/util/List;)V", List.of(binding)));
        loader.setStatic(EXTENSION, "owner", loader.construct(OWNER, "()V"));
        loader.setStatic(EXTENSION, "modelSource", loader.construct(MODEL_SOURCE, "()V"));
        loader.setStatic(EXTENSION, "enabled", Boolean.TRUE);
        loader.setStatic(EXTENSION, "allExtended", List.of(form));
        loader.setStatic(EXTENSION, "versions", Map.of(guid("form"), 4));
        loader.setStatic(
                EXTENSION,
                "extendedGrid",
                loader.construct(GRID, "(Ljava/util/List;)V", List.of(binding("ParamAngleX", 0.5f))));
        loader.setStatic(EXTENSION, "morphTargets", loader.construct(MORPH, "(Z)V", Boolean.FALSE));
        loader.setStatic(EXTENSION, "rebuilds", Boolean.FALSE);
        loader.setStatic(EXTENSION, "throwsOnUpdate", Boolean.FALSE);
        loader.setStatic(EXTENSION, "updates", 0);
        loader.setStatic(EXTENSION, "lastForce", -1);
    }

    private Object binding(final String parameterId, final float key) throws Exception {
        return loader.construct(
                BINDING,
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;ILjava/util/List;)V",
                Map.of(parameterId, 1),
                parameterId,
                "BEZIER",
                1,
                List.of(Float.valueOf(key)));
    }

    private static Map<String, Integer> guid(final String name) {
        return Map.of(name, 1);
    }

    // ------------------------------------------------------------------
    // Fixture generation
    // ------------------------------------------------------------------

    private void defineFixtures() {
        definePlain(MODEL_SOURCE);
        definePlain(
                FORM,
                "getGuid",
                "()Ljava/lang/Object;",
                "guid",
                "Ljava/lang/Object;",
                "getEditVersion",
                "()I",
                "version",
                "I");
        defineOwner();
        defineGrid();
        defineBinding();
        defineMorph();
        defineExtension();
    }

    private void definePlain(final String name) {
        definingOwner = name;
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, name, null, "java/lang/Object", null);
        constructor(writer);
        writer.visitEnd();
        loader.define(name, writer.toByteArray());
    }

    /** Plain fixture with public instance fields and matching zero-argument getters. */
    private void definePlain(
            final String name,
            final String method1,
            final String descriptor1,
            final String field1,
            final String fieldDescriptor1,
            final String method2,
            final String descriptor2,
            final String field2,
            final String fieldDescriptor2) {
        definingOwner = name;
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, name, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, field1, fieldDescriptor1, null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, field2, fieldDescriptor2, null, null).visitEnd();
        constructor(writer, field1, fieldDescriptor1, field2, fieldDescriptor2);
        getter(writer, method1, descriptor1, field1, fieldDescriptor1);
        getter(writer, method2, descriptor2, field2, fieldDescriptor2);
        writer.visitEnd();
        loader.define(name, writer.toByteArray());
    }

    private void defineOwner() {
        definingOwner = OWNER;
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, OWNER, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "keyforms", "Ljava/util/List;", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "keyformGridSource", "L" + GRID + ";", null, null)
                .visitEnd();
        constructor(writer);
        getter(writer, "getKeyforms", "()Ljava/util/List;", "keyforms", "Ljava/util/List;", true);
        getter(writer, "getKeyformGridSource", "()L" + GRID + ";", "keyformGridSource", "L" + GRID + ";", true);
        writer.visitEnd();
        loader.define(OWNER, writer.toByteArray());
    }

    private void defineGrid() {
        definingOwner = GRID;
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, GRID, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "bindings", "Ljava/util/List;", null, null)
                .visitEnd();
        constructor(writer, "bindings", "Ljava/util/List;");
        getter(writer, "getKeyformBindings", "()Ljava/util/List;", "bindings", "Ljava/util/List;");
        writer.visitEnd();
        loader.define(GRID, writer.toByteArray());
    }

    private void defineBinding() {
        definingOwner = BINDING;
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, BINDING, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "parameterGuid", "Ljava/lang/Object;", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC, "parameterId", "Ljava/lang/Object;", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC, "type", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "insertPointCount", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "keys", "Ljava/util/List;", null, null).visitEnd();
        constructor(
                writer,
                "parameterGuid",
                "Ljava/lang/Object;",
                "parameterId",
                "Ljava/lang/Object;",
                "type",
                "Ljava/lang/Object;",
                "insertPointCount",
                "I",
                "keys",
                "Ljava/util/List;");
        getter(writer, "getParameterGuid", "()Ljava/lang/Object;", "parameterGuid", "Ljava/lang/Object;");
        getter(writer, "getParameterId", "()Ljava/lang/Object;", "parameterId", "Ljava/lang/Object;");
        getter(writer, "getExtendedInterpolationType", "()Ljava/lang/Object;", "type", "Ljava/lang/Object;");
        getter(writer, "getInsertPointCount", "()I", "insertPointCount", "I");
        getter(writer, "getKeys", "()Ljava/util/List;", "keys", "Ljava/util/List;");
        writer.visitEnd();
        loader.define(BINDING, writer.toByteArray());
    }

    private void defineMorph() {
        definingOwner = MORPH;
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, MORPH, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "needs", "Z", null, null).visitEnd();
        constructor(writer, "needs", "Z");
        getter(writer, "isNeedSetupFormStructure", "()Z", "needs", "Z");
        writer.visitEnd();
        loader.define(MORPH, writer.toByteArray());
    }

    private void defineExtension() {
        definingOwner = EXTENSION;
        final ClassWriter writer = writer();
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, EXTENSION, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "owner", "L" + OWNER + ";", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "modelSource", "L" + MODEL_SOURCE + ";", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "enabled", "Z", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "allExtended", "Ljava/util/List;", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "versions", "Ljava/util/Map;", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "extendedGrid", "L" + GRID + ";", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "morphTargets", "L" + MORPH + ";", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "rebuilds", "Z", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "throwsOnUpdate", "Z", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "updates", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "lastForce", "I", null, null).visitEnd();
        constructor(writer);
        getter(writer, "getOwner", "()L" + OWNER + ";", "owner", "L" + OWNER + ";", true);
        getter(writer, "getModelSource", "()L" + MODEL_SOURCE + ";", "modelSource", "L" + MODEL_SOURCE + ";", true);
        getter(writer, "isExtendedInterpolationEnabled", "()Z", "enabled", "Z", true);
        getter(writer, "getAllExtendedKeyforms", "()Ljava/util/List;", "allExtended", "Ljava/util/List;", true);
        getter(writer, "getKeyformUpdateMap", "()Ljava/util/Map;", "versions", "Ljava/util/Map;", true);
        getter(writer, "getExtendedKeyformGridSource", "()L" + GRID + ";", "extendedGrid", "L" + GRID + ";", true);
        getter(
                writer,
                "getExtendedKeyformMorphTargetSet",
                "()L" + MORPH + ";",
                "morphTargets",
                "L" + MORPH + ";",
                true);

        final MethodVisitor update = writer.visitMethod(ACC_PUBLIC, "updateInterpolatedForms", "(Z)Z", null, null);
        update.visitCode();
        final org.objectweb.asm.Label noThrow = new org.objectweb.asm.Label();
        update.visitFieldInsn(GETSTATIC, EXTENSION, "throwsOnUpdate", "Z");
        update.visitJumpInsn(IFEQ, noThrow);
        update.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        update.visitInsn(DUP);
        update.visitLdcInsn("boom");
        update.visitMethodInsn(
                INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        update.visitInsn(ATHROW);
        update.visitLabel(noThrow);
        update.visitFieldInsn(GETSTATIC, EXTENSION, "updates", "I");
        update.visitInsn(ICONST_1);
        update.visitInsn(IADD);
        update.visitFieldInsn(PUTSTATIC, EXTENSION, "updates", "I");
        update.visitVarInsn(ILOAD, 1);
        update.visitInsn(ICONST_1);
        update.visitInsn(IAND);
        update.visitFieldInsn(PUTSTATIC, EXTENSION, "lastForce", "I");
        update.visitFieldInsn(GETSTATIC, EXTENSION, "rebuilds", "Z");
        update.visitInsn(IRETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();
        writer.visitEnd();
        loader.define(EXTENSION, writer.toByteArray());
    }

    private ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader;
            }
        };
    }

    private void constructor(final ClassWriter writer, final String... fieldAndDescriptor) {
        final String descriptor = describe(fieldAndDescriptor);
        final MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", descriptor, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        int slot = 1;
        for (int i = 0; i < fieldAndDescriptor.length; i += 2) {
            final String field = fieldAndDescriptor[i];
            final String type = fieldAndDescriptor[i + 1];
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(loadOpcode(type), slot);
            init.visitFieldInsn(PUTFIELD, definingOwner, field, type);
            slot += size(type);
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private void getter(
            final ClassWriter writer,
            final String name,
            final String descriptor,
            final String field,
            final String fieldDescriptor) {
        getter(writer, name, descriptor, field, fieldDescriptor, false);
    }

    private void getter(
            final ClassWriter writer,
            final String name,
            final String descriptor,
            final String field,
            final String fieldDescriptor,
            final boolean isStatic) {
        final MethodVisitor getter = writer.visitMethod(ACC_PUBLIC, name, descriptor, null, null);
        getter.visitCode();
        if (isStatic) getter.visitFieldInsn(GETSTATIC, definingOwner, field, fieldDescriptor);
        else {
            getter.visitVarInsn(ALOAD, 0);
            getter.visitFieldInsn(GETFIELD, definingOwner, field, fieldDescriptor);
        }
        getter.visitInsn(returnOpcode(fieldDescriptor));
        getter.visitMaxs(0, 0);
        getter.visitEnd();
    }

    private static String describe(final String... fieldAndDescriptor) {
        final StringBuilder descriptor = new StringBuilder("(");
        for (int i = 1; i < fieldAndDescriptor.length; i += 2) descriptor.append(fieldAndDescriptor[i]);
        return descriptor.append(")V").toString();
    }

    private static int loadOpcode(final String type) {
        return switch (type) {
            case "Z", "I", "B", "C", "S" -> ILOAD;
            default -> ALOAD;
        };
    }

    private static int size(final String type) {
        return switch (type) {
            case "J", "D" -> 2;
            default -> 1;
        };
    }

    private static int returnOpcode(final String type) {
        return switch (type) {
            case "V" -> RETURN;
            case "Z", "I", "B", "C", "S" -> IRETURN;
            case "J" -> LRETURN;
            case "F" -> FRETURN;
            case "D" -> DRETURN;
            default -> ARETURN;
        };
    }
}

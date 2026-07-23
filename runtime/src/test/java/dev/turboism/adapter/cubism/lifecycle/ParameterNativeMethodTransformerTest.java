package dev.turboism.adapter.cubism.lifecycle;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterNativeMethodTransformerTest {

    @Test
    void instrumentsOnlyTheExactVerifiedPaletteMethodAndPreservesNormalAndFailureExit() throws Exception {
        final byte[] original = fixtureClass();
        final ParameterNativeMethodTransformer transformer =
            new ParameterNativeMethodTransformer(
                "fixture/PaletteOperation",
                "set",
                "(Lfixture/ParameterSource;F)V",
                null,
                "fixture/ParameterSource",
                "getIdString",
                "()Ljava/lang/String;",
                "java/lang/String",
                "toString",
                "()Ljava/lang/String;"
            );

        final byte[] transformed = transformer.transform(
            null,
            null,
            "fixture/PaletteOperation",
            null,
            null,
            original
        );

        assertNotNull(transformed);
        final FixtureLoader loader = new FixtureLoader();
        final Class<?> sourceType = loader.define("fixture.ParameterSource", sourceClass());
        final Class<?> operationType = loader.define("fixture.PaletteOperation", transformed);
        final Object source = sourceType.getConstructor(String.class).newInstance("ParamA");
        final Object operation = operationType.getConstructor().newInstance();
        final Method set = operationType.getMethod("set", sourceType, float.class);

        final RecordingBridgeLifecycle lifecycle = new RecordingBridgeLifecycle(operationType, operation);
        NativeParameterLifecycleBridge.install(lifecycle.bridge());
        try {
            set.invoke(operation, source, 3.0F);
            lifecycle.coordinator().awaitIdle();
            assertEquals(6.0F, operationType.getField("value").getFloat(operation));
            assertEquals(java.util.List.of("on:0.0->6.0", "after:6.0"), lifecycle.events());

            lifecycle.events().clear();
            operationType.getField("fail").setBoolean(operation, true);
            assertThrows(java.lang.reflect.InvocationTargetException.class, () ->
                set.invoke(operation, source, 4.0F)
            );
            lifecycle.coordinator().awaitIdle();
            assertEquals(java.util.List.of(), lifecycle.events());

            operationType.getField("fail").setBoolean(operation, false);
            set.invoke(operation, source, 5.0F);
            lifecycle.coordinator().awaitIdle();
            assertEquals(10.0F, operationType.getField("value").getFloat(operation));
            assertEquals(java.util.List.of("on:6.0->10.0", "after:10.0"), lifecycle.events());
        } finally {
            NativeParameterLifecycleBridge.uninstall(lifecycle.bridge());
            lifecycle.coordinator().close();
        }
    }

    private static byte[] fixtureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/PaletteOperation", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "value", "F", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "fail", "Z", null, null).visitEnd();
        constructor(writer, "fixture/PaletteOperation");
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "set",
            "(Lfixture/ParameterSource;F)V",
            null,
            null
        );
        method.visitCode();
        final org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, "fixture/PaletteOperation", "fail", "Z");
        method.visitJumpInsn(Opcodes.IFEQ, continueLabel);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("native failure");
        method.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/IllegalStateException",
            "<init>",
            "(Ljava/lang/String;)V",
            false
        );
        method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(continueLabel);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.FLOAD, 2);
        method.visitFieldInsn(Opcodes.PUTFIELD, "fixture/PaletteOperation", "value", "F");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] sourceClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/ParameterSource", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "id", "Ljava/lang/String;", null, null).visitEnd();
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            "(Ljava/lang/String;)V",
            null,
            null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, "fixture/ParameterSource", "id", "Ljava/lang/String;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        final MethodVisitor getter = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getIdString",
            "()Ljava/lang/String;",
            null,
            null
        );
        getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, "fixture/ParameterSource", "id", "Ljava/lang/String;");
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(final ClassWriter writer, final String owner) {
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static final class RecordingBridgeLifecycle {
        private final java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ParameterLifecycleCoordinator coordinator = new ParameterLifecycleCoordinator();
        private final dev.turboism.sdk.cubism.model.Parameter parameter =
            new dev.turboism.sdk.cubism.model.Parameter() {
                @Override public dev.turboism.sdk.cubism.id.ParameterId id() {
                    return new dev.turboism.sdk.cubism.id.ParameterId("ParamA");
                }
                @Override public float getValue() {
                    try {
                        return operationType.getField("value").getFloat(operation);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
                @Override public float getMinimumValue() { return -30.0F; }
                @Override public float getMaximumValue() { return 30.0F; }
                @Override public float getDefaultValue() { return 0.0F; }
                @Override public void setValue(final float value) {
                    try {
                        operationType.getField("value").setFloat(operation, value);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            };
        private final Class<?> operationType;
        private final Object operation;
        private final NativeParameterLifecycleBridge bridge;

        private RecordingBridgeLifecycle(final Class<?> operationType, final Object operation) {
            this.operationType = operationType;
            this.operation = operation;
            coordinator.register(new ParameterLifecycleCoordinator.PluginHooks(
                descriptor(),
                java.util.List.of(new dev.turboism.sdk.cubism.CubismPlugin() {
                    @Override public float beforeSetParameterValue(
                        final dev.turboism.sdk.cubism.model.Parameter parameter,
                        final float value
                    ) { return value * 2.0F; }
                    @Override public void onParameterValueChanged(
                        final dev.turboism.sdk.cubism.model.Parameter parameter,
                        final float oldValue,
                        final float newValue
                    ) { events.add("on:" + oldValue + "->" + newValue); }
                    @Override public void afterSetParameterValue(
                        final dev.turboism.sdk.cubism.model.Parameter parameter,
                        final float value
                    ) { events.add("after:" + value); }
                }),
                logger()
            ));
            bridge = new NativeParameterLifecycleBridge(
                coordinator,
                () -> model(parameter)
            );
        }

        private ParameterLifecycleCoordinator coordinator() { return coordinator; }
        private NativeParameterLifecycleBridge bridge() { return bridge; }
        private java.util.List<String> events() { return events; }

        private dev.turboism.sdk.cubism.model.CubismModel model(
            final dev.turboism.sdk.cubism.model.Parameter parameter
        ) {
            return new dev.turboism.sdk.cubism.model.CubismModel() {
                @Override public dev.turboism.sdk.cubism.id.ModelId id() {
                    return new dev.turboism.sdk.cubism.id.ModelId("model-a");
                }
                @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                    return new dev.turboism.sdk.cubism.model.Parameters() {
                        @Override public java.util.List<dev.turboism.sdk.cubism.model.Parameter> all() {
                            return java.util.List.of(parameter);
                        }
                        @Override public dev.turboism.sdk.cubism.model.Parameter find(
                            final dev.turboism.sdk.cubism.id.ParameterId id
                        ) { return parameter; }
                    };
                }
                @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unavailable(); }
                @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unavailable(); }
                @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unavailable(); }
                @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unavailable(); }
                @Override public void update() { throw unavailable(); }
                private UnsupportedOperationException unavailable() {
                    return new UnsupportedOperationException();
                }
            };
        }

        private static dev.turboism.sdk.plugin.PluginDescriptor descriptor() {
            return new dev.turboism.sdk.plugin.PluginDescriptor() {
                @Override public String id() { return "plugin-a"; }
                @Override public String name() { return "plugin-a"; }
                @Override public String version() { return "1.0.0"; }
                @Override public String description() { return "test"; }
                @Override public java.util.List<String> entrypoints() { return java.util.List.of(); }
                @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
                @Override public java.util.List<Author> authors() { return java.util.List.of(); }
                @Override public String license() { return "Test"; }
                @Override public java.util.Optional<String> website() { return java.util.Optional.empty(); }
                @Override public java.util.List<String> resources() { return java.util.List.of(); }
                @Override public I18n i18n() { return new I18n() {
                    @Override public String baseName() { return "messages"; }
                    @Override public java.util.List<String> locales() { return java.util.List.of(); }
                }; }
                @Override public java.util.List<DependencyRef> dependencies() { return java.util.List.of(); }
                @Override public java.util.List<PermissionRef> permissions() { return java.util.List.of(); }
                @Override public java.util.List<String> capabilities() { return java.util.List.of(); }
                @Override public Environment environment() { return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "none"; }
                }; }
            };
        }

        private static dev.turboism.sdk.plugin.PluginLogger logger() {
            return new dev.turboism.sdk.plugin.PluginLogger() {
                @Override public void debug(final String message) { }
                @Override public void info(final String message) { }
                @Override public void warn(final String message) { }
                @Override public void error(final String message) { }
                @Override public void error(final String message, final Throwable throwable) { }
            };
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.EditorExitResult;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.cubism.hook.AnimationFileHooks;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLifecycleNativeMethodTransformerTest {

    private static final String OWNER = "fixture/LifecycleHost";

    @Test
    void transformationDoesNotPrintHostClassMethodOrDescriptorIdentities() {
        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(stream);
            System.setErr(stream);
            final ProjectLifecycleNativeMethodTransformer transformer =
                new ProjectLifecycleNativeMethodTransformer(
                    List.of(ProjectLifecycleNativeMethodTransformer.Binding.editorExit(
                        OWNER,
                        "exit",
                        "()Z"
                    )),
                    null
                );
            assertNotNull(transformer.transform(
                null,
                null,
                OWNER,
                null,
                null,
                fixtureClass()
            ));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        final String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.isEmpty(), () -> "unexpected native-console output: " + output);
    }

    @Test
    void transformedMethodsPublishNormalRejectedAndExceptionalLifecycleResults() throws Exception {
        final List<ProjectLifecycleNativeMethodTransformer.Binding> bindings = List.of(
            ProjectLifecycleNativeMethodTransformer.Binding.modelOpen(
                OWNER,
                "openModel",
                "(Ljava/lang/String;Ljava/lang/Object;Ljava/io/File;ZLjava/lang/Object;)Ljava/lang/Object;"
            ),
            ProjectLifecycleNativeMethodTransformer.Binding.animationOpen(
                OWNER,
                "openAnimation",
                "(Ljava/lang/Object;Ljava/io/File;ZLjava/lang/Object;)Ljava/lang/Object;"
            ),
            ProjectLifecycleNativeMethodTransformer.Binding.content(
                OWNER,
                "save",
                "()Z",
                ProjectContentKind.MODEL,
                ProjectFileOperationType.SAVE
            ),
            ProjectLifecycleNativeMethodTransformer.Binding.editorExit(OWNER, "exit", "()Z")
        );
        final ProjectLifecycleNativeMethodTransformer transformer =
            new ProjectLifecycleNativeMethodTransformer(bindings, null);
        final byte[] transformed = transformer.transform(
            null,
            null,
            OWNER,
            null,
            null,
            fixtureClass()
        );
        assertNotNull(transformed);

        final FixtureLoader loader = new FixtureLoader();
        final Class<?> hostType = loader.define("fixture.LifecycleHost", transformed);
        final Object host = hostType.getConstructor().newInstance();
        final Method openModel = hostType.getMethod(
            "openModel",
            String.class,
            Object.class,
            File.class,
            boolean.class,
            Object.class
        );
        final Method openAnimation = hostType.getMethod(
            "openAnimation",
            Object.class,
            File.class,
            boolean.class,
            Object.class
        );
        final Method save = hostType.getMethod("save");
        final Method exit = hostType.getMethod("exit");

        final List<String> events = new CopyOnWriteArrayList<>();
        final ProjectFileLifecycleCoordinator projectFiles = new ProjectFileLifecycleCoordinator();
        final EditorLifecycleCoordinator editor = new EditorLifecycleCoordinator();
        projectFiles.register(new ProjectFileLifecycleCoordinator.PluginHooks(
            descriptor(),
            List.of(modelHooks(events)),
            List.of(animationHooks(events)),
            logger(),
            true
        ));
        editor.register(new EditorLifecycleCoordinator.PluginHooks(
            descriptor(),
            List.of(editorHooks(events)),
            logger(),
            true
        ));
        final NativeProjectLifecycleBridge bridge = new NativeProjectLifecycleBridge(
            projectFiles,
            editor,
            "5.3.02"
        );
        NativeProjectLifecycleBridge.install(bridge);
        try {
            openModel.invoke(host, "Model A", null, null, false, null);
            projectFiles.awaitIdle();
            assertEquals(List.of(
                "before-model-create",
                "on-model-create:Model A",
                "after-model-create:true:none"
            ), events);

            events.clear();
            openAnimation.invoke(host, host, new File("motion.can3"), false, null);
            projectFiles.awaitIdle();
            assertEquals(List.of(
                "before-animation-open",
                "on-animation-open:Animation A",
                "after-animation-open:true:none"
            ), events);

            events.clear();
            hostType.getField("result").setBoolean(host, false);
            save.invoke(host);
            projectFiles.awaitIdle();
            assertEquals(List.of(
                "before-model-save",
                "after-model-save:false:none"
            ), events);

            events.clear();
            hostType.getField("result").setBoolean(host, true);
            exit.invoke(host);
            editor.awaitIdle();
            assertEquals(List.of(
                "before-editor-exit",
                "on-editor-exit",
                "after-editor-exit:true:none"
            ), events);

            events.clear();
            hostType.getField("fail").setBoolean(host, true);
            assertThrows(InvocationTargetException.class, () ->
                openModel.invoke(host, "Model A", null, null, false, null)
            );
            projectFiles.awaitIdle();
            assertEquals(List.of(
                "before-model-create",
                "after-model-create:false:java.lang.IllegalStateException"
            ), events);
        } finally {
            NativeProjectLifecycleBridge.uninstall(bridge);
            editor.close();
            projectFiles.close();
        }
    }

    private static ModelFileHooks modelHooks(final List<String> events) {
        return new ModelFileHooks() {
            @Override public void beforeCreateModel(final ProjectFileOperation operation) {
                events.add("before-model-create");
            }
            @Override public void onModelCreated(final ProjectContentSnapshot model) {
                events.add("on-model-create:" + model.name());
            }
            @Override public void afterCreateModel(final ProjectFileOperationResult result) {
                events.add("after-model-create:" + result.succeeded() + ":"
                    + result.failureType().orElse("none"));
            }
            @Override public void beforeSaveModel(final ProjectFileOperation operation) {
                events.add("before-model-save");
            }
            @Override public void onModelSaved(final ProjectContentSnapshot model) {
                events.add("on-model-save");
            }
            @Override public void afterSaveModel(final ProjectFileOperationResult result) {
                events.add("after-model-save:" + result.succeeded() + ":"
                    + result.failureType().orElse("none"));
            }
        };
    }

    private static AnimationFileHooks animationHooks(final List<String> events) {
        return new AnimationFileHooks() {
            @Override public void beforeOpenAnimation(final ProjectFileOperation operation) {
                events.add("before-animation-open");
            }
            @Override public void onAnimationOpened(final ProjectContentSnapshot animation) {
                events.add("on-animation-open:" + animation.name());
            }
            @Override public void afterOpenAnimation(final ProjectFileOperationResult result) {
                events.add("after-animation-open:" + result.succeeded() + ":"
                    + result.failureType().orElse("none"));
            }
        };
    }

    private static EditorLifecycleHooks editorHooks(final List<String> events) {
        return new EditorLifecycleHooks() {
            @Override public void beforeEditorExit(final EditorLifecycleSnapshot editor) {
                events.add("before-editor-exit");
            }
            @Override public void onEditorExiting(final EditorLifecycleSnapshot editor) {
                events.add("on-editor-exit");
            }
            @Override public void afterEditorExit(final EditorExitResult result) {
                events.add("after-editor-exit:" + result.accepted() + ":"
                    + result.failureType().orElse("none"));
            }
        };
    }

    private static byte[] fixtureClass() {
        final ClassWriter writer = new ClassWriter(
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        );
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "fail", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "result", "Z", null, Boolean.TRUE).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "file", "Ljava/io/File;", null, null).visitEnd();
        constructor(writer);
        objectReturnMethod(
            writer,
            "openModel",
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/io/File;ZLjava/lang/Object;)Ljava/lang/Object;"
        );
        objectReturnMethod(
            writer,
            "openAnimation",
            "(Ljava/lang/Object;Ljava/io/File;ZLjava/lang/Object;)Ljava/lang/Object;"
        );
        booleanReturnMethod(writer, "save");
        booleanReturnMethod(writer, "exit");
        constantStringMethod(writer, "getModelName", "Model A");
        constantStringMethod(writer, "getDocumentUID", "model-document-a");
        constantStringMethod(writer, "getName", "Animation A");
        selfMethod(writer, "getAnimation");
        emptyListMethod(writer, "getSceneDocs");
        fileGetter(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(final ClassWriter writer) {
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            "()V",
            null,
            null
        );
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            "<init>",
            "()V",
            false
        );
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "result", "Z");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void objectReturnMethod(
        final ClassWriter writer,
        final String name,
        final String descriptor
    ) {
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            name,
            descriptor,
            null,
            null
        );
        method.visitCode();
        throwWhenFailed(method);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void booleanReturnMethod(final ClassWriter writer, final String name) {
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            name,
            "()Z",
            null,
            null
        );
        method.visitCode();
        throwWhenFailed(method);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OWNER, "result", "Z");
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void throwWhenFailed(final MethodVisitor method) {
        final Label success = new Label();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OWNER, "fail", "Z");
        method.visitJumpInsn(Opcodes.IFEQ, success);
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
        method.visitLabel(success);
    }

    private static void constantStringMethod(
        final ClassWriter writer,
        final String name,
        final String value
    ) {
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            name,
            "()Ljava/lang/String;",
            null,
            null
        );
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void selfMethod(final ClassWriter writer, final String name) {
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            name,
            "()Ljava/lang/Object;",
            null,
            null
        );
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emptyListMethod(final ClassWriter writer, final String name) {
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            name,
            "()Ljava/util/List;",
            null,
            null
        );
        method.visitCode();
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/util/List",
            "of",
            "()Ljava/util/List;",
            true
        );
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void fileGetter(final ClassWriter writer) {
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getFile",
            "()Ljava/io/File;",
            null,
            null
        );
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OWNER, "file", "Ljava/io/File;");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return "plugin-transformer"; }
            @Override public String name() { return "plugin-transformer"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return "test"; }
            @Override public List<String> entrypoints() { return List.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Test"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() { return "messages"; }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
    }

    private static final class FixtureLoader extends ClassLoader {
        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

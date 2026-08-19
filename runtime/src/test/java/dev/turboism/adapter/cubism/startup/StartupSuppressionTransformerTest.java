package dev.turboism.adapter.cubism.startup;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StartupSuppressionTransformerTest {

    @Test
    void suppressesOnlyStartupCallsAndTheExactSplashMethod() throws Exception {
        final StartupSuppressionProfile profile = StartupSuppressionProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_02
        ).orElseThrow();
        final StartupSuppressionTransformer transformer = new StartupSuppressionTransformer(
            profile,
            new RuntimeStartupConfig(false, true, true, true)
        );

        final byte[] transformed = transformer.transformClass(fixtureApplication());
        final FixtureLoader loader = new FixtureLoader();
        loader.define("com.live2d.ui.window.V", emptyClass("com/live2d/ui/window/V"));
        final Class<?> controller = loader.define(
            "com.live2d.cubism.CEAppCtrl",
            controllerClass()
        );
        final Class<?> application = loader.define(
            "com.live2d.cubism.CECubismEditorApp",
            transformed
        );
        final Object app = application.getConstructor().newInstance();
        final Method startup = application.getMethod("a", String[].class);
        final Method manualUpdate = application.getMethod("manualUpdate");

        startup.invoke(app, (Object) new String[0]);
        assertEquals(0, controller.getField("updateChecks").getInt(null));
        assertEquals(0, controller.getField("informationPages").getInt(null));
        final Method splash = application.getDeclaredMethod("e");
        splash.setAccessible(true);
        assertNull(splash.invoke(app));

        manualUpdate.invoke(app);
        assertEquals(1, controller.getField("updateChecks").getInt(null));
    }

    @Test
    void rejectsTheWholeClassWhenARequestedInvocationIsNotExactlyMatched() {
        final StartupSuppressionProfile profile = StartupSuppressionProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_02
        ).orElseThrow();
        final StartupSuppressionTransformer transformer = new StartupSuppressionTransformer(
            profile,
            new RuntimeStartupConfig(false, true, true, true)
        );

        assertThrows(
            StartupSuppressionTransformer.TransformationRejectedException.class,
            () -> transformer.transformClass(fixtureApplicationWithoutInformationCall())
        );
    }

    private static byte[] fixtureApplication() {
        return applicationClass(true);
    }


    static byte[] fixtureApplicationForInstaller() {
        return fixtureApplication();
    }

    private static byte[] fixtureApplicationWithoutInformationCall() {
        return applicationClass(false);
    }

    private static byte[] applicationClass(final boolean includeInformation) {
        final String owner = "com/live2d/cubism/CECubismEditorApp";
        final String controller = "com/live2d/cubism/CEAppCtrl";
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        constructor(writer, owner);

        MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "a",
            "([Ljava/lang/String;)V",
            null,
            null
        );
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, controller);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, controller, "<init>", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, controller, "command_checkUpdate", "()V", false);
        if (includeInformation) {
            method.visitTypeInsn(Opcodes.NEW, controller);
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, controller, "<init>", "()V", false);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, controller, "showInformation", "()V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        method = writer.visitMethod(Opcodes.ACC_PUBLIC, "manualUpdate", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, controller);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, controller, "<init>", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, controller, "command_checkUpdate", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        method = writer.visitMethod(
            Opcodes.ACC_PRIVATE,
            "e",
            "()Lcom/live2d/ui/window/V;",
            null,
            null
        );
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "com/live2d/ui/window/V");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "com/live2d/ui/window/V",
            "<init>",
            "()V",
            false
        );
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] controllerClass() {
        final String owner = "com/live2d/cubism/CEAppCtrl";
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "updateChecks",
            "I",
            null,
            null
        ).visitEnd();
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "informationPages",
            "I",
            null,
            null
        ).visitEnd();
        constructor(writer, owner);
        counterMethod(writer, owner, "command_checkUpdate", "updateChecks");
        counterMethod(writer, owner, "showInformation", "informationPages");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void counterMethod(
        final ClassWriter writer,
        final String owner,
        final String methodName,
        final String fieldName
    ) {
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            methodName,
            "()V",
            null,
            null
        );
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, fieldName, "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, owner, fieldName, "I");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static byte[] emptyClass(final String owner) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        constructor(writer, owner);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(final ClassWriter writer, final String owner) {
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            "()V",
            null,
            null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            "<init>",
            "()V",
            false
        );
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader() {
            super(StartupSuppressionTransformerTest.class.getClassLoader());
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

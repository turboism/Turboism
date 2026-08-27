package dev.turboism.adapter.cubism.filechooser;

import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChooserHistoryNativeMethodTransformerTest {

    private static final String OWNER = "fixture/FileChooserHost";
    private static final String CONTEXT = "fixture.FileChooserHost";

    @TempDir
    Path tempDir;

    private NativeFileChooserHistoryBridge installedBridge;

    @AfterEach
    void uninstall() {
        if (installedBridge != null) {
            NativeFileChooserHistoryBridge.uninstall(installedBridge);
            installedBridge = null;
        }
    }

    private static FileChooserHistoryHostProfile profile() {
        return new FileChooserHistoryHostProfile(
            "5.3.02",
            List.of(
                new FileChooserHistoryHostProfile.SaveDialogMethod(
                    "c", "(Ljava/lang/Object;)Ljava/io/File;"
                ),
                new FileChooserHistoryHostProfile.SaveDialogMethod(
                    "a", "(Ljava/lang/Object;Z)Ljava/io/File;"
                )
            ),
            List.of(CONTEXT)
        );
    }

    private void installBridge(final FileChooserHistoryService service) {
        installedBridge = new NativeFileChooserHistoryBridge(service, profile());
        NativeFileChooserHistoryBridge.install(installedBridge);
    }

    private FileChooserHistoryService enabledService(
        final Path exportDirectory,
        final AtomicReference<Path> captured
    ) {
        return new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() { return Optional.empty(); }
            @Override public Optional<Path> exportRecentDirectory() {
                return Optional.of(exportDirectory);
            }
            @Override public void setProjectRecentDirectory(final Path dir) { }
            @Override public void setExportRecentDirectory(final Path dir) { captured.set(dir); }
            @Override public boolean exportSeparationEnabled() { return true; }
            @Override public Registration registerProvider(final Provider provider) { return () -> { }; }
        };
    }

    @Test
    void recordsEachExactTransformedSelector() throws Exception {
        final AtomicInteger transformed = new AtomicInteger();
        final byte[] bytes = new FileChooserHistoryNativeMethodTransformer(
            OWNER,
            profile().saveDialogMethods(),
            null,
            ignored -> transformed.incrementAndGet()
        ).transform(null, null, OWNER, null, null, fixtureClass());

        assertNotNull(bytes);
        assertEquals(2, transformed.get());
    }

    @Test
    void transformedSaveDialogMethodsPreserveOriginalLogicAndPublishBeforeAfter() throws Exception {
        final byte[] transformed = new FileChooserHistoryNativeMethodTransformer(
            OWNER, profile().saveDialogMethods(), null
        ).transform(null, null, OWNER, null, null, fixtureClass());
        assertNotNull(transformed);

        final FixtureLoader loader = new FixtureLoader();
        final Class<?> hostType = loader.define(OWNER.replace('/', '.'), transformed);
        final Object host = hostType.getConstructor().newInstance();

        final File chosen = tempDir.resolve("chosen.cmo3").toFile();
        assertTrue(chosen.createNewFile());
        field(host, "result").set(host, chosen);
        final FakeImpl impl = new FakeImpl();
        field(host, "d").set(host, impl);

        final AtomicReference<Path> captured = new AtomicReference<>();
        installBridge(enabledService(tempDir, captured));

        final Method c = hostType.getMethod("c", Object.class);
        final Method a = hostType.getMethod("a", Object.class, boolean.class);

        field(host, "b").set(host, new ArrayList<File>());
        assertSame(chosen, c.invoke(host, new Object()));
        assertEquals(List.of(tempDir.toFile()), field(host, "b").get(host));
        assertEquals(tempDir.toFile(), impl.currentDirectory);
        assertEquals(tempDir, captured.get());

        field(host, "b").set(host, new ArrayList<File>());
        assertSame(chosen, a.invoke(host, new Object(), true));
        assertEquals(List.of(tempDir.toFile()), field(host, "b").get(host));
        assertEquals(tempDir, captured.get());
    }

    @Test
    void transformedMethodThrowsWhenOriginalThrows() throws Exception {
        final byte[] transformed = new FileChooserHistoryNativeMethodTransformer(
            OWNER, profile().saveDialogMethods(), null
        ).transform(null, null, OWNER, null, null, fixtureClass());
        final FixtureLoader loader = new FixtureLoader();
        final Class<?> hostType = loader.define(OWNER.replace('/', '.'), transformed);
        final Object host = hostType.getConstructor().newInstance();
        field(host, "fail").setBoolean(host, true);
        final AtomicReference<Path> captured = new AtomicReference<>();
        installBridge(enabledService(tempDir, captured));

        try {
            hostType.getMethod("c", Object.class).invoke(host, new Object());
            throw new AssertionError("expected the original native failure to propagate");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        assertNull(captured.get());
    }

    @Test
    void noBridgeInstalledMeansZeroBehaviorChange() throws Exception {
        final byte[] transformed = new FileChooserHistoryNativeMethodTransformer(
            OWNER, profile().saveDialogMethods(), null
        ).transform(null, null, OWNER, null, null, fixtureClass());
        final FixtureLoader loader = new FixtureLoader();
        final Class<?> hostType = loader.define(OWNER.replace('/', '.'), transformed);
        final Object host = hostType.getConstructor().newInstance();
        final File chosen = tempDir.resolve("chosen.cmo3").toFile();
        assertTrue(chosen.createNewFile());
        field(host, "result").set(host, chosen);
        field(host, "b").set(host, new ArrayList<File>());
        field(host, "d").set(host, new FakeImpl());

        assertSame(chosen, hostType.getMethod("c", Object.class).invoke(host, new Object()));
        assertTrue(((List<?>) field(host, "b").get(host)).isEmpty());
    }

    @Test
    void disabledSeparationLeavesChooserUntouched() throws Exception {
        final byte[] transformed = new FileChooserHistoryNativeMethodTransformer(
            OWNER, profile().saveDialogMethods(), null
        ).transform(null, null, OWNER, null, null, fixtureClass());
        final FixtureLoader loader = new FixtureLoader();
        final Class<?> hostType = loader.define(OWNER.replace('/', '.'), transformed);
        final Object host = hostType.getConstructor().newInstance();
        final File chosen = tempDir.resolve("chosen.cmo3").toFile();
        assertTrue(chosen.createNewFile());
        field(host, "result").set(host, chosen);
        field(host, "b").set(host, new ArrayList<File>());
        final FakeImpl impl = new FakeImpl();
        field(host, "d").set(host, impl);
        installBridge(new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() { return Optional.empty(); }
            @Override public Optional<Path> exportRecentDirectory() { return Optional.of(tempDir); }
            @Override public void setProjectRecentDirectory(final Path dir) { }
            @Override public void setExportRecentDirectory(final Path dir) { }
            @Override public boolean exportSeparationEnabled() { return false; }
            @Override public Registration registerProvider(final Provider provider) { return () -> { }; }
        });

        assertSame(chosen, hostType.getMethod("c", Object.class).invoke(host, new Object()));

        assertTrue(((List<?>) field(host, "b").get(host)).isEmpty());
        assertNull(impl.currentDirectory);
    }

    @Test
    void nonTargetClassesAndLoadersAreNotTransformed() throws Exception {
        final FileChooserHistoryNativeMethodTransformer transformer =
            new FileChooserHistoryNativeMethodTransformer(
                OWNER, profile().saveDialogMethods(), NativeFileChooserHistoryBridgeTest.class.getClassLoader()
            );
        assertNull(transformer.transform(null, null, "fixture/OtherHost", null, null, fixtureClass()));
        assertNull(transformer.transform(null, null, OWNER, null, null, null));
    }

    @Test
    void nonSaveDialogMethodsAreLeftAlone() throws Exception {
        final byte[] transformed = new FileChooserHistoryNativeMethodTransformer(
            OWNER, profile().saveDialogMethods(), null
        ).transform(null, null, OWNER, null, null, fixtureClass());
        final FixtureLoader loader = new FixtureLoader();
        final Class<?> hostType = loader.define(OWNER.replace('/', '.'), transformed);
        final Object host = hostType.getConstructor().newInstance();
        final File untouched = tempDir.resolve("x").toFile();
        field(host, "result").set(host, untouched);
        // x() is not a save-dialog binding and carries no bridge calls.
        assertSame(untouched, hostType.getMethod("x").invoke(host));
    }

    private static Field field(final Object host, final String name) throws Exception {
        final Field field = host.getClass().getField(name);
        return field;
    }

    private static byte[] fixtureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null,
            "java/lang/Object", null
        );
        writer.visitField(Opcodes.ACC_PUBLIC, "result", "Ljava/io/File;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "b", "Ljava/util/List;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "d", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "fail", "Z", null, null).visitEnd();

        final MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        saveDialogMethod(writer, "c", "(Ljava/lang/Object;)Ljava/io/File;");
        saveDialogMethod(writer, "a", "(Ljava/lang/Object;Z)Ljava/io/File;");

        final MethodVisitor x = writer.visitMethod(Opcodes.ACC_PUBLIC, "x", "()Ljava/io/File;", null, null);
        x.visitCode();
        x.visitVarInsn(Opcodes.ALOAD, 0);
        x.visitFieldInsn(Opcodes.GETFIELD, OWNER, "result", "Ljava/io/File;");
        x.visitInsn(Opcodes.ARETURN);
        x.visitMaxs(0, 0);
        x.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void saveDialogMethod(
        final ClassWriter writer,
        final String name,
        final String descriptor
    ) {
        final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        final Label success = new Label();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OWNER, "fail", "Z");
        method.visitJumpInsn(Opcodes.IFEQ, success);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("native failure");
        method.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
            "(Ljava/lang/String;)V", false
        );
        method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(success);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OWNER, "result", "Ljava/io/File;");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    static final class FakeImpl {
        File currentDirectory;

        public void setCurrentDirectory(final File directory) {
            currentDirectory = directory;
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

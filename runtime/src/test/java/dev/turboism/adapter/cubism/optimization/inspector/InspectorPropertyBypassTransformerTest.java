package dev.turboism.adapter.cubism.optimization.inspector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

class InspectorPropertyBypassTransformerTest {

    private static final String SOURCE = InspectorPropertyBypassTarget.SOURCE_OWNER;
    private static final String ALPHA = "com/live2d/cubism/doc/model/drawable/AlphaComposition";
    private static final String COLOR = "com/live2d/cubism/doc/model/drawable/ColorComposition";

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;

        Loader(final Path artifact) throws Exception {
            super(InspectorPropertyBypassTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    @AfterEach
    void clearSlots() {
        System.getProperties().remove(InspectorPropertyBypassBridge.BYPASS_CALLBACK_PROPERTY);
        System.getProperties().remove(InspectorPropertyBypassBridge.STATS_PROPERTY);
        System.getProperties().remove(InspectorPropertyBypassBridge.ENABLE_PROPERTY);
        System.getProperties().remove(InspectorPropertyBypassBridge.VALIDATION_PROPERTY);
        InspectorPropertyBypassBridge.setConfiguredDefault(false);
    }

    private static byte[] source() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, SOURCE, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd();
        final MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor update = writer.visitMethod(ACC_PUBLIC, "updateModelInstances", "()V", null, null);
        update.visitCode();
        update.visitFieldInsn(GETSTATIC, SOURCE, "calls", "I");
        update.visitInsn(ICONST_1);
        update.visitInsn(IADD);
        update.visitFieldInsn(PUTSTATIC, SOURCE, "calls", "I");
        update.visitInsn(RETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] marker(final String name) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        final MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] setter(final String owner, final String method, final String descriptor) {
        return setter(owner, method, descriptor, false);
    }

    private static byte[] setter(
            final String owner, final String method, final String descriptor, final boolean changed) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, owner, null, "java/lang/Object", null);
        final MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor body = writer.visitMethod(ACC_PUBLIC, method, descriptor, null, null);
        body.visitCode();
        if (changed) body.visitInsn(org.objectweb.asm.Opcodes.NOP);
        body.visitTypeInsn(NEW, SOURCE);
        body.visitInsn(DUP);
        body.visitMethodInsn(INVOKESPECIAL, SOURCE, "<init>", "()V", false);
        body.visitMethodInsn(INVOKEVIRTUAL, SOURCE, "updateModelInstances", "()V", false);
        body.visitInsn(RETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Map<String, byte[]> references() {
        final Map<String, byte[]> references = new LinkedHashMap<>();
        for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
            references.put(seam.owner(), setter(seam.owner(), seam.method(), seam.descriptor()));
        }
        return references;
    }

    @Test
    void predicateTrueSkipsNativeAndFalseRunsIt() throws Exception {
        final Path artifact = Files.createTempFile("inspector-bypass-host", ".jar");
        final Loader loader = new Loader(artifact);
        loader.define(SOURCE, source());
        loader.define(ALPHA, marker(ALPHA));
        loader.define(COLOR, marker(COLOR));
        final Map<String, byte[]> references = references();

        final InspectorPropertyBypassTarget.Seam seam = InspectorPropertyBypassTarget.SEAMS.get(0);
        final InspectorPropertyBypassTransformer transformer =
                new InspectorPropertyBypassTransformer(loader, artifact, references);
        final byte[] output =
                transformer.transform(null, loader, seam.owner(), null, loader.domain, references.get(seam.owner()));
        assertNotNull(output, transformer.failure());
        final Class<?> type = loader.define(seam.owner(), output);
        final Object setter = type.getDeclaredConstructor().newInstance();
        final Method method = type.getMethod(seam.method(), loader.loadClass(ALPHA.replace('/', '.')));
        final Class<?> sourceType = loader.loadClass(SOURCE.replace('/', '.'));

        try {
            System.getProperties()
                    .put(InspectorPropertyBypassBridge.BYPASS_CALLBACK_PROPERTY, (Predicate<Object>) ignored -> true);
            method.invoke(
                    setter,
                    loader.loadClass(ALPHA.replace('/', '.'))
                            .getDeclaredConstructor()
                            .newInstance());
            assertEquals(0, sourceType.getField("calls").getInt(null));
            assertEquals(1, transformer.matches());
            assertTrue(transformer.failure() == null);

            System.getProperties()
                    .put(InspectorPropertyBypassBridge.BYPASS_CALLBACK_PROPERTY, (Predicate<Object>) ignored -> false);
            method.invoke(
                    setter,
                    loader.loadClass(ALPHA.replace('/', '.'))
                            .getDeclaredConstructor()
                            .newInstance());
            assertEquals(1, sourceType.getField("calls").getInt(null));
        } finally {
            clearSlots();
        }
    }

    @Test
    void shapeMismatchWrongSlotAndForeignDomainFailClosed() throws Exception {
        final Path artifact = Files.createTempFile("inspector-bypass-host", ".jar");
        final Loader loader = new Loader(artifact);
        final Map<String, byte[]> references = references();
        final InspectorPropertyBypassTarget.Seam seam = InspectorPropertyBypassTarget.SEAMS.get(0);
        final InspectorPropertyBypassTransformer transformer =
                new InspectorPropertyBypassTransformer(loader, artifact, references);

        final byte[] changed = setter(seam.owner(), seam.method(), seam.descriptor(), true);
        assertNull(transformer.transform(null, loader, seam.owner(), null, loader.domain, changed));
        assertNotNull(transformer.failure());

        final Path other = Files.createTempFile("inspector-bypass-other", ".jar");
        final Loader foreign = new Loader(other);
        assertNull(
                transformer.transform(null, foreign, seam.owner(), null, foreign.domain, references.get(seam.owner())));
        assertNotNull(transformer.failure());
    }

    @Test
    void bridgeUsesTheDirectShaderPathAndFallsBackOnFailure() {
        final FakeHost host = new FakeHost();
        final InspectorPropertyBypassBridge bridge = new InspectorPropertyBypassBridge(host);
        try (bridge) {
            bridge.install();
            InspectorPropertyBypassBridge.setConfiguredDefault(true);
            assertTrue(bridge.shouldBypass("setter"));
            assertEquals(1, host.setups);
            assertEquals(1L, bridge.snapshot().get("bypassed"));

            host.fail = true;
            assertFalse(bridge.shouldBypass("setter"));
            assertEquals(1L, bridge.snapshot().get("failures"));
        }
    }

    private static final class FakeHost implements InspectorPropertyBypassBridge.Host {
        private Object drawable = new Object();
        private boolean fail;
        private int setups;

        @Override
        public Object drawable(final Object setter) {
            return drawable;
        }

        @Override
        public void setupShader(final Object drawable) {
            if (fail) throw new IllegalStateException("shader unavailable");
            setups++;
        }
    }
}

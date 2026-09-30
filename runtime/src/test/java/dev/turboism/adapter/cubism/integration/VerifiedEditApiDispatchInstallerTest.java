package dev.turboism.adapter.cubism.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.selector.EditorIntegrationWebSocketSelectorContract;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class VerifiedEditApiDispatchInstallerTest {
    private static final String OWNER = "com/live2d/cubism/doc/webSocket/l";
    private static final String SOCKET = "org/java_websocket/WebSocket";
    private static final String DESCRIPTOR = "(Ljava/lang/String;L" + SOCKET + ";)V";

    @Test
    void installedBytesInvokeReceiverAndClosingRestoresTheNativeBody() throws Exception {
        final Host host = new Host(true, false, false);
        final var installer = host.installer();
        final List<Object> messages = new ArrayList<>();
        try {
            assertTrue(installer.install((message, socket) -> {
                messages.add(message);
                return true;
            }));
            assertTrue(installer.isInstalled());
            assertEquals(List.of(OWNER.replace('/', '.')), installer.transformedClassNames());
            host.invoke(host.applied, "claimed");
            assertEquals(List.of("claimed"), messages);
            assertTrue(installer.install((message, socket) -> false));
            assertEquals(1, host.transformers.size());
        } finally {
            installer.close();
        }
        assertArrayEquals(host.original, host.applied);
        assertTrue(host.transformers.isEmpty());
        assertFalse(installer.isInstalled());
        assertFalse(System.getProperties().containsKey(VerifiedEditApiDispatchInstaller.CALLBACK_KEY));
        host.invoke(host.applied, "native");
        assertEquals(List.of("claimed"), messages);
    }

    @Test
    void noOpRetransformationAndChangedTargetDoNotAdvertiseInstallation() {
        for (final Host host : List.of(new Host(false, false, false), new Host(true, true, false))) {
            try (var installer = host.installer()) {
                assertThrows(IllegalStateException.class, () -> installer.install((message, socket) -> true));
                assertFalse(installer.isInstalled());
                assertTrue(host.transformers.isEmpty());
                assertFalse(System.getProperties().containsKey(VerifiedEditApiDispatchInstaller.CALLBACK_KEY));
            }
        }
    }

    @Test
    void registrationFailureRemovesOnlyItsOwnReceiver() {
        final Host host = new Host(true, false, true);
        try (var installer = host.installer()) {
            assertThrows(IllegalStateException.class, () -> installer.install((message, socket) -> true));
            assertFalse(installer.isInstalled());
            assertTrue(host.transformers.isEmpty());
            assertFalse(System.getProperties().containsKey(VerifiedEditApiDispatchInstaller.CALLBACK_KEY));
        }
    }

    @Test
    void aSecondInstallerCannotReplaceOrRemoveAnExistingCallback() {
        final Host host = new Host(true, false, false);
        final Object existing = new Object();
        System.getProperties().put(VerifiedEditApiDispatchInstaller.CALLBACK_KEY, existing);
        try (var installer = host.installer()) {
            assertThrows(IllegalStateException.class, () -> installer.install((message, socket) -> true));
            assertSame(existing, System.getProperties().get(VerifiedEditApiDispatchInstaller.CALLBACK_KEY));
            assertTrue(host.transformers.isEmpty());
        } finally {
            System.getProperties().remove(VerifiedEditApiDispatchInstaller.CALLBACK_KEY, existing);
        }
    }

    private static final class Host {
        private final byte[] original;
        private byte[] applied;
        private final List<ClassFileTransformer> transformers = new ArrayList<>();
        private final ClassLoader loader;
        private final Instrumentation instrumentation;

        Host(final boolean transform, final boolean changed, final boolean failRegistration) {
            original = bytes(OWNER, changed ? "changed" : "a");
            applied = original;
            loader = loader(original);
            instrumentation = (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Instrumentation.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "isRetransformClassesSupported", "isModifiableClass" -> true;
                            case "addTransformer" -> {
                                if (failRegistration) throw new IllegalStateException("registration failure");
                                transformers.add((ClassFileTransformer) args[0]);
                                yield null;
                            }
                            case "removeTransformer" -> transformers.remove(args[0]);
                            case "retransformClasses" -> {
                                byte[] result = original;
                                if (transform) {
                                    for (final ClassFileTransformer transformer : List.copyOf(transformers)) {
                                        final byte[] patched = transformer.transform(
                                                null, loader, OWNER, ((Class<?>[]) args[0])[0], null, result);
                                        if (patched != null) result = patched;
                                    }
                                }
                                applied = result;
                                yield null;
                            }
                            default -> null;
                        };
                    });
        }

        VerifiedEditApiDispatchInstaller installer() {
            final var resolver = TestVerifiedResolvers.createCompatible(
                    "5.3.02",
                    "5.3.99",
                    EditorIntegrationWebSocketSelectorContract.ADAPTER_SLICE_ID,
                    Set.of(EditorIntegrationWebSocketSelectorContract.DISPATCH_CAPABILITY_ID),
                    List.of(StaticSelector.method(
                            "cubism.integration.websocket.dispatch.on-message",
                            OWNER,
                            "a",
                            DESCRIPTOR,
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)),
                    loader);
            return VerifiedEditApiDispatchInstaller.fromVerifiedResolver(instrumentation, resolver, loader);
        }

        ClassLoader loader(final byte[] target) {
            final Map<String, byte[]> definitions = Map.of(OWNER, target, SOCKET, bytes(SOCKET, null));
            return new ClassLoader(getClass().getClassLoader()) {
                @Override
                protected Class<?> findClass(final String name) throws ClassNotFoundException {
                    final byte[] data = definitions.get(name.replace('.', '/'));
                    if (data == null) throw new ClassNotFoundException(name);
                    return defineClass(name, data, 0, data.length);
                }
            };
        }

        void invoke(final byte[] target, final String message) throws Exception {
            final ClassLoader callLoader = loader(target);
            final Class<?> type = Class.forName(OWNER.replace('/', '.'), true, callLoader);
            final var method = type.getDeclaredMethod(
                    "a", String.class, Class.forName(SOCKET.replace('/', '.'), false, callLoader));
            method.setAccessible(true);
            method.invoke(type.getConstructor().newInstance(), message, null);
        }
    }

    private static byte[] bytes(final String owner, final String targetName) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        final var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        if (targetName != null) {
            final var method =
                    writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, targetName, DESCRIPTOR, null, null);
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}

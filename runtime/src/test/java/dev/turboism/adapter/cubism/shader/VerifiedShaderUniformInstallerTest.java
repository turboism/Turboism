package dev.turboism.adapter.cubism.shader;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VerifiedShaderUniformInstallerTest {
    @AfterEach void restored() { NativeShaderUniformBridge.enabled(false); }

    static Instrumentation instrumentation(AtomicInteger adds, AtomicInteger removes, boolean failAdd, boolean failRemove) {
        return (Instrumentation) Proxy.newProxyInstance(Instrumentation.class.getClassLoader(), new Class<?>[] { Instrumentation.class }, (proxy, method, args) ->
            switch (method.getName()) {
                case "getAllLoadedClasses" -> new Class<?>[0];
                case "addTransformer" -> { adds.incrementAndGet(); if (failAdd) throw new IllegalStateException("add"); yield null; }
                case "removeTransformer" -> { removes.incrementAndGet(); if (failRemove) throw new IllegalStateException("remove"); yield true; }
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test void installationEnablesBridgeOnlyForItsLifetime() {
        assertFalse(NativeShaderUniformBridge.enabled());
        try (var installed = VerifiedShaderUniformInstaller.install(
                instrumentation(new AtomicInteger(), new AtomicInteger(), false, false), ignored -> { })) {
            assertNotNull(installed);
            assertTrue(NativeShaderUniformBridge.enabled());
        }
        assertFalse(NativeShaderUniformBridge.enabled());
    }

    @Test void alreadyLoadedHostShaderDeclinesInstall() {
        // A late definition can never be patched: simulate the owner class already present.
        byte[] minimal = minimalShader();
        Class<?> loaded = new ClassLoader(null) {
            Class<?> define() { return defineClass(ShaderUniformTransformer.OWNER.replace('/', '.'), minimal, 0, minimal.length); }
        }.define();
        var instrumentation = (Instrumentation) Proxy.newProxyInstance(Instrumentation.class.getClassLoader(),
            new Class<?>[] { Instrumentation.class }, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getAllLoadedClasses" -> new Class<?>[] { loaded };
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        assertNull(VerifiedShaderUniformInstaller.install(instrumentation, ignored -> { }));
        assertFalse(NativeShaderUniformBridge.enabled());
    }

    static byte[] minimalShader() {
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            ShaderUniformTransformer.OWNER, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void duplicateAndRepeatedCloseDoNotRemoveAnotherInstallation() {
        var adds = new AtomicInteger(); var removes = new AtomicInteger();
        var instrumentation = instrumentation(adds, removes, false, false);
        var first = VerifiedShaderUniformInstaller.install(instrumentation, ignored -> { });
        assertNotNull(first);
        try { assertNull(VerifiedShaderUniformInstaller.install(instrumentation, ignored -> { })); }
        finally { first.close(); }
        first.close();
        assertEquals(1, adds.get());
        assertEquals(1, removes.get());
    }

    @Test void failedRegistrationDoesNotPoisonNextInstall() {
        var adds = new AtomicInteger(); var removes = new AtomicInteger();
        assertNull(VerifiedShaderUniformInstaller.install(instrumentation(adds, removes, true, false), ignored -> { }));
        try (var next = VerifiedShaderUniformInstaller.install(instrumentation(adds, removes, false, false), ignored -> { })) {
            assertNotNull(next);
        }
        assertEquals(2, adds.get());
        assertEquals(1, removes.get());
    }

    @Test void failedRemovalDoesNotInterruptShutdownOrLeaveOwnership() {
        var adds = new AtomicInteger(); var removes = new AtomicInteger();
        var first = VerifiedShaderUniformInstaller.install(instrumentation(adds, removes, false, true), ignored -> { });
        assertNotNull(first);
        assertDoesNotThrow(first::close);
        try (var next = VerifiedShaderUniformInstaller.install(instrumentation(adds, removes, false, false), ignored -> { })) {
            assertNotNull(next);
        }
        assertEquals(2, adds.get());
        assertEquals(2, removes.get());
    }
}

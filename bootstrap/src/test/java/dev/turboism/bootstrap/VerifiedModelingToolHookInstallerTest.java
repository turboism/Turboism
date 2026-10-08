package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.modeling.ModelingToolHostProfile;
import dev.turboism.adapter.cubism.modeling.ModelingToolLifecycleTransformer;
import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedModelingToolHookInstallerTest {
    @TempDir
    Path temporary;

    static final ModelingToolHostProfile PROFILE = new ModelingToolHostProfile(
            "5.3.03",
            "com/live2d/cubism/CEAppCtrl",
            "setupCurrentTool",
            "(Lcom/live2d/cubism/view/palette/tool/toolMode/AToolGroup;Lcom/live2d/cubism/view/palette/tool/toolMode/AToolMode;Z)V");

    @Test
    void installsAndRestoresLoadedOwnerOnClose() throws Exception {
        Fixture f = new Fixture(true);
        var installer = f.installer(AttachmentMode.PREMAIN);
        installer.install();
        assertEquals(List.of("bind", "add", "transform"), f.events);
        assertNotNull(f.transformed);
        assertFalse(java.util.Arrays.equals(f.original, f.transformed));
        installer.close();
        installer.close();
        assertEquals(List.of("bind", "add", "transform", "remove", "restore", "unbind"), f.events);
        assertNull(f.transformer);
        assertArrayEquals(fixture(true), f.original);
    }

    @Test
    void failureToWeaveRollsBackWithoutPublishingReadiness() throws Exception {
        Fixture f = new Fixture(false);
        var installer = f.installer(AttachmentMode.PREMAIN);
        assertThrows(IllegalStateException.class, installer::install);
        assertEquals(List.of("bind", "add", "transform", "remove", "restore", "unbind"), f.events);
        assertNull(f.transformer);
    }

    @Test
    void failedTeardownIsRetryableAndRetainsBridgeUntilBytecodeIsRestored() throws Exception {
        Fixture f = new Fixture(true);
        var installer = f.installer(AttachmentMode.PREMAIN);
        installer.install();
        f.rejectRemoval = true;
        assertThrows(IllegalStateException.class, installer::close);
        assertFalse(f.events.contains("unbind"));
        assertThrows(IllegalStateException.class, installer::install);
        f.rejectRemoval = false;
        f.rejectRestore = true;
        assertThrows(IllegalStateException.class, installer::close);
        assertFalse(f.events.contains("unbind"));
        f.rejectRestore = false;
        installer.close();
        installer.close();
        assertEquals("unbind", f.events.get(f.events.size() - 1));
    }

    @Test
    void rejectsAgentmainWrongLoaderDuplicateAndUnmodifiableOwnersBeforeSideEffects() throws Exception {
        Fixture f = new Fixture(true);
        assertThrows(
                IllegalStateException.class,
                () -> f.installer(AttachmentMode.AGENTMAIN).install());
        assertTrue(f.events.isEmpty());
        f.modifiable = false;
        assertThrows(
                IllegalStateException.class,
                () -> f.installer(AttachmentMode.PREMAIN).install());
        assertTrue(f.events.isEmpty());
        f.modifiable = true;
        f.loaded = new Class<?>[] {f.target, f.target};
        assertThrows(
                IllegalStateException.class,
                () -> f.installer(AttachmentMode.PREMAIN).install());
        assertTrue(f.events.isEmpty());
        f.loaded = new Class<?>[] {new Loader().define(f.original)};
        assertThrows(
                IllegalStateException.class,
                () -> f.installer(AttachmentMode.PREMAIN).install());
        assertTrue(f.events.isEmpty());
    }

    @Test
    void transformerRejectsWrongArtifactLoaderOrSignature() throws Exception {
        Fixture f = new Fixture(true);
        var transformer = new ModelingToolLifecycleTransformer(PROFILE, f.loader, f.artifact, ignored -> {});
        assertNull(transformer.transform(null, new Loader(), PROFILE.ownerInternalName(), null, f.domain, f.original));
        assertNull(transformer.transform(
                null, f.loader, PROFILE.ownerInternalName(), null, new ProtectionDomain(null, null), f.original));
        assertNull(transformer.transform(null, f.loader, PROFILE.ownerInternalName(), null, f.domain, fixture(false)));
        assertEquals(0, transformer.transformedCount());
    }

    class Fixture {
        final List<String> events = new ArrayList<>();
        final byte[] original;
        final Loader loader = new Loader();
        final Path artifact = temporary.resolve("Cubism.jar").toAbsolutePath();
        final ProtectionDomain domain;
        final Class<?> target;
        Class<?>[] loaded;
        ClassFileTransformer transformer;
        byte[] transformed;
        boolean modifiable = true, rejectRemoval, rejectRestore;

        Fixture(boolean valid) throws Exception {
            original = fixture(valid);
            target = loader.define(original);
            loaded = new Class<?>[] {target};
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
        }

        VerifiedModelingToolHookInstaller installer(AttachmentMode mode) {
            Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Instrumentation.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isRetransformClassesSupported" -> true;
                        case "getAllLoadedClasses" -> loaded;
                        case "isModifiableClass" -> modifiable;
                        case "addTransformer" -> {
                            assertEquals(true, args[1]);
                            events.add("add");
                            transformer = (ClassFileTransformer) args[0];
                            yield null;
                        }
                        case "removeTransformer" -> {
                            events.add("remove");
                            if (rejectRemoval) yield false;
                            transformer = null;
                            yield true;
                        }
                        case "retransformClasses" -> {
                            if (transformer == null) {
                                events.add("restore");
                                if (rejectRestore) throw new IllegalStateException("restore");
                            } else {
                                events.add("transform");
                                transformed = transformer.transform(
                                        null, loader, PROFILE.ownerInternalName(), target, domain, original);
                            }
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            return new VerifiedModelingToolHookInstaller(
                    instrumentation,
                    mode,
                    loader,
                    PROFILE,
                    artifact,
                    () -> events.add("bind"),
                    () -> events.add("unbind"));
        }
    }

    private static final java.util.Map<Boolean, byte[]> FIXTURES = new java.util.HashMap<>();

    static synchronized byte[] fixture(boolean valid) throws Exception {
        if (FIXTURES.containsKey(valid)) return FIXTURES.get(valid).clone();
        final Path directory = java.nio.file.Files.createTempDirectory("modeling-hook-fixture-");
        try {
            final String types = "com.live2d.cubism.view.palette.tool.toolMode.";
            final Path group = directory.resolve("AToolGroup.java");
            final Path mode = directory.resolve("AToolMode.java");
            final Path app = directory.resolve("CEAppCtrl.java");
            java.nio.file.Files.writeString(
                    group, "package " + types.substring(0, types.length() - 1) + "; public class AToolGroup {}");
            java.nio.file.Files.writeString(
                    mode, "package " + types.substring(0, types.length() - 1) + "; public class AToolMode {}");
            final String method = valid
                    ? "public void setupCurrentTool(" + types + "AToolGroup group, " + types
                            + "AToolMode mode, boolean persist) { if (persist) return; if (group == null) return; throw new IllegalStateException(); }"
                    : "";
            java.nio.file.Files.writeString(app, "package com.live2d.cubism; public class CEAppCtrl {" + method + "}");
            final var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler);
            assertEquals(
                    0,
                    compiler.run(
                            null,
                            null,
                            null,
                            "--release",
                            "17",
                            "-d",
                            directory.toString(),
                            group.toString(),
                            mode.toString(),
                            app.toString()));
            final byte[] bytes =
                    java.nio.file.Files.readAllBytes(directory.resolve(PROFILE.ownerInternalName() + ".class"));
            FIXTURES.put(valid, bytes);
            return bytes.clone();
        } finally {
            try (var files = java.nio.file.Files.walk(directory)) {
                for (Path file :
                        files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(file);
            }
        }
    }

    static class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(PROFILE.ownerInternalName().replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}

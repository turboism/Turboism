package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTransformer;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTransformer;
import dev.turboism.adapter.cubism.optimization.stateelision.RedundantStateElisionTransformer;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTransformer;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Replays the JVM retransform protocol over licensed reference bytes; never runs Editor code. */
class VerifiedElisionInstallerRollbackTest {
    enum Hook {
        UPLOAD(SkippedFrameUploadElisionTransformer.class, "turboism.upload-elision."),
        INPUT(InputPathElisionTransformer.class, "turboism.input-path."),
        COMPOSITE(CanvasCompositeElisionTransformer.class, "turboism.canvas-composite."),
        STATE(RedundantStateElisionTransformer.class, "turboism.state-elision.");
        final Class<?> transformer;
        final String prefix;
        Hook(Class<?> transformer, String prefix) { this.transformer = transformer; this.prefix = prefix; }
    }
    interface Install { void run() throws Exception; }
    record Installer(Install install, AutoCloseable handle, BooleanSupplier restored) { }

    @Test void successfulInstallRestoresEveryChangedClass() throws Exception {
        for (Hook hook : Hook.values()) {
            try (Fixture f = fixture(hook)) {
                Installer installer = f.installer();
                installer.install.run();
                assertFalse(f.rewritten.isEmpty(), hook.toString());
                installer.handle.close();
                assertTrue(installer.restored.getAsBoolean(), hook.toString());
                f.assertRestored();
                installer.handle.close();
            }
        }
    }

    @Test void secondClassAdmissionRejectionRestoresFirstClass() throws Exception {
        for (Hook hook : List.of(Hook.UPLOAD, Hook.COMPOSITE)) {
            try (Fixture f = fixture(hook)) {
                Installer installer = f.installer();
                f.rejectAt = 2;
                Exception failure = assertThrows(Exception.class, installer.install::run);
                assertTrue(failure.getMessage().contains("not admitted"), failure.toString());
                assertEquals(1, f.rewritten.size(), "first class really received changed bytes");
                assertTrue(installer.restored.getAsBoolean());
                f.assertRestored();
            }
        }
    }

    @Test void failureAfterRewriteRollsBackSingleClassInstallers() throws Exception {
        for (Hook hook : List.of(Hook.INPUT, Hook.STATE)) {
            try (Fixture f = fixture(hook)) {
                Installer installer = f.installer();
                f.throwAfter = 1;
                assertThrows(UnmodifiableClassException.class, installer.install::run);
                assertEquals(1, f.rewritten.size());
                assertTrue(installer.restored.getAsBoolean());
                f.assertRestored();
            }
        }
    }

    @Test void restorationFailureDoesNotAbandonOtherClassesAndCanBeRetried() throws Exception {
        for (Hook hook : List.of(Hook.UPLOAD, Hook.COMPOSITE)) {
            try (Fixture f = fixture(hook)) {
                Installer installer = f.installer();
                f.throwAfter = 2;
                f.failFirstRestore = true;
                Exception failure = assertThrows(UnmodifiableClassException.class, installer.install::run);
                assertEquals(1, failure.getSuppressed().length, "cleanup failure preserves install failure");
                assertFalse(installer.restored.getAsBoolean());
                assertEquals(2, f.rewritten.size());
                assertEquals(1, f.rewritten.stream().filter(f::isRestored).count(),
                    "the other class must still be restored after the first restoration fails");
                assertTrue(f.active.isEmpty());
                installer.handle.close();
                assertTrue(installer.restored.getAsBoolean());
                f.assertRestored();
            }
        }
    }

    private static Fixture fixture(Hook hook) throws Exception {
        String supplied = System.getenv("TURBOISM_UNIFORM_HOST_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "exact reference artifact not supplied");
        return new Fixture(Path.of(supplied), hook);
    }

    private static final class Fixture implements AutoCloseable {
        final Path artifact;
        final Hook hook;
        final URLClassLoader loader;
        final List<ClassFileTransformer> active = new ArrayList<>();
        final Map<Class<?>, byte[]> original = new LinkedHashMap<>(), installed = new LinkedHashMap<>();
        final Set<Class<?>> rewritten = new LinkedHashSet<>();
        final Instrumentation instrumentation;
        int hookClasses, rejectAt, throwAfter;
        boolean failFirstRestore;

        Fixture(Path artifact, Hook hook) throws Exception {
            this.artifact = artifact;
            this.hook = hook;
            List<URL> urls = new ArrayList<>();
            try (var files = Files.walk(artifact.getParent(), 3)) {
                for (Path path : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    urls.add(path.toUri().toURL());
                }
            }
            loader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
            instrumentation = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isRetransformClassesSupported", "isModifiableClass" -> true;
                    case "addTransformer" -> { active.add((ClassFileTransformer) args[0]); yield null; }
                    case "removeTransformer" -> active.remove(args[0]);
                    case "retransformClasses" -> {
                        for (Class<?> type : (Class<?>[]) args[0]) retransform(type);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        Installer installer() throws Exception {
            return switch (hook) {
                case UPLOAD -> {
                    var i = new VerifiedSkippedFrameUploadElisionInstaller(instrumentation, artifact, loader);
                    yield new Installer(i::install, i, i::restored);
                }
                case INPUT -> {
                    var i = new VerifiedInputPathElisionInstaller(instrumentation, artifact, loader);
                    yield new Installer(i::install, i, i::restored);
                }
                case COMPOSITE -> {
                    var i = new VerifiedCanvasCompositeElisionInstaller(instrumentation, artifact, loader);
                    yield new Installer(i::install, i, i::restored);
                }
                case STATE -> {
                    var i = new VerifiedRedundantStateElisionInstaller(instrumentation, artifact, loader);
                    yield new Installer(i::install, i, i::restored);
                }
            };
        }

        void retransform(Class<?> type) throws Exception {
            boolean hooking = active.stream().anyMatch(hook.transformer::isInstance);
            if (hooking) hookClasses++;
            if (!hooking && failFirstRestore && rewritten.contains(type)) {
                failFirstRestore = false;
                throw new UnmodifiableClassException("injected restoration failure");
            }
            byte[] bytes;
            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                bytes = input.readAllBytes();
            }
            original.putIfAbsent(type, bytes.clone());
            for (ClassFileTransformer transformer : List.copyOf(active)) {
                byte[] next = transformer.transform(type.getModule(), type.getClassLoader(),
                    type.getName().replace('.', '/'), type,
                    hooking && hookClasses == rejectAt ? null : type.getProtectionDomain(), bytes);
                if (next != null) {
                    bytes = next;
                    if (hook.transformer.isInstance(transformer)) rewritten.add(type);
                }
            }
            installed.put(type, bytes);
            if (hooking && hookClasses == throwAfter) {
                throw new UnmodifiableClassException("injected post-rewrite failure");
            }
        }

        boolean isRestored(Class<?> type) { return Arrays.equals(original.get(type), installed.get(type)); }
        void assertRestored() {
            assertTrue(active.isEmpty(), "all owned registrations removed");
            for (Class<?> type : rewritten) assertTrue(isRestored(type), type.getName());
            assertFalse(System.getProperties().keySet().stream()
                .anyMatch(key -> key.toString().startsWith(hook.prefix)), "bridge slots unpublished");
        }
        @Override public void close() throws Exception { loader.close(); }
    }
}

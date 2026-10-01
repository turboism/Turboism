package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.mesh.MeshEditorLifecycleTransformer;
import dev.turboism.adapter.cubism.mesh.MeshToolSessionHostProfile;
import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VerifiedMeshToolSessionHookInstallerTest {
    private static final MeshToolSessionHostProfile PROFILE = new MeshToolSessionHostProfile(
            "5.3.03", "example/ExactMeshEditor", "startMode", "(Ljava/util/List;)V", "endMode", "()V");

    @Test
    void retransformsAnAlreadyLoadedExactOwnerInsteadOfFailing() {
        // Regression: the host loads its mesh-editor class during its own startup, so an
        // already-loaded owner must be retransformed. Treating it as a hard failure meant the
        // lifecycle was never observed and every custom tool activation was refused.
        final AtomicReference<ClassFileTransformer> added = new AtomicReference<>();
        final AtomicBoolean canRetransform = new AtomicBoolean();
        final List<Class<?>> retransformed = new ArrayList<>();
        final Profile alreadyLoaded = profileFor(ExactMeshEditor.class);
        final Instrumentation instrumentation = instrumentation(
                added,
                canRetransform,
                new AtomicBoolean(),
                new Class<?>[] {ExactMeshEditor.class},
                retransformed,
                true);

        final VerifiedMeshToolSessionHookInstaller installer =
                installer(instrumentation, AttachmentMode.PREMAIN, alreadyLoaded);

        installer.install();
        assertTrue(added.get() instanceof MeshEditorLifecycleTransformer);
        assertTrue(canRetransform.get(), "the transformer must be registered with retransformation enabled");
        assertEquals(List.of(ExactMeshEditor.class), retransformed);
        assertEquals(MeshEditorLifecycleTransformer.Outcome.TARGET_TRANSFORMED, installer.transformOutcome());

        installer.close();
        // Teardown retransforms the owner again so the host keeps its original bytecode.
        assertEquals(2, retransformed.size());
    }

    @Test
    void failsClosedWhenNoLoadedOwnerIsTransformed() {
        final AtomicReference<ClassFileTransformer> added = new AtomicReference<>();
        final Profile loaded = profileFor(ExactMeshEditor.class);
        final Instrumentation instrumentation = instrumentation(
                added,
                new AtomicBoolean(),
                new AtomicBoolean(),
                new Class<?>[] {ExactMeshEditor.class},
                new ArrayList<>(),
                false);
        final VerifiedMeshToolSessionHookInstaller installer =
                installer(instrumentation, AttachmentMode.PREMAIN, loaded);

        final IllegalStateException failure = assertThrows(IllegalStateException.class, installer::install);
        assertTrue(failure.getMessage().contains("TRANSFORM_DELTA_MISMATCH"), failure.getMessage());
        // A failed install must not leave a transformer behind.
        assertNull(added.get());
    }

    @Test
    void rejectsAgentmainAndWrongLoaderOwnersWithoutRetransforming() {
        final AtomicReference<ClassFileTransformer> added = new AtomicReference<>();
        final List<Class<?>> retransformed = new ArrayList<>();
        final Instrumentation instrumentation =
                instrumentation(added, new AtomicBoolean(), new AtomicBoolean(), new Class<?>[0], retransformed, false);
        final VerifiedMeshToolSessionHookInstaller agentmain =
                installer(instrumentation, AttachmentMode.AGENTMAIN, PROFILE);
        assertThrows(IllegalStateException.class, agentmain::install);
        assertEquals(List.of(), retransformed);
    }

    @Test
    void rejectsAHostLoaderThatCannotLinkTheBoundBridgeBeforeAnySideEffects() {
        final List<String> events = new ArrayList<>();
        final var instrumentation = instrumentation(
                new AtomicReference<>(),
                new AtomicBoolean(),
                new AtomicBoolean(),
                new Class<?>[0],
                new ArrayList<>(),
                false);
        final var installer = new VerifiedMeshToolSessionHookInstaller(
                instrumentation,
                AttachmentMode.PREMAIN,
                new ClassLoader(null) {},
                PROFILE,
                Path.of("Cubism.jar").toAbsolutePath(),
                () -> events.add("bind"),
                () -> events.add("unbind"));
        final var failure = assertThrows(IllegalStateException.class, installer::install);
        assertTrue(failure.getMessage().contains("bridge"));
        assertEquals(List.of(), events);
    }

    @Test
    void teardownIsIdempotent() {
        final AtomicReference<ClassFileTransformer> added = new AtomicReference<>();
        final AtomicBoolean removed = new AtomicBoolean();
        final Instrumentation instrumentation =
                instrumentation(added, new AtomicBoolean(), removed, new Class<?>[0], new ArrayList<>(), false);
        final VerifiedMeshToolSessionHookInstaller installer =
                installer(instrumentation, AttachmentMode.PREMAIN, PROFILE);

        installer.install();
        installer.close();
        installer.close();
        assertTrue(removed.get());
    }

    @Test
    void failedRemovalKeepsTheBridgeAndCanBeRetriedWithoutDuplicatingTheTransformer() {
        final AtomicBoolean failRemoval = new AtomicBoolean(true);
        final List<String> events = new ArrayList<>();
        final var installer = retryInstaller(failRemoval, new AtomicBoolean(), events);
        installer.install();

        assertThrows(IllegalStateException.class, installer::close);
        assertEquals(List.of("bind", "add", "transform", "remove"), events);
        assertThrows(IllegalStateException.class, installer::install);
        failRemoval.set(false);
        installer.close();
        installer.close();
        assertEquals(List.of("bind", "add", "transform", "remove", "remove", "restore", "unbind"), events);
    }

    @Test
    void failedRestorationRetainsTheBridgeUntilRetryAndDoesNotRemoveTwice() {
        final AtomicBoolean failRestoration = new AtomicBoolean(true);
        final List<String> events = new ArrayList<>();
        final var installer = retryInstaller(new AtomicBoolean(), failRestoration, events);
        installer.install();

        assertThrows(IllegalStateException.class, installer::close);
        assertEquals(List.of("bind", "add", "transform", "remove", "restore"), events);
        assertThrows(IllegalStateException.class, installer::install);
        failRestoration.set(false);
        installer.close();
        installer.close();
        assertEquals(List.of("bind", "add", "transform", "remove", "restore", "restore", "unbind"), events);
    }

    private static VerifiedMeshToolSessionHookInstaller retryInstaller(
            final AtomicBoolean failRemoval, final AtomicBoolean failRestoration, final List<String> events) {
        final AtomicReference<MeshEditorLifecycleTransformer> transformer = new AtomicReference<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                VerifiedMeshToolSessionHookInstallerTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isRetransformClassesSupported", "isModifiableClass" -> true;
                    case "getAllLoadedClasses" -> new Class<?>[] {ExactMeshEditor.class};
                    case "addTransformer" -> {
                        events.add("add");
                        transformer.set((MeshEditorLifecycleTransformer) args[0]);
                        yield null;
                    }
                    case "removeTransformer" -> {
                        events.add("remove");
                        if (failRemoval.get()) yield false;
                        transformer.set(null);
                        yield true;
                    }
                    case "retransformClasses" -> {
                        if (transformer.get() != null) {
                            events.add("transform");
                            transformFixture(transformer.get(), ExactMeshEditor.class);
                        } else {
                            events.add("restore");
                            if (failRestoration.get()) throw new IllegalStateException("restore unavailable");
                        }
                        yield null;
                    }
                    default -> null;
                });
        return new VerifiedMeshToolSessionHookInstaller(
                instrumentation,
                AttachmentMode.PREMAIN,
                VerifiedMeshToolSessionHookInstallerTest.class.getClassLoader(),
                profileFor(ExactMeshEditor.class).value(),
                Path.of("Cubism.jar").toAbsolutePath().normalize(),
                () -> events.add("bind"),
                () -> events.add("unbind"));
    }

    private static Profile profileFor(final Class<?> owner) {
        return new Profile(new MeshToolSessionHostProfile(
                "5.3.03", owner.getName().replace('.', '/'), "startMode", "(Ljava/util/List;)V", "endMode", "()V"));
    }

    private record Profile(MeshToolSessionHostProfile value) {}

    private static VerifiedMeshToolSessionHookInstaller installer(
            final Instrumentation instrumentation, final AttachmentMode mode, final Profile profile) {
        return installer(instrumentation, mode, profile.value());
    }

    private static VerifiedMeshToolSessionHookInstaller installer(
            final Instrumentation instrumentation,
            final AttachmentMode mode,
            final MeshToolSessionHostProfile profile) {
        // The bridge binding is exercised through the recorded transformer effects; the exact
        // resolver/coordinator contracts are covered by their own focused tests.
        return new VerifiedMeshToolSessionHookInstaller(
                instrumentation,
                mode,
                VerifiedMeshToolSessionHookInstallerTest.class.getClassLoader(),
                profile,
                Path.of("Cubism.jar").toAbsolutePath().normalize(),
                () -> {},
                () -> {});
    }

    private static Instrumentation instrumentation(
            final AtomicReference<ClassFileTransformer> added,
            final AtomicBoolean canRetransform,
            final AtomicBoolean removed,
            final Class<?>[] loaded,
            final List<Class<?>> retransformed,
            final boolean emulateTransform) {
        return (Instrumentation) Proxy.newProxyInstance(
                VerifiedMeshToolSessionHookInstallerTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAllLoadedClasses" -> loaded;
                    case "addTransformer" -> {
                        added.set((ClassFileTransformer) args[0]);
                        canRetransform.set(args.length > 1 && (Boolean) args[1]);
                        yield null;
                    }
                    case "removeTransformer" -> {
                        removed.set(args[0] == added.getAndSet(null));
                        yield true;
                    }
                    case "retransformClasses" -> {
                        for (final Object target : (Object[]) args[0]) {
                            retransformed.add((Class<?>) target);
                        }
                        // Replay the real fixture definition, including selector shape and bridge weaving.
                        if (emulateTransform && added.get() instanceof MeshEditorLifecycleTransformer lifecycle) {
                            for (final Object target : (Object[]) args[0]) {
                                transformFixture(lifecycle, (Class<?>) target);
                            }
                        }
                        yield null;
                    }
                    case "isModifiableClass" -> true;
                    case "isRetransformClassesSupported",
                            "isRedefineClassesSupported",
                            "isNativeMethodPrefixSupported",
                            "isModifiableModule" -> true;
                    case "getInitiatedClasses" -> new Class<?>[0];
                    case "getObjectSize" -> 0L;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "instrumentation-proxy";
                    default -> null;
                });
    }

    private static void transformFixture(final ClassFileTransformer transformer, final Class<?> target)
            throws Exception {
        final String owner = target.getName().replace('.', '/');
        try (var resource = target.getResourceAsStream("/" + owner + ".class")) {
            if (resource == null) throw new IllegalStateException("fixture definition is missing");
            final var domain = new java.security.ProtectionDomain(
                    new java.security.CodeSource(
                            Path.of("Cubism.jar").toAbsolutePath().toUri().toURL(),
                            (java.security.cert.Certificate[]) null),
                    null);
            transformer.transform(null, target.getClassLoader(), owner, target, domain, resource.readAllBytes());
        }
    }

    static final class ExactMeshEditor {
        void startMode(List<?> entries) {}

        void endMode() {}
    }
}

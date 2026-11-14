package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.test.Stubs;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pins the one-enumeration-per-phase contract: every hook of a phase pass shares one
 * {@link Instrumentation#getAllLoadedClasses()} sample, and a new phase or an explicit
 * invalidation samples again.
 */
class LoadedClassSnapshotTest {

    @Test
    void onePhasePassEnumeratesTheLoadedClassesOnceForEveryRetransformingHook() throws Exception {
        final AtomicInteger enumerations = new AtomicInteger();
        final List<Class<?>> retransformed = new ArrayList<>();
        final Instrumentation instrumentation = countingInstrumentation(enumerations, retransformed);
        final LoadedClassSnapshot phase = new LoadedClassSnapshot(instrumentation);

        for (int hook = 0; hook < 5; hook++) {
            VerifiedHookInstallerSupport.retransformIfLoaded(
                    instrumentation, phase.current(), Set.of(Target.class.getName()), Target.class.getClassLoader());
        }

        assertEquals(1, enumerations.get(), "one phase pass must enumerate the loaded classes once");
        assertEquals(5, retransformed.size(), "every hook must still reach its loaded target");
        assertTrue(retransformed.stream().allMatch(type -> type == Target.class));
    }

    @Test
    void aHookThatDefinesAClassTheSampleCannotAnswerForDropsTheSample() {
        final AtomicInteger enumerations = new AtomicInteger();
        final Instrumentation instrumentation = countingInstrumentation(enumerations, new ArrayList<>());
        final LoadedClassSnapshot phase = new LoadedClassSnapshot(instrumentation);

        final LoadedClassIndex first = phase.current();
        assertSame(first, phase.current(), "a phase pass must reuse its sample");
        assertEquals(1, enumerations.get());

        // The sample already answers for Target, so a hook that only re-observed it keeps the sample.
        phase.invalidateUnlessContains(List.of(Target.class));
        assertSame(first, phase.current(), "a class the sample answers for must not drop it");
        assertEquals(1, enumerations.get());

        // A class the sample never saw must force the next reader to enumerate again.
        phase.invalidateUnlessContains(List.of(Other.class));
        final LoadedClassIndex second = phase.current();

        assertNotSame(first, second, "an invalidated phase must sample again");
        assertEquals(2, enumerations.get());
    }

    @Test
    void aNewPhaseStartsFromItsOwnSample() {
        final AtomicInteger enumerations = new AtomicInteger();
        final Instrumentation instrumentation = countingInstrumentation(enumerations, new ArrayList<>());

        final LoadedClassIndex premain = new LoadedClassSnapshot(instrumentation).current();
        final LoadedClassIndex hostResolved = new LoadedClassSnapshot(instrumentation).current();

        assertNotSame(premain, hostResolved, "each phase must own its snapshot");
        assertEquals(2, enumerations.get());
    }

    @Test
    void indexesNamesSoALookupDoesNotScanEveryLoadedClass() {
        final Instrumentation instrumentation = countingInstrumentation(new AtomicInteger(), new ArrayList<>());
        final LoadedClassIndex index = LoadedClassIndex.snapshot(instrumentation);

        assertTrue(index.containsName(Target.class.getName()));
        assertTrue(index.containsName(Target.class.getName(), Target.class.getClassLoader()));
        assertFalse(index.containsName(Target.class.getName(), new FixtureLoader()));
        assertFalse(index.containsName("absent.Fixture"));
        assertEquals(
                List.of(Target.class), index.select(Set.of(Target.class.getName()), Target.class.getClassLoader()));
        assertEquals(List.of(), index.select(Set.of(Target.class.getName()), new FixtureLoader()));
    }

    @Test
    void anEnvironmentWithoutInstrumentationFailsOnlyWhenTheSnapshotIsRead() {
        final HookEnvironment environment = HookEnvironment.builder().build();

        assertThrows(IllegalStateException.class, environment::loadedClasses);
    }

    @Test
    void threeRealInstallersOfOnePhasePassShareOneEnumeration() throws Exception {
        final AtomicInteger enumerations = new AtomicInteger();
        final List<Class<?>> retransformed = new ArrayList<>();
        final Instrumentation instrumentation = countingInstrumentation(enumerations, retransformed);
        final LoadedClassIndex phase = new LoadedClassSnapshot(instrumentation).current();
        final String owner = Target.class.getName().replace('.', '/');
        final ClassLoader loader = Target.class.getClassLoader();

        try (var dock = new VerifiedDockTabPopupHookInstaller(
                        instrumentation,
                        method(owner, "open"),
                        field(owner, "palette"),
                        method("fixture/Menu", "append"),
                        loader,
                        phase);
                var dispose = new VerifiedFloatingFrameDisposeHookInstaller(
                        instrumentation, method(owner, "dispose"), loader, phase);
                var close = new VerifiedFloatingTabCloseHookInstaller(
                        instrumentation, method(owner, "close"), field(owner, "palette"), loader, phase)) {
            dock.install();
            dispose.install();
            close.install();

            assertEquals(1, enumerations.get(), "three installers of one phase must enumerate once");
            assertEquals(3, retransformed.size(), "every installer must still reach its loaded target");
        }
    }

    private static dev.turboism.mapping.verification.StaticSelector method(final String owner, final String name) {
        return dev.turboism.mapping.verification.StaticSelector.method(
                owner + "." + name,
                owner,
                name,
                "(Ljava/lang/Object;)V",
                dev.turboism.mapping.verification.StaticSelector.ACCESS_PUBLIC);
    }

    private static dev.turboism.mapping.verification.StaticSelector field(final String owner, final String name) {
        return new dev.turboism.mapping.verification.StaticSelector(
                owner + "." + name,
                owner + "." + name,
                dev.turboism.mapping.verification.StaticSelector.Kind.FIELD,
                owner,
                name,
                "Ljava/lang/Object;",
                0,
                dev.turboism.mapping.verification.StaticSelector.ACCESS_STATIC);
    }

    private static Instrumentation countingInstrumentation(
            final AtomicInteger enumerations, final List<Class<?>> retransformed) {
        return (Instrumentation) Proxy.newProxyInstance(
                LoadedClassSnapshotTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAllLoadedClasses" -> {
                        enumerations.incrementAndGet();
                        yield new Class<?>[] {Target.class};
                    }
                    case "isRetransformClassesSupported" -> true;
                    case "isModifiableClass" -> true;
                    case "addTransformer" -> null;
                    case "removeTransformer" -> true;
                    case "retransformClasses" -> {
                        retransformed.addAll(List.of((Class<?>[]) arguments[0]));
                        yield null;
                    }
                    default -> Stubs.defaultValue(method.getReturnType());
                });
    }

    private static final class FixtureLoader extends ClassLoader {}

    static final class Other {}

    static final class Target {
        private final Object palette = new Object();

        public void open(final Object event) {}

        public void dispose(final Object event) {}

        public void close(final Object event) {}
    }
}

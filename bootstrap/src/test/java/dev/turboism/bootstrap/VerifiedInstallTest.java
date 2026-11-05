package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.RuntimeStartupConfig;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays the install protocol over a fake {@link Instrumentation}; no host bytecode and no Editor are
 * involved, so every assertion is about the routine itself: admission as a value, registration order,
 * one retransformation, the match/post-check proof, the restoration proof and the merged cleanup.
 */
class VerifiedInstallTest {

    private static final Admission ADMISSION =
            new Admission("test.verified-install.hook", PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    @TempDir
    Path directory;

    /** A rewritten class the plan declares; the fake instrumentation owns its bytes. */
    static final class HostA {}

    /** A second rewritten class, so ordering and per-owner proofs are observable. */
    static final class HostB {}

    /** The payload a test spec binds. */
    record Contract(String label) {}

    @Test
    void refusedAdmissionIsAValueAndNeverTouchesInstrumentation() throws Exception {
        final Fake fake = new Fake();
        final RuntimeStartupConfig safeMode = new RuntimeStartupConfig(true, false, false, false);

        final var attempt = VerifiedInstall.install(spec(fake), context(fake, safeMode));

        final var refused = assertInstanceOf(VerifiedInstall.Attempt.Refused.class, attempt);
        assertEquals("host-not-supported-or-hook-disabled", refused.reason());
        assertTrue(fake.events.isEmpty(), "a refused hook must not register, retransform or open the artifact");
    }

    @Test
    void unadmittedTargetIsARefusalNotAFailure() throws Exception {
        final Fake fake = new Fake();
        final var spec = new WeaveSpec<>(
                ADMISSION,
                "test hook",
                artifact -> Optional.empty(),
                plan(List.of(), WeavePlan.PostCheck.NONE),
                BridgeLayer.NONE,
                WeaveEffects.none());

        final var attempt = VerifiedInstall.install(spec, context(fake, openPolicy()));

        assertInstanceOf(VerifiedInstall.Attempt.Refused.class, attempt);
        assertTrue(fake.events.isEmpty());
    }

    @Test
    void registersInSpecOrderThenRetransformsOnceAndClosesInReverse() throws Exception {
        final Fake fake = new Fake();
        final Woven first = fake.transformer("first", HostA.class);
        final Woven second = fake.transformer("second", HostB.class);
        final var effects = new RecordingEffects(fake.events);
        final var bridge = new RecordingBridge(fake.events);
        final var spec = new WeaveSpec<>(
                ADMISSION,
                "test hook",
                artifact -> Optional.of(new Contract("bound")),
                plan(List.of(target(first, HostA.class), target(second, HostB.class)), WeavePlan.PostCheck.NONE),
                bridge,
                effects);

        final var installed = install(spec, fake);

        assertEquals(
                List.of(
                        "bridge-install",
                        "add:first",
                        "add:second",
                        "call:retransformClasses",
                        "retransform:" + WeaveTarget.internalName(HostA.class),
                        "retransform:" + WeaveTarget.internalName(HostB.class),
                        "confirmed:bound"),
                fake.events,
                "bridge first, spec order, one retransformation, side effects only after the proof");
        assertEquals("bound", installed.contract().label());
        final int installedEvents = fake.events.size();

        installed.close();

        assertEquals(
                List.of(
                        "remove:second",
                        "remove:first",
                        "withdrawn",
                        "add:observer",
                        "call:retransformClasses",
                        "retransform:" + WeaveTarget.internalName(HostA.class),
                        "call:retransformClasses",
                        "retransform:" + WeaveTarget.internalName(HostB.class),
                        "remove:observer",
                        "bridge-close"),
                fake.events.subList(installedEvents, fake.events.size()),
                "transformers are removed in reverse, then the side effects, then one capture, then the bridge last");
        assertTrue(installed.restored());
        assertTrue(fake.active.isEmpty());
    }

    @Test
    void closeIsIdempotent() throws Exception {
        final Fake fake = new Fake();
        final var installed =
                install(spec(fake, List.of(target(fake.transformer("only", HostA.class), HostA.class))), fake);
        installed.close();
        final int settled = fake.events.size();

        installed.close();

        assertEquals(settled, fake.events.size(), "a settled handle does nothing on a second close");
        assertTrue(installed.restored());
    }

    @Test
    void failedMatchCountRemovesEveryTransformerAndRethrows() throws Exception {
        final Fake fake = new Fake();
        final Woven first = fake.transformer("first", HostA.class);
        final Woven second = fake.transformer("second", HostB.class);
        second.matches = 3;
        final var spec = new WeaveSpec<>(
                ADMISSION,
                "test hook",
                artifact -> Optional.of(new Contract("bound")),
                plan(List.of(target(first, HostA.class), target(second, HostB.class, 2)), WeavePlan.PostCheck.NONE),
                new RecordingBridge(fake.events),
                WeaveEffects.none());

        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> install(spec, fake));

        assertTrue(failure.getMessage().contains("rewrite was not admitted"), failure.getMessage());
        assertTrue(fake.active.isEmpty(), "every registration is removed again");
        assertTrue(fake.events.contains("bridge-close"), "the bridge is closed by the rollback");
    }

    @Test
    void recordedTransformerFailureFailsTheInstall() throws Exception {
        final Fake fake = new Fake();
        final Woven only = fake.transformer("only", HostA.class);
        only.failure = "reviewed method absent";
        final var spec = new WeaveSpec<>(
                ADMISSION,
                "test hook",
                artifact -> Optional.of(new Contract("bound")),
                plan(List.of(target(only, HostA.class)), WeavePlan.PostCheck.NONE),
                BridgeLayer.NONE,
                WeaveEffects.none());

        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> install(spec, fake));

        assertTrue(failure.getMessage().contains("reviewed method absent"), failure.getMessage());
        assertTrue(fake.active.isEmpty());
    }

    @Test
    void failedPostCheckRollsTheWeaveBack() throws Exception {
        final Fake fake = new Fake();
        final var spec = new WeaveSpec<>(
                ADMISSION,
                "test hook",
                artifact -> Optional.of(new Contract("bound")),
                plan(List.of(target(fake.transformer("only", HostA.class), HostA.class)), targets -> "elided=0"),
                BridgeLayer.NONE,
                new RecordingEffects(fake.events));

        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> install(spec, fake));

        assertTrue(failure.getMessage().contains("post-check failed: elided=0"), failure.getMessage());
        assertTrue(fake.active.isEmpty());
        assertTrue(fake.events.contains("withdrawn"), "the rollback withdraws the hook's side effects");
    }

    @Test
    void unprovenRestorationKeepsTheHandleOpenForARetry() throws Exception {
        final Fake fake = new Fake();
        final var installed =
                install(spec(fake, List.of(target(fake.transformer("only", HostA.class), HostA.class))), fake);
        fake.breakRestore = true;

        final IllegalStateException failure = assertThrows(IllegalStateException.class, installed::close);

        assertTrue(failure.getMessage().contains("restoration not proven"), failure.getMessage());
        assertFalse(installed.restored());
        assertTrue(fake.active.isEmpty(), "the registration is removed before the proof is attempted");

        fake.breakRestore = false;
        installed.close();

        assertTrue(installed.restored(), "a second close re-captures and settles the handle");
    }

    @Test
    void fatalCleanupFailureOutranksTheOrdinaryOne() throws Exception {
        final Fake fake = new Fake();
        final var installed = install(
                withEffects(
                        spec(fake, List.of(target(fake.transformer("only", HostA.class), HostA.class))),
                        new WeaveEffects<Contract>() {
                            @Override
                            public void withdrawn(final VerifiedInstall<Contract> hook) {
                                throw new IllegalStateException("injected withdrawal failure");
                            }
                        }),
                fake);
        fake.captureFailure = new OutOfMemoryError("injected fatal capture failure");

        final OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, installed::close);

        assertEquals(1, failure.getSuppressed().length, "the ordinary cleanup failure is suppressed");
        assertInstanceOf(IllegalStateException.class, failure.getSuppressed()[0]);
        assertFalse(installed.restored());
    }

    private WeaveSpec<Contract> spec(final Fake fake) {
        return spec(fake, List.of(target(fake.transformer("only", HostA.class), HostA.class)));
    }

    private WeaveSpec<Contract> spec(final Fake fake, final List<WeaveTarget> targets) {
        return new WeaveSpec<>(
                ADMISSION,
                "test hook",
                artifact -> Optional.of(new Contract("bound")),
                plan(targets, WeavePlan.PostCheck.NONE),
                BridgeLayer.NONE,
                WeaveEffects.none());
    }

    private static WeavePlan<Contract> plan(final List<WeaveTarget> targets, final WeavePlan.PostCheck postCheck) {
        return new WeavePlan<>((contract, source) -> targets, postCheck);
    }

    private static WeaveTarget target(final Woven transformer, final Class<?> type) {
        return target(transformer, type, 1);
    }

    private static WeaveTarget target(final Woven transformer, final Class<?> type, final int expectedMatches) {
        return WeaveTarget.single(
                type,
                transformer,
                expectedMatches,
                transformer::matches,
                transformer::failure,
                owner -> transformer.beforeSha256());
    }

    private VerifiedInstall<Contract> install(final WeaveSpec<Contract> spec, final Fake fake) throws Exception {
        final var attempt = VerifiedInstall.install(spec, context(fake, openPolicy()));
        if (!(attempt instanceof VerifiedInstall.Attempt.Installed<Contract> installed)) {
            throw new AssertionError("the spec was refused instead of installed: " + attempt);
        }
        return installed.hook();
    }

    private static WeaveSpec<Contract> withEffects(
            final WeaveSpec<Contract> spec, final WeaveEffects<Contract> effects) {
        return new WeaveSpec<>(spec.admission(), spec.feature(), spec.resolver(), spec.plan(), spec.bridge(), effects);
    }

    private InstallContext context(final Fake fake, final RuntimeStartupConfig policy) throws Exception {
        final Path artifact = directory.resolve("host.jar");
        try (OutputStream out = Files.newOutputStream(artifact);
                JarOutputStream ignored = new JarOutputStream(out)) {
            // The plan supplies its own targets, so the artifact only has to be a readable archive.
        }
        return new InstallContext(
                fake.instrumentation(),
                artifact,
                getClass().getClassLoader(),
                policy,
                Runtime.version().feature());
    }

    private static RuntimeStartupConfig openPolicy() {
        return new RuntimeStartupConfig(false, false, false, false);
    }

    /** Side effects recorded in the same event log, so their position in the protocol is observable. */
    private static final class RecordingEffects implements WeaveEffects<Contract> {
        private final List<String> events;

        RecordingEffects(final List<String> events) {
            this.events = events;
        }

        @Override
        public void confirmed(final VerifiedInstall<Contract> hook) {
            events.add("confirmed:" + hook.contract().label());
        }

        @Override
        public void withdrawn(final VerifiedInstall<Contract> hook) {
            events.add("withdrawn");
        }
    }

    /** Slot bridge recorded in the same event log. */
    private static final class RecordingBridge implements BridgeLayer {
        private final List<String> events;

        RecordingBridge(final List<String> events) {
            this.events = events;
        }

        @Override
        public void install() {
            events.add("bridge-install");
        }

        @Override
        public void close() {
            events.add("bridge-close");
        }
    }

    /** Byte-rewriting transformer double whose own bookkeeping the routine reads. */
    private static final class Woven implements ClassFileTransformer {
        private final String label;
        private final Class<?> owner;
        private int matches;
        private String failure;
        private String beforeSha256;

        Woven(final String label, final Class<?> owner) {
            this.label = label;
            this.owner = owner;
        }

        @Override
        public byte[] transform(
                final Module module,
                final ClassLoader loader,
                final String name,
                final Class<?> redefined,
                final ProtectionDomain domain,
                final byte[] bytes) {
            if (bytes == null || redefined != owner) return null;
            if (beforeSha256 == null) beforeSha256 = VerifiedHookInstallerSupport.sha256HexUnchecked(bytes);
            matches++;
            final byte[] woven = bytes.clone();
            woven[woven.length - 1] ^= 1;
            return woven;
        }

        int matches() {
            return matches;
        }

        String failure() {
            return failure;
        }

        String beforeSha256() {
            return beforeSha256;
        }
    }

    /**
     * JVM instrumentation double: byte arrays are synthetic, but registration, retransformation and the
     * retransform-visible bytes follow the real protocol, including the injected failures.
     */
    private static final class Fake implements InvocationHandler {
        final List<ClassFileTransformer> active = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        private final Map<Class<?>, byte[]> official = new LinkedHashMap<>();
        private final Map<Class<?>, byte[]> current = new LinkedHashMap<>();
        private final Map<ClassFileTransformer, String> labels = new IdentityHashMap<>();
        Error captureFailure;
        boolean breakRestore;

        Instrumentation instrumentation() {
            return (Instrumentation) Proxy.newProxyInstance(
                    VerifiedInstallTest.class.getClassLoader(), new Class<?>[] {Instrumentation.class}, this);
        }

        Woven transformer(final String label, final Class<?> owner) {
            final Woven woven = new Woven(label, owner);
            labels.put(woven, label);
            final byte[] bytes = ("official:" + owner.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            official.put(owner, bytes);
            current.put(owner, bytes);
            return woven;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            return switch (method.getName()) {
                case "isRetransformClassesSupported", "isModifiableClass" -> true;
                case "addTransformer" -> {
                    final ClassFileTransformer transformer = (ClassFileTransformer) args[0];
                    active.add(transformer);
                    events.add("add:" + label(transformer));
                    yield null;
                }
                case "removeTransformer" -> {
                    final boolean removed = active.remove(args[0]);
                    events.add("remove:" + label((ClassFileTransformer) args[0]));
                    yield removed;
                }
                case "retransformClasses" -> {
                    events.add("call:retransformClasses");
                    for (final Class<?> type : (Class<?>[]) args[0]) {
                        retransform(type);
                    }
                    yield null;
                }
                case "toString" -> "FakeInstrumentation";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        private void retransform(final Class<?> type) {
            events.add("retransform:" + WeaveTarget.internalName(type));
            if (captureFailure != null) throw captureFailure;
            byte[] bytes = breakRestore ? current.get(type) : official.get(type);
            for (final ClassFileTransformer transformer : List.copyOf(active)) {
                bytes = apply(transformer, type, bytes);
            }
            current.put(type, bytes);
        }

        private byte[] apply(final ClassFileTransformer transformer, final Class<?> type, final byte[] bytes) {
            try {
                final byte[] next = transformer.transform(
                        type.getModule(),
                        type.getClassLoader(),
                        WeaveTarget.internalName(type),
                        type,
                        type.getProtectionDomain(),
                        bytes);
                return next == null ? bytes : next;
            } catch (final IllegalClassFormatException impossible) {
                throw new AssertionError(impossible);
            }
        }

        private String label(final ClassFileTransformer transformer) {
            return labels.getOrDefault(transformer, "observer");
        }
    }
}

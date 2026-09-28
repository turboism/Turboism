package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorHostProfile;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifiedWarpAltMirrorHookCloseTest {

    @Test
    void closeRestoresEveryLoadedTransformedTarget() throws Exception {
        assertRestoration(false);
    }

    @Test
    void failedRestorationIsReportedAfterOtherTargetsAreAttempted() throws Exception {
        assertRestoration(true);
    }

    @Test
    void directCloseWithdrawsCapabilitiesBeforeTransformerRemoval() {
        final List<String> events = new ArrayList<>();
        final VerifiedWarpAltMirrorHookInstaller installer = installer(events, false);
        installer.install();
        installer.onClose(() -> events.add("withdraw"));

        installer.close();
        installer.close();

        assertEquals(List.of("withdraw", "remove"), events);
    }

    @Test
    void failedRemovalStillPermanentlyWithdrawsCapabilities() {
        final List<String> events = new ArrayList<>();
        final VerifiedWarpAltMirrorHookInstaller installer = installer(events, true);
        installer.install();
        installer.onClose(() -> events.add("withdraw"));

        assertThrows(IllegalStateException.class, installer::close);
        installer.close();

        assertEquals(List.of("withdraw", "remove"), events);
    }

    @Test
    void runtimeBindingAfterCloseImmediatelyWithdrawsCapabilities() {
        final List<String> events = new ArrayList<>();
        final VerifiedWarpAltMirrorHookInstaller installer = installer(events, false);
        installer.close();

        installer.onClose(() -> events.add("withdraw"));

        assertEquals(List.of("withdraw"), events);
    }

    private static VerifiedWarpAltMirrorHookInstaller installer(final List<String> events, final boolean failRemoval) {
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isRetransformClassesSupported" -> true;
                    case "getAllLoadedClasses" -> new Class<?>[0];
                    case "addTransformer" -> null;
                    case "removeTransformer" -> {
                        events.add("remove");
                        if (failRemoval) throw new IllegalStateException("removal failed");
                        yield true;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        return new VerifiedWarpAltMirrorHookInstaller(
                instrumentation,
                null,
                null,
                WarpAltMirrorHostProfile.forReviewedVersion("5.3.02").orElseThrow());
    }

    private static void assertRestoration(final boolean failFirstTarget) throws Exception {
        final List<String> events = new ArrayList<>();
        final ClassFileTransformer[] transformer = {null};
        final boolean[] targetsLoaded = {false};
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isRetransformClassesSupported", "isModifiableClass" -> true;
                    case "getAllLoadedClasses" ->
                        targetsLoaded[0] ? new Class<?>[] {Point.class, Drag.class, String.class} : new Class<?>[0];
                    case "addTransformer" -> {
                        transformer[0] = (ClassFileTransformer) args[0];
                        yield null;
                    }
                    case "removeTransformer" -> {
                        events.add("remove");
                        yield true;
                    }
                    case "retransformClasses" -> {
                        for (final Class<?> type : (Class<?>[]) args[0]) {
                            events.add("restore:" + type.getSimpleName());
                            if (failFirstTarget && type == Point.class) {
                                throw new IllegalStateException("point restoration failed");
                            }
                        }
                        yield null;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        final var profile = new WarpAltMirrorHostProfile(
                Point.class.getName().replace('.', '/'),
                "move",
                "(Ljava/lang/Object;F)V",
                Drag.class.getName().replace('.', '/'),
                "drag",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                "fixture/UnusedStrip",
                "mount",
                "()V",
                "layout",
                "()V",
                "fixture/UnusedGreen",
                "tick",
                "()V",
                "fixture/UnusedSelector",
                "add",
                "(Ljava/lang/Object;FZ)Z",
                "setWeight",
                "(Ljava/lang/Object;F)V");
        final var installer =
                new VerifiedWarpAltMirrorHookInstaller(instrumentation, Point.class.getClassLoader(), null, profile);
        installer.install();
        for (final Class<?> type : List.of(Point.class, Drag.class)) {
            final String owner = type.getName().replace('.', '/');
            try (var input = type.getResourceAsStream("/" + owner + ".class")) {
                assertNotNull(input);
                assertNotNull(transformer[0].transform(
                        type.getModule(),
                        type.getClassLoader(),
                        owner,
                        null,
                        type.getProtectionDomain(),
                        input.readAllBytes()));
            }
        }
        targetsLoaded[0] = true;
        assertThrows(
                IllegalStateException.class,
                () -> installer.defineLazyTargets(new ClassLoader(Point.class.getClassLoader()) {}),
                "binding must use the same loader as the resolved runtime host");
        installer.onClose(() -> events.add("withdraw"));

        if (failFirstTarget) assertThrows(IllegalStateException.class, installer::close);
        else installer.close();
        assertEquals(List.of("withdraw", "remove", "restore:Point", "restore:Drag"), events);
        installer.close();
    }

    public static final class Point {
        public void move(final Object target, final float weight) {}
    }

    public static final class Drag {
        public void drag(final Object target, final Object event) {}
    }
}

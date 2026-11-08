package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.automesh.host.AutoMeshFunnelTransformer;
import dev.turboism.automesh.host.AutoMeshHostProfile;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.test.Stubs;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Reversible installer tests: exact release admission, bridge-before-transformer ordering, and
 * host restoration before the callbacks disappear.
 *
 * <p>The instrumentation double reports an empty loaded-class set, so the retransformation scan is
 * the observable seam: the tests observe whether the callbacks are still published while the scan
 * runs, and whether a failed scan keeps them.</p>
 */
class VerifiedAutoMeshHookInstallerTest {

    private static final String OWNER = "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/AutoMeshUtil";
    private static final String MESH_EDITOR_OWNER =
            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/AutoMeshUtil_InMeshEditor";
    private static final String MODELING_KEY = "turboism.automesh.guard.modeling-mode";
    private static final String MESH_EDITOR_KEY = "turboism.automesh.guard.mesh-editor-mode";

    @Test
    void publishesTheCallbacksBeforeTheScanAndDropsThemOnlyAfterRestoration() throws Exception {
        final List<String> calls = new ArrayList<>();
        final List<Boolean> callbacksDuringScan = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls, callbacksDuringScan);

        final VerifiedAutoMeshHookInstaller installer = installer(instrumentation, AutoMeshHostProfile.CUBISM_5_3_04);
        try {
            installer.install();
            installer.install();
            assertTrue(callbacksDuringScan.get(0), "the callbacks must be live while the scan runs");
            assertEquals(2, calls.stream().filter("add:true"::equals).count(), "one transformer per funnel");

            assertTrue(bridgeIsPublished(MODELING_KEY));
            assertTrue(bridgeIsPublished(MESH_EDITOR_KEY));
        } finally {
            installer.close();
        }

        assertEquals(
                List.of("add:true", "add:true", "scan", "remove", "remove", "scan"),
                calls,
                "teardown must remove both transformers and restore the hosts before dropping callbacks");
        assertTrue(callbacksDuringScan.get(1), "the callbacks must stay live while the hosts are restored");
        assertFalse(
                bridgeIsPublished(MODELING_KEY) || bridgeIsPublished(MESH_EDITOR_KEY),
                "a closed installation must not leave a callback the host no longer calls");
    }

    @Test
    void installFailsClosedWhenRetransformationIsUnsupported() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Instrumentation.class}, (proxy, method, arguments) -> {
                    if ("isRetransformClassesSupported".equals(method.getName())) {
                        return false;
                    }
                    calls.add(method.getName());
                    return defaultValue(method.getReturnType());
                });
        final VerifiedAutoMeshHookInstaller installer = installer(instrumentation, AutoMeshHostProfile.CUBISM_5_3_04);
        assertThrows(IllegalStateException.class, installer::install);
        assertEquals(List.of(), calls, "no transformer may be added without retransformation support");
        installer.close();
    }

    @Test
    void installRollsBackBothTransformersWhenTheLoadedClassScanFails() throws Exception {
        final List<String> calls = new ArrayList<>();
        final AtomicInteger scans = new AtomicInteger();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Instrumentation.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "isRetransformClassesSupported" -> {
                            return true;
                        }
                        case "addTransformer" -> {
                            calls.add("add:" + arguments[1]);
                            return null;
                        }
                        case "getAllLoadedClasses" -> {
                            calls.add("scan");
                            if (scans.incrementAndGet() == 1) {
                                throw new IllegalStateException("scan failure");
                            }
                            return new Class<?>[0];
                        }
                        case "removeTransformer" -> {
                            calls.add("remove");
                            return true;
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                });
        final VerifiedAutoMeshHookInstaller installer = installer(instrumentation, AutoMeshHostProfile.CUBISM_5_3_04);
        assertThrows(IllegalStateException.class, installer::install);
        assertEquals(
                List.of("add:true", "add:true", "scan", "remove", "remove", "scan"),
                calls,
                "a failed installation must remove both transformers and restore the hosts");
        assertFalse(
                bridgeIsPublished(MODELING_KEY) || bridgeIsPublished(MESH_EDITOR_KEY),
                "the rollback must withdraw the callbacks");
        installer.close();
    }

    @Test
    void closeKeepsTheCallbacksUntilTheHostsAreRestored() throws Exception {
        final List<String> calls = new ArrayList<>();
        final AtomicInteger scans = new AtomicInteger();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Instrumentation.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "isRetransformClassesSupported" -> {
                            return true;
                        }
                        case "addTransformer" -> {
                            calls.add("add:" + arguments[1]);
                            return null;
                        }
                        case "getAllLoadedClasses" -> {
                            calls.add("scan");
                            if (scans.incrementAndGet() == 2) {
                                throw new IllegalStateException("restoration failure");
                            }
                            return new Class<?>[0];
                        }
                        case "removeTransformer" -> {
                            calls.add("remove");
                            return true;
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                });
        final VerifiedAutoMeshHookInstaller installer = installer(instrumentation, AutoMeshHostProfile.CUBISM_5_3_04);
        installer.install();
        assertThrows(IllegalStateException.class, installer::close);
        assertTrue(
                bridgeIsPublished(MODELING_KEY),
                "a failed restoration must keep the callbacks the transformed host still calls");
        installer.close();
        assertEquals(
                List.of("add:true", "add:true", "scan", "remove", "remove", "scan", "scan"),
                calls,
                "the retried teardown must not re-register a transformer");
        assertFalse(bridgeIsPublished(MODELING_KEY));
    }

    @Test
    void rejectsAnUnreviewedReleaseAndAMismatchedSelectorShape() {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls, new ArrayList<>());
        final AutoMeshHostProfile reviewed = AutoMeshHostProfile.CUBISM_5_3_04;
        final AutoMeshHostProfile unreviewed = new AutoMeshHostProfile(
                "5.4.00",
                reviewed.modelingFunnelOwner(),
                reviewed.modelingFunnel(),
                reviewed.meshEditorFunnelOwner(),
                reviewed.meshEditorFunnel());
        assertThrows(
                IllegalArgumentException.class,
                () -> installer(instrumentation, unreviewed),
                "an unreviewed release must never be transformed");

        final AutoMeshHostProfile crossedOwners = new AutoMeshHostProfile(
                reviewed.hostVersion(),
                reviewed.modelingFunnelOwner(),
                StaticSelector.method(
                        "crossed",
                        MESH_EDITOR_OWNER,
                        reviewed.modelingFunnel().memberName(),
                        reviewed.modelingFunnel().descriptor(),
                        StaticSelector.ACCESS_PUBLIC),
                reviewed.meshEditorFunnelOwner(),
                reviewed.meshEditorFunnel());
        assertThrows(
                IllegalArgumentException.class,
                () -> installer(instrumentation, crossedOwners),
                "a funnel selector bound to another owner must be rejected");
        assertEquals(List.of(), calls);
    }

    @Test
    void theReviewedProfileGuardsTheTwoExactFunnelOwnersOnly() {
        assertEquals(
                Set.of(OWNER, MESH_EDITOR_OWNER),
                AutoMeshFunnelTransformer.sitesOf(AutoMeshHostProfile.CUBISM_5_3_04).stream()
                        .map(AutoMeshFunnelTransformer.Site::ownerInternalName)
                        .collect(Collectors.toSet()));
    }

    @Test
    void everyReviewedReleaseIsSupportedAndNothingElseIs() {
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            assertTrue(VerifiedAutoMeshHookInstaller.supportsExactCubismVersion(version), version);
        }
        assertFalse(VerifiedAutoMeshHookInstaller.supportsExactCubismVersion("5.4.00"));
        assertFalse(VerifiedAutoMeshHookInstaller.supportsExactCubismVersion("5.3.04.1"));
        assertFalse(VerifiedAutoMeshHookInstaller.supportsExactCubismVersion(null));
    }

    private static boolean bridgeIsPublished(final String key) {
        return System.getProperties().get(key) != null;
    }

    private static VerifiedAutoMeshHookInstaller installer(
            final Instrumentation instrumentation, final AutoMeshHostProfile profile) {
        return new VerifiedAutoMeshHookInstaller(
                instrumentation,
                profile,
                (funnel, arguments) -> Boolean.FALSE,
                message -> {},
                VerifiedAutoMeshHookInstallerTest.class.getClassLoader());
    }

    private static Instrumentation instrumentation(final List<String> calls, final List<Boolean> callbacksDuringScan) {
        return (Instrumentation) Proxy.newProxyInstance(
                VerifiedAutoMeshHookInstallerTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "isRetransformClassesSupported" -> {
                            return true;
                        }
                        case "addTransformer" -> {
                            calls.add("add:" + arguments[1]);
                            return null;
                        }
                        case "getAllLoadedClasses" -> {
                            calls.add("scan");
                            callbacksDuringScan.add(
                                    bridgeIsPublished(MODELING_KEY) || bridgeIsPublished(MESH_EDITOR_KEY));
                            return new Class<?>[0];
                        }
                        case "isModifiableClass" -> {
                            return true;
                        }
                        case "removeTransformer" -> {
                            calls.add("remove");
                            return true;
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                });
    }

    private static Object defaultValue(final Class<?> type) {
        return Stubs.defaultValue(type);
    }
}

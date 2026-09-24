package dev.turboism.adapter.cubism.integration;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorIntegrationSettingsDialogSelectorContract;

import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the show-hook's admission gate.
 *
 * <p>The selectors use the exact reviewed owner names but are resolved through an in-memory
 * plan, so these assertions are about the gate's shape — version, capability, alias, and the
 * exact per-version private-final {@code y.b(X|V)} signature — never about a live artifact.
 * The live artifact is pinned by the reviewed records and the manifest.</p>
 */
class VerifiedEditToggleHookInstallerTest {

    private static final String CAPABILITY =
        EditorIntegrationSettingsDialogSelectorContract.EDIT_TOGGLE_CAPABILITY_ID;
    private static final String BUILD_ALIAS =
        EditorIntegrationSettingsDialogSelectorContract.BUILD_HOOK_ALIAS;
    private static final String DIALOG_OWNER = "com/live2d/cubism/doc/webSocket/y";
    private static final String DESC_5203 = "(Lcom/live2d/ui/window/X;)V";
    private static final String DESC_530X = "(Lcom/live2d/ui/window/V;)V";
    private static final ClassLoader LOADER =
        VerifiedEditToggleHookInstallerTest.class.getClassLoader();

    @Test
    void theHookAdmitsEveryReviewedVersion() {
        assertDoesNotThrow(() -> VerifiedEditToggleHookInstaller.fromVerifiedResolver(
            instrumentation(), resolver("5.2.03", buildSelector(DESC_5203)), LOADER));
        assertDoesNotThrow(() -> VerifiedEditToggleHookInstaller.fromVerifiedResolver(
            instrumentation(), resolver("5.3.02", buildSelector(DESC_530X)), LOADER));
        assertDoesNotThrow(() -> VerifiedEditToggleHookInstaller.fromVerifiedResolver(
            instrumentation(), resolver("5.3.03", buildSelector(DESC_530X)), LOADER));
    }

    @Test
    void unsupportedVersionsAreRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> VerifiedEditToggleHookInstaller.fromVerifiedResolver(
                instrumentation(), resolver("5.4.00", buildSelector(DESC_530X)), LOADER));
    }

    @Test
    void missingCapabilityOrAliasIsRefused() {
        // A plan with the capability but only unrelated aliases never admits the hook.
        final VerifiedMemberResolver noAlias = TestVerifiedResolvers.create(
            "5.3.02", "adapter.editor-model.readwrite",
            Set.of(CAPABILITY),
            List.of(StaticSelector.classSelector(
                "cubism.integration.external-app-settings.dialog.class", DIALOG_OWNER)),
            LOADER);
        assertThrows(IllegalArgumentException.class,
            () -> VerifiedEditToggleHookInstaller.fromVerifiedResolver(
                instrumentation(), noAlias, LOADER));

        final VerifiedMemberResolver noCapability = TestVerifiedResolvers.create(
            "5.3.02", "adapter.editor-model.readwrite",
            Set.of("cubism.editor-model.read"),
            buildSelector(DESC_530X), LOADER);
        assertThrows(IllegalArgumentException.class,
            () -> VerifiedEditToggleHookInstaller.fromVerifiedResolver(
                instrumentation(), noCapability, LOADER));
    }

    @Test
    void theDescriptorMustMatchTheRunningVersion() {
        // The 5.2.03 window type is X, not V — a cross-version descriptor is refused.
        assertThrows(IllegalArgumentException.class,
            () -> VerifiedEditToggleHookInstaller.fromVerifiedResolver(
                instrumentation(), resolver("5.2.03", buildSelector(DESC_530X)), LOADER));
    }

    @Test
    void memberDriftIsRefused() {
        // Public method instead of the reviewed private final build method.
        assertThrows(IllegalArgumentException.class,
            () -> VerifiedEditToggleHookInstaller.fromVerifiedResolver(
                instrumentation(),
                resolver("5.3.02", List.of(
                    StaticSelector.method(BUILD_ALIAS, DIALOG_OWNER, "b",
                        DESC_530X, StaticSelector.ACCESS_PUBLIC))),
                LOADER));

        // The show entry y.a is not the seam — a public member is shape-rejected above;
        // a renamed private member must also fail owner/name validation.
        assertThrows(IllegalArgumentException.class,
            () -> VerifiedEditToggleHookInstaller.fromVerifiedResolver(
                instrumentation(),
                resolver("5.3.02", List.of(
                    StaticSelector.method(BUILD_ALIAS, DIALOG_OWNER, "a",
                        DESC_530X, 0x0012))),
                LOADER));
    }

    private static List<StaticSelector> buildSelector(final String descriptor) {
        return List.of(
            StaticSelector.method(BUILD_ALIAS, DIALOG_OWNER, "b", descriptor, 0x0012));
    }

    private static VerifiedMemberResolver resolver(
        final String version,
        final List<StaticSelector> selectors
    ) {
        return TestVerifiedResolvers.create(
            version, "adapter.editor-model.readwrite",
            Set.of(CAPABILITY), selectors, LOADER);
    }

    private static Instrumentation instrumentation() {
        return (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
            LOADER,
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "getAllLoadedClasses" -> new Class<?>[0];
                case "isModifiableClass" -> false;
                case "toString" -> "InstrumentationDouble";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            }
        );
    }
}

package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistoryIngressSelectorContract;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the hook's admission gate.
 *
 * <p>The selectors use the exact reviewed owner names but are resolved through an in-memory plan, so
 * these assertions are about the gate's shape — version, capability, alias set and the exact public
 * instance signature — and never about a live artifact. The live artifact is pinned by the reviewed
 * records and the manifest.</p>
 */
class VerifiedNativeEditBeginHookInstallerTest {

    private static final String MODELING_OWNER = "com/live2d/cubism/doc/modeling/CModelingEditMode_Main";
    private static final String INHERITED_OWNER = "com/live2d/cubism/doc/ACEditMode";
    private static final String DESCRIPTOR = "(Ljava/lang/String;)Lcom/live2d/undo/GroupUndo;";
    private static final ClassLoader LOADER = VerifiedNativeEditBeginHookInstallerTest.class.getClassLoader();

    @Test
    void theHookRequiresTheReviewedVersionAndTheAdmittedAliases() {
        assertDoesNotThrow(
            () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                instrumentation(), resolver("5.3.03", bothEntries()), LOADER
            ),
            "5.3.03 admits the native entry hook from its own reviewed record"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                instrumentation(), resolver("5.3.02", List.of()), LOADER
            ),
            "the ingress aliases are not admitted"
        );
    }

    @Test
    void theModelingEntryMustBeTheExactAdmittedTarget() {
        final List<StaticSelector> wrongOwner = new ArrayList<>();
        wrongOwner.add(instanceEntry(
            EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS,
            "fixture/NotTheModelingEntry"
        ));
        wrongOwner.add(instanceEntry(
            EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, INHERITED_OWNER
        ));

        assertThrows(
            IllegalArgumentException.class,
            () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                instrumentation(), resolver("5.3.02", wrongOwner), LOADER
            )
        );
    }

    @Test
    void theInheritedEntryMustBeTheExactAdmittedTarget() {
        final List<StaticSelector> wrongOwner = new ArrayList<>();
        wrongOwner.add(instanceEntry(
            EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS, MODELING_OWNER
        ));
        wrongOwner.add(instanceEntry(
            EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, "fixture/NotTheBaseEntry"
        ));

        assertThrows(
            IllegalArgumentException.class,
            () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                instrumentation(), resolver("5.3.02", wrongOwner), LOADER
            )
        );
    }

    @Test
    void aTargetWithoutTheExactSignatureIsRefused() {
        final List<StaticSelector> wrongDescriptor = new ArrayList<>();
        wrongDescriptor.add(StaticSelector.method(
            EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS,
            MODELING_OWNER,
            "beginEdit",
            "(I)Lcom/live2d/undo/GroupUndo;",
            StaticSelector.ACCESS_PUBLIC
        ));
        wrongDescriptor.add(instanceEntry(
            EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, INHERITED_OWNER
        ));

        assertThrows(
            IllegalArgumentException.class,
            () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                instrumentation(), resolver("5.3.02", wrongDescriptor), LOADER
            )
        );
    }

    @Test
    void aStaticTargetIsRefusedBecauseBeginEditIsAnInstanceMethod() {
        final List<StaticSelector> staticEntry = new ArrayList<>();
        staticEntry.add(StaticSelector.staticMethod(
            EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS,
            MODELING_OWNER,
            "beginEdit",
            DESCRIPTOR,
            StaticSelector.ACCESS_PUBLIC
        ));
        staticEntry.add(instanceEntry(
            EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, INHERITED_OWNER
        ));

        assertThrows(
            IllegalArgumentException.class,
            () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                instrumentation(), resolver("5.3.02", staticEntry), LOADER
            )
        );
    }

    @Test
    void anAcceptedResolutionBuildsAnUninstalledInstaller() throws Exception {
        final VerifiedNativeEditBeginHookInstaller installer =
            VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                instrumentation(), resolver("5.2.03", bothEntries()), LOADER
            );

        assertFalse(installer.isInstalled(), "nothing is instrumented before install");
        assertFalse(
            System.getProperties().containsKey(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY),
            "the receiver is registered only by install"
        );
        installer.close();
        assertFalse(installer.isInstalled());
    }

    @Test
    void aRefusedInstallLeavesNoReceiverAndNoTransformerBehind() {
        final VerifiedNativeEditBeginHookInstaller installer =
            VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                instrumentation(false), resolver("5.3.02", bothEntries()), LOADER
            );

        assertThrows(IllegalStateException.class, () -> installer.install(NativeEditBeginBridge.ingress()));
        assertFalse(installer.isInstalled());
        assertFalse(
            System.getProperties().containsKey(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY),
            "a refused install must not leave the receiver registered"
        );
    }

    @Test
    void theHookIsKeyedOnTheTwoReviewedAliases() {
        assertEquals(
            "cubism.editor-model.edit-mode.begin",
            EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS,
            "the modeling entry keeps the alias the mature Editor surface already uses"
        );
        assertEquals(
            "cubism.editor-history.edit-mode.begin",
            EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS
        );
        assertTrue(
            EditorHistoryIngressSelectorContract.REQUIRED_ALIASES.contains(
                EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS
            ),
            "the inherited entry is part of the reviewed ingress family"
        );
        assertFalse(
            EditorHistoryIngressSelectorContract.REQUIRED_ALIASES.contains(
                EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS
            ),
            "the modeling entry is not re-declared by the ingress"
        );
    }

    private static List<StaticSelector> bothEntries() {
        return List.of(
            instanceEntry(EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS, MODELING_OWNER),
            instanceEntry(EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, INHERITED_OWNER)
        );
    }

    private static StaticSelector instanceEntry(final String alias, final String owner) {
        return StaticSelector.method(alias, owner, "beginEdit", DESCRIPTOR, StaticSelector.ACCESS_PUBLIC);
    }

    private static VerifiedMemberResolver resolver(
        final String version,
        final List<StaticSelector> selectors
    ) {
        return TestVerifiedResolvers.create(
            version,
            EditorHistoryIngressSelectorContract.ADAPTER_SLICE_ID,
            Set.of(EditorHistoryIngressSelectorContract.CAPABILITY_ID),
            selectors,
            VerifiedNativeEditBeginHookInstallerTest.class.getClassLoader()
        );
    }

    private static Instrumentation instrumentation() {
        return instrumentation(true);
    }

    private static Instrumentation instrumentation(final boolean retransformSupported) {
        return (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
            VerifiedNativeEditBeginHookInstallerTest.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> retransformSupported;
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

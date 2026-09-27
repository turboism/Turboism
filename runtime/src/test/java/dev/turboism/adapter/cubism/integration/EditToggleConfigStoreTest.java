package dev.turboism.adapter.cubism.integration;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorIntegrationSettingsDialogSelectorContract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fake-host contract tests for {@link EditToggleConfigStore}: the fake {@code UUConfig}
 * mirrors the verified member shapes (singleton field, defaulting read, write-back) over an
 * in-memory map, so persistence semantics — absent key, read-back, restart simulation,
 * listener write-through — run end to end while admission stays gated by the verified plan.
 */
class EditToggleConfigStoreTest {

    private static final String CAPABILITY =
        EditorIntegrationSettingsDialogSelectorContract.EDIT_TOGGLE_CAPABILITY_ID;
    private static final String CONFIG_INSTANCE_ALIAS =
        EditorIntegrationSettingsDialogSelectorContract.CONFIG_INSTANCE_ALIAS;
    private static final String CONFIG_READ_ALIAS =
        EditorIntegrationSettingsDialogSelectorContract.CONFIG_READ_ALIAS;
    private static final String CONFIG_WRITE_ALIAS =
        EditorIntegrationSettingsDialogSelectorContract.CONFIG_WRITE_ALIAS;
    private static final String CONFIG = internal(
        NativeEditToggleInjectorTest.FakeUUConfig.class);

    @BeforeEach
    void resetConfig() {
        NativeEditToggleInjectorTest.FakeUUConfig.a.values.clear();
    }

    @Test
    void absentKeyReadsFalse() {
        final EditToggleConfigStore store = admittedStore();
        assertFalse(store.load(), "no persisted key must fail closed");
    }

    @Test
    void nonBooleanValueReadsFalse() {
        NativeEditToggleInjectorTest.FakeUUConfig.a.values.put(
            EditToggleConfigStore.CONFIG_KEY, "not-a-boolean");
        assertFalse(admittedStore().load());
    }

    @Test
    void writesReadBackThroughTheSameHostConfig() {
        final EditToggleConfigStore store = admittedStore();
        store.store(true);
        assertTrue(store.load());
        assertEquals(Boolean.TRUE,
            NativeEditToggleInjectorTest.FakeUUConfig.a.values
                .get(EditToggleConfigStore.CONFIG_KEY),
            "the host config holds the exact key");
        store.store(false);
        assertFalse(store.load());
    }

    @Test
    void persistedStateSurvivesASimulatedRestart() {
        admittedStore().store(true);

        // Restart: fresh state, fresh store resolution over the same host config.
        final EditToggleState restarted = new EditToggleState();
        final EditToggleConfigStore store =
            EditToggleConfigStore.fromVerifiedResolver(resolver()).orElseThrow();
        restarted.setEnabled(store.load());

        assertTrue(restarted.isEnabled(),
            "a restart must restore the persisted checkbox state");
    }

    @Test
    void toggleListenerPersistsEachTransition() {
        final EditToggleState state = new EditToggleState();
        state.addListener(admittedStore()::store);

        state.setEnabled(true);
        state.setEnabled(true);  // no-op write must not double-persist
        state.setEnabled(false);

        assertEquals(Boolean.FALSE,
            NativeEditToggleInjectorTest.FakeUUConfig.a.values
                .get(EditToggleConfigStore.CONFIG_KEY));
    }

    @Test
    void missingConfigAliasesStayUnpersisted() {
        final VerifiedMemberResolver denied = TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of(CAPABILITY),
            List.of(StaticSelector.classSelector(
                "cubism.integration.external-app-settings.dialog.class",
                "com/live2d/cubism/doc/webSocket/y")),
            EditToggleConfigStoreTest.class.getClassLoader()
        );

        assertEquals(Optional.empty(), EditToggleConfigStore.fromVerifiedResolver(denied));
    }

    private static EditToggleConfigStore admittedStore() {
        return EditToggleConfigStore.fromVerifiedResolver(resolver())
            .orElseThrow(() -> new AssertionError("admission unexpectedly denied"));
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of(CAPABILITY),
            List.of(
                StaticSelector.field(CONFIG_INSTANCE_ALIAS, CONFIG,
                    "a", "L" + CONFIG + ";", 0x0019),
                StaticSelector.method(CONFIG_READ_ALIAS, CONFIG,
                    "a", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
                    StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method(CONFIG_WRITE_ALIAS, CONFIG,
                    "b", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
                    StaticSelector.ACCESS_PUBLIC)),
            EditToggleConfigStoreTest.class.getClassLoader()
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }
}

package dev.turboism.exportsettings;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loader-neutral bridge channel tests: the transformed host bytecode may only ever
 * observe JDK functional callbacks under the namespaced property keys.
 */
class NativeExportSettingsDialogBridgeTest {

    private Registration installed;

    @AfterEach
    void tearDown() {
        if (installed != null) {
            installed.close();
            installed = null;
        }
    }

    @Test
    void publishesLoaderNeutralJdkCallbacksAndRemovesOnlyItsOwnValues() {
        final List<String> calls = new ArrayList<>();
        installed = NativeExportSettingsDialogBridge.install(new NativeExportSettingsDialogBridge.Handler() {
            @Override
            public Object attach(final Object owner, final Object container) {
                calls.add("attach");
                return null;
            }

            @Override
            public void cancel(final Object owner) {
                calls.add("cancel");
            }

            @Override
            public Boolean decide(final Object owner) {
                calls.add("decide");
                return Boolean.TRUE;
            }
        });

        final Object owner = new Object();
        final Object container = new Object();
        assertNotNull(System.getProperties().get(NativeExportSettingsDialogBridge.ATTACH_KEY));
        assertNotNull(System.getProperties().get(NativeExportSettingsDialogBridge.CANCEL_KEY));
        assertNotNull(System.getProperties().get(NativeExportSettingsDialogBridge.DECIDE_KEY));
        assertTrue(System.getProperties().get(NativeExportSettingsDialogBridge.ATTACH_KEY)
            instanceof BiFunction);
        assertTrue(System.getProperties().get(NativeExportSettingsDialogBridge.CANCEL_KEY)
            instanceof Consumer);
        assertTrue(System.getProperties().get(NativeExportSettingsDialogBridge.DECIDE_KEY)
            instanceof Function);

        @SuppressWarnings("unchecked")
        final BiFunction<Object, Object, Object> attach =
            (BiFunction<Object, Object, Object>) System.getProperties().get(
                NativeExportSettingsDialogBridge.ATTACH_KEY
            );
        assertNull(attach.apply(owner, container));
        assertEquals(List.of("attach"), calls);

        @SuppressWarnings("unchecked")
        final Consumer<Object> cancel = (Consumer<Object>) System.getProperties().get(
            NativeExportSettingsDialogBridge.CANCEL_KEY
        );
        cancel.accept(owner);
        assertEquals(List.of("attach", "cancel"), calls);

        @SuppressWarnings("unchecked")
        final Function<Object, Object> decide = (Function<Object, Object>) System.getProperties().get(
            NativeExportSettingsDialogBridge.DECIDE_KEY
        );
        assertSame(Boolean.TRUE, decide.apply(owner));
        assertEquals(List.of("attach", "cancel", "decide"), calls);

        installed.close();
        installed = null;
        assertNull(System.getProperties().get(NativeExportSettingsDialogBridge.ATTACH_KEY));
        assertNull(System.getProperties().get(NativeExportSettingsDialogBridge.CANCEL_KEY));
        assertNull(System.getProperties().get(NativeExportSettingsDialogBridge.DECIDE_KEY));

        // Reinstall after teardown must succeed and carry the new handler.
        installed = NativeExportSettingsDialogBridge.install(
            (owner2, container2) -> null
        );
        assertNotNull(System.getProperties().get(NativeExportSettingsDialogBridge.ATTACH_KEY));
    }

    @Test
    void refusesASecondInstallWhileOneIsActive() {
        installed = NativeExportSettingsDialogBridge.install((owner, container) -> null);
        assertThrows(
            IllegalStateException.class,
            () -> NativeExportSettingsDialogBridge.install((owner, container) -> null)
        );
    }

    @Test
    void dispatchFailsOpenForAttachAndCancelAndFailsClosedForDecide() {
        final List<String> calls = new ArrayList<>();
        installed = NativeExportSettingsDialogBridge.install(new NativeExportSettingsDialogBridge.Handler() {
            @Override
            public Object attach(final Object owner, final Object container) {
                calls.add("attach");
                throw new IllegalStateException("attach failure");
            }

            @Override
            public void cancel(final Object owner) {
                calls.add("cancel");
                throw new IllegalStateException("cancel failure");
            }

            @Override
            public Boolean decide(final Object owner) {
                calls.add("decide");
                throw new IllegalStateException("decide failure");
            }
        });
        final Object owner = new Object();
        @SuppressWarnings("unchecked")
        final BiFunction<Object, Object, Object> attach =
            (BiFunction<Object, Object, Object>) System.getProperties().get(
                NativeExportSettingsDialogBridge.ATTACH_KEY
            );
        assertNull(attach.apply(owner, new Object()), "attach must fail open");
        @SuppressWarnings("unchecked")
        final Consumer<Object> cancel = (Consumer<Object>) System.getProperties().get(
            NativeExportSettingsDialogBridge.CANCEL_KEY
        );
        cancel.accept(owner);
        @SuppressWarnings("unchecked")
        final Function<Object, Object> decide = (Function<Object, Object>) System.getProperties().get(
            NativeExportSettingsDialogBridge.DECIDE_KEY
        );
        assertSame(Boolean.FALSE, decide.apply(owner), "decide must fail closed");
        assertEquals(List.of("attach", "cancel", "decide"), calls);
    }

    @Test
    void decideRejectsNullHandlerDecisionsAndCachedCallbacksAfterTeardown() {
        installed = NativeExportSettingsDialogBridge.install(new NativeExportSettingsDialogBridge.Handler() {
            @Override
            public Object attach(final Object owner, final Object container) {
                return null;
            }

            @Override
            public Boolean decide(final Object owner) {
                return null;
            }
        });
        @SuppressWarnings("unchecked")
        final Function<Object, Object> decide = (Function<Object, Object>) System.getProperties().get(
            NativeExportSettingsDialogBridge.DECIDE_KEY
        );
        assertSame(Boolean.FALSE, decide.apply(new Object()),
            "a null handler decision must reject the selected path");
        installed.close();
        installed = null;
        assertSame(Boolean.FALSE, decide.apply(new Object()),
            "a cached callback after bridge teardown must fail closed");
    }
}

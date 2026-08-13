package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeObjectContextMenuBridgeTest {

    @Test
    void publishesLoaderNeutralCallbacksAndRemovesOnlyItsOwnValues() {
        final Object menu = new Object();
        final Object source = new Object();
        try (Registration ignored = NativeObjectContextMenuBridge.install(
            (actualMenu, location, actualSource) -> {
                assertSame(menu, actualMenu);
                assertEquals(Location.PART_TAB, location);
                assertSame(source, actualSource);
                return actualMenu;
            }
        )) {
            final Object raw = System.getProperties().get(
                NativeObjectContextMenuBridge.propertyKey(Location.PART_TAB)
            );
            assertTrue(raw instanceof BiFunction<?, ?, ?>);
            @SuppressWarnings("unchecked")
            final BiFunction<Object, Object, Object> callback =
                (BiFunction<Object, Object, Object>) raw;
            assertSame(menu, callback.apply(menu, source));
            assertThrows(IllegalStateException.class, () ->
                NativeObjectContextMenuBridge.install((value, location, selected) -> value)
            );
        }
        assertFalse(System.getProperties().containsKey(
            NativeObjectContextMenuBridge.propertyKey(Location.PART_TAB)
        ));
    }

    @Test
    void callbackFailsOpenWhenRuntimeHandlerThrows() {
        final Object menu = new Object();
        try (Registration ignored = NativeObjectContextMenuBridge.install(
            (actualMenu, location, source) -> { throw new AssertionError("fixture"); }
        )) {
            @SuppressWarnings("unchecked")
            final BiFunction<Object, Object, Object> callback =
                (BiFunction<Object, Object, Object>) System.getProperties().get(
                    NativeObjectContextMenuBridge.propertyKey(Location.DEFORMER_TAB)
                );
            assertSame(menu, callback.apply(menu, new Object()));
        }
    }
}

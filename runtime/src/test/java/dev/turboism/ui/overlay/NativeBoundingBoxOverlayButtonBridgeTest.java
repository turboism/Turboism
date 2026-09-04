package dev.turboism.ui.overlay;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBoundingBoxOverlayButtonBridgeTest {

    @Test
    void publishesLoaderNeutralJdkCallbacksAndRemovesOnlyItsOwnValues() {
        final Properties properties = System.getProperties();
        assertNull(properties.get(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY));
        assertNull(properties.get(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY));

        final Object button = new Object();
        try (Registration installed = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[] {button}
        )) {

            final Object setup = properties.get(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY);
            final Object failure = properties.get(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY);
            assertInstanceOf(BiFunction.class, setup);
            assertInstanceOf(Consumer.class, failure);

            final Object overlay = new Object();
            final Object scene = new Object();
            final Object[] buttons = (Object[]) ((BiFunction<Object, Object, Object>) setup).apply(overlay, scene);
            assertSame(button, buttons[0]);

            // A throwing diagnostics callback must not escape the consumer contract.
            ((Consumer<Object>) failure).accept(new IllegalStateException("augmentation failed"));

            NativeBoundingBoxOverlayButtonBridge.deactivateCallbacks();
            assertEquals(
                0,
                ((Object[]) ((BiFunction<Object, Object, Object>) setup).apply(overlay, scene)).length
            );
        }

        assertNull(properties.get(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY));
        assertNull(properties.get(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY));

        // Re-installation works after identity-removal.
        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[0]
        )) {
            assertTrue(properties.containsKey(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY));
        }
    }

    @Test
    void rejectsAConcurrentSecondInstallationAndRecoversAfterClose() {
        final Registration first = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[0]
        );
        try {
            assertThrows(
                IllegalStateException.class,
                () -> NativeBoundingBoxOverlayButtonBridge.install(
                    (overlay, sceneGraph) -> new Object[0]
                )
            );
        } finally {
            first.close();
        }
        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[0]
        )) {
            // The bridge is usable again after the previous installation closed.
        }
    }

    @Test
    void firstFailureReportsBurstIsSuppressedAndLaterIntervalReportsAgain() throws Exception {
        // Overflow-safe pure decision helper: sentinel always reports, a recent report is
        // suppressed, an interval-elapsed report is emitted, and a nanoTime wrap cannot
        // suppress a report.
        assertTrue(NativeBoundingBoxOverlayButtonBridge.shouldReport(1_000L, Long.MIN_VALUE));
        assertFalse(NativeBoundingBoxOverlayButtonBridge.shouldReport(1_000L, 1_000L));
        assertTrue(NativeBoundingBoxOverlayButtonBridge.shouldReport(
            1_000L + 30_000_000_000L, 1_000L
        ));
        // nanoTime wrap: previous near Long.MAX_VALUE, now wrapped past MIN_VALUE with a
        // large unsigned elapsed time; the report must not be suppressed by the wrap.
        assertTrue(NativeBoundingBoxOverlayButtonBridge.shouldReport(
            Long.MIN_VALUE + 30_000_000_010L, Long.MAX_VALUE - 10L
        ));

        // End-to-end through the installed failure consumer with captured stderr.
        final Registration installed = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[0]
        );
        final Consumer<Object> failure = (Consumer<Object>) System.getProperties().get(
            NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY
        );
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            final java.lang.reflect.Field sentinel =
                NativeBoundingBoxOverlayButtonBridge.class.getDeclaredField("LAST_FAILURE_REPORT");
            sentinel.setAccessible(true);
            final java.util.concurrent.atomic.AtomicLong last =
                (java.util.concurrent.atomic.AtomicLong) sentinel.get(null);

            // First failure: reported.
            last.set(Long.MIN_VALUE);
            failure.accept(new IllegalStateException("first failure"));
            assertEquals(1, countOccurrences(captured));

            // Immediate burst: suppressed by the rate limit.
            failure.accept(new IllegalStateException("burst failure"));
            assertEquals(1, countOccurrences(captured));

            // Later interval (simulated by resetting the sentinel): reported again.
            last.set(Long.MIN_VALUE);
            failure.accept(new IllegalStateException("later failure"));
            assertEquals(2, countOccurrences(captured));
        } finally {
            System.setErr(originalErr);
            installed.close();
        }
    }

    @Test
    void closedGenerationCannotReactivateWhenANewerBridgeIsInstalled() {
        final Registration first = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, scene) -> new Object[] {"first"}
        );
        final long firstGeneration = NativeBoundingBoxOverlayButtonBridge.activeGeneration();
        assertTrue(NativeBoundingBoxOverlayButtonBridge.isGenerationActive(firstGeneration));
        first.close();
        assertFalse(NativeBoundingBoxOverlayButtonBridge.isGenerationActive(firstGeneration));

        final Registration second = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, scene) -> new Object[] {"second"}
        );
        try {
            final long secondGeneration = NativeBoundingBoxOverlayButtonBridge.activeGeneration();
            assertTrue(secondGeneration != firstGeneration);
            assertFalse(NativeBoundingBoxOverlayButtonBridge.isGenerationActive(firstGeneration));
            assertTrue(NativeBoundingBoxOverlayButtonBridge.isGenerationActive(secondGeneration));
        } finally {
            second.close();
        }
    }

    @Test
    void partialPropertyPublicationRollsBackAndLeavesTheBridgeReusable() {
        final Properties original = System.getProperties();
        final ThrowOnFailurePropertyPut broken = new ThrowOnFailurePropertyPut(original);
        System.setProperties(broken);
        try {
            assertThrows(
                IllegalStateException.class,
                () -> NativeBoundingBoxOverlayButtonBridge.install((overlay, scene) -> new Object[0])
            );
            assertFalse(broken.containsKey(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY));
            assertFalse(broken.containsKey(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY));
        } finally {
            System.setProperties(original);
        }

        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, scene) -> new Object[0]
        )) {
            assertTrue(NativeBoundingBoxOverlayButtonBridge.activeGeneration() != 0L);
        }
    }

    private static int countOccurrences(final ByteArrayOutputStream captured) {
        final String text = captured.toString(StandardCharsets.UTF_8);
        int count = 0;
        int index = 0;
        final String marker = "Turboism bounding-box overlay augmentation failed safely";
        while ((index = text.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }
        return count;
    }

    private static final class ThrowOnFailurePropertyPut extends Properties {
        private ThrowOnFailurePropertyPut(final Properties seed) {
            super();
            super.putAll(seed);
        }

        @Override
        public synchronized Object put(final Object key, final Object value) {
            if (NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY.equals(key)) {
                throw new IllegalStateException("injected second property publication failure");
            }
            return super.put(key, value);
        }
    }
}

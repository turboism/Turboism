package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedEmbeddedPanelHostOperationsTest {

    @Test
    void deferredEdtDispatchReturnsWhileTheEdtIsBusy() throws Exception {
        CountDownLatch edtEntered = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        CountDownLatch operationRan = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtEntered.countDown();
            await(releaseEdt);
        });
        assertTrue(edtEntered.await(2, TimeUnit.SECONDS));

        try {
            assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> VerifiedEmbeddedPanelHostOperations.runOnEdtLater(operationRan::countDown)
            );
            assertEquals(1L, operationRan.getCount());
        } finally {
            releaseEdt.countDown();
        }

        assertTrue(operationRan.await(2, TimeUnit.SECONDS));
    }

    @Test
    void panelCleanupHidesClosesRemovesWindowEntryAndAlwaysRefreshes() {
        List<String> operations = new ArrayList<>();

        VerifiedEmbeddedPanelHostOperations.closePanel(
            () -> operations.add("hide"),
            () -> operations.add("close"),
            () -> operations.add("remove-window-item"),
            () -> operations.add("refresh")
        );

        assertEquals(List.of("hide", "close", "remove-window-item", "refresh"), operations);

        operations.clear();
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> VerifiedEmbeddedPanelHostOperations.closePanel(
                () -> {
                    operations.add("hide");
                    throw new IllegalStateException("hide failed");
                },
                () -> operations.add("close"),
                () -> operations.add("remove-window-item"),
                () -> operations.add("refresh")
            )
        );
        assertEquals("hide failed", failure.getMessage());
        assertEquals(List.of("hide", "close", "remove-window-item", "refresh"), operations);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test EDT wait interrupted", exception);
        }
    }
}

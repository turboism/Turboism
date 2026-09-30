package dev.turboism.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CanvasHintHandle;
import dev.turboism.sdk.ui.CanvasHintNotification;
import dev.turboism.sdk.ui.CanvasHintPosition;
import dev.turboism.sdk.ui.StatusNotification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CanvasHintHandleRuntimeTest {

    @Test
    void renewReissuesTheSameKeyedHintAndCloseDismissesTheLatestRegistration() {
        RecordingAdapter adapter = new RecordingAdapter();
        RuntimeUiHostCapabilityService service = service(adapter, new DisposableScope());

        CanvasHintHandle handle =
                service.notifyCanvasHint(new CanvasHintNotification("screen-color", "Incompatible", 1.0f));
        assertEquals(1, adapter.shown.size());
        assertTrue(
                adapter.shown.get(0).id().startsWith("8:plugin.a:"),
                "the plugin scope prefixes the hint key: "
                        + adapter.shown.get(0).id());
        assertTrue(adapter.shown.get(0).id().endsWith(":screen-color"));

        handle.renew();
        assertEquals(2, adapter.shown.size(), "renew must re-issue the hint");
        assertEquals(
                adapter.shown.get(0).id(),
                adapter.shown.get(1).id(),
                "renew must reuse the same key so the host refreshes instead of stacking");

        handle.close();
        assertEquals(1, adapter.closed, "close must dismiss the newest registration");
        assertEquals(2, adapter.shown.size(), "close must not re-issue");
    }

    @Test
    void aClosedHandleIgnoresLaterRenewsAndDismissIsIdempotent() {
        RecordingAdapter adapter = new RecordingAdapter();
        RuntimeUiHostCapabilityService service = service(adapter, new DisposableScope());

        CanvasHintHandle handle =
                service.notifyCanvasHint(new CanvasHintNotification("screen-color", "Incompatible", 1.0f));
        handle.dismiss();
        handle.dismiss();
        handle.renew();
        handle.close();

        assertEquals(1, adapter.shown.size(), "a spent handle must not re-issue");
        assertEquals(1, adapter.closed, "repeated dismissal must close the hint once");
    }

    @Test
    void thePluginScopeKeepsTheClickActionAndPositionOfAHint() {
        RecordingAdapter adapter = new RecordingAdapter();
        RuntimeUiHostCapabilityService service = service(adapter, new DisposableScope());
        AtomicBoolean clicked = new AtomicBoolean();

        CanvasHintHandle handle =
                service.notifyCanvasHint(new CanvasHintNotification("screen-color", "Incompatible", 1.0f)
                        .withOnClick(() -> clicked.set(true))
                        .withPosition(new CanvasHintPosition(12.0f, 34.0f)));

        CanvasHintNotification delivered = adapter.shown.get(0);
        assertTrue(
                delivered.onClick().isPresent(),
                "scoping the hint key must not drop the click action that makes it clickable");
        assertEquals(
                new CanvasHintPosition(12.0f, 34.0f),
                delivered.position().orElseThrow(),
                "scoping the hint key must not drop an explicit position");

        delivered.onClick().orElseThrow().run();
        assertTrue(clicked.get(), "the delivered click action must reach the plugin callback");

        handle.close();
    }

    @Test
    void aClickThatReachesThePluginDismissesTheDismissibleHint() {
        RecordingAdapter adapter = new RecordingAdapter();
        RuntimeUiHostCapabilityService service = service(adapter, new DisposableScope());

        CanvasHintHandle handle =
                service.notifyDismissibleCanvasHint(new CanvasHintNotification("screen-color", "Incompatible", 1.0f));
        assertEquals(0, adapter.closed, "nothing is dismissed before the click");

        adapter.shown.get(0).onClick().orElseThrow().run();

        assertEquals(1, adapter.closed, "a click must dismiss the hint the plugin is showing");
        handle.close();
        assertEquals(1, adapter.closed, "explicit disposal stays idempotent after a click");
    }

    @Test
    void thePluginDisposalScopeClearsALiveHintOnce() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter();
        DisposableScope scope = new DisposableScope();
        RuntimeUiHostCapabilityService service = service(adapter, scope);

        CanvasHintHandle handle =
                service.notifyCanvasHint(new CanvasHintNotification("screen-color", "Incompatible", 1.0f));
        handle.renew();
        handle.renew();
        assertEquals(3, adapter.shown.size());

        scope.close();

        assertEquals(1, adapter.closed, "disposal must clear the hint once, not once per renew");
    }

    @Test
    void aRenewRacingCloseLeavesNoLiveNativeHint() throws Exception {
        LatchingAdapter adapter = new LatchingAdapter();
        RuntimeUiHostCapabilityService service = service(adapter, new DisposableScope());
        CanvasHintHandle handle =
                service.notifyCanvasHint(new CanvasHintNotification("screen-color", "Incompatible", 1.0f));
        assertEquals(1, adapter.shown.size());

        adapter.armNextShow();
        Thread renewer = new Thread(handle::renew, "hint-renew");
        renewer.start();
        assertTrue(adapter.showEntered.await(5, TimeUnit.SECONDS), "renew must reach the host call before close runs");

        Thread closer = new Thread(handle::close, "hint-close");
        closer.start();
        // Deterministic interleave: a close that cannot be delayed by the in-flight renew
        // (baseline bug) runs to completion; a close that correctly waits for the in-flight
        // host call parks on the handle's lock. Either terminal state releases the gate.
        awaitTrue(
                () -> closer.getState() == Thread.State.TERMINATED || closer.getState() == Thread.State.BLOCKED,
                "close must either finish or block on the handle lock");
        adapter.releaseShow.countDown();
        renewer.join(TimeUnit.SECONDS.toMillis(5));
        closer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(renewer.isAlive(), "renew did not finish");
        assertFalse(closer.isAlive(), "close did not finish");

        assertEquals(2, adapter.issued.size(), "the armed renew must have reached the host");
        assertTrue(
                adapter.issued.get(adapter.issued.size() - 1).closed.get(),
                "the newest hint registration must be dismissed once the handle is closed");
    }

    private static void awaitTrue(final java.util.function.BooleanSupplier condition, final String description)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + description);
            }
            Thread.sleep(1);
        }
    }

    private static RuntimeUiHostCapabilityService service(
            final StatusToolbarAdapter adapter, final DisposableScope scope) {
        return new RuntimeUiHostCapabilityService(
                dev.turboism.permissions.PermissionChecker.allowAll(),
                "plugin.a",
                UiHostStateSource.DEFAULT,
                scope,
                adapter);
    }

    /** Records every hint the runtime asked the host to show, and every dismissal. */
    private static final class RecordingAdapter implements StatusToolbarAdapter {

        private final List<CanvasHintNotification> shown = new ArrayList<>();
        private int closed;

        @Override
        public AdapterResult<Registration> notifyCanvasHint(final CanvasHintNotification notification) {
            shown.add(notification);
            return AdapterResult.available(() -> closed++);
        }

        @Override
        public AdapterResult<Registration> notifyStatus(final StatusNotification notification) {
            return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(Capability.STATUS_NOTIFY.id()));
        }
    }

    /**
     * Adapter whose armed {@code notifyCanvasHint} call blocks on a latch so a renew can be
     * interleaved deterministically with a concurrent close.
     */
    private static final class LatchingAdapter implements StatusToolbarAdapter {

        private final List<CanvasHintNotification> shown = Collections.synchronizedList(new ArrayList<>());
        private final List<IssuedHint> issued = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch showEntered = new CountDownLatch(1);
        private final CountDownLatch releaseShow = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean();

        private void armNextShow() {
            armed.set(true);
        }

        @Override
        public AdapterResult<Registration> notifyCanvasHint(final CanvasHintNotification notification) {
            shown.add(notification);
            final IssuedHint hint = new IssuedHint();
            issued.add(hint);
            if (armed.compareAndSet(true, false)) {
                showEntered.countDown();
                try {
                    releaseShow.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return AdapterResult.available(hint);
        }

        @Override
        public AdapterResult<Registration> notifyStatus(final StatusNotification notification) {
            return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(Capability.STATUS_NOTIFY.id()));
        }

        private static final class IssuedHint implements Registration {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() {
                closed.set(true);
            }
        }
    }
}

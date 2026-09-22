package dev.turboism.ui;

import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CanvasHintHandle;
import dev.turboism.sdk.ui.CanvasHintNotification;
import dev.turboism.sdk.ui.CanvasHintPosition;
import dev.turboism.sdk.ui.StatusNotification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasHintHandleRuntimeTest {

    @Test
    void renewReissuesTheSameKeyedHintAndCloseDismissesTheLatestRegistration() {
        RecordingAdapter adapter = new RecordingAdapter();
        RuntimeUiHostCapabilityService service = service(adapter, new DisposableScope());

        CanvasHintHandle handle = service.notifyCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 1.0f)
        );
        assertEquals(1, adapter.shown.size());
        assertTrue(adapter.shown.get(0).id().startsWith("8:plugin.a:"),
            "the plugin scope prefixes the hint key: " + adapter.shown.get(0).id());
        assertTrue(adapter.shown.get(0).id().endsWith(":screen-color"));

        handle.renew();
        assertEquals(2, adapter.shown.size(), "renew must re-issue the hint");
        assertEquals(adapter.shown.get(0).id(), adapter.shown.get(1).id(),
            "renew must reuse the same key so the host refreshes instead of stacking");

        handle.close();
        assertEquals(1, adapter.closed, "close must dismiss the newest registration");
        assertEquals(2, adapter.shown.size(), "close must not re-issue");
    }

    @Test
    void aClosedHandleIgnoresLaterRenewsAndDismissIsIdempotent() {
        RecordingAdapter adapter = new RecordingAdapter();
        RuntimeUiHostCapabilityService service = service(adapter, new DisposableScope());

        CanvasHintHandle handle = service.notifyCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 1.0f)
        );
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

        CanvasHintHandle handle = service.notifyCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 1.0f)
                .withOnClick(() -> clicked.set(true))
                .withPosition(new CanvasHintPosition(12.0f, 34.0f))
        );

        CanvasHintNotification delivered = adapter.shown.get(0);
        assertTrue(delivered.onClick().isPresent(),
            "scoping the hint key must not drop the click action that makes it clickable");
        assertEquals(new CanvasHintPosition(12.0f, 34.0f), delivered.position().orElseThrow(),
            "scoping the hint key must not drop an explicit position");

        delivered.onClick().orElseThrow().run();
        assertTrue(clicked.get(), "the delivered click action must reach the plugin callback");

        handle.close();
    }

    @Test
    void aClickThatReachesThePluginDismissesTheDismissibleHint() {
        RecordingAdapter adapter = new RecordingAdapter();
        RuntimeUiHostCapabilityService service = service(adapter, new DisposableScope());

        CanvasHintHandle handle = service.notifyDismissibleCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 1.0f)
        );
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

        CanvasHintHandle handle = service.notifyCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 1.0f)
        );
        handle.renew();
        handle.renew();
        assertEquals(3, adapter.shown.size());

        scope.close();

        assertEquals(1, adapter.closed, "disposal must clear the hint once, not once per renew");
    }

    private static RuntimeUiHostCapabilityService service(
        final RecordingAdapter adapter,
        final DisposableScope scope
    ) {
        return new RuntimeUiHostCapabilityService(
            dev.turboism.permissions.PermissionChecker.allowAll(),
            "plugin.a",
            UiHostStateSource.DEFAULT,
            scope,
            adapter
        );
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
            return AdapterResult.unavailable(
                SafeModeDiagnostic.capabilityUnavailable(Capability.STATUS_NOTIFY.id())
            );
        }
    }
}

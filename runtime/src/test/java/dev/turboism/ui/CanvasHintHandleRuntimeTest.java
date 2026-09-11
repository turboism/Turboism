package dev.turboism.ui;

import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CanvasHintHandle;
import dev.turboism.sdk.ui.CanvasHintNotification;
import dev.turboism.sdk.ui.StatusNotification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
        assertEquals(1, adapter.shownIds.size());
        assertTrue(adapter.shownIds.get(0).startsWith("8:plugin.a:"),
            "the plugin scope prefixes the hint key: " + adapter.shownIds.get(0));
        assertTrue(adapter.shownIds.get(0).endsWith(":screen-color"));

        handle.renew();
        assertEquals(2, adapter.shownIds.size(), "renew must re-issue the hint");
        assertEquals(adapter.shownIds.get(0), adapter.shownIds.get(1),
            "renew must reuse the same key so the host refreshes instead of stacking");

        handle.close();
        assertEquals(1, adapter.closed, "close must dismiss the newest registration");
        assertEquals(2, adapter.shownIds.size(), "close must not re-issue");
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

        assertEquals(1, adapter.shownIds.size(), "a spent handle must not re-issue");
        assertEquals(1, adapter.closed, "repeated dismissal must close the hint once");
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
        assertEquals(3, adapter.shownIds.size());

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

        private final List<String> shownIds = new ArrayList<>();
        private int closed;

        @Override
        public AdapterResult<Registration> notifyCanvasHint(final CanvasHintNotification notification) {
            shownIds.add(notification.id());
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

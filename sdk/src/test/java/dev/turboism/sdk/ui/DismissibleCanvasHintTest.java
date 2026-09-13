package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DismissibleCanvasHintTest {

    @Test
    void clickingTheHintClosesTheRegistrationItReturned() {
        Recorder recorder = new Recorder();
        UiHostCapabilityService uiHost = stub(recorder);

        Registration handle = uiHost.notifyDismissibleCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 5.0f)
        );

        assertEquals(1, recorder.sent.size(), "the wrapper must show exactly one hint");
        assertTrue(recorder.sent.get(0).onClick().isPresent(),
            "the wrapper must attach a click action");
        assertFalse(recorder.closed.get(), "nothing is dismissed before the click");

        recorder.sent.get(0).onClick().orElseThrow().run();

        assertTrue(recorder.closed.get(), "clicking the hint must dismiss it");
        handle.close();
    }

    @Test
    void theReturnedHandleStillDismissesTheHintExplicitly() {
        Recorder recorder = new Recorder();
        UiHostCapabilityService uiHost = stub(recorder);

        Registration handle = uiHost.notifyDismissibleCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 5.0f)
        );
        handle.close();

        assertTrue(recorder.closed.get());
    }

    @Test
    void aClickThatRacesHandlePublicationStillDismissesTheHint() {
        Recorder recorder = new Recorder();
        // The stub clicks the hint from inside the notification call, before the
        // wrapper can publish the registration the click action would close.
        recorder.clickOnSend = true;
        UiHostCapabilityService uiHost = stub(recorder);

        Registration handle = uiHost.notifyDismissibleCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 5.0f)
        );

        assertTrue(recorder.closed.get(), "a racing click must not leave the hint up");
        handle.close();
    }

    @Test
    void theWrapperReplacesAClickActionTheCallerAlreadySet() {
        Recorder recorder = new Recorder();
        UiHostCapabilityService uiHost = stub(recorder);
        AtomicInteger original = new AtomicInteger();

        uiHost.notifyDismissibleCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 5.0f)
                .withOnClick(original::incrementAndGet)
        );
        recorder.sent.get(0).onClick().orElseThrow().run();

        assertEquals(0, original.get(), "the dismiss action must own the click");
        assertTrue(recorder.closed.get());
    }

    /** Minimal host that records what the wrapper asked it to show. */
    private static UiHostCapabilityService stub(final Recorder recorder) {
        return (UiHostCapabilityService) Proxy.newProxyInstance(
            UiHostCapabilityService.class.getClassLoader(),
            new Class<?>[] { UiHostCapabilityService.class },
            (InvocationHandler) (proxy, method, args) -> {
                // Checked before isDefault(): notifyCanvasHint is itself a default method, and the
                // wrapper under test reaches this host through that default method.
                if ("notifyCanvasHint".equals(method.getName())) {
                    CanvasHintNotification notification = (CanvasHintNotification) args[0];
                    recorder.sent.add(notification);
                    if (recorder.clickOnSend) {
                        notification.onClick().orElseThrow().run();
                    }
                    return new CanvasHintHandle() {
                        @Override
                        public void renew() {
                        }

                        @Override
                        public void close() {
                            recorder.closed.set(true);
                        }
                    };
                }
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static final class Recorder {
        private final List<CanvasHintNotification> sent = new ArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean clickOnSend;
    }
}

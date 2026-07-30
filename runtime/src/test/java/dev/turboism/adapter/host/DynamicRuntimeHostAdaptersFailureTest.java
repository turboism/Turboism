package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicRuntimeHostAdaptersFailureTest {

    @Test
    void adapterRuntimeFailureRemainsPrimaryWhenDeferredCleanupFails() {
        RuntimeException primary = new RuntimeException("adapter failed");
        AssertionError cleanup = new AssertionError("cleanup failed");
        DynamicRuntimeHostAdapters dynamic = connectedDynamic(primary, cleanup);

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> dynamic.view().statusToolbar().notifyStatus(notification())
        );

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanup, thrown.getSuppressed()[0]);
    }

    @Test
    void adapterErrorRemainsPrimaryWhenDeferredCleanupFails() {
        AssertionError primary = new AssertionError("adapter failed");
        RuntimeException cleanup = new RuntimeException("cleanup failed");
        DynamicRuntimeHostAdapters dynamic = connectedDynamic(primary, cleanup);

        AssertionError thrown = assertThrows(
            AssertionError.class,
            () -> dynamic.view().statusToolbar().notifyStatus(notification())
        );

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanup, thrown.getSuppressed()[0]);
    }

    @Test
    void successfulAdapterPropagatesDeferredCleanupFailureUnwrapped() {
        AssertionError cleanup = new AssertionError("cleanup failed");
        DynamicRuntimeHostAdapters dynamic = connectedDynamic(null, cleanup);

        assertSame(cleanup, assertThrows(
            AssertionError.class,
            () -> dynamic.view().statusToolbar().notifyStatus(notification())
        ));
    }

    @Test
    void successfulCleanupPreservesAdapterResultAndPrimaryFailure() {
        DynamicRuntimeHostAdapters success = connectedDynamic(null, null);
        success.view().statusToolbar().notifyStatus(notification()).value().orElseThrow().close();

        RuntimeException primary = new RuntimeException("adapter failed");
        DynamicRuntimeHostAdapters failure = connectedDynamic(primary, null);
        assertSame(primary, assertThrows(
            RuntimeException.class,
            () -> failure.view().statusToolbar().notifyStatus(notification())
        ));
    }

    private static DynamicRuntimeHostAdapters connectedDynamic(
        final Throwable adapterFailure,
        final Throwable cleanupFailure
    ) {
        DynamicRuntimeHostAdapters dynamic = new DynamicRuntimeHostAdapters();
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        StatusToolbarAdapter statusToolbar = new StatusToolbarAdapter() {
            @Override
            public AdapterResult<Registration> notifyStatus(final StatusNotification notification) {
                throwIfPresent(adapterFailure);
                return AdapterResult.available(() -> { });
            }
        };
        dynamic.connect(new RuntimeHostAdapters(
            safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
            statusToolbar, safe.uiSurface()
        ));
        dynamic.onOutermostAdapterCallComplete(() -> throwIfPresent(cleanupFailure));
        return dynamic;
    }

    private static StatusNotification notification() {
        return new StatusNotification("failure-test", "INFO", "Failure test");
    }

    private static void throwIfPresent(final Throwable failure) {
        if (failure == null) {
            return;
        }
        DynamicRuntimeHostAdaptersFailureTest.<RuntimeException>sneakyThrow(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(final Throwable failure) throws T {
        throw (T) failure;
    }
}

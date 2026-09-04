package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DynamicCubismAuthoringTransactionTest {

    @Test
    void forwardsThePluginIdentityAndSynchronousCallback() {
        final DynamicCubismModelAccess dynamic = new DynamicCubismModelAccess();
        final RecordingProvider provider = new RecordingProvider();
        dynamic.connect(provider);

        final AuthoringTransactionResult<String> result =
            ((RuntimeAuthoringTransactionProvider) dynamic)
                .authoringTransactions("plugin.alpha")
                .execute(
                    AuthoringTransactionOptions.of("Inspect"),
                    () -> "done"
                );

        assertEquals("plugin.alpha", provider.pluginId.get());
        assertEquals(Optional.of("done"), result.value());
        assertEquals(AuthoringTransactionOutcome.NO_CHANGE, result.outcome());
    }

    @Test
    void capturedServiceFailsClosedAfterDisconnectWithoutRunningTheCallback() {
        final DynamicCubismModelAccess dynamic = new DynamicCubismModelAccess();
        dynamic.connect(new RecordingProvider());
        final AuthoringTransactionService captured =
            ((RuntimeAuthoringTransactionProvider) dynamic)
                .authoringTransactions("plugin.alpha");
        dynamic.deactivate();
        final AtomicBoolean invoked = new AtomicBoolean();

        final AuthoringTransactionResult<Void> result = captured.execute(
            AuthoringTransactionOptions.of("Stale"),
            () -> {
                invoked.set(true);
                return null;
            }
        );

        assertFalse(invoked.get());
        assertEquals(AuthoringTransactionOutcome.UNAVAILABLE, result.outcome());
        assertEquals(
            Optional.of("cubism.authoring.transactions.host-unavailable"),
            result.diagnosticId()
        );
    }

    @Test
    void hostReplacementWaitsForTheWholeTransactionCallbackLease() throws Exception {
        final DynamicCubismModelAccess dynamic = new DynamicCubismModelAccess();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        dynamic.connect(new RecordingProvider());
        final AuthoringTransactionService service =
            ((RuntimeAuthoringTransactionProvider) dynamic)
                .authoringTransactions("plugin.alpha");
        final AtomicReference<Throwable> transactionFailure = new AtomicReference<>();
        final Thread transaction = new Thread(() -> {
            try {
                service.execute(
                    AuthoringTransactionOptions.of("Held transaction"),
                    () -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("release timed out");
                        }
                        return null;
                    }
                );
            } catch (Throwable failure) {
                transactionFailure.set(failure);
            }
        }, "authoring-transaction-test");
        transaction.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        final AtomicBoolean disconnected = new AtomicBoolean();
        final Thread disconnect = new Thread(() -> {
            dynamic.deactivate();
            disconnected.set(true);
        }, "authoring-disconnect-test");
        disconnect.start();

        Thread.sleep(100L);
        assertFalse(disconnected.get());
        release.countDown();
        transaction.join(TimeUnit.SECONDS.toMillis(5));
        disconnect.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(transaction.isAlive());
        assertFalse(disconnect.isAlive());
        assertTrue(disconnected.get());
        assertEquals(null, transactionFailure.get());
    }

    private static final class RecordingProvider
        implements CubismModelAccess, RuntimeAuthoringTransactionProvider {

        private final AtomicReference<String> pluginId = new AtomicReference<>();

        @Override
        public CubismModel active() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public AuthoringTransactionService authoringTransactions(final String requestedPluginId) {
            pluginId.set(requestedPluginId);
            return new AuthoringTransactionService() {
                @Override
                public <T> AuthoringTransactionResult<T> execute(
                    final AuthoringTransactionOptions options,
                    final AuthoringTransactionWork<T> work
                ) {
                    final T value;
                    try {
                        value = work.run();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    final HistorySnapshot history = new HistorySnapshot(
                        HistorySnapshot.Availability.AVAILABLE,
                        1,
                        1,
                        0,
                        List.of(),
                        false,
                        false,
                        "document-binding-1",
                        "manager-binding-1"
                    );
                    return AuthoringTransactionResult.noChange(
                        value,
                        new AuthoringTransactionReceipt(
                            "transaction-1",
                            options.label(),
                            history,
                            history,
                            Optional.empty()
                        )
                    );
                }
            };
        }
    }
}

package dev.turboism.adapter.cubism;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CubismFacadeAuthoringTransactionTest {

    @Test
    void executeRequiresWritePermissionBeforeTheDelegateOrCallbackRuns() {
        final AtomicInteger delegateCalls = new AtomicInteger();
        final AtomicBoolean callbackInvoked = new AtomicBoolean();
        final AuthoringTransactionService delegate = delegate(delegateCalls);
        final CubismFacadeImpl facade = facade(List.of(), () -> true, delegate);

        assertThrows(CubismPermissionException.class, () ->
            facade.authoringTransactions().execute(
                AuthoringTransactionOptions.of("Denied transaction"),
                () -> {
                    callbackInvoked.set(true);
                    return "unexpected";
                }
            )
        );

        assertEquals(0, delegateCalls.get());
        assertFalse(callbackInvoked.get());
    }

    @Test
    void allowedInvocationDelegatesAndPreservesTheTypedResult() {
        final AtomicInteger delegateCalls = new AtomicInteger();
        final CubismFacadeImpl facade = facade(
            List.of(permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
            () -> true,
            delegate(delegateCalls)
        );

        final AuthoringTransactionResult<String> result =
            facade.authoringTransactions().execute(
                AuthoringTransactionOptions.of("Allowed transaction"),
                () -> "done"
            );

        assertEquals(1, delegateCalls.get());
        assertEquals(Optional.of("done"), result.value());
    }

    @Test
    void serviceCapturedBeforePluginDisableStillRechecksLifecycleOnExecute() {
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicInteger delegateCalls = new AtomicInteger();
        final AuthoringTransactionService captured = facade(
            List.of(permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
            active::get,
            delegate(delegateCalls)
        ).authoringTransactions();
        active.set(false);

        assertThrows(IllegalStateException.class, () -> captured.execute(
            AuthoringTransactionOptions.of("Stale transaction"),
            () -> "unexpected"
        ));
        assertEquals(0, delegateCalls.get());
    }

    private static CubismFacadeImpl facade(
        final List<PluginPermission> permissions,
        final BooleanSupplier active,
        final AuthoringTransactionService service
    ) {
        return new CubismFacadeImpl(
            emptySource(),
            new CubismPermissionGate(
                "plugin.test",
                permissions,
                ignored -> { },
                Clock.systemUTC()
            ),
            unavailableModelAccess(),
            active,
            service
        );
    }

    private static AuthoringTransactionService delegate(final AtomicInteger calls) {
        return new AuthoringTransactionService() {
            @Override
            public <T> AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options,
                final AuthoringTransactionWork<T> work
            ) {
                calls.incrementAndGet();
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

    private static HostSnapshotSource emptySource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() {
                return new HostSelection(
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                );
            }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }

    private static CubismModelAccess unavailableModelAccess() {
        return () -> { throw new IllegalStateException("unavailable"); };
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return ""; }
            @Override public String reason() { return "test"; }
        };
    }
}

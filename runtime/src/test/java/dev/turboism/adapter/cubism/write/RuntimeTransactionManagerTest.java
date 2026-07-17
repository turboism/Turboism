package dev.turboism.adapter.cubism.write;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.WorkBudgetPolicy;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.transaction.CommitFailedException;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.RollbackFailedException;
import dev.turboism.sdk.cubism.transaction.TransactionAlreadyActiveException;
import dev.turboism.sdk.cubism.transaction.TransactionClosedException;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.WorkBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTransactionManagerTest {

    private static final DocumentId DOCUMENT_ID = new DocumentId("document-1");
    private static final ModelId MODEL_ID = new ModelId("model-1");
    private static final ParameterId PARAMETER_ID = new ParameterId("parameter-1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private RuntimeScheduler scheduler;

    @AfterEach
    void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void commitsQueuedParameterWritesWhenTransactionIsOpen() throws TransactionException {
        final FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        final RuntimeTransactionManager manager = managerWith(adapter, permission());
        final ModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);

        transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F));
        transaction.commit();

        assertEquals(TransactionStatus.COMMITTED, transaction.status());
        assertEquals(0.75, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertFalse(manager.query("plugin.demo", DOCUMENT_ID).isPresent());
    }

    @Test
    void commitIsDispatchedThroughRuntimeScheduler() throws Exception {
        final FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        final RecordingPolicy policy = new RecordingPolicy();
        final RuntimeTransactionManager manager = managerWith(adapter, policy, permission());
        final ModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);

        transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F));
        transaction.commit();

        assertTrue(policy.dispatched.await(1, TimeUnit.SECONDS));
        PluginTask task = policy.task.get();
        assertEquals("transaction.commit", task.taskType());
        assertEquals("plugin.demo", task.pluginId());
        assertEquals(0.75, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
    }

    @Test
    void restoresCapturedSnapshotWhenTransactionRollsBack() throws TransactionException {
        final FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        final RuntimeTransactionManager manager = managerWith(adapter, permission());
        final ModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);

        transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F));
        transaction.rollback();

        assertEquals(TransactionStatus.ROLLED_BACK, transaction.status());
        assertEquals(0.25, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertFalse(manager.query("plugin.demo", DOCUMENT_ID).isPresent());
    }

    @Test
    void rejectsOutOfRangeParameterValuesAndLeavesModelUnchanged() throws TransactionException {
        final FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        final RuntimeTransactionManager manager = managerWith(adapter, permission());
        final ModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);
        transaction.enqueue(new WriteParameterCommand("command-range", MODEL_ID, PARAMETER_ID, 5.0F));

        final CommitFailedException error = assertThrows(CommitFailedException.class, transaction::commit);

        assertEquals(TransactionStatus.ROLLED_BACK, transaction.status());
        assertEquals(0.25, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertFalse(manager.query("plugin.demo", DOCUMENT_ID).isPresent());
        assertNotNull(error);
    }

    @Test
    void partialCommitFailureRestoresCapturedSnapshot() throws TransactionException {
        final FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        final RuntimeTransactionManager manager = managerWith(adapter, permission());
        final ModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);
        transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F));
        transaction.enqueue(new WriteParameterCommand("command-2", MODEL_ID, new ParameterId("missing"), 0.5F));

        final CommitFailedException error = assertThrows(CommitFailedException.class, transaction::commit);

        assertEquals("Commit failed and transaction was rolled back", error.getMessage());
        assertEquals(TransactionStatus.ROLLED_BACK, transaction.status());
        assertEquals(0.25, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertFalse(manager.query("plugin.demo", DOCUMENT_ID).isPresent());
    }

    @Test
    void rollbackFailureAfterPartialCommitFailureReportsRollbackFailure() throws TransactionException {
        final FailingRestoreAdapter adapter = new FailingRestoreAdapter();
        adapter.addDocument("document-1", "Document", new FakeHostWriteAdapter.FakeModel(
            MODEL_ID,
            "Model",
            List.of(new FakeHostWriteAdapter.FakeParameter(PARAMETER_ID.value(), "Parameter", 0.25))
        ));
        final RuntimeTransactionManager manager = managerWith(adapter, permission());
        final ModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);
        transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F));
        transaction.enqueue(new WriteParameterCommand("command-2", MODEL_ID, new ParameterId("missing"), 0.5F));

        final RollbackFailedException error = assertThrows(RollbackFailedException.class, transaction::commit);

        assertEquals("Rollback failed after commit failure", error.getMessage());
        assertEquals(TransactionStatus.FAILED, transaction.status());
    }

    @Test
    void deniesOpenWhenWritePermissionIsMissing() {
        final RuntimeTransactionManager manager = managerWith(adapterWithParameterValue(0.25));

        final CubismPermissionException error = assertThrows(
            CubismPermissionException.class,
            () -> manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID)
        );

        assertEquals(
            "Missing required permission turboism.cubism.model.write for transaction.open",
            error.getMessage()
        );
    }

    @Test
    void deniesEnqueueAndCommitWhenPermissionIsRevokedAfterOpen() throws TransactionException {
        final MutablePermission permission = new MutablePermission(true);
        final RuntimeTransactionManager manager = managerWith(adapterWithParameterValue(0.25), permission);
        final ModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);

        permission.setAllowed(false);

        assertThrows(
            CubismPermissionException.class,
            () -> transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F))
        );
        assertThrows(CubismPermissionException.class, transaction::commit);
    }

    @Test
    void rejectsDoubleOpenForSamePluginAndDocument() throws TransactionException {
        final RuntimeTransactionManager manager = managerWith(adapterWithParameterValue(0.25), permission());
        final TestPluginContext context = new TestPluginContext("plugin.demo");

        manager.openTransaction(context, DOCUMENT_ID);

        final TransactionAlreadyActiveException error = assertThrows(
            TransactionAlreadyActiveException.class,
            () -> manager.openTransaction(context, DOCUMENT_ID)
        );
        assertEquals("Transaction already open for plugin plugin.demo and document document-1", error.getMessage());
    }

    @Test
    void closedTransactionRejectsFurtherEnqueue() throws TransactionException {
        final RuntimeTransactionManager manager = managerWith(adapterWithParameterValue(0.25), permission());
        final ModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);
        transaction.commit();

        assertThrows(
            TransactionClosedException.class,
            () -> transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F))
        );
    }

    @Test
    void allowsDifferentPluginsToOpenSameDocument() throws TransactionException {
        final RuntimeTransactionManager manager = managerWith(adapterWithParameterValue(0.25), permission());

        manager.openTransaction(new TestPluginContext("plugin.one"), DOCUMENT_ID);
        manager.openTransaction(new TestPluginContext("plugin.two"), DOCUMENT_ID);

        assertTrue(manager.query("plugin.one", DOCUMENT_ID).isPresent());
        assertTrue(manager.query("plugin.two", DOCUMENT_ID).isPresent());
    }

    private RuntimeTransactionManager managerWith(
        final FakeHostWriteAdapter adapter,
        final PluginPermission... permissions
    ) {
        return managerWith(adapter, new RecordingPolicy(), permissions);
    }

    private RuntimeTransactionManager managerWith(
        final FakeHostWriteAdapter adapter,
        final WorkBudgetPolicy policy,
        final PluginPermission... permissions
    ) {
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            policy,
            new PluginExecutorRegistry(1, 8, events::add, CLOCK),
            availableSidecar(),
            events::add
        );
        return new RuntimeTransactionManager(
            adapter,
            dev.turboism.permissions.PermissionChecker.from(List.of(permissions)),
            scheduler
        );
    }

    private RuntimeTransactionManager managerWith(
        final FakeHostWriteAdapter adapter,
        final dev.turboism.permissions.PermissionChecker permissionChecker
    ) {
        return managerWith(adapter, new RecordingPolicy(), permissionChecker);
    }

    private RuntimeTransactionManager managerWith(
        final FakeHostWriteAdapter adapter,
        final WorkBudgetPolicy policy,
        final dev.turboism.permissions.PermissionChecker permissionChecker
    ) {
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            policy,
            new PluginExecutorRegistry(1, 8, events::add, CLOCK),
            availableSidecar(),
            events::add
        );
        return new RuntimeTransactionManager(adapter, permissionChecker, scheduler);
    }

    private static SidecarDispatcher availableSidecar() {
        return (task, callback) -> {
            callback.run();
            return java.util.concurrent.CompletableFuture.completedFuture(
                dev.turboism.core.runtime.sidecar.SidecarResult.success("")
            );
        };
    }

    private static FakeHostWriteAdapter adapterWithParameterValue(final double value) {
        final FakeHostWriteAdapter adapter = new FakeHostWriteAdapter();
        adapter.addDocument("document-1", "Document", new FakeHostWriteAdapter.FakeModel(
            MODEL_ID,
            "Model",
            List.of(new FakeHostWriteAdapter.FakeParameter(PARAMETER_ID.value(), "Parameter", value))
        ));
        return adapter;
    }

    private static PluginPermission permission() {
        return new TestPermission(RuntimeTransactionManager.TURBOISM_CUBISM_WRITE);
    }

    private static final class RecordingPolicy implements WorkBudgetPolicy {

        private final CountDownLatch dispatched = new CountDownLatch(1);
        private final AtomicReference<PluginTask> task = new AtomicReference<>();

        @Override
        public WorkBudget classify(PluginTask task) {
            this.task.set(task);
            if ("transaction.commit".equals(task.taskType()) || "transaction.rollback".equals(task.taskType())) {
                dispatched.countDown();
            }
            return WorkBudget.HEAVY;
        }
    }

    private static final class FailingRestoreAdapter extends FakeHostWriteAdapter {

        @Override
        public synchronized void restore(final HostSnapshot snapshot) throws TransactionException {
            throw new TransactionException(snapshot.documentId().id(), 1999, "ERROR", "forced restore failure");
        }
    }
}

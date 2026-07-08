package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTransactionManagerTest {

    private static final DocumentId DOCUMENT_ID = new DocumentId("document-1");
    private static final ModelId MODEL_ID = new ModelId("model-1");
    private static final ParameterId PARAMETER_ID = new ParameterId("parameter-1");

    @Test
    void commitsQueuedParameterWritesWhenTransactionIsOpen() throws TransactionException {
        final FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        final RuntimeTransactionManager manager = managerWith(adapter, permission());
        final RuntimeModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);

        transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F));
        transaction.commit();

        assertEquals(TransactionStatus.COMMITTED, transaction.status());
        assertEquals(0.75, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertFalse(manager.query("plugin.demo", DOCUMENT_ID).isPresent());
    }

    @Test
    void restoresCapturedSnapshotWhenTransactionRollsBack() throws TransactionException {
        final FakeHostWriteAdapter adapter = adapterWithParameterValue(0.25);
        final RuntimeTransactionManager manager = managerWith(adapter, permission());
        final RuntimeModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);

        transaction.enqueue(new WriteParameterCommand("command-1", MODEL_ID, PARAMETER_ID, 0.75F));
        transaction.rollback();

        assertEquals(TransactionStatus.ROLLED_BACK, transaction.status());
        assertEquals(0.25, adapter.parameterValue(MODEL_ID.value(), PARAMETER_ID.value()));
        assertFalse(manager.query("plugin.demo", DOCUMENT_ID).isPresent());
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
        final RuntimeModelTransaction transaction = manager.openTransaction(new TestPluginContext("plugin.demo"), DOCUMENT_ID);

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

        final TransactionException error = assertThrows(
            TransactionException.class,
            () -> manager.openTransaction(context, DOCUMENT_ID)
        );
        assertEquals("Transaction already open for plugin plugin.demo and document document-1", error.getMessage());
    }

    @Test
    void allowsDifferentPluginsToOpenSameDocument() throws TransactionException {
        final RuntimeTransactionManager manager = managerWith(adapterWithParameterValue(0.25), permission());

        manager.openTransaction(new TestPluginContext("plugin.one"), DOCUMENT_ID);
        manager.openTransaction(new TestPluginContext("plugin.two"), DOCUMENT_ID);

        assertTrue(manager.query("plugin.one", DOCUMENT_ID).isPresent());
        assertTrue(manager.query("plugin.two", DOCUMENT_ID).isPresent());
    }

    private static RuntimeTransactionManager managerWith(
        final FakeHostWriteAdapter adapter,
        final PluginPermission... permissions
    ) {
        return new RuntimeTransactionManager(adapter, dev.turboism.permissions.PermissionChecker.from(List.of(permissions)));
    }

    private static RuntimeTransactionManager managerWith(
        final FakeHostWriteAdapter adapter,
        final dev.turboism.permissions.PermissionChecker permissionChecker
    ) {
        return new RuntimeTransactionManager(adapter, permissionChecker);
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
}

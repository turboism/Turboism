package dev.turboism.script;

import dev.turboism.graal.GraalHostManager;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.script.ScriptDescriptor;
import dev.turboism.sdk.script.ScriptId;
import dev.turboism.sdk.script.ScriptLanguage;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeScriptHostBridgeTest {

    @Test
    void bulkParameterReadsAndWritesUseOneUiDispatchPerHostCall() throws Exception {
        final MutableParameter angleX = new MutableParameter("ParamAngleX", 1F);
        final MutableParameter angleY = new MutableParameter("ParamAngleY", 2F);
        final RecordingContext context = new RecordingContext(angleX, angleY);
        final RuntimeScriptHostBridge bridge = bridge(
            context,
            List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ, PermissionIds.TURBOISM_CUBISM_MODEL_WRITE)
        );

        final String read = bridge.call(
            "cubism.parameters.getMany", "{\"ids\":[\"ParamAngleY\",\"ParamAngleX\"]}"
        );
        final String written = bridge.call(
            "cubism.parameters.setMany",
            "{\"changes\":[{\"id\":\"ParamAngleX\",\"value\":10},{\"id\":\"ParamAngleY\",\"value\":-5}]}"
        );

        assertTrue(read.indexOf("ParamAngleY") < read.indexOf("ParamAngleX"));
        assertTrue(written.contains("\"updated\""));
        assertEquals(10F, angleX.value);
        assertEquals(-5F, angleY.value);
        assertEquals(2, context.uiDispatches);
        assertTrue(context.lastUiThread.get());
    }

    @Test
    void hostStatusDoesNotWaitForTheUiScheduler() throws Exception {
        final RecordingContext context = new RecordingContext(
            new MutableParameter("ParamAngleX", 1F)
        );

        final String result = bridge(
            context, List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ)
        ).call("cubism.status", "{}");

        assertTrue(result.contains("\"hostPresent\":true"));
        assertTrue(result.contains("\"activeModel\":true"));
        assertEquals(0, context.uiDispatches);
        assertTrue(context.lastCubismUiThread.get());
    }

    @Test
    void timedOutHostStatusDoesNotRunAfterTheEdtUnblocks() throws Exception {
        final RecordingContext context = new RecordingContext(
            new MutableParameter("ParamAngleX", 1F)
        );
        final CountDownLatch edtBlocked = new CountDownLatch(1);
        final CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(5L, TimeUnit.SECONDS));
        final RuntimeScriptHostBridge bridge = bridge(
            context,
            List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ),
            100L,
            TimeUnit.MILLISECONDS
        );

        assertThrows(
            java.util.concurrent.TimeoutException.class,
            () -> bridge.call("cubism.status", "{}")
        );
        releaseEdt.countDown();
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(null, context.lastCubismUiThread.get());
    }

    @Test
    void modelSnapshotReturnsPlainBoundedSummary() throws Exception {
        final RecordingContext context = new RecordingContext(
            new MutableParameter("ParamAngleX", 1F),
            new MutableParameter("ParamAngleY", 2F)
        );
        final String result = bridge(
            context, List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ)
        ).call("cubism.model.snapshot", "{}");

        assertTrue(result.contains("\"id\":\"model-1\""));
        assertTrue(result.contains("\"parameters\""));
        assertEquals(1, context.uiDispatches);
    }

    @Test
    void oversizedBulkWriteRejects257DistinctParametersWithoutMutation() {
        final MutableParameter[] parameters = new MutableParameter[257];
        final StringBuilder payload = new StringBuilder("{\"changes\":[");
        for (int index = 0; index < parameters.length; index++) {
            parameters[index] = new MutableParameter("Param" + index, index);
            if (index > 0) payload.append(',');
            payload.append("{\"id\":\"Param").append(index)
                .append("\",\"value\":").append(index + 1000).append('}');
        }
        payload.append("]}");
        final RuntimeScriptHostBridge bridge = bridge(
            new RecordingContext(parameters), List.of(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
            )
        );

        final GraalHostManager.HostCallException failure = assertThrows(
            GraalHostManager.HostCallException.class,
            () -> bridge.call("cubism.parameters.setMany", payload.toString())
        );

        assertEquals("SCRIPT_ARGUMENT_INVALID", failure.code());
        for (int index = 0; index < parameters.length; index++) {
            assertEquals(index, parameters[index].value);
        }
    }

    @Test
    void maximum256ParameterBulkWriteSucceeds() throws Exception {
        final MutableParameter[] parameters = new MutableParameter[256];
        final StringBuilder payload = new StringBuilder("{\"changes\":[");
        for (int index = 0; index < parameters.length; index++) {
            parameters[index] = new MutableParameter("Param" + index, index);
            if (index > 0) payload.append(',');
            payload.append("{\"id\":\"Param").append(index)
                .append("\",\"value\":").append(index + 1000).append('}');
        }
        payload.append("]}");
        final RuntimeScriptHostBridge bridge = bridge(
            new RecordingContext(parameters), List.of(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
            )
        );

        final String result = bridge.call("cubism.parameters.setMany", payload.toString());

        assertTrue(result.contains("\"updated\""));
        for (int index = 0; index < parameters.length; index++) {
            assertEquals(index + 1000F, parameters[index].value);
        }
    }

    @Test
    void writeOperationsRequireReadAndWritePermissions() {
        final RecordingContext context = new RecordingContext(
            new MutableParameter("ParamAngleX", 1F)
        );
        final RuntimeScriptHostBridge bridge = bridge(
            context,
            List.of(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE)
        );

        final GraalHostManager.HostCallException failure = assertThrows(
            GraalHostManager.HostCallException.class,
            () -> bridge.call(
                "cubism.parameters.set",
                "{\"id\":\"ParamAngleX\",\"value\":2}"
            )
        );

        assertEquals("SCRIPT_PERMISSION_DENIED", failure.code());
        assertTrue(failure.getMessage().contains(
            PermissionIds.TURBOISM_CUBISM_MODEL_READ
        ));
        assertEquals(1F, context.parameters.get(0).value);
        assertEquals(0, context.uiDispatches);
    }

    @Test
    void modelSnapshotTruncatesMoreThan256ParametersWithMetadata() throws Exception {
        final MutableParameter[] parameters = IntStream.range(0, 257)
            .mapToObj(index -> new MutableParameter("Param" + index, index))
            .toArray(MutableParameter[]::new);
        final RecordingContext context = new RecordingContext(parameters);
        final RuntimeScriptHostBridge bridge = bridge(
            context,
            List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ)
        );

        final String result = bridge.call("cubism.model.snapshot", "{}");

        assertTrue(result.contains("\"parameterCount\":257"));
        assertTrue(result.contains("\"parametersTruncated\":true"));
        assertTrue(result.contains("\"Param255\""));
        assertTrue(!result.contains("\"Param256\""));
    }

    @Test
    void permissionDenialRemainsStructuredAcrossUiDispatch() {
        final RuntimeScriptHostBridge bridge = bridge(new RecordingContext(), List.of());

        final GraalHostManager.HostCallException failure = assertThrows(
            GraalHostManager.HostCallException.class,
            () -> bridge.call("cubism.status", "{}")
        );

        assertEquals("SCRIPT_PERMISSION_DENIED", failure.code());
    }

    @Test
    void missingParameterRemainsStructuredAcrossUiDispatch() {
        final RuntimeScriptHostBridge bridge = bridge(
            new RecordingContext(new MutableParameter("ParamAngleX", 1F)),
            List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ)
        );

        final GraalHostManager.HostCallException failure = assertThrows(
            GraalHostManager.HostCallException.class,
            () -> bridge.call("cubism.parameters.get", "{\"id\":\"Missing\"}")
        );

        assertEquals("SCRIPT_PARAMETER_NOT_FOUND", failure.code());
    }

    @Test
    void unsupportedModelAccessRemainsStructuredAcrossUiDispatch() {
        final RuntimeScriptHostBridge bridge = bridge(
            RecordingContext.withUnavailableModel(),
            List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ)
        );

        final GraalHostManager.HostCallException failure = assertThrows(
            GraalHostManager.HostCallException.class,
            () -> bridge.call("cubism.parameters.list", "{}")
        );

        assertEquals("SCRIPT_ACTIVE_MODEL_UNAVAILABLE", failure.code());
    }

    @Test
    void invalidArgumentRemainsStructuredAcrossUiDispatch() {
        final RuntimeScriptHostBridge bridge = bridge(
            new RecordingContext(new MutableParameter("ParamAngleX", 1F)),
            List.of(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
            )
        );

        final GraalHostManager.HostCallException failure = assertThrows(
            GraalHostManager.HostCallException.class,
            () -> bridge.call("cubism.parameters.set", "{\"id\":\"ParamAngleX\",\"value\":\"bad\"}")
        );

        assertEquals("SCRIPT_ARGUMENT_INVALID", failure.code());
    }

    @Test
    void timedOutBulkWriteStopsBeforeItsNextMutation() throws Exception {
        final CountDownLatch firstMutationEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstMutation = new CountDownLatch(1);
        final BlockingParameter first = new BlockingParameter(
            "ParamAngleX",
            1F,
            firstMutationEntered,
            releaseFirstMutation
        );
        final MutableParameter second = new MutableParameter("ParamAngleY", 2F);
        final RecordingContext context = new RecordingContext(first, second);
        final RuntimeScriptHostBridge bridge = bridge(
            context,
            List.of(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
            ),
            100L,
            TimeUnit.MILLISECONDS
        );
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread caller = new Thread(() -> {
            try {
                bridge.call(
                    "cubism.parameters.setMany",
                    "{\"changes\":[{\"id\":\"ParamAngleX\",\"value\":10},"
                        + "{\"id\":\"ParamAngleY\",\"value\":-5}]}"
                );
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "script-host-call-test");

        caller.start();
        assertTrue(firstMutationEntered.await(5L, TimeUnit.SECONDS));
        caller.join(5_000L);
        assertTrue(!caller.isAlive());
        assertTrue(failure.get() instanceof java.util.concurrent.TimeoutException);

        releaseFirstMutation.countDown();
        assertTrue(first.finished.await(5L, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(10F, first.value);
        assertEquals(2F, second.value);
    }

    private static RuntimeScriptHostBridge bridge(
        final RecordingContext context,
        final List<String> permissions
    ) {
        return new RuntimeScriptHostBridge(context, descriptor(permissions));
    }

    private static RuntimeScriptHostBridge bridge(
        final RecordingContext context,
        final List<String> permissions,
        final long uiTimeout,
        final TimeUnit uiTimeoutUnit
    ) {
        return new RuntimeScriptHostBridge(
            context,
            descriptor(permissions),
            uiTimeout,
            uiTimeoutUnit
        );
    }

    private static ScriptDescriptor descriptor(
        final List<String> permissions
    ) {
        return new ScriptDescriptor(
            new ScriptId("test.script"),
            "Test",
            "1.0.0",
            ScriptLanguage.JAVASCRIPT,
            "main.js",
            permissions
        );
    }

    private static final class RecordingContext implements PluginContext {
        private final List<MutableParameter> parameters;
        private final boolean modelAvailable;
        private int uiDispatches;
        private final AtomicReference<Boolean> lastUiThread = new AtomicReference<>(false);
        private final AtomicReference<Boolean> lastCubismUiThread = new AtomicReference<>();

        private RecordingContext(final MutableParameter... parameters) {
            this(true, parameters);
        }

        private RecordingContext(
            final boolean modelAvailable,
            final MutableParameter... parameters
        ) {
            this.parameters = List.of(parameters);
            this.modelAvailable = modelAvailable;
        }

        private static RecordingContext withUnavailableModel() {
            return new RecordingContext(false);
        }

        @Override public dev.turboism.sdk.plugin.PluginDescriptor descriptor() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.plugin.DisposableScope disposableScope() {
            return new dev.turboism.sdk.plugin.DisposableScope();
        }
        @Override public dev.turboism.sdk.plugin.PluginLogger logger() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.plugin.PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
            throw new UnsupportedOperationException();
        }
        @Override public dev.turboism.sdk.event.EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.action.ActionRegistry actions() { throw new UnsupportedOperationException(); }
        @Override public dev.turboism.sdk.menu.MenuRegistry menus() { throw new UnsupportedOperationException(); }
        @Override public List<PluginPermission> permissions() {
            return List.of(
                permission(PermissionIds.TURBOISM_CUBISM_MODEL_READ),
                permission(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE)
            );
        }
        private static PluginPermission permission(final String id) {
            return new PluginPermission() {
                @Override public String id() { return id; }
                @Override public String scope() { return "application"; }
                @Override public String reason() { return "test"; }
            };
        }
        @Override public dev.turboism.sdk.ui.UiScheduler uiScheduler() {
            return new dev.turboism.sdk.ui.UiScheduler() {
                @Override public dev.turboism.sdk.plugin.Registration runOnUiThread(final Runnable work) {
                    uiDispatches++;
                    final AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
                    SwingUtilities.invokeLater(() -> {
                        if (cancelled.get()) {
                            return;
                        }
                        lastUiThread.set(SwingUtilities.isEventDispatchThread());
                        work.run();
                    });
                    return () -> cancelled.set(true);
                }
                @Override public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(
                    final Runnable work, final Duration delay
                ) {
                    return runOnUiThread(work);
                }
            };
        }
        @Override public CubismFacade cubism() {
            final CubismModel model = new CubismModel() {
                @Override public ModelId id() { return new ModelId("model-1"); }
                @Override public String name() { return "Model One"; }
                @Override public Parameters parameters() {
                    return new Parameters() {
                        @Override public List<Parameter> all() { return List.copyOf(parameters); }
                        @Override public Parameter find(final ParameterId id) {
                            return parameters.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
                        }
                    };
                }
                @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw new UnsupportedOperationException(); }
                @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw new UnsupportedOperationException(); }
                @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw new UnsupportedOperationException(); }
                @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw new UnsupportedOperationException(); }
                @Override public void update() { }
            };
            return new CubismFacade() {
                @Override public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() { throw new UnsupportedOperationException(); }
                @Override public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() { return Optional.empty(); }
                @Override public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() { return Optional.empty(); }
                @Override public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() { return Optional.empty(); }
                @Override public boolean isHostPresent() {
                    lastCubismUiThread.set(SwingUtilities.isEventDispatchThread());
                    return true;
                }
                @Override public dev.turboism.sdk.cubism.model.CubismModelAccess model() {
                    if (!modelAvailable) {
                        throw new UnsupportedOperationException(
                            "Cubism model access is unavailable."
                        );
                    }
                    return () -> {
                        lastCubismUiThread.set(SwingUtilities.isEventDispatchThread());
                        return model;
                    };
                }
                @Override public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    private static class MutableParameter implements Parameter {
        private final ParameterId id;
        protected volatile float value;

        private MutableParameter(final String id, final float value) {
            this.id = new ParameterId(id);
            this.value = value;
        }

        @Override public ParameterId id() { return id; }
        @Override public Optional<String> name() { return Optional.of(id.value()); }
        @Override public ParameterType type() { return ParameterType.NORMAL; }
        @Override public float getValue() { return value; }
        @Override public float getMinimumValue() { return -30F; }
        @Override public float getMaximumValue() { return 30F; }
        @Override public float getDefaultValue() { return 0F; }
        @Override public void setValue(final float value) { this.value = value; }
    }

    private static final class BlockingParameter extends MutableParameter {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final CountDownLatch finished = new CountDownLatch(1);

        private BlockingParameter(
            final String id,
            final float value,
            final CountDownLatch entered,
            final CountDownLatch release
        ) {
            super(id, value);
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void setValue(final float value) {
            entered.countDown();
            try {
                if (!release.await(5L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out awaiting test release");
                }
                super.setValue(value);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            } finally {
                finished.countDown();
            }
        }
    }
}

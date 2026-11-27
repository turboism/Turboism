package dev.turboism.tests.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.kernel.context.CorePluginContext;
import dev.turboism.kernel.context.PluginContextEnvironment;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.event.ParameterValueEvent;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.test.Stubs;
import dev.turboism.test.concurrent.Await;
import dev.turboism.test.plugin.FakePluginPaths;
import dev.turboism.test.storage.InMemoryPluginStorage;
import dev.turboism.testkit.RecordingHostSnapshotSource;
import dev.turboism.testkit.RuntimeTestKit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies cross-plugin functional behavior between {@code parameter-lock} (DP9) and
 * {@code parameter-sync} (DP10) over the real runtime parameter lifecycle and event broker.
 */
class ParameterLockSyncInteractionTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);
    private static final String DOCUMENT_ID = "doc-lock-sync";
    private static final String DOCUMENT_NAME = "TestModel.cmo3";
    private static final String MODEL_GUID = "model-guid-lock-sync";
    private static final String PARAM_DRIVER = "ParamAngleX";
    private static final String PARAM_LOCKED = "ParamAngleY";
    private static final String PARAM_UNLOCKED = "ParamBodyAngleX";
    private static final float MIN_VALUE = 0.0f;
    private static final float MAX_VALUE = 1.0f;
    private static final float BODY_MIN_VALUE = -10.0f;
    private static final float BODY_MAX_VALUE = 10.0f;
    private static final float INITIAL_VALUE = 0.0f;
    private static final float DRIVER_TARGET = 0.75f;
    private static final float EXPECTED_BODY_SYNC = 5.0f;
    private static final float LOCKED_ATTEMPT = 0.90f;
    private static final float FLOAT_TOLERANCE = 1e-4f;
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(3);
    private static final String CALLER_PLUGIN_ID = "dev.turboism.test.caller";

    @TempDir
    Path tempDir;

    @Test
    void lockedTargetRetainsValueAndSuppressesReverseOscillationUnderBothEnableOrders() throws Exception {
        verifyLockFirstPrecedence(true);
        verifyLockFirstPrecedence(false);
    }

    @Test
    void lockedDriverBlocksMutationAndEmitsNoSyncCascade() throws Exception {
        try (InteractionHarness harness = new InteractionHarness(tempDir.resolve("locked-driver"))) {
            harness.enableLockPlugin();
            harness.enableSyncPlugin();
            harness.awaitLockReady();
            harness.configureSyncSet(List.of(PARAM_DRIVER, PARAM_LOCKED, PARAM_UNLOCKED));
            harness.setParameterLocked(PARAM_LOCKED, true);

            final List<String> changedParameterIds = harness.recordObservedChanges();
            harness.callerParameter(PARAM_LOCKED).setValue(LOCKED_ATTEMPT);
            harness.awaitBrokerSettled();

            assertEquals(INITIAL_VALUE, harness.rawValue(PARAM_LOCKED), FLOAT_TOLERANCE);
            assertEquals(INITIAL_VALUE, harness.rawValue(PARAM_DRIVER), FLOAT_TOLERANCE);
            assertEquals(INITIAL_VALUE, harness.rawValue(PARAM_UNLOCKED), FLOAT_TOLERANCE);
            assertEquals(List.of(), changedParameterIds);
        }
    }

    @Test
    void disablingParameterLockReleasesInterceptionSoSubsequentSyncWritesFlow() throws Exception {
        try (InteractionHarness harness = new InteractionHarness(tempDir.resolve("disable-lock"))) {
            harness.enableLockPlugin();
            harness.enableSyncPlugin();
            harness.awaitLockReady();
            harness.configureSyncSet(List.of(PARAM_DRIVER, PARAM_LOCKED));
            harness.setParameterLocked(PARAM_LOCKED, true);

            harness.callerParameter(PARAM_DRIVER).setValue(DRIVER_TARGET);
            harness.awaitBrokerSettled();
            assertEquals(INITIAL_VALUE, harness.rawValue(PARAM_LOCKED), FLOAT_TOLERANCE);

            harness.disableLockPlugin();

            final float nextDriverValue = 0.50f;
            harness.callerParameter(PARAM_DRIVER).setValue(nextDriverValue);
            Await.condition(
                    "unlocked target receives synchronized value after parameter-lock disable",
                    () -> Math.abs(harness.rawValue(PARAM_LOCKED) - nextDriverValue) < FLOAT_TOLERANCE,
                    ASYNC_TIMEOUT);
            assertEquals(nextDriverValue, harness.rawValue(PARAM_DRIVER), FLOAT_TOLERANCE);
        }
    }

    @Test
    void throwingThirdPartyObserverDoesNotBreakLockInterceptionOrSyncPropagation() throws Exception {
        try (InteractionHarness harness = new InteractionHarness(tempDir.resolve("fault-isolation"))) {
            harness.registerThrowingNeighborHooks();
            harness.enableLockPlugin();
            harness.enableSyncPlugin();
            harness.awaitLockReady();
            harness.configureSyncSet(List.of(PARAM_DRIVER, PARAM_LOCKED, PARAM_UNLOCKED));
            harness.setParameterLocked(PARAM_LOCKED, true);

            harness.callerParameter(PARAM_DRIVER).setValue(DRIVER_TARGET);
            Await.condition(
                    "unlocked partner updates despite throwing neighbor hook",
                    () -> Math.abs(harness.rawValue(PARAM_UNLOCKED) - EXPECTED_BODY_SYNC) < FLOAT_TOLERANCE,
                    ASYNC_TIMEOUT);
            harness.awaitBrokerSettled();

            assertEquals(DRIVER_TARGET, harness.rawValue(PARAM_DRIVER), FLOAT_TOLERANCE);
            assertEquals(INITIAL_VALUE, harness.rawValue(PARAM_LOCKED), FLOAT_TOLERANCE);
            assertEquals(EXPECTED_BODY_SYNC, harness.rawValue(PARAM_UNLOCKED), FLOAT_TOLERANCE);
        }
    }

    @Test
    void groupedAuthoringTransactionSweepDoesNotTriggerPostTransactionSyncDrift() throws Exception {
        try (InteractionHarness harness = new InteractionHarness(tempDir.resolve("grouped-tx-sweep"))) {
            harness.enableSyncPlugin();
            harness.configureSyncSet(List.of(PARAM_DRIVER, PARAM_UNLOCKED));

            final float independentPartnerBaseline = 2.5f;
            harness.setRawValue(PARAM_UNLOCKED, independentPartnerBaseline);
            final List<String> changedParameterIds = harness.recordObservedChanges();

            ParameterLifecycleCoordinator.runWithSuppressedCompletionObservers(() -> {
                final Parameter driver = harness.callerParameter(PARAM_DRIVER);
                final float driverBaseline = driver.getValue();
                driver.setValue(DRIVER_TARGET);
                driver.setValue(driverBaseline);
                return null;
            });
            harness.awaitBrokerSettled();

            assertEquals(INITIAL_VALUE, harness.rawValue(PARAM_DRIVER), FLOAT_TOLERANCE);
            assertEquals(independentPartnerBaseline, harness.rawValue(PARAM_UNLOCKED), FLOAT_TOLERANCE);
            assertEquals(List.of(), changedParameterIds);
        }
    }

    private void verifyLockFirstPrecedence(final boolean enableLockFirst) throws Exception {
        final String dirName = enableLockFirst ? "order-lock-then-sync" : "order-sync-then-lock";
        try (InteractionHarness harness = new InteractionHarness(tempDir.resolve(dirName))) {
            if (enableLockFirst) {
                harness.enableLockPlugin();
                harness.enableSyncPlugin();
            } else {
                harness.enableSyncPlugin();
                harness.enableLockPlugin();
            }
            harness.awaitLockReady();
            harness.configureSyncSet(List.of(PARAM_DRIVER, PARAM_LOCKED, PARAM_UNLOCKED));
            harness.setParameterLocked(PARAM_LOCKED, true);

            final List<String> changedParameterIds = harness.recordObservedChanges();
            harness.callerParameter(PARAM_DRIVER).setValue(DRIVER_TARGET);

            Await.condition(
                    "unlocked synchronized partner reaches proportional target",
                    () -> Math.abs(harness.rawValue(PARAM_UNLOCKED) - EXPECTED_BODY_SYNC) < FLOAT_TOLERANCE,
                    ASYNC_TIMEOUT);
            harness.awaitBrokerSettled();

            assertEquals(DRIVER_TARGET, harness.rawValue(PARAM_DRIVER), FLOAT_TOLERANCE);
            assertEquals(INITIAL_VALUE, harness.rawValue(PARAM_LOCKED), FLOAT_TOLERANCE);
            assertEquals(EXPECTED_BODY_SYNC, harness.rawValue(PARAM_UNLOCKED), FLOAT_TOLERANCE);
            assertTrue(
                    !changedParameterIds.contains(PARAM_LOCKED),
                    "locked parameter must never emit ParameterValueEvent.On: " + changedParameterIds);
        }
    }

    private static final class InteractionHarness implements AutoCloseable {
        private final Path root;
        private final RuntimeScheduler scheduler;
        private final RuntimeEventBroker eventBroker;
        private final ParameterLifecycleCoordinator parameterLifecycle;
        private final ParameterHookRegistry parameterHooks;
        private final SharedModel sharedModel;
        private final HostSnapshotSource snapshotSource;
        private final CubismFacade callerFacade;
        private final PackagedPluginJarSupport.LoadedJarPlugin lockJar;
        private final PackagedPluginJarSupport.LoadedJarPlugin syncJar;
        private ActiveGeneration lockGeneration;
        private ActiveGeneration syncGeneration;

        private InteractionHarness(final Path root) throws Exception {
            this.root = root;
            this.scheduler = RuntimeTestKit.scheduler(FIXED_CLOCK, 2, 32);
            this.eventBroker = new RuntimeEventBroker(scheduler);
            this.parameterLifecycle = new ParameterLifecycleCoordinator();
            this.parameterLifecycle.attachEventBroker(eventBroker);
            this.parameterHooks = new ParameterHookRegistry(parameterLifecycle);
            this.sharedModel = new SharedModel(MODEL_GUID);
            this.sharedModel.add(PARAM_DRIVER, MIN_VALUE, MAX_VALUE, INITIAL_VALUE);
            this.sharedModel.add(PARAM_LOCKED, MIN_VALUE, MAX_VALUE, INITIAL_VALUE);
            this.sharedModel.add(PARAM_UNLOCKED, BODY_MIN_VALUE, BODY_MAX_VALUE, INITIAL_VALUE);
            final HostSnapshotSource.HostModel hostModel = new HostSnapshotSource.HostModel(
                    MODEL_GUID,
                    "TestModel",
                    List.of(
                            new HostSnapshotSource.HostParameter(
                                    PARAM_DRIVER, PARAM_DRIVER, 0.0, 0.0, 0.0, 1.0, true, true),
                            new HostSnapshotSource.HostParameter(
                                    PARAM_LOCKED, PARAM_LOCKED, 0.0, 0.0, 0.0, 1.0, true, true),
                            new HostSnapshotSource.HostParameter(
                                    PARAM_UNLOCKED, PARAM_UNLOCKED, 0.0, 0.0, -10.0, 10.0, true, true)),
                    List.of(),
                    List.of());
            this.snapshotSource = RecordingHostSnapshotSource.builder()
                    .document(new HostSnapshotSource.HostDocument(
                            DOCUMENT_ID, DOCUMENT_NAME, DOCUMENT_NAME, Optional.empty(), Optional.of(hostModel)))
                    .model(hostModel)
                    .hostPresent(true)
                    .build();
            this.callerFacade = createFacade(
                    CALLER_PLUGIN_ID,
                    List.of(
                            Stubs.permission(CubismFacadeImpl.MODEL_READ_PERMISSION, "application", "test"),
                            Stubs.permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION, "application", "test")),
                    () -> true);
            this.lockJar = PackagedPluginJarSupport.loadFromSystemProperty(
                    PackagedPluginJarSupport.PARAMETER_LOCK_BUILD_DIR_PROP);
            this.syncJar = PackagedPluginJarSupport.loadFromSystemProperty(
                    PackagedPluginJarSupport.PARAMETER_SYNC_BUILD_DIR_PROP);
        }

        void enableLockPlugin() throws Exception {
            lockGeneration = startGeneration(lockJar, root.resolve("lock"));
        }

        void enableSyncPlugin() throws Exception {
            syncGeneration = startGeneration(syncJar, root.resolve("sync"));
        }

        void disableLockPlugin() throws Exception {
            if (lockGeneration != null) {
                lockGeneration.disableAndClose(parameterHooks);
                lockGeneration = null;
            }
        }

        void awaitLockReady() throws Exception {
            final Object entrypoint = lockJar.primaryEntrypoint();
            final Method lockStateMethod = entrypoint.getClass().getDeclaredMethod("lockState");
            lockStateMethod.setAccessible(true);
            Await.condition(
                    "parameter-lock initial model load completed",
                    () -> {
                        try {
                            final Object state = lockStateMethod.invoke(entrypoint);
                            final Method hasLoaded = state.getClass().getDeclaredMethod("hasLoaded", String.class);
                            hasLoaded.setAccessible(true);
                            return Boolean.TRUE.equals(hasLoaded.invoke(state, MODEL_GUID));
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException(failure);
                        }
                    },
                    ASYNC_TIMEOUT);
        }

        void setParameterLocked(final String parameterId, final boolean locked) throws Exception {
            final Object entrypoint = lockJar.primaryEntrypoint();
            final Method lockStateMethod = entrypoint.getClass().getDeclaredMethod("lockState");
            lockStateMethod.setAccessible(true);
            final Object state = lockStateMethod.invoke(entrypoint);
            final Method setLocked =
                    state.getClass().getDeclaredMethod("setLocked", String.class, String.class, boolean.class);
            setLocked.setAccessible(true);
            setLocked.invoke(state, MODEL_GUID, parameterId, locked);
        }

        void configureSyncSet(final List<String> parameterIds) throws Exception {
            final Object entrypoint = syncJar.primaryEntrypoint();
            final Method ensureLoaded = entrypoint.getClass().getMethod("ensureLoaded", String.class);
            final CompletableFuture<?> future = (CompletableFuture<?>) ensureLoaded.invoke(entrypoint, MODEL_GUID);
            future.join();
            final Method getRuleSet = entrypoint.getClass().getMethod("getRuleSet", String.class);
            final Object ruleSet = getRuleSet.invoke(entrypoint, MODEL_GUID);
            final Method setSynchronized = ruleSet.getClass().getMethod("setSynchronized", String.class, boolean.class);
            for (String id : parameterIds) {
                setSynchronized.invoke(ruleSet, id, true);
            }
        }

        List<String> recordObservedChanges() {
            final List<String> observed = new CopyOnWriteArrayList<>();
            final RuntimeEventBroker.Owner observerOwner = eventBroker.admit("dev.turboism.test.observer");
            observerOwner.beginInitializing();
            observerOwner.beginEnabling();
            observerOwner.activate();
            eventBroker.subscribeAdapter(
                    observerOwner.key(),
                    ParameterValueEvent.On.class,
                    0,
                    0,
                    event -> observed.add(event.parameter().id().value()));
            return observed;
        }

        void registerThrowingNeighborHooks() {
            final RuntimeEventBroker.Owner faultyOwner = eventBroker.admit("dev.turboism.test.faulty");
            faultyOwner.beginInitializing();
            faultyOwner.beginEnabling();
            faultyOwner.activate();
            eventBroker.subscribeAdapter(faultyOwner.key(), ParameterValueEvent.Before.class, 0, 0, event -> {
                throw new IllegalStateException("simulated faulty before-hook");
            });
            eventBroker.subscribeAdapter(faultyOwner.key(), ParameterValueEvent.On.class, 0, 1, event -> {
                throw new IllegalStateException("simulated faulty on-observer");
            });
        }

        Parameter callerParameter(final String parameterId) {
            return callerFacade.model().active().parameters().find(new ParameterId(parameterId));
        }

        float rawValue(final String parameterId) {
            return sharedModel.get(parameterId).value;
        }

        void setRawValue(final String parameterId, final float value) {
            sharedModel.get(parameterId).value = value;
        }

        void awaitBrokerSettled() throws InterruptedException {
            parameterLifecycle.awaitIdle();
            Thread.sleep(80L);
            parameterLifecycle.awaitIdle();
        }

        private ActiveGeneration startGeneration(
                final PackagedPluginJarSupport.LoadedJarPlugin jarPlugin, final Path pluginHome) throws Exception {
            final PluginDescriptor descriptor = jarPlugin.descriptor();
            final DisposableScope scope = new DisposableScope();
            final AtomicBoolean active = new AtomicBoolean(true);
            final RuntimeEventBroker.Owner owner = eventBroker.admit(descriptor.id());
            final List<PluginPermission> permissions = descriptor.permissions().stream()
                    .map(ref ->
                            Stubs.permission(ref.id(), ref.scope(), ref.reason().orElse("")))
                    .toList();
            final CubismFacade facade = createFacade(descriptor.id(), permissions, active::get);
            final UiScheduler uiScheduler = immediateDispatchNonPollingUiScheduler();
            final CorePluginContext.Dependencies dependencies = new CorePluginContext.Dependencies(
                    descriptor,
                    Stubs.noopLogger(),
                    FakePluginPaths.of(pluginHome),
                    uiScheduler,
                    scheduler,
                    Stubs.diagnostics(FIXED_CLOCK.instant()),
                    scope,
                    snapshotSource,
                    dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource.EMPTY,
                    dev.turboism.ui.UiHostStateSource.DEFAULT,
                    ignored -> {},
                    FIXED_CLOCK,
                    dev.turboism.failure.RuntimeFailureSink.noop(),
                    eventBroker,
                    owner.key(),
                    jarPlugin.classLoader());
            final PluginContextEnvironment env = PluginContextEnvironment.safeMode()
                    .localization(PackagedPluginJarSupport.passthroughLocalization())
                    .pluginStorage(new InMemoryPluginStorage())
                    .generationAdmission(active::get)
                    .build();
            final CorePluginContext baseContext = new CorePluginContext(dependencies, env);
            final PluginContext pluginContext = wrapWithCubismFacade(baseContext, facade);

            owner.registerAnnotated(jarPlugin.subscribers(), jarPlugin.entrypoints());
            owner.beginInitializing();
            for (TurboismPlugin entrypoint : jarPlugin.entrypoints()) {
                entrypoint.init(pluginContext);
            }
            owner.beginEnabling();
            for (TurboismPlugin entrypoint : jarPlugin.entrypoints()) {
                entrypoint.enable();
            }
            owner.activate();
            parameterHooks.register(
                    descriptor, jarPlugin.entrypoints(), Stubs.noopLogger(), scope, eventBroker, owner.key());
            return new ActiveGeneration(jarPlugin.entrypoints(), scope, owner, active);
        }

        private static PluginContext wrapWithCubismFacade(final PluginContext delegate, final CubismFacade facade) {
            return (PluginContext) Proxy.newProxyInstance(
                    PluginContext.class.getClassLoader(),
                    new Class<?>[] {PluginContext.class},
                    (proxy, method, args) -> {
                        if ("cubism".equals(method.getName()) && method.getParameterCount() == 0) {
                            return facade;
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private static UiScheduler immediateDispatchNonPollingUiScheduler() {
            return new UiScheduler() {
                @Override
                public Registration runOnUiThread(final Runnable work) {
                    work.run();
                    return () -> {};
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                    return () -> {};
                }
            };
        }

        private CubismFacade createFacade(
                final String pluginId, final List<PluginPermission> permissions, final BooleanSupplier activeScope) {
            final CubismPermissionGate gate =
                    new CubismPermissionGate(pluginId, permissions, ignored -> {}, FIXED_CLOCK);
            final CubismModelAccess access = new CubismModelAccess() {
                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public CubismModel active() {
                    return sharedModel;
                }
            };
            return new CubismFacadeImpl(
                    snapshotSource, gate, access, parameterLifecycle, new PartLifecycleCoordinator(), activeScope);
        }

        @Override
        public void close() throws Exception {
            if (lockGeneration != null) {
                lockGeneration.shutdown(parameterHooks);
            }
            if (syncGeneration != null) {
                syncGeneration.shutdown(parameterHooks);
            }
            parameterLifecycle.close();
            scheduler.shutdown();
            lockJar.close();
            syncJar.close();
        }
    }

    private record ActiveGeneration(
            List<TurboismPlugin> entrypoints,
            DisposableScope scope,
            RuntimeEventBroker.Owner owner,
            AtomicBoolean active) {

        void disableAndClose(final ParameterHookRegistry hooks) throws Exception {
            active.set(false);
            owner.beginClosing();
            hooks.unregister(owner.key());
            for (TurboismPlugin entrypoint : entrypoints) {
                entrypoint.disable();
            }
            scope.close();
        }

        void shutdown(final ParameterHookRegistry hooks) throws Exception {
            disableAndClose(hooks);
            for (TurboismPlugin entrypoint : entrypoints) {
                entrypoint.shutdown();
            }
        }
    }

    private static final class SharedModel implements CubismModel {
        private final ModelId id;
        private final Map<String, RawParameter> parameters = new LinkedHashMap<>();

        private SharedModel(final String guid) {
            this.id = new ModelId(guid);
        }

        void add(final String id, final float min, final float max, final float initial) {
            parameters.put(id, new RawParameter(id, min, max, initial));
        }

        RawParameter get(final String id) {
            return parameters.get(id);
        }

        @Override
        public ModelId id() {
            return id;
        }

        @Override
        public Parameters parameters() {
            return new Parameters() {
                @Override
                public List<Parameter> all() {
                    return List.copyOf(parameters.values());
                }

                @Override
                public Parameter find(final ParameterId parameterId) {
                    return parameters.get(parameterId.value());
                }
            };
        }

        @Override
        public ParameterGroups parameterGroups() {
            return null;
        }

        @Override
        public Parts parts() {
            return null;
        }

        @Override
        public Drawables drawables() {
            return null;
        }

        @Override
        public Deformers deformers() {
            return null;
        }

        @Override
        public Glues glues() {
            return null;
        }

        @Override
        public void update() {}
    }

    private static final class RawParameter implements Parameter {
        private final ParameterId id;
        private final float min;
        private final float max;
        private volatile float value;

        private RawParameter(final String id, final float min, final float max, final float initial) {
            this.id = new ParameterId(id);
            this.min = min;
            this.max = max;
            this.value = initial;
        }

        @Override
        public ParameterId id() {
            return id;
        }

        @Override
        public Optional<String> name() {
            return Optional.of(id.value());
        }

        @Override
        public float getValue() {
            return value;
        }

        @Override
        public float getMinimumValue() {
            return min;
        }

        @Override
        public float getMaximumValue() {
            return max;
        }

        @Override
        public float getDefaultValue() {
            return INITIAL_VALUE;
        }

        @Override
        public void setValue(final float nextValue) {
            this.value = Math.max(min, Math.min(max, nextValue));
        }
    }
}

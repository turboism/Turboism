package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.export.ExportSettingsDecisionCallback;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.DisposableScope;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeExportSettingsContributionRegistryTest {

    private static final String OPTION = "option-1";
    private static final String DOCUMENT = "document-1";
    private static final ModelId MODEL = new ModelId("model-1");

    @Test
    void duplicateOptionIdFailsAndKeepsTheFirstContribution() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        final AtomicReference<Boolean> firstCalled = new AtomicReference<>(Boolean.FALSE);
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
            firstCalled.set(Boolean.TRUE);
            return ExportSettingsDecision.proceedUnchanged();
        }));
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.contribute(contribution(OPTION, (selected, documentId, modelId) ->
                ExportSettingsDecision.proceedUnchanged()))
        );
        final ExportSettingsDecision decision = invoke(registry, true);
        assertEquals(ExportSettingsDecision.Outcome.REJECT, decision.outcome());
        assertTrue(firstCalled.get(), "first contribution must remain registered");
    }

    @Test
    void unselectedInvocationBypassesCallbacksAndProceedsUnchanged() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
            throw new AssertionError("unselected invocation must bypass callbacks");
        }));
        final ExportSettingsDecision decision = invoke(registry, false);
        assertEquals(ExportSettingsDecision.Outcome.PROCEED_UNCHANGED, decision.outcome());
        assertEquals("", decision.messageKey());
    }

    @Test
    void selectedCallbackRejectionPropagatesWithItsBoundedIdentity() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) ->
            ExportSettingsDecision.reject("plugin.export.rejected")));
        final ExportSettingsDecision decision = invoke(registry, true);
        assertEquals(ExportSettingsDecision.Outcome.REJECT, decision.outcome());
        assertEquals("plugin.export.rejected", decision.messageKey());
    }

    @Test
    void selectedUnexpectedProceedIsRejectedByTheRegistryRatherThanImplyingExecution() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) ->
            ExportSettingsDecision.proceedUnchanged()));
        final ExportSettingsDecision decision = invoke(registry, true);
        assertEquals(ExportSettingsDecision.Outcome.REJECT, decision.outcome());
        assertEquals(RuntimeExportSettingsContributionRegistry.PROCEED_UNEXPECTED_KEY,
            decision.messageKey());
    }

    @Test
    void callbackReceivesSelectedStateAndTurboismOwnedIdentities() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        final AtomicReference<Object[]> captured = new AtomicReference<>();
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
            captured.set(new Object[]{selected, documentId, modelId});
            return ExportSettingsDecision.reject("plugin.export.rejected");
        }));
        invoke(registry, true);
        assertArrayEquals2(captured.get());
    }

    @Test
    void staleGenerationIsRejectedAfterScopeCleanup() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) ->
            ExportSettingsDecision.proceedUnchanged()));
        final long token = registry.generation();
        registry.close();
        final ExportSettingsDecision decision = invoke(registry, token, true);
        assertEquals(ExportSettingsDecision.Outcome.REJECT, decision.outcome());
        assertEquals(RuntimeExportSettingsContributionRegistry.STALE_GENERATION_KEY,
            decision.messageKey());
    }

    @Test
    void wrongGenerationTokenIsRejectedWithoutClosing() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) ->
            ExportSettingsDecision.proceedUnchanged()));
        final ExportSettingsDecision decision = invoke(registry, registry.generation() + 1, true);
        assertEquals(ExportSettingsDecision.Outcome.REJECT, decision.outcome());
        assertEquals(RuntimeExportSettingsContributionRegistry.STALE_GENERATION_KEY,
            decision.messageKey());
    }

    @Test
    void familyRecursionIsRejectedAndTheGuardIsClearedAfterwards() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        final AtomicReference<ExportSettingsDecision> nested = new AtomicReference<>();
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
            nested.set(registry.invoke(
                OPTION, true, DOCUMENT, MODEL, registry.generation()));
            return nested.get();
        }));
        final ExportSettingsDecision decision = invoke(registry, true);
        assertEquals(ExportSettingsDecision.Outcome.REJECT, decision.outcome());
        assertEquals(RuntimeExportSettingsContributionRegistry.RECURSION_KEY,
            decision.messageKey());
        assertEquals(RuntimeExportSettingsContributionRegistry.RECURSION_KEY,
            nested.get().messageKey());
        // The guard must be cleared: a later invocation works normally.
        final ExportSettingsDecision after = invoke(registry, false);
        assertEquals(ExportSettingsDecision.Outcome.PROCEED_UNCHANGED, after.outcome());
    }

    @Test
    void callbackMayCloseItsRegistrationOrRegistryWithoutDeadlockingLifecycleLock() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            final RuntimeExportSettingsContributionRegistry registrationRegistry = registry();
            final AtomicReference<Registration> registration = new AtomicReference<>();
            registration.set(registrationRegistry.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
                registration.get().close();
                return ExportSettingsDecision.reject("callback.rejected");
            })));
            assertEquals(
                RuntimeExportSettingsContributionRegistry.STALE_GENERATION_KEY,
                invoke(registrationRegistry, true).messageKey()
            );

            final RuntimeExportSettingsContributionRegistry closedRegistry = registry();
            closedRegistry.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
                closedRegistry.close();
                return ExportSettingsDecision.reject("callback.rejected");
            }));
            assertEquals(
                RuntimeExportSettingsContributionRegistry.STALE_GENERATION_KEY,
                invoke(closedRegistry, true).messageKey()
            );
        });
    }

    @Test
    void edtCallbackCannotReleaseLoaderDuringSynchronousSelfUnload() throws Exception {
        final DisposableScope scope = new DisposableScope();
        final RuntimeExportSettingsContributionRegistry registry = registry();
        scope.register(registry);
        scope.register(registry.scopeCloseGuard());
        final AtomicReference<Throwable> scopeFailure = new AtomicReference<>();
        final AtomicReference<ExportSettingsDecision> decision = new AtomicReference<>();
        final AtomicBoolean callbackOnEdt = new AtomicBoolean();
        final AtomicBoolean loaderClosed = new AtomicBoolean();

        registry.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
            callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
            try {
                scope.close();
                loaderClosed.set(true);
            } catch (Throwable failure) {
                scopeFailure.set(failure);
            }
            return ExportSettingsDecision.proceedUnchanged();
        }));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
            SwingUtilities.invokeAndWait(() -> decision.set(invoke(registry, true)))
        );

        assertTrue(callbackOnEdt.get());
        assertTrue(scopeFailure.get() instanceof IllegalStateException);
        assertEquals(
            RuntimeExportSettingsContributionRegistry.CALLBACK_ACTIVE_DURING_SCOPE_CLOSE_KEY,
            scopeFailure.get().getMessage()
        );
        assertFalse(loaderClosed.get(), "a failed scope close must retain the plugin loader");
        assertEquals(RuntimeExportSettingsContributionRegistry.STALE_GENERATION_KEY,
            decision.get().messageKey());
        final RuntimeExportSettingsContributionRegistry.LifecycleSnapshot snapshot =
            registry.lifecycleSnapshot();
        assertTrue(snapshot.closed());
        assertEquals(0, snapshot.activeCallbacks());
        assertTrue(snapshot.closeReturnedReentrantly());
        assertFalse(snapshot.closeDrainTimedOut());
        assertFalse(snapshot.closeDrainInterrupted());
        assertTimeoutPreemptively(Duration.ofMillis(200), registry::close);
    }

    @Test
    void crossThreadScopeCloseTimesOutAndKeepsLoaderRetained() throws Exception {
        final DisposableScope scope = new DisposableScope();
        final RuntimeExportSettingsContributionRegistry registry = registry();
        scope.register(registry);
        scope.register(registry.scopeCloseGuard());
        final CountDownLatch callbackEntered = new CountDownLatch(1);
        final CountDownLatch releaseCallback = new CountDownLatch(1);
        final AtomicReference<ExportSettingsDecision> callbackDecision = new AtomicReference<>();
        final AtomicBoolean loaderClosed = new AtomicBoolean();
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
            callbackEntered.countDown();
            try {
                if (!releaseCallback.await(10, TimeUnit.SECONDS)) {
                    return ExportSettingsDecision.reject("callback.timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ExportSettingsDecision.reject("callback.interrupted");
            }
            return ExportSettingsDecision.reject("callback.rejected");
        }));
        final Thread callbackThread = new Thread(
            () -> callbackDecision.set(invoke(registry, true)),
            "export-settings-callback"
        );
        callbackThread.start();

        try {
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            final AtomicReference<Exception> scopeFailure = new AtomicReference<>();
            assertTimeout(Duration.ofSeconds(6), () -> {
                try {
                    scope.close();
                    loaderClosed.set(true);
                } catch (Exception failure) {
                    scopeFailure.set(failure);
                }
            });
            assertFalse(loaderClosed.get(), "a timeout must retain the plugin loader");
            assertEquals(RuntimeExportSettingsContributionRegistry.CALLBACK_DRAIN_TIMEOUT_KEY,
                scopeFailure.get().getMessage());
            final RuntimeExportSettingsContributionRegistry.LifecycleSnapshot timedOut =
                registry.lifecycleSnapshot();
            assertTrue(timedOut.closed());
            assertEquals(1, timedOut.activeCallbacks());
            assertFalse(timedOut.closeReturnedReentrantly());
            assertTrue(timedOut.closeDrainTimedOut());
            assertFalse(timedOut.closeDrainInterrupted());
            assertTimeoutPreemptively(Duration.ofMillis(200), registry::close);
        } finally {
            releaseCallback.countDown();
            callbackThread.join(2_000);
        }

        assertFalse(callbackThread.isAlive());
        assertEquals(RuntimeExportSettingsContributionRegistry.STALE_GENERATION_KEY,
            callbackDecision.get().messageKey());
        assertEquals(0, registry.lifecycleSnapshot().activeCallbacks());
        assertTrue(registry.lifecycleSnapshot().closeDrainTimedOut());
    }

    @Test
    void everyCallbackThrowableIsContainedAsTypedRejection() {
        final RuntimeExportSettingsContributionRegistry runtime = registry();
        runtime.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
            throw new RuntimeException("boom");
        }));
        assertEquals(RuntimeExportSettingsContributionRegistry.CALLBACK_FAILED_KEY,
            invoke(runtime, true).messageKey());

        final RuntimeExportSettingsContributionRegistry fatal = registry();
        fatal.contribute(contribution(OPTION, (selected, documentId, modelId) -> {
            throw new AssertionError("boom");
        }));
        assertEquals(RuntimeExportSettingsContributionRegistry.CALLBACK_FAILED_KEY,
            invoke(fatal, true).messageKey());

        final RuntimeExportSettingsContributionRegistry nullResult = registry();
        nullResult.contribute(contribution(OPTION, (selected, documentId, modelId) -> null));
        assertEquals(RuntimeExportSettingsContributionRegistry.CALLBACK_FAILED_KEY,
            invoke(nullResult, true).messageKey());
    }

    @Test
    void registrationCloseRemovesTheContributionAndIsIdempotent() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        final Registration registration = registry.contribute(contribution(OPTION,
            (selected, documentId, modelId) -> ExportSettingsDecision.proceedUnchanged()));
        registration.close();
        registration.close();
        final ExportSettingsDecision decision = invoke(registry, true);
        assertEquals(ExportSettingsDecision.Outcome.REJECT, decision.outcome());
        assertEquals(RuntimeExportSettingsContributionRegistry.UNKNOWN_OPTION_KEY,
            decision.messageKey());
    }

    @Test
    void selectedUnknownOptionFailsClosed() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        final ExportSettingsDecision decision = registry.invoke(
            "option-unknown", true, DOCUMENT, MODEL, registry.generation());
        assertEquals(ExportSettingsDecision.Outcome.REJECT, decision.outcome());
        assertEquals(RuntimeExportSettingsContributionRegistry.UNKNOWN_OPTION_KEY,
            decision.messageKey());
    }

    @Test
    void scopeCleanupRemovesAllContributionsAndRejectsFurtherUse() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        registry.contribute(contribution(OPTION, (selected, documentId, modelId) ->
            ExportSettingsDecision.proceedUnchanged()));
        registry.close();
        assertThrows(IllegalStateException.class, () -> registry.contribute(
            contribution("option-2", (selected, documentId, modelId) ->
                ExportSettingsDecision.proceedUnchanged())));
        assertEquals(RuntimeExportSettingsContributionRegistry.STALE_GENERATION_KEY,
            invoke(registry, true).messageKey());
    }

    @Test
    void registryValidatesIdentityAndInvocationArguments() {
        assertThrows(IllegalArgumentException.class, () -> registry(" ", 0L));
        assertThrows(NullPointerException.class, () -> registry(null, 0L));
        final RuntimeExportSettingsContributionRegistry registry = registry();
        assertThrows(NullPointerException.class, () -> registry.contribute(null));
        assertThrows(NullPointerException.class, () -> registry.invoke(
            null, true, DOCUMENT, MODEL, registry.generation()));
        assertThrows(NullPointerException.class, () -> registry.invoke(
            OPTION, true, null, MODEL, registry.generation()));
        assertThrows(NullPointerException.class, () -> registry.invoke(
            OPTION, true, DOCUMENT, null, registry.generation()));
    }

    @Test
    void registryImplementsThePreviewServiceContract() {
        final RuntimeExportSettingsContributionRegistry registry = registry();
        assertTrue(registry instanceof ExportSettingsContributionService);
        assertFalse(ExportSettingsContributionService.unavailable() instanceof
            RuntimeExportSettingsContributionRegistry);
    }

    private static RuntimeExportSettingsContributionRegistry registry() {
        return registry("plugin-1", 7L);
    }

    private static RuntimeExportSettingsContributionRegistry registry(
        final String pluginId,
        final long generation
    ) {
        return new RuntimeExportSettingsContributionRegistry(pluginId, generation);
    }

    private static ExportSettingsContribution contribution(
        final String optionId,
        final ExportSettingsDecisionCallback callback
    ) {
        return new ExportSettingsContribution(optionId, "label." + optionId, callback);
    }

    private static ExportSettingsDecision invoke(
        final RuntimeExportSettingsContributionRegistry registry,
        final boolean selected
    ) {
        return invoke(registry, registry.generation(), selected);
    }

    private static ExportSettingsDecision invoke(
        final RuntimeExportSettingsContributionRegistry registry,
        final long expectedGeneration,
        final boolean selected
    ) {
        return registry.invoke(OPTION, selected, DOCUMENT, MODEL, expectedGeneration);
    }

    private static void assertArrayEquals2(final Object[] captured) {
        assertEquals(Boolean.TRUE, captured[0]);
        assertEquals(DOCUMENT, captured[1]);
        assertEquals(MODEL, captured[2]);
    }
}

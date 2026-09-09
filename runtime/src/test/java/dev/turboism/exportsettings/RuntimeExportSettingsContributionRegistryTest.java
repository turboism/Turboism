package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.export.ExportSettingsDecisionCallback;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;
import java.time.Duration;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

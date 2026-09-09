package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExportSettingsContributionServiceContractTest {

    @Test
    void pluginContextDefaultsToTheTypedUnavailableExportSettingsSingleton() throws Exception {
        final Method accessor = Arrays.stream(PluginContext.class.getMethods())
            .filter(method -> method.getName().equals("exportSettings"))
            .findFirst()
            .orElseThrow();

        assertTrue(accessor.isDefault());
        assertEquals(ExportSettingsContributionService.class, accessor.getReturnType());
        assertEquals(0, accessor.getParameterCount());

        final PluginContext context = (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[]{PluginContext.class},
            (proxy, method, args) -> method.isDefault() ? invokeDefault(proxy, method, args) : null
        );
        assertSame(ExportSettingsContributionService.unavailable(), context.exportSettings());
    }

    @Test
    void serviceSurfaceIsPreviewBoundedAndSafeModeFailsClosed() throws Exception {
        assertEquals(
            List.of("contribute", "unavailable"),
            Arrays.stream(ExportSettingsContributionService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList()
        );
        assertEquals(
            Registration.class,
            ExportSettingsContributionService.class
                .getDeclaredMethod("contribute", ExportSettingsContribution.class)
                .getReturnType()
        );

        // Safe mode refuses contribution without touching a registry.
        final ExportSettingsContributionService safe =
            ExportSettingsContributionService.unavailable();
        assertSame(ExportSettingsContributionService.Unavailable.INSTANCE, safe);
        assertThrows(
            UnsupportedOperationException.class,
            () -> safe.contribute(contribution("option-1", "label.key", (s, d, m) ->
                ExportSettingsDecision.proceedUnchanged()))
        );
    }

    @Test
    void decisionResultHasExactlyTwoOutcomesAndCarriesOneBoundedRejectionIdentity() {
        assertArrayEquals(
            new ExportSettingsDecision.Outcome[]{
                ExportSettingsDecision.Outcome.PROCEED_UNCHANGED,
                ExportSettingsDecision.Outcome.REJECT
            },
            ExportSettingsDecision.Outcome.values()
        );

        final ExportSettingsDecision proceed = ExportSettingsDecision.proceedUnchanged();
        assertEquals(ExportSettingsDecision.Outcome.PROCEED_UNCHANGED, proceed.outcome());
        assertEquals("", proceed.messageKey());

        final ExportSettingsDecision rejected = ExportSettingsDecision.reject("export.rejected");
        assertEquals(ExportSettingsDecision.Outcome.REJECT, rejected.outcome());
        assertEquals("export.rejected", rejected.messageKey());

        assertThrows(NullPointerException.class, () -> ExportSettingsDecision.reject(null));
        assertThrows(IllegalArgumentException.class, () -> ExportSettingsDecision.reject(""));
        assertThrows(IllegalArgumentException.class, () -> ExportSettingsDecision.reject(" "));
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExportSettingsDecision(
                ExportSettingsDecision.Outcome.PROCEED_UNCHANGED, "unexpected.key")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExportSettingsDecision(ExportSettingsDecision.Outcome.REJECT, "")
        );
        assertThrows(
            NullPointerException.class,
            () -> new ExportSettingsDecision(null, "")
        );
    }

    @Test
    void contributionIsImmutableDefaultOffAndValidated() throws Exception {
        // Default-off by construction: the record exposes exactly the option identity,
        // the label localization key, and the typed callback; no default/selection state.
        assertEquals(
            List.of("optionId", "labelKey", "callback"),
            Arrays.stream(ExportSettingsContribution.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList()
        );

        final ExportSettingsDecisionCallback callback =
            (selected, documentId, modelId) -> ExportSettingsDecision.proceedUnchanged();
        final ExportSettingsContribution contribution = contribution("option-1", "label.key", callback);
        assertEquals("option-1", contribution.optionId());
        assertEquals("label.key", contribution.labelKey());
        assertSame(callback, contribution.callback());

        assertThrows(IllegalArgumentException.class, () -> contribution("", "label.key", callback));
        assertThrows(IllegalArgumentException.class, () -> contribution(" ", "label.key", callback));
        assertThrows(IllegalArgumentException.class, () -> contribution("option-1", "", callback));
        assertThrows(NullPointerException.class, () -> contribution("option-1", "label.key", null));
    }

    @Test
    void callbackIsAFunctionalInterfaceUsingOnlySdkIdentityTypes() throws Exception {
        assertTrue(ExportSettingsDecisionCallback.class
            .isAnnotationPresent(java.lang.FunctionalInterface.class));
        final Method decide = ExportSettingsDecisionCallback.class
            .getDeclaredMethod("decide", boolean.class, String.class, ModelId.class);
        assertEquals(ExportSettingsDecision.class, decide.getReturnType());
        assertEquals(3, decide.getParameterCount());
    }

    @Test
    void publicSurfaceExposesNoHostOrRuntimeTypes() {
        final Set<String> forbidden = Set.of(
            Path.class.getName(),
            "java.io.File",
            "java.awt.",
            "javax.swing.",
            "com.live2d.",
            "dev.turboism.adapter.",
            "dev.turboism.core."
        );
        for (Class<?> type : Set.of(
            ExportSettingsContributionService.class,
            ExportSettingsContributionService.Unavailable.class,
            ExportSettingsContribution.class,
            ExportSettingsDecision.class,
            ExportSettingsDecision.Outcome.class,
            ExportSettingsDecisionCallback.class
        )) {
            for (Method method : type.getMethods()) {
                for (String prefix : forbidden) {
                    assertFalse(
                        method.toGenericString().contains(prefix),
                        method.toGenericString()
                    );
                }
            }
        }
    }

    private static ExportSettingsContribution contribution(
        final String optionId,
        final String labelKey,
        final ExportSettingsDecisionCallback callback
    ) {
        return new ExportSettingsContribution(optionId, labelKey, callback);
    }

    private static Object invokeDefault(
        final Object proxy,
        final Method method,
        final Object[] args
    ) throws Throwable {
        final java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles
            .privateLookupIn(method.getDeclaringClass(), java.lang.invoke.MethodHandles.lookup());
        return lookup.unreflectSpecial(method, method.getDeclaringClass())
            .bindTo(proxy)
            .invokeWithArguments(args == null ? new Object[0] : args);
    }
}

package dev.turboism.plugin.protectedexport;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;
import java.util.Optional;

/**
 * Registers the default-off protected-export option.
 *
 * <p>The option is deliberately a narrow SDK-only seam. An unchecked export is a native
 * passthrough. A checked export vetoes the plain native flow and performs a read-only,
 * identity-bound planner diagnostic; when the runtime authority has armed the protected
 * orchestrator for this plugin's option, that veto is what hands control to the
 * orchestrated copy/flatten/obfuscate/validate/publish pipeline. This class never names
 * a path, mutates a model, or performs an export.</p>
 */
public final class ProtectedExportPlugin implements TurboismPlugin {

    static final String OPTION_ID = "protected-export";
    static final String OPTION_LABEL_KEY = "protected-export.option";
    static final String UNAVAILABLE_KEY = "protected-export.unavailable";

    private volatile PluginContext context;
    private volatile Registration registration;
    private volatile boolean enabled;
    private volatile Object registrationToken;

    @Override
    public synchronized void init(final PluginContext context) {
        Objects.requireNonNull(context, "context");
        if (registration != null || enabled) {
            disable();
        }
        this.context = context;
        this.registrationToken = null;
        this.enabled = false;
    }

    @Override
    public synchronized void enable() {
        final PluginContext activeContext = requireContext();
        if (registration != null || enabled) {
            return;
        }
        final Object callbackToken = new Object();
        try {
            final Registration candidate = Objects.requireNonNull(
                activeContext.exportSettings().contribute(
                    new ExportSettingsContribution(
                        OPTION_ID,
                        OPTION_LABEL_KEY,
                        (selected, documentId, modelId) ->
                            decide(callbackToken, selected, documentId, modelId)
                    )
                ),
                "export settings registration"
            );
            registrationToken = callbackToken;
            registration = candidate;
            enabled = true;
        } catch (RuntimeException unavailable) {
            registrationToken = null;
            registration = null;
            enabled = false;
            warnSafely(
                activeContext,
                "Protected export option registration is unavailable; protected export remains unavailable."
            );
        }
    }

    @Override
    public synchronized void disable() {
        final Registration activeRegistration = registration;
        registrationToken = null;
        registration = null;
        enabled = false;
        if (activeRegistration != null) {
            try {
                activeRegistration.close();
            } catch (RuntimeException failure) {
                warnSafely(context, "Protected export option registration teardown failed safely.");
            }
        }
    }

    @Override
    public synchronized void shutdown() {
        try {
            disable();
        } finally {
            registrationToken = null;
            registration = null;
            enabled = false;
            context = null;
        }
    }

    boolean enabled() {
        return enabled;
    }

    private ExportSettingsDecision decide(
        final Object callbackToken,
        final boolean selected,
        final String documentId,
        final ModelId modelId
    ) {
        if (!selected) {
            return ExportSettingsDecision.proceedUnchanged();
        }

        final PluginContext activeContext = context;
        if (!enabled || registration == null || callbackToken != registrationToken
            || activeContext == null) {
            return unavailable();
        }

        try {
            final CubismModel activeModel = currentModel(activeContext, documentId, modelId);
            if (activeModel == null) {
                return unavailable();
            }

            final ProtectedExportPlan plan = new ProtectedExportPlanner().plan(activeModel);
            if (!plan.unresolvedConditions().isEmpty()) {
                warnSafely(
                    activeContext,
                    "Protected export preflight has unresolved conditions; protected export remains unavailable."
                );
            }
        } catch (Throwable unavailable) {
            warnSafely(
                activeContext,
                "Protected export preflight is unavailable; protected export remains unavailable."
            );
        }

        // A read-only plan is descriptive evidence, never execution authority.
        return unavailable();
    }

    private static CubismModel currentModel(
        final PluginContext context,
        final String callbackDocumentId,
        final ModelId callbackModelId
    ) {
        if (callbackDocumentId == null || callbackDocumentId.isBlank()
            || callbackModelId == null || callbackModelId.value().isBlank()) {
            return null;
        }

        final CubismFacade cubism = context.cubism();
        if (cubism == null) {
            return null;
        }
        final Optional<DocumentSnapshot> activeDocument = cubism.activeDocument();
        if (activeDocument == null || activeDocument.isEmpty()) {
            return null;
        }
        final DocumentSnapshot document = activeDocument.orElseThrow();
        if (!document.isModelDocument()
            || !callbackDocumentId.equals(document.documentId())
            || document.model().isEmpty()
            || !callbackModelId.value().equals(document.model().orElseThrow().modelId())) {
            return null;
        }

        final CubismModel activeModel = cubism.model().active();
        if (activeModel == null) {
            return null;
        }
        final ModelId activeModelId = activeModel.id();
        if (activeModelId == null || !callbackModelId.equals(activeModelId)
            || activeModelId.value().isBlank()) {
            return null;
        }
        return activeModel;
    }

    private static ExportSettingsDecision unavailable() {
        return ExportSettingsDecision.reject(UNAVAILABLE_KEY);
    }

    private static void warnSafely(final PluginContext context, final String message) {
        if (context == null) {
            return;
        }
        try {
            context.logger().warn(message);
        } catch (Throwable ignored) {
            // A diagnostic sink must not alter the fixed fail-closed decision.
        }
    }

    private PluginContext requireContext() {
        final PluginContext activeContext = context;
        if (activeContext == null) {
            throw new IllegalStateException("Protected Export must be initialized before enable");
        }
        return activeContext;
    }
}

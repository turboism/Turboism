package dev.turboism.plugin.protectedexport;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedExportPluginTest {

    private static final String DOCUMENT_ID = "document-1";
    private static final ModelId MODEL_ID = new ModelId("model-1");

    @Test
    void enableRegistersOneDefaultOffOptionAndRepeatedEnableIsIdempotent() {
        final RecordingService service = new RecordingService();
        final RecordingCubism cubism = RecordingCubism.withDocument(RecordingModel.empty(MODEL_ID),
            document(DOCUMENT_ID, MODEL_ID.value()));
        final ProtectedExportPlugin plugin = initialized(service, cubism);

        plugin.enable();
        plugin.enable();

        assertTrue(plugin.enabled());
        assertEquals(1, service.contributeCalls);
        assertEquals(1, service.activeRegistrations());
        final ExportSettingsContribution contribution = service.contributions.get(0);
        assertEquals(ProtectedExportPlugin.OPTION_ID, contribution.optionId());
        assertEquals(ProtectedExportPlugin.OPTION_LABEL_KEY, contribution.labelKey());
        assertNotNull(contribution.callback());
    }

    @Test
    void disableAndShutdownCloseRegistrationExactlyOnce() {
        final RecordingService service = new RecordingService();
        final ProtectedExportPlugin plugin = initialized(service,
            RecordingCubism.withDocument(RecordingModel.empty(MODEL_ID), document(DOCUMENT_ID, MODEL_ID.value())));
        plugin.enable();
        final RecordingRegistration registration = service.registrations.get(0);

        plugin.disable();
        plugin.disable();
        plugin.shutdown();
        plugin.shutdown();

        assertFalse(plugin.enabled());
        assertEquals(1, registration.closeCalls);
        assertEquals(0, service.activeRegistrations());
    }

    @Test
    void disableThenEnableCreatesOneFreshRegistration() {
        final RecordingService service = new RecordingService();
        final RecordingModel model = RecordingModel.empty(MODEL_ID);
        final RecordingCubism cubism = RecordingCubism.withDocument(model,
            document(DOCUMENT_ID, MODEL_ID.value()));
        final ProtectedExportPlugin plugin = initialized(service, cubism);

        plugin.enable();
        final ExportSettingsContribution first = service.contributions.get(0);
        plugin.disable();
        plugin.enable();

        assertTrue(plugin.enabled());
        assertEquals(2, service.contributeCalls);
        assertEquals(1, service.activeRegistrations());
        assertEquals(
            ExportSettingsDecision.Outcome.REJECT,
            first.callback().decide(true, DOCUMENT_ID, MODEL_ID).outcome()
        );
        assertEquals(0, model.partsReads);
    }

    @Test
    void shutdownThenInitAllowsASeparateFreshLifecycle() {
        final RecordingService firstService = new RecordingService();
        final ProtectedExportPlugin plugin = initialized(firstService,
            RecordingCubism.withDocument(RecordingModel.empty(MODEL_ID), document(DOCUMENT_ID, MODEL_ID.value())));
        plugin.enable();
        plugin.shutdown();

        final RecordingService secondService = new RecordingService();
        plugin.init(pluginContext(secondService,
            RecordingCubism.withDocument(RecordingModel.empty(MODEL_ID), document(DOCUMENT_ID, MODEL_ID.value())),
            new RecordingLogger()));
        plugin.enable();

        assertTrue(plugin.enabled());
        assertEquals(1, firstService.registrations.get(0).closeCalls);
        assertEquals(1, secondService.contributeCalls);
        assertEquals(1, secondService.activeRegistrations());
    }

    @Test
    void unavailableServiceFailsClosedWithoutARegistration() {
        final RecordingLogger logger = new RecordingLogger();
        final ProtectedExportPlugin plugin = new ProtectedExportPlugin();
        plugin.init(pluginContext(ExportSettingsContributionService.unavailable(),
            RecordingCubism.withDocument(RecordingModel.empty(MODEL_ID), document(DOCUMENT_ID, MODEL_ID.value())),
            logger));

        assertDoesNotThrow(plugin::enable);

        assertFalse(plugin.enabled());
        assertTrue(logger.warnings.stream().anyMatch(message -> message.contains("unavailable")));
    }

    @Test
    void contributionFailureLeavesPluginDisabledAndUnregistered() {
        final RecordingService service = new RecordingService();
        service.failure = new IllegalStateException("service unavailable");
        final ProtectedExportPlugin plugin = initialized(service,
            RecordingCubism.withDocument(RecordingModel.empty(MODEL_ID), document(DOCUMENT_ID, MODEL_ID.value())));

        assertDoesNotThrow(plugin::enable);

        assertFalse(plugin.enabled());
        assertEquals(1, service.contributeCalls);
        assertEquals(0, service.activeRegistrations());
        assertTrue(service.registrations.isEmpty());
    }

    @Test
    void lateSelectedCallbackAfterDisableRejectsWithoutReadingModel() {
        final RecordingService service = new RecordingService();
        final RecordingModel model = RecordingModel.empty(MODEL_ID);
        final RecordingCubism cubism = RecordingCubism.withDocument(model, document(DOCUMENT_ID, MODEL_ID.value()));
        final ProtectedExportPlugin plugin = initialized(service, cubism);
        plugin.enable();
        final var callback = service.contributions.get(0).callback();
        plugin.disable();

        final ExportSettingsDecision decision = callback.decide(true, DOCUMENT_ID, MODEL_ID);

        assertUnavailable(decision);
        assertEquals(0, cubism.activeDocumentReads);
        assertEquals(0, cubism.modelAccessReads);
        assertEquals(0, model.partsReads);
    }

    @Test
    void lateSelectedCallbackAfterShutdownRejectsWithoutContext() {
        final RecordingService service = new RecordingService();
        final RecordingCubism cubism = RecordingCubism.withDocument(RecordingModel.empty(MODEL_ID),
            document(DOCUMENT_ID, MODEL_ID.value()));
        final ProtectedExportPlugin plugin = initialized(service, cubism);
        plugin.enable();
        final var callback = service.contributions.get(0).callback();
        plugin.shutdown();

        assertUnavailable(callback.decide(true, DOCUMENT_ID, MODEL_ID));
        assertEquals(0, cubism.activeDocumentReads);
        assertEquals(0, cubism.modelAccessReads);
    }

    @Test
    void uncheckedCallbackIsNativePassthroughWithoutAnyRead() {
        final RecordingService service = new RecordingService();
        final RecordingModel model = RecordingModel.empty(MODEL_ID);
        final RecordingCubism cubism = RecordingCubism.withDocument(model,
            document(DOCUMENT_ID, MODEL_ID.value()));
        final ProtectedExportPlugin plugin = initialized(service, cubism);
        plugin.enable();

        final ExportSettingsDecision decision = service.contributions.get(0).callback().decide(false, null, null);

        assertEquals(ExportSettingsDecision.Outcome.PROCEED_UNCHANGED, decision.outcome());
        assertEquals(0, cubism.activeDocumentReads);
        assertEquals(0, cubism.modelAccessReads);
        assertEquals(0, model.partsReads);
        assertEquals(0, model.updateCalls.get());
    }

    @Test
    void checkedCallbackRejectsWhenDocumentIdentityDoesNotMatchBeforePlanner() {
        final RecordingService service = new RecordingService();
        final RecordingModel model = RecordingModel.empty(MODEL_ID);
        final RecordingCubism cubism = RecordingCubism.withDocument(model,
            document(DOCUMENT_ID, MODEL_ID.value()));
        final ProtectedExportPlugin plugin = initialized(service, cubism);
        plugin.enable();

        final ExportSettingsDecision decision = service.contributions.get(0).callback()
            .decide(true, "different-document", MODEL_ID);

        assertUnavailable(decision);
        assertEquals(1, cubism.activeDocumentReads);
        assertEquals(0, cubism.modelAccessReads);
        assertEquals(0, model.partsReads);
    }

    @Test
    void checkedCallbackRejectsWhenActiveModelIdentityDoesNotMatchBeforePlanner() {
        final RecordingService service = new RecordingService();
        final RecordingModel model = RecordingModel.empty(new ModelId("actual-model"));
        final RecordingCubism cubism = RecordingCubism.withDocument(model,
            document(DOCUMENT_ID, "callback-model"));
        final ProtectedExportPlugin plugin = initialized(service, cubism);
        plugin.enable();

        final ExportSettingsDecision decision = service.contributions.get(0).callback()
            .decide(true, DOCUMENT_ID, new ModelId("callback-model"));

        assertUnavailable(decision);
        assertEquals(1, cubism.activeDocumentReads);
        assertEquals(1, cubism.modelAccessReads);
        assertEquals(0, model.partsReads);
    }

    @Test
    void checkedCallbackRejectsWhenDocumentModelProofIsUnavailable() {
        final RecordingService service = new RecordingService();
        final RecordingModel model = RecordingModel.empty(MODEL_ID);
        final RecordingCubism cubism = RecordingCubism.withDocument(model,
            new DocumentSnapshot(DOCUMENT_ID, "Model", "model.cmo3", Optional.empty(), Optional.empty()));
        final ProtectedExportPlugin plugin = initialized(service, cubism);
        plugin.enable();

        assertUnavailable(service.contributions.get(0).callback().decide(true, DOCUMENT_ID, MODEL_ID));
        assertEquals(0, cubism.modelAccessReads);
        assertEquals(0, model.partsReads);
    }

    @Test
    void checkedCallbackRunsReadOnlyPlannerButUnresolvedConditionCannotProceed() {
        final RecordingService service = new RecordingService();
        final RecordingModel model = RecordingModel.empty(MODEL_ID);
        final RecordingCubism cubism = RecordingCubism.withDocument(model,
            document(DOCUMENT_ID, MODEL_ID.value()));
        final ProtectedExportPlugin plugin = initialized(service, cubism);
        plugin.enable();

        final ExportSettingsDecision decision = service.contributions.get(0).callback()
            .decide(true, DOCUMENT_ID, MODEL_ID);

        assertUnavailable(decision);
        assertTrue(model.partsReads > 0);
        assertTrue(model.parametersReads > 0);
        assertTrue(model.deformersReads > 0);
        assertTrue(model.drawablesReads > 0);
        assertTrue(model.gluesReads > 0);
        assertEquals(0, model.updateCalls.get());
    }

    @Test
    void checkedCallbackRejectsMissingIdentityWithoutReadingHostState() {
        final RecordingService service = new RecordingService();
        final RecordingModel model = RecordingModel.empty(MODEL_ID);
        final RecordingCubism cubism = RecordingCubism.withDocument(model, Optional.empty());
        final ProtectedExportPlugin plugin = initialized(service, cubism);
        plugin.enable();

        assertUnavailable(service.contributions.get(0).callback().decide(true, null, null));
        assertEquals(0, cubism.activeDocumentReads);
        assertEquals(0, cubism.modelAccessReads);
        assertEquals(0, model.partsReads);
    }

    private static ProtectedExportPlugin initialized(
        final ExportSettingsContributionService exportSettings,
        final RecordingCubism cubism
    ) {
        final ProtectedExportPlugin plugin = new ProtectedExportPlugin();
        plugin.init(pluginContext(exportSettings, cubism, new RecordingLogger()));
        return plugin;
    }

    private static PluginContext pluginContext(
        final ExportSettingsContributionService exportSettings,
        final RecordingCubism cubism,
        final RecordingLogger logger
    ) {
        return proxy(PluginContext.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "exportSettings" -> exportSettings;
            case "cubism" -> cubism.facade;
            case "logger" -> logger;
            case "permissions" -> List.of();
            default -> defaultValue(method.getReturnType());
        });
    }

    private static DocumentSnapshot document(final String documentId, final String modelId) {
        return new DocumentSnapshot(
            documentId,
            "Model",
            "model.cmo3",
            Optional.empty(),
            Optional.of(new ModelSnapshot(modelId, "Model", List.of(), List.of(), List.of(), List.of()))
        );
    }

    private static void assertUnavailable(final ExportSettingsDecision decision) {
        assertEquals(ExportSettingsDecision.Outcome.REJECT, decision.outcome());
        assertEquals(ProtectedExportPlugin.UNAVAILABLE_KEY, decision.messageKey());
    }

    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    private static final class RecordingService implements ExportSettingsContributionService {
        private final List<ExportSettingsContribution> contributions = new ArrayList<>();
        private final List<RecordingRegistration> registrations = new ArrayList<>();
        private int contributeCalls;
        private RuntimeException failure;

        @Override
        public Registration contribute(final ExportSettingsContribution contribution) {
            contributeCalls++;
            if (failure != null) {
                throw failure;
            }
            contributions.add(contribution);
            final RecordingRegistration registration = new RecordingRegistration();
            registrations.add(registration);
            return registration;
        }

        int activeRegistrations() {
            return (int) registrations.stream().filter(registration -> !registration.closed).count();
        }
    }

    private static final class RecordingRegistration implements Registration {
        private boolean closed;
        private int closeCalls;

        @Override
        public void close() {
            closeCalls++;
            if (!closed) {
                closed = true;
            }
        }
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void debug(final String message) {
        }

        @Override
        public void info(final String message) {
        }

        @Override
        public void warn(final String message) {
            warnings.add(message);
        }

        @Override
        public void error(final String message) {
        }

        @Override
        public void error(final String message, final Throwable throwable) {
        }
    }

    private static final class RecordingCubism {
        private final CubismModel model;
        private final CubismFacade facade;
        private int activeDocumentReads;
        private int modelAccessReads;

        private RecordingCubism(final CubismModel model, final Optional<DocumentSnapshot> document) {
            this.model = model;
            final CubismModelAccess modelAccess = proxy(CubismModelAccess.class, (proxy, method, arguments) -> {
                if (method.getName().equals("active")) {
                    modelAccessReads++;
                    return this.model;
                }
                return defaultValue(method.getReturnType());
            });
            this.facade = proxy(CubismFacade.class, (proxy, method, arguments) -> {
                if (method.getName().equals("activeDocument")) {
                    activeDocumentReads++;
                    return document;
                }
                if (method.getName().equals("model")) {
                    return modelAccess;
                }
                return defaultValue(method.getReturnType());
            });
        }

        static RecordingCubism withDocument(
            final RecordingModel model,
            final DocumentSnapshot document
        ) {
            return new RecordingCubism(model.model, Optional.of(document));
        }

        static RecordingCubism withDocument(
            final RecordingModel model,
            final Optional<DocumentSnapshot> document
        ) {
            return new RecordingCubism(model.model, document);
        }
    }

    private static final class RecordingModel {
        private final CubismModel model;
        private int partsReads;
        private int parametersReads;
        private int deformersReads;
        private int drawablesReads;
        private int gluesReads;
        private final AtomicInteger updateCalls = new AtomicInteger();

        private RecordingModel(final ModelId modelId) {
            this.model = proxy(CubismModel.class, (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "id" -> {
                        return modelId;
                    }
                    case "parts" -> {
                        partsReads++;
                        return emptyCollection(Parts.class);
                    }
                    case "parameters" -> {
                        parametersReads++;
                        return emptyCollection(Parameters.class);
                    }
                    case "deformers" -> {
                        deformersReads++;
                        return emptyCollection(Deformers.class);
                    }
                    case "drawables" -> {
                        drawablesReads++;
                        return emptyCollection(Drawables.class);
                    }
                    case "glues" -> {
                        gluesReads++;
                        return emptyCollection(Glues.class);
                    }
                    case "modelInstances" -> {
                        return List.of();
                    }
                    case "update" -> {
                        updateCalls.incrementAndGet();
                        return null;
                    }
                    default -> {
                        return defaultValue(method.getReturnType());
                    }
                }
            });
        }

        static RecordingModel empty(final ModelId modelId) {
            return new RecordingModel(modelId);
        }
    }

    private static <T> T emptyCollection(final Class<T> type) {
        return proxy(type, (proxy, method, arguments) -> {
            if (method.getName().equals("all")) {
                return List.of();
            }
            return defaultValue(method.getReturnType());
        });
    }
}

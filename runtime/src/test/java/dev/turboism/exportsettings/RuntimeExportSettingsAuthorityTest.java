package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.export.ExportSettingsDecisionCallback;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared authority tests: plugin-scoped registry aggregation, immutable localized
 * snapshots, dialog-scoped state lifecycle and bounded confirm routing.
 */
class RuntimeExportSettingsAuthorityTest {

    private static final String DOCUMENT_ID = "doc-1";
    private static final String MODEL_ID = "model-1";

    @Test
    void materializesLocalizedOptionsOnceAndCancelRemovesStateWithoutCallbacks() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);

        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));

        assertEquals(
            3, optionsOf(container).getComponentCount(),
            "one owned panel must be appended after the native options"
        );
        final JCheckBox box = checkbox(container, "Localized protect");
        assertFalse(box.isSelected(), "contributed option must default off");
        assertEquals(0, context.callbackCalls.size(), "attach must never invoke callbacks");

        authority.cancel(owner);
        assertEquals(0, context.callbackCalls.size(), "cancel must never invoke callbacks");
        flushEdt();
        assertEquals(
            2, optionsOf(container).getComponentCount(),
            "cancel must remove the owned panel, leaving the native options"
        );
        assertSame(Boolean.TRUE, authority.decide(owner),
            "a cancelled dialog must proceed unchanged without invoking callbacks");
        assertEquals(0, context.callbackCalls.size());
    }

    @Test
    void unselectedConfirmProceedsWithoutInvokingCallbacks() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));

        assertSame(Boolean.TRUE, authority.decide(owner));
        assertEquals(0, context.callbackCalls.size(), "unselected confirm must bypass callbacks");
    }

    @Test
    void selectedConfirmInvokesOwningCallbackExactlyOnceAndFailsClosedUntilProtectedWorkExists() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);

        // The frozen S2A registry contract converts a selected callback's proceed into a
        // bounded rejection rather than implying execution, so this slice's selected path
        // always prevents native export continuation.
        assertSame(Boolean.FALSE, authority.decide(owner));
        assertEquals(1, context.callbackCalls.size(),
            "selected confirm must invoke the callback exactly once");
        assertNotNull(context.callbackArguments.get());
        assertTrue(context.callbackArguments.get().selected());
        assertEquals(DOCUMENT_ID, context.callbackArguments.get().documentId());
        assertEquals(new ModelId(MODEL_ID), context.callbackArguments.get().modelId());
        assertEquals(0L, context.registry.generation(),
            "the captured plugin generation must be routed to the owning registry");
    }

    @Test
    void selectedCallbackRejectionPreventsContinuation() {
        final TestContext context = new TestContext((selected, documentId, modelId) -> ExportSettingsDecision.reject("custom.reason"));
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);

        assertSame(Boolean.FALSE, authority.decide(owner),
            "a rejected selected callback must prevent native export continuation");
        assertEquals(1, context.callbackCalls.size());
    }

    @Test
    void attachFailureFailsClosedAtConfirm() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final Object owner = new Object();
        // A dialog content tree without a native options container cannot host the
        // contribution: the mount resolution fails the materialization.
        final Container hostile = new JPanel(new BorderLayout());
        assertNullResult(authority.attach(owner, hostile));
        assertSame(Boolean.FALSE, authority.decide(owner),
            "an attach failure must reject the confirm instead of silently proceeding");
        assertEquals(0, context.callbackCalls.size());
    }

    @Test
    void noContributionProceedsByteIdentically() {
        final RuntimeExportSettingsAuthority authority =
            new RuntimeExportSettingsAuthority(() -> Optional.of(
                new ExportSettingsIdentity(DOCUMENT_ID, new ModelId(MODEL_ID))
            ));
        authority.hostGeneration(7L);
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, new JPanel()));
        assertSame(Boolean.TRUE, authority.decide(owner),
            "a dialog without contributions must proceed unchanged");
        assertSame(Boolean.TRUE, authority.decide(new Object()),
            "an unknown owner without state must proceed unchanged");
    }

    @Test
    void duplicatePluginRegistrationFailsClosedButCrossPluginLocalOptionIdsRemainIndependent() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        assertThrows(
            IllegalStateException.class,
            () -> authority.register("plugin-a", 0L, context.registry, key -> key),
            "duplicate plugin registration must fail closed"
        );

        final List<Boolean> otherCalls = new ArrayList<>();
        final RuntimeExportSettingsContributionRegistry other = new RuntimeExportSettingsContributionRegistry(
            "plugin-b", 0L
        );
        final Registration otherBinding = authority.register(
            "plugin-b", 0L, other, key -> "Other protect"
        );
        other.contribute(new ExportSettingsContribution(
            "protect", "other.label", (selected, documentId, modelId) -> {
                otherCalls.add(selected);
                return ExportSettingsDecision.reject("other.rejected");
            }
        ));
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        assertEquals(3, optionsOf(container).getComponentCount(),
            "one owned panel must materialize for the two plugin-local options");
        checkbox(container, "Localized protect");
        checkbox(container, "Other protect").setSelected(true);
        assertSame(Boolean.FALSE, authority.decide(owner),
            "the selected plugin-local contribution still fails closed in this inert slice");
        assertEquals(List.of(Boolean.TRUE), otherCalls,
            "selection must route by plugin owner, generation, and local option id");
        assertEquals(0, context.callbackCalls.size(),
            "the same local id owned by another plugin must not be invoked");
        otherBinding.close();
    }

    @Test
    void staleHostGenerationRejects() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);
        authority.hostGeneration(8L);
        assertSame(Boolean.FALSE, authority.decide(owner),
            "a replaced host generation must reject the selected path");
        assertEquals(0, context.callbackCalls.size());
    }

    @Test
    void staleDocumentModelIdentityRejects() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);
        context.identity = Optional.of(new ExportSettingsIdentity(
            "doc-2", new ModelId(MODEL_ID)
        ));
        assertSame(Boolean.FALSE, authority.decide(owner),
            "a stale document identity must reject the selected path");
        assertEquals(0, context.callbackCalls.size());
    }

    @Test
    void missingIdentityRejectsOnlyTheSelectedPath() {
        final TestContext context = new TestContext();
        context.identity = Optional.empty();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        assertSame(Boolean.TRUE, authority.decide(owner),
            "an unselected dialog must proceed even without identity");
        final JPanel second = dialogContent();
        final Object secondOwner = new Object();
        assertNullResult(authority.attach(secondOwner, second));
        checkbox(second, "Localized protect").setSelected(true);
        assertSame(Boolean.FALSE, authority.decide(secondOwner),
            "a selected dialog without identity must reject");
        assertEquals(0, context.callbackCalls.size());
    }

    @Test
    void selectedConfirmRejectsWhenIdentitySourceThrows() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = new RuntimeExportSettingsAuthority(
            () -> {
                throw new AssertionError("identity unavailable");
            }
        );
        context.authorityRef.set(authority);
        context.binding = authority.register(
            "plugin-a", 0L, context.registry, key -> "Localized " + key.replace("export.", "")
        );
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);

        assertSame(Boolean.FALSE, authority.decide(owner),
            "a selected option must not be converted to unselected when identity resolution throws");
        assertEquals(0, context.callbackCalls.size());
    }

    @Test
    void selectionReadFailureRejectsRatherThanProceedingAsUnselected() {
        final TestContext context = new TestContext();
        final AtomicBoolean failSelectionRead = new AtomicBoolean();
        final RuntimeExportSettingsAuthority authority = new RuntimeExportSettingsAuthority(
            () -> context.identity,
            () -> new ExportSettingsAttachBackend(label -> new JCheckBox(label) {
                @Override
                public boolean isSelected() {
                    if (failSelectionRead.get()) {
                        throw new AssertionError("selection unavailable");
                    }
                    return super.isSelected();
                }
            })
        );
        context.authorityRef.set(authority);
        context.binding = authority.register(
            "plugin-a", 0L, context.registry, key -> "Localized " + key.replace("export.", "")
        );
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);
        failSelectionRead.set(true);

        assertSame(Boolean.FALSE, authority.decide(owner),
            "an unreadable selected state must not be converted to an empty selection");
        assertEquals(0, context.callbackCalls.size());
    }

    @Test
    void resetHostInvalidatesOpenDialogsForDelayedConfirmCallbacks() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);

        authority.resetHost();
        flushEdt();
        assertEquals(2, optionsOf(container).getComponentCount(),
            "reset must detach the owned panel, leaving the native options");
        assertSame(Boolean.FALSE, authority.decide(owner),
            "reset must not turn an open dialog into an unchecked native passthrough");
        assertEquals(0, context.callbackCalls.size());
    }

    @Test
    void pluginUnregisterMarksOpenDialogsStale() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);
        context.binding.close();
        assertSame(Boolean.FALSE, authority.decide(owner),
            "a plugin disabled while its dialog is open must reject the confirm");
        assertEquals(0, context.callbackCalls.size());
        assertSame(Boolean.FALSE, authority.decide(owner),
            "a second delayed confirm after plugin removal must remain rejected");
    }

    @Test
    void recursionRejectsAndTheGuardClears() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);
        context.recursiveCallback = true;
        assertSame(Boolean.FALSE, authority.decide(owner),
            "family recursion must reject instead of re-entering");
        context.recursiveCallback = false;
        final JPanel second = dialogContent();
        final Object secondOwner = new Object();
        assertNullResult(authority.attach(secondOwner, second));
        checkbox(second, "Localized protect").setSelected(true);
        assertSame(Boolean.FALSE, authority.decide(secondOwner),
            "the selected path stays fail-closed after the recursion rejection");
        assertEquals(2, context.callbackCalls.size(),
            "the recursion guard must clear so the next selected confirm invokes the callback again");
    }

    @Test
    void clearDialogsAndCloseInvalidateEveryDialogScopedState() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);

        authority.clearDialogs();
        flushEdt();
        assertEquals(2, optionsOf(container).getComponentCount(),
            "clearDialogs must detach the owned panel, leaving the native options");
        assertSame(Boolean.FALSE, authority.decide(owner),
            "a delayed confirm after clearDialogs must remain rejected");

        final JPanel again = dialogContent();
        final Object secondOwner = new Object();
        assertNullResult(authority.attach(secondOwner, again));
        authority.close();
        flushEdt();
        assertEquals(2, optionsOf(again).getComponentCount(),
            "close must detach the owned panel, leaving the native options");
        assertSame(Boolean.FALSE, authority.decide(secondOwner),
            "a delayed confirm after close must remain rejected");
        assertEquals(0, context.callbackCalls.size());
    }

    @Test
    void snapshotResolvesLabelsBeforeTheNativeBoundaryAndIsImmutable() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        final var snapshot = authority.snapshot();
        assertEquals(1, snapshot.options().size());
        assertEquals("protect", snapshot.options().get(0).optionId());
        assertEquals("Localized protect", snapshot.options().get(0).label());
        assertEquals("plugin-a", snapshot.options().get(0).pluginId());
        assertEquals(0L, snapshot.options().get(0).pluginGeneration());
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.options().add(null),
            "snapshot options must be immutable"
        );
    }

    @Test
    void checkedPluginRejectionReportsTheVetoWhenOrchestrationIsUnwired() {
        final TestContext context = new TestContext(
            (selected, documentId, modelId) -> ExportSettingsDecision.reject("custom.reason"));
        final RuntimeExportSettingsAuthority authority = context.authority();
        final List<ExportSettingsVetoDiagnostic> vetoes = new ArrayList<>();
        authority.vetoReporter(vetoes::add);
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);

        assertSame(Boolean.FALSE, authority.decide(owner));
        assertEquals(1, vetoes.size(), "an unwired checked veto must reach the visible sink");
        assertEquals("custom.reason", vetoes.get(0).key());
        assertEquals("Localized custom.reason", vetoes.get(0).detail(),
            "the plugin's resolved message must travel with the diagnostic");
    }

    @Test
    void uncheckedConfirmReportsNoVeto() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        final List<ExportSettingsVetoDiagnostic> vetoes = new ArrayList<>();
        authority.vetoReporter(vetoes::add);
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));

        assertSame(Boolean.TRUE, authority.decide(owner));
        assertTrue(vetoes.isEmpty(), "a clean unchecked confirm must stay silent");
    }

    @Test
    void structuralVetoesReportTheirBoundedKey() {
        // attach failure: no options container in the dialog tree
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        final List<ExportSettingsVetoDiagnostic> vetoes = new ArrayList<>();
        authority.vetoReporter(vetoes::add);
        authority.hostGeneration(7L);
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, new JPanel(new BorderLayout())));
        assertSame(Boolean.FALSE, authority.decide(owner));
        assertEquals(1, vetoes.size());
        assertEquals(
            ExportSettingsAttachBackend.OPTIONS_CONTAINER_KEY, vetoes.get(0).key(),
            "the backend's typed attach key must survive into the diagnostic");

        // stale host generation
        final TestContext stale = new TestContext();
        final RuntimeExportSettingsAuthority staleAuthority = stale.authority();
        final List<ExportSettingsVetoDiagnostic> staleVetoes = new ArrayList<>();
        staleAuthority.vetoReporter(staleVetoes::add);
        staleAuthority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object staleOwner = new Object();
        assertNullResult(staleAuthority.attach(staleOwner, container));
        checkbox(container, "Localized protect").setSelected(true);
        staleAuthority.hostGeneration(8L);
        assertSame(Boolean.FALSE, staleAuthority.decide(staleOwner));
        assertEquals(1, staleVetoes.size());
        assertEquals(RuntimeExportSettingsAuthority.STALE_HOST_KEY, staleVetoes.get(0).key());

        // identity drift on the selected path
        final TestContext drift = new TestContext();
        final RuntimeExportSettingsAuthority driftAuthority = drift.authority();
        final List<ExportSettingsVetoDiagnostic> driftVetoes = new ArrayList<>();
        driftAuthority.vetoReporter(driftVetoes::add);
        driftAuthority.hostGeneration(7L);
        final JPanel driftContainer = dialogContent();
        final Object driftOwner = new Object();
        assertNullResult(driftAuthority.attach(driftOwner, driftContainer));
        checkbox(driftContainer, "Localized protect").setSelected(true);
        drift.identity = Optional.of(new ExportSettingsIdentity("doc-2", new ModelId(MODEL_ID)));
        assertSame(Boolean.FALSE, driftAuthority.decide(driftOwner));
        assertEquals(1, driftVetoes.size());
        assertEquals(
            RuntimeExportSettingsAuthority.IDENTITY_MISMATCH_KEY, driftVetoes.get(0).key());
    }

    @Test
    void tombstoneVetoReportsTheInvalidationReason() {
        final TestContext context = new TestContext();
        final RuntimeExportSettingsAuthority authority = context.authority();
        final List<ExportSettingsVetoDiagnostic> vetoes = new ArrayList<>();
        authority.vetoReporter(vetoes::add);
        authority.hostGeneration(7L);
        final JPanel container = dialogContent();
        final Object owner = new Object();
        assertNullResult(authority.attach(owner, container));
        checkbox(container, "Localized protect").setSelected(true);

        authority.resetHost();
        flushEdt();
        assertSame(Boolean.FALSE, authority.decide(owner),
            "a delayed confirm on an invalidated dialog must still veto");
        assertEquals(1, vetoes.size(),
            "the tombstone veto must name the invalidation reason");
        assertEquals(RuntimeExportSettingsAuthority.STALE_HOST_KEY, vetoes.get(0).key());
    }

    @Test
    void armedOrchestrationHandoffReportsNoAuthorityVeto() throws Exception {
        final TestContext context = new TestContext(
            (selected, documentId, modelId) -> ExportSettingsDecision.reject("protected-export.unavailable"));
        final RuntimeExportSettingsAuthority authority = context.authority();
        final List<ExportSettingsVetoDiagnostic> vetoes = new ArrayList<>();
        final List<ExportSettingsVetoDiagnostic> refusals = new ArrayList<>();
        authority.vetoReporter(vetoes::add);
        authority.hostGeneration(7L);

        final Object owner = new Object();
        final List<ExportSettingsVetoDiagnostic> sessionReports = new ArrayList<>();
        final ProtectedExportOrchestrator orchestrator = orchestratorFixture(
            owner, true, sessionReports);
        orchestrator.refusalReporter(refusals::add);
        authority.markProtectedExportRedirectSeamInstalled();
        authority.protectedExportOrchestrator(orchestrator);
        try {
            final JPanel container = dialogContent();
            assertNullResult(authority.attach(owner, container));
            checkbox(container, "Localized protect").setSelected(true);

            assertSame(Boolean.FALSE, authority.decide(owner),
                "an armed session still vetoes the outer export");
            assertTrue(vetoes.isEmpty(),
                "the armed handoff stays silent at the authority: the session reports itself");
        } finally {
            orchestrator.close();
        }
    }

    @Test
    void refusedOrchestrationSurfacesThroughTheOrchestratorReporter() throws Exception {
        final TestContext context = new TestContext(
            (selected, documentId, modelId) -> ExportSettingsDecision.reject("protected-export.unavailable"));
        final RuntimeExportSettingsAuthority authority = context.authority();
        final List<ExportSettingsVetoDiagnostic> vetoes = new ArrayList<>();
        final List<ExportSettingsVetoDiagnostic> refusals = new ArrayList<>();
        authority.vetoReporter(vetoes::add);
        authority.hostGeneration(7L);

        final Object owner = new Object();
        final List<ExportSettingsVetoDiagnostic> sessionReports = new ArrayList<>();
        final ProtectedExportOrchestrator orchestrator = orchestratorFixture(
            owner, false, sessionReports);
        orchestrator.refusalReporter(refusals::add);
        // The redirect seam is deliberately not marked installed.
        authority.protectedExportOrchestrator(orchestrator);
        try {
            final JPanel container = dialogContent();
            assertNullResult(authority.attach(owner, container));
            checkbox(container, "Localized protect").setSelected(true);

            assertSame(Boolean.FALSE, authority.decide(owner));
            assertTrue(vetoes.isEmpty(),
                "a refused handoff reports through the orchestrator, not the authority");
            assertEquals(1, refusals.size());
            assertEquals(ProtectedExportOrchestrator.NOT_ADMITTED_KEY, refusals.get(0).key());
            assertEquals("redirect-seam-missing", refusals.get(0).detail());
        } finally {
            orchestrator.close();
        }
    }

    /**
     * A real orchestrator over a proxy host admitting exactly {@code owner}. With the
     * redirect seam installed the request arms; the fake host then fails the session
     * on the orchestrator worker, which only the test's report sink observes.
     */
    private static ProtectedExportOrchestrator orchestratorFixture(
        final Object owner,
        final boolean seamInstalled,
        final List<ExportSettingsVetoDiagnostic> sessionFailures
    ) throws Exception {
        final Path stagingRoot = Files.createTempDirectory("veto-orchestrator");
        final Object modelSource = new Object();
        final Object document = new Object();
        final File sourceFile = stagingRoot.resolve("source.cmo3").toFile();
        Files.writeString(sourceFile.toPath(), "fixture-cmo3");
        final ProtectedExportHostOperations host = (ProtectedExportHostOperations)
            java.lang.reflect.Proxy.newProxyInstance(
                RuntimeExportSettingsAuthorityTest.class.getClassLoader(),
                new Class<?>[] {ProtectedExportHostOperations.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isExportDialog" -> args[0] == owner;
                    case "dialogModelSource" -> modelSource;
                    case "modelSourceDocument" -> document;
                    case "documentFile" -> sourceFile;
                    case "isModelingDocument" -> args[0] == document;
                    case "currentDocument" -> document;
                    case "projectContains" -> args[0] == document;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "admitting-host-proxy";
                    default -> switch (method.getReturnType().getName()) {
                        case "boolean" -> Boolean.FALSE;
                        case "int", "float", "long" -> 0;
                        default -> null;
                    };
                });
        return new ProtectedExportOrchestrator(
            host,
            new ProtectedExportStaging(data -> null),
            stagingRoot,
            "plugin-a",
            "protect",
            () -> seamInstalled,
            () -> true,
            () -> 7L,
            new ProtectedExportOrchestrator.EdtDispatcher() {
                @Override
                public <T> T call(final java.util.concurrent.Callable<T> action)
                    throws Exception {
                    return action.call();
                }

                @Override
                public void submit(final Runnable task) {
                    task.run();
                }
            },
            report -> sessionFailures.add(
                new ExportSettingsVetoDiagnostic(
                    report.failureKey() == null ? "protected-export.failed" : report.failureKey(),
                    report.failureDetail()
                )),
            400L,
            400L
        );
    }

    /**
     * Stand-in for the reviewed native dialog content pane: a component-order
     * options list carrying the host's own check-box leaves (JCheckBox
     * subclasses, like {@code CCheckBox$a}), plus a separate button row.
     */
    private static JPanel dialogContent() {
        final JPanel content = new JPanel(new BorderLayout());
        final JPanel options = new JPanel();
        options.add(new NativeOptionCheckBox("native.1"));
        options.add(new NativeOptionCheckBox("native.2"));
        content.add(options, BorderLayout.CENTER);
        final JPanel buttons = new JPanel();
        buttons.add(new JButton("OK"));
        buttons.add(new JButton("Cancel"));
        content.add(buttons, BorderLayout.SOUTH);
        return content;
    }

    /** The options-list child of a {@link #dialogContent()} stand-in. */
    private static JPanel optionsOf(final Container content) {
        return (JPanel) ((BorderLayout) content.getLayout())
            .getLayoutComponent(BorderLayout.CENTER);
    }

    /** Stands in for the host's native option leaves; never the exact JCheckBox class. */
    private static final class NativeOptionCheckBox extends JCheckBox {
        private NativeOptionCheckBox(final String text) {
            super(text);
        }
    }

    private static JCheckBox checkbox(final Container container, final String label) {
        final JCheckBox found = findCheckbox(container, label);
        if (found != null) {
            return found;
        }
        throw new AssertionError("owned checkbox not found: " + label);
    }

    private static JCheckBox findCheckbox(final Container container, final String label) {
        for (Component component : container.getComponents()) {
            if (component instanceof JCheckBox box && label.equals(box.getText())) {
                return box;
            }
            if (component instanceof Container nested) {
                final JCheckBox found = findCheckbox(nested, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void assertNullResult(final Object result) {
        assertNull(result);
    }

    /** Flushes the queued EDT removal events posted by the attach backend teardown. */
    private static void flushEdt() {
        try {
            SwingUtilities.invokeAndWait(() -> {
            });
        } catch (Exception failure) {
            throw new AssertionError("EDT flush failed", failure);
        }
    }

    private static final class TestContext {
        private final RuntimeExportSettingsContributionRegistry registry =
            new RuntimeExportSettingsContributionRegistry("plugin-a", 0L);
        private final List<Boolean> callbackCalls = new ArrayList<>();
        private final AtomicReference<CallbackArguments> callbackArguments = new AtomicReference<>();
        private final AtomicReference<RuntimeExportSettingsAuthority> authorityRef =
            new AtomicReference<>();
        private Optional<ExportSettingsIdentity> identity = Optional.of(
            new ExportSettingsIdentity(DOCUMENT_ID, new ModelId(MODEL_ID))
        );
        private boolean recursiveCallback;
        private Registration binding;
        private final ExportSettingsDecisionCallback registryCallback;

        private TestContext() {
            this((selected, documentId, modelId) -> ExportSettingsDecision.proceedUnchanged());
        }

        private TestContext(final ExportSettingsDecisionCallback callback) {
            this.registryCallback = (selected, documentId, modelId) -> {
                callbackCalls.add(selected);
                callbackArguments.set(new CallbackArguments(selected, documentId, modelId));
                if (recursiveCallback && authorityRef.get() != null) {
                    authorityRef.get().decide(new Object());
                }
                return callback.decide(selected, documentId, modelId);
            };
            registry.contribute(new ExportSettingsContribution(
                "protect", "export.protect", registryCallback
            ));
        }

        private RuntimeExportSettingsAuthority authority() {
            final RuntimeExportSettingsAuthority authority = new RuntimeExportSettingsAuthority(
                () -> identity
            );
            authorityRef.set(authority);
            binding = authority.register(
                "plugin-a",
                0L,
                registry,
                key -> "Localized " + key.replace("export.", "")
            );
            return authority;
        }
    }

    private record CallbackArguments(
        boolean selected,
        String documentId,
        ModelId modelId
    ) {
    }
}

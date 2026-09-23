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

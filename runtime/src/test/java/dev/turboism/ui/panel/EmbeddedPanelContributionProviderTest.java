package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class EmbeddedPanelContributionProviderTest {

    @Test
    void installsOnceRoutesOwnerScopedActivationRebuildsAndCleansUp() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator = new RuntimeEmbeddedPanelActivationCoordinator();
        RecordingHost host = new RecordingHost();
        EmbeddedPanelContributionProvider provider = new EmbeddedPanelContributionProvider(
                admission(7), host, coordinator, EditorUiActionRouter.unavailable());

        Registration registration = provider.apply(
                7, List.of(contribution("plugin-a", "shared", 0), contribution("plugin-b", "shared", 1)));

        coordinator.activate("plugin-b", EmbeddedPanelId.of("shared"));
        coordinator.activate("plugin-b", EmbeddedPanelId.of("shared"));
        assertEquals(List.of("plugin-b:shared", "plugin-b:shared"), host.activated);
        assertEquals(List.of("plugin-a:shared", "plugin-b:shared"), host.installed);

        host.rebuild.run();
        coordinator.activate("plugin-a", EmbeddedPanelId.of("shared"));
        assertEquals(
                List.of("plugin-a:shared", "plugin-b:shared", "plugin-a:shared", "plugin-b:shared"), host.installed);
        assertEquals(List.of("plugin-b:shared", "plugin-a:shared"), host.closed);

        registration.close();
        assertEquals(List.of("plugin-b:shared", "plugin-a:shared", "plugin-b:shared", "plugin-a:shared"), host.closed);
        assertThrows(IllegalStateException.class, () -> coordinator.activate("plugin-a", EmbeddedPanelId.of("shared")));
    }

    @Test
    void reconcilesChangedSnapshotWithoutClosingUnchangedNativePanels() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator = new RuntimeEmbeddedPanelActivationCoordinator();
        RecordingHost host = new RecordingHost();
        EmbeddedPanelContributionProvider provider = new EmbeddedPanelContributionProvider(
                admission(7), host, coordinator, EditorUiActionRouter.unavailable());

        Registration registration = provider.apply(7, List.of(contribution("plugin-a", "first", 0)));
        registration = provider.reconcile(
                7, List.of(contribution("plugin-a", "first", 0), contribution("plugin-b", "second", 1)), registration);

        assertEquals(List.of("plugin-a:first", "plugin-b:second"), host.installed);
        assertEquals(List.of(), host.closed);

        registration = provider.reconcile(7, List.of(contribution("plugin-a", "first", 0)), registration);

        assertEquals(List.of("plugin-b:second"), host.closed);
        coordinator.activate("plugin-a", EmbeddedPanelId.of("first"));
        registration.close();
        assertEquals(List.of("plugin-b:second", "plugin-a:first"), host.closed);
    }

    @Test
    void changedContentUnderSameIdentityUpdatesInPlaceWithoutClosingTheHandle() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator = new RuntimeEmbeddedPanelActivationCoordinator();
        RecordingHost host = new RecordingHost();
        EmbeddedPanelContributionProvider provider = new EmbeddedPanelContributionProvider(
                admission(7), host, coordinator, EditorUiActionRouter.unavailable());

        Registration registration = provider.apply(7, List.of(contribution("plugin-a", "shared", 0)));
        assertEquals(List.of("plugin-a:shared"), host.installed);
        assertEquals(List.of(), host.closed);
        assertEquals(List.of(), host.updated);

        // Same plugin + contribution identity but changed descriptor/content:
        // the installed handle must be updated in place and must NOT be closed
        // (closing it would drop the live palette and the floating window).
        registration = provider.reconcile(7, List.of(contribution("plugin-a", "shared", 1)), registration);

        assertEquals(List.of("plugin-a:shared"), host.installed, "no second native install");
        assertEquals(List.of("plugin-a:shared@1"), host.updated, "one in-place update carrying the changed descriptor");
        assertEquals(List.of(), host.closed, "retained handle must stay open after reconcile");
        coordinator.activate("plugin-a", EmbeddedPanelId.of("shared"));
        assertEquals(List.of("plugin-a:shared"), host.activated, "retained handle stays functional");

        // The single close happens only when the registration is closed.
        registration.close();
        assertEquals(List.of("plugin-a:shared"), host.closed);
    }

    @Test
    void updateContentFatalWithCleanupErrorPropagatesFatalAndDoesNotRebuild() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator = new RuntimeEmbeddedPanelActivationCoordinator();
        RecordingHost host = new RecordingHost();
        EmbeddedPanelContributionProvider provider = new EmbeddedPanelContributionProvider(
                admission(7), host, coordinator, EditorUiActionRouter.unavailable());

        Registration registration =
                provider.apply(7, List.of(contribution("plugin-a", "p1", 0), contribution("plugin-a", "p2", 1)));
        assertEquals(List.of("plugin-a:p1", "plugin-a:p2"), host.installed);

        // When p1 updates, throw fatal OutOfMemoryError
        final OutOfMemoryError oom = new OutOfMemoryError("synthetic OOM during updateContent");
        host.updateFailures.put("plugin-a:p1", oom);
        // When p1 handle closes during cleanup, throw ordinary AssertionError
        final AssertionError closeError = new AssertionError("ordinary close failure");
        host.closeFailures.put("plugin-a:p1", closeError);

        final OutOfMemoryError thrown = assertThrows(
                OutOfMemoryError.class,
                () -> provider.reconcile(
                        7,
                        List.of(contribution("plugin-a", "p1", 10), contribution("plugin-a", "p2", 20)),
                        registration));

        assertSame(oom, thrown);
        assertEquals(1, thrown.getSuppressed().length, "Close error must be suppressed onto fatal OOM");
        assertSame(closeError, thrown.getSuppressed()[0]);

        // Prove p2 (the other panel) was also closed during session teardown
        assertTrue(host.closed.contains("plugin-a:p2"), "Other panel must be closed during session cleanup");

        // Prove it did NOT enter apply rebuild: installed list still has only initial 2 panels
        assertEquals(List.of("plugin-a:p1", "plugin-a:p2"), host.installed, "Must not enter apply rebuild on fatal");
    }

    @Test
    void reconcileOrdinaryUpdateFailureRecoversByRebuildingPanels() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator = new RuntimeEmbeddedPanelActivationCoordinator();
        RecordingHost host = new RecordingHost();
        EmbeddedPanelContributionProvider provider = new EmbeddedPanelContributionProvider(
                admission(7), host, coordinator, EditorUiActionRouter.unavailable());

        Registration registration = provider.apply(7, List.of(contribution("plugin-a", "shared", 0)));
        assertEquals(List.of("plugin-a:shared"), host.installed);

        // When updateContent runs, throw ordinary IllegalStateException
        host.updateFailures.put("plugin-a:shared", new IllegalStateException("ordinary update failure"));

        // Reconcile must catch ordinary failure, clean up old session, and rebuild via apply
        final Registration rebuilt =
                provider.reconcile(7, List.of(contribution("plugin-a", "shared", 1)), registration);

        org.junit.jupiter.api.Assertions.assertNotNull(rebuilt);
        assertEquals(List.of("plugin-a:shared", "plugin-a:shared"), host.installed, "Must rebuild via apply");
        assertTrue(host.closed.contains("plugin-a:shared"), "Old panel must be closed during recovery");
        rebuilt.close();
    }

    @Test
    void failsClosedForMissingPanelAndStaleGeneration() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator = new RuntimeEmbeddedPanelActivationCoordinator();
        EmbeddedPanelContributionProvider provider = new EmbeddedPanelContributionProvider(
                admission(7), new RecordingHost(), coordinator, EditorUiActionRouter.unavailable());

        assertThrows(
                IllegalStateException.class, () -> provider.apply(6, List.of(contribution("plugin-a", "panel", 0))));
        Registration registration = provider.apply(7, List.of(contribution("plugin-a", "panel", 0)));
        assertThrows(
                IllegalStateException.class, () -> coordinator.activate("plugin-a", EmbeddedPanelId.of("missing")));
        registration.close();
    }

    @Test
    void routesTypedPanelControlEventsToTheContributionOwner() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator = new RuntimeEmbeddedPanelActivationCoordinator();
        RecordingHost host = new RecordingHost();
        List<String> routed = new ArrayList<>();
        List<Optional<UiActionEvent>> events = new ArrayList<>();
        EditorUiActionRouter router = new EditorUiActionRouter() {
            @Override
            public void invoke(final String pluginId, final String actionId) {
                routed.add(pluginId + ":" + actionId);
            }

            @Override
            public void invoke(final String pluginId, final String actionId, final Optional<UiActionEvent> event) {
                routed.add(pluginId + ":" + actionId);
                events.add(event);
            }
        };
        EmbeddedPanelContributionProvider provider =
                new EmbeddedPanelContributionProvider(admission(7), host, coordinator, router);
        provider.apply(7, List.of(contribution("plugin-a", "profile", 0)));
        UiActionEvent event = UiActionEvent.text("name", "Alice");

        host.actions.get(0).accept("profile.name.changed", Optional.of(event));

        assertEquals(List.of("plugin-a:profile.name.changed"), routed);
        assertEquals(List.of(Optional.of(event)), events);
    }

    private static EditorUiContribution<EmbeddedPanelContribution> contribution(
            final String pluginId, final String id, final int order) {
        return new EditorUiContribution<>(
                new EditorUiContributionIdentity(pluginId, EditorUiFamily.PANEL, id),
                order,
                new EmbeddedPanelContribution(
                        id,
                        "Panel " + id,
                        "right",
                        order,
                        PanelView.column(PanelView.button("run", "Run", "panel.run"))));
    }

    private static EditorUiProviderAdmission admission(final long generation) {
        return EditorUiProviderAdmission.admitted(
                EditorUiFamily.PANEL,
                generation,
                new EditorUiProviderAdmission.VerificationEvidence(
                        "5.3.02", 42, "a".repeat(64), "adapter.editor-ui.panel", "b".repeat(64)));
    }

    private static final class RecordingHost implements EmbeddedPanelHostOperations {
        private final List<String> installed = new ArrayList<>();
        private final List<String> activated = new ArrayList<>();
        private final List<String> closed = new ArrayList<>();
        private final List<String> updated = new ArrayList<>();
        private final java.util.Map<String, Throwable> updateFailures = new java.util.HashMap<>();
        private final java.util.Map<String, Throwable> closeFailures = new java.util.HashMap<>();
        private final List<BiConsumer<String, Optional<UiActionEvent>>> actions = new ArrayList<>();
        private Runnable rebuild = () -> {};

        @Override
        public PanelHandle addPanel(
                final EmbeddedPanelContributionDescriptor contribution,
                final BiConsumer<String, Optional<UiActionEvent>> action) {
            final String key = contribution.pluginId() + ":" + contribution.contributionId();
            installed.add(key);
            actions.add(action);
            return new PanelHandle() {
                private boolean handleClosed;

                @Override
                public void activate() {
                    if (handleClosed) {
                        throw new IllegalStateException("panel handle is closed");
                    }
                    activated.add(key);
                }

                @Override
                public void updateContent(final EmbeddedPanelContributionDescriptor descriptor) {
                    if (handleClosed) {
                        throw new IllegalStateException("panel handle is closed");
                    }
                    final Throwable failure = updateFailures.remove(key);
                    if (failure instanceof Error err) throw err;
                    if (failure instanceof RuntimeException re) throw re;
                    updated.add(key + "@" + descriptor.priority());
                }

                @Override
                public void close() {
                    if (!handleClosed) {
                        handleClosed = true;
                        closed.add(key);
                        final Throwable failure = closeFailures.remove(key);
                        if (failure instanceof Error err) throw err;
                        if (failure instanceof RuntimeException re) throw re;
                    }
                }
            };
        }

        @Override
        public Registration onRebuild(final Runnable reconcile) {
            rebuild = reconcile;
            return () -> rebuild = () -> {};
        }
    }
}

package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddedPanelContributionProviderTest {

    @Test
    void installsOnceRoutesOwnerScopedActivationRebuildsAndCleansUp() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator =
            new RuntimeEmbeddedPanelActivationCoordinator();
        RecordingHost host = new RecordingHost();
        EmbeddedPanelContributionProvider provider = new EmbeddedPanelContributionProvider(
            admission(7),
            host,
            coordinator
        );

        Registration registration = provider.apply(7, List.of(
            contribution("plugin-a", "shared", 0),
            contribution("plugin-b", "shared", 1)
        ));

        coordinator.activate("plugin-b", EmbeddedPanelId.of("shared"));
        coordinator.activate("plugin-b", EmbeddedPanelId.of("shared"));
        assertEquals(List.of("plugin-b:shared", "plugin-b:shared"), host.activated);
        assertEquals(List.of("plugin-a:shared", "plugin-b:shared"), host.installed);

        host.rebuild.run();
        coordinator.activate("plugin-a", EmbeddedPanelId.of("shared"));
        assertEquals(
            List.of("plugin-a:shared", "plugin-b:shared", "plugin-a:shared", "plugin-b:shared"),
            host.installed
        );
        assertEquals(List.of("plugin-b:shared", "plugin-a:shared"), host.closed);

        registration.close();
        assertEquals(
            List.of("plugin-b:shared", "plugin-a:shared", "plugin-b:shared", "plugin-a:shared"),
            host.closed
        );
        assertThrows(
            IllegalStateException.class,
            () -> coordinator.activate("plugin-a", EmbeddedPanelId.of("shared"))
        );
    }

    @Test
    void failsClosedForMissingPanelAndStaleGeneration() {
        RuntimeEmbeddedPanelActivationCoordinator coordinator =
            new RuntimeEmbeddedPanelActivationCoordinator();
        EmbeddedPanelContributionProvider provider = new EmbeddedPanelContributionProvider(
            admission(7),
            new RecordingHost(),
            coordinator
        );

        assertThrows(
            IllegalStateException.class,
            () -> provider.apply(6, List.of(contribution("plugin-a", "panel", 0)))
        );
        Registration registration = provider.apply(7, List.of(contribution("plugin-a", "panel", 0)));
        assertThrows(
            IllegalStateException.class,
            () -> coordinator.activate("plugin-a", EmbeddedPanelId.of("missing"))
        );
        registration.close();
    }

    private static EditorUiContribution<EmbeddedPanelContribution> contribution(
        final String pluginId,
        final String id,
        final int order
    ) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity(pluginId, EditorUiFamily.PANEL, id),
            order,
            new EmbeddedPanelContribution(id, "Panel " + id, "right", order)
        );
    }

    private static EditorUiProviderAdmission admission(final long generation) {
        return EditorUiProviderAdmission.admitted(
            EditorUiFamily.PANEL,
            generation,
            new EditorUiProviderAdmission.VerificationEvidence(
                "5.3.02",
                42,
                "a".repeat(64),
                "adapter.editor-ui.panel",
                "b".repeat(64)
            )
        );
    }

    private static final class RecordingHost implements EmbeddedPanelHostOperations {
        private final List<String> installed = new ArrayList<>();
        private final List<String> activated = new ArrayList<>();
        private final List<String> closed = new ArrayList<>();
        private Runnable rebuild = () -> { };

        @Override
        public PanelHandle addPanel(final EmbeddedPanelContributionDescriptor contribution) {
            final String key = contribution.pluginId() + ":" + contribution.contributionId();
            installed.add(key);
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
                public void close() {
                    if (!handleClosed) {
                        handleClosed = true;
                        closed.add(key);
                    }
                }
            };
        }

        @Override
        public Registration onRebuild(final Runnable reconcile) {
            rebuild = reconcile;
            return () -> rebuild = () -> { };
        }
    }
}

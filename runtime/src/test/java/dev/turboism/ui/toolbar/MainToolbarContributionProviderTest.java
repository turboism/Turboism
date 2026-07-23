package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainToolbarContributionProviderTest {

    @Test
    void installsInOrderRoutesActionsAndCleansInReverseOrder() {
        RecordingHost host = new RecordingHost();
        List<String> actions = new ArrayList<>();
        MainToolbarContributionProvider provider = new MainToolbarContributionProvider(
            admission(3),
            host,
            (pluginId, actionId) -> actions.add(pluginId + ":" + actionId)
        );

        Registration registration = provider.apply(3, List.of(
            contribution("plugin-a", "first", 0, MainToolbarRegistry.Placement.first()),
            contribution("plugin-b", "home", 10, MainToolbarRegistry.Placement.before(
                MainToolbarRegistry.Anchor.HOST_HOME_ENTRY
            ))
        ));

        assertEquals(List.of("first", "home"), host.installedIds);
        host.actions.get(1).run();
        assertEquals(List.of("plugin-b:action.home"), actions);
        registration.close();
        assertEquals(List.of("home", "first"), host.closedIds);
    }

    @Test
    void rebuildAndAppearanceRefreshReconcileWithoutDuplicates() {
        RecordingHost host = new RecordingHost();
        MainToolbarContributionProvider provider = new MainToolbarContributionProvider(
            admission(3),
            host,
            (pluginId, actionId) -> { }
        );
        Registration registration = provider.apply(3, List.of(
            contribution("plugin-a", "home", 0, MainToolbarRegistry.Placement.first())
        ));

        host.rebuild.run();
        host.appearance.run();

        assertEquals(3, host.installCount.get());
        assertEquals(2, host.closeCount.get());
        registration.close();
        assertEquals(3, host.closeCount.get());
    }

    @Test
    void missingRequiredAnchorFailsClosedAndRollsBackEarlierButtons() {
        RecordingHost host = new RecordingHost();
        host.anchorPresent = false;
        MainToolbarContributionProvider provider = new MainToolbarContributionProvider(
            admission(3),
            host,
            (pluginId, actionId) -> { }
        );

        assertThrows(IllegalStateException.class, () -> provider.apply(3, List.of(
            contribution("plugin-a", "first", 0, MainToolbarRegistry.Placement.first()),
            contribution("plugin-a", "home", 1, MainToolbarRegistry.Placement.before(
                MainToolbarRegistry.Anchor.HOST_HOME_ENTRY
            ))
        )));
        assertEquals(List.of("first"), host.closedIds);
    }

    private static EditorUiContribution<MainToolbarRegistry.MainToolbarButtonContribution> contribution(
        final String pluginId,
        final String id,
        final int order,
        final MainToolbarRegistry.Placement placement
    ) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity(pluginId, EditorUiFamily.MAIN_TOOLBAR, id),
            order,
            new MainToolbarRegistry.MainToolbarButtonContribution(
                id,
                "action." + id,
                "label." + id,
                "tooltip." + id,
                MainToolbarRegistry.IconVariants.normal("icons/" + id + ".svg"),
                placement,
                order
            )
        );
    }

    private static EditorUiProviderAdmission admission(final long generation) {
        return EditorUiProviderAdmission.admitted(
            EditorUiFamily.MAIN_TOOLBAR,
            generation,
            new EditorUiProviderAdmission.VerificationEvidence(
                "5.3.02",
                42,
                "a".repeat(64),
                "adapter.editor-ui.main-toolbar",
                "b".repeat(64)
            )
        );
    }

    private static final class RecordingHost implements MainToolbarHostOperations {
        private final AnchorHandle anchor = new AnchorHandle() { };
        private final List<String> installedIds = new ArrayList<>();
        private final List<String> closedIds = new ArrayList<>();
        private final List<Runnable> actions = new ArrayList<>();
        private final AtomicInteger installCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private boolean anchorPresent = true;
        private Runnable rebuild = () -> { };
        private Runnable appearance = () -> { };

        @Override
        public Optional<AnchorHandle> anchor(final MainToolbarRegistry.Anchor requested) {
            return anchorPresent ? Optional.of(anchor) : Optional.empty();
        }

        @Override
        public Registration addButton(
            final MainToolbarContributionDescriptor contribution,
            final Optional<AnchorHandle> resolvedAnchor,
            final Runnable action
        ) {
            installedIds.add(contribution.contributionId());
            actions.add(action);
            installCount.incrementAndGet();
            return () -> {
                closedIds.add(contribution.contributionId());
                closeCount.incrementAndGet();
            };
        }

        @Override
        public Registration onRebuild(final Runnable reconcile) {
            rebuild = reconcile;
            return () -> rebuild = () -> { };
        }

        @Override
        public Registration onAppearanceChanged(final Runnable refresh) {
            appearance = refresh;
            return () -> appearance = () -> { };
        }
    }
}

package dev.turboism.ui.overlay;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundingBoxOverlayButtonContributionProviderTest {

    @Test
    void adaptsTypedContributionsAndReturnsHostCleanup() {
        final RecordingHost host = new RecordingHost();
        final BoundingBoxOverlayButtonContributionProvider provider =
            new BoundingBoxOverlayButtonContributionProvider(admission(7), host);
        final AtomicInteger clicks = new AtomicInteger();
        final BoundingBoxOverlayButton button = new BoundingBoxOverlayButton(
            "fit",
            "Fit selection",
            BoundingBoxOverlayButton.IconVariants.normal("icons/fit.png"),
            10,
            clicks::incrementAndGet
        );

        final Registration registration = provider.apply(7, List.of(new EditorUiContribution<>(
            new EditorUiContributionIdentity(
                "plugin.overlay",
                EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON,
                button.id()
            ),
            button.order(),
            button
        )));

        assertEquals("plugin.overlay", host.descriptors.get(0).pluginId());
        assertEquals(button, host.descriptors.get(0).button());
        host.descriptors.get(0).button().onClick().run();
        assertEquals(1, clicks.get());
        registration.close();
        assertEquals(1, host.closeCount);
    }

    @Test
    void retainsTheNativeRegistrationWhenReconcilingChangedContributions() {
        final RecordingHost host = new RecordingHost();
        final BoundingBoxOverlayButtonContributionProvider provider =
            new BoundingBoxOverlayButtonContributionProvider(admission(7), host);
        final BoundingBoxOverlayButton first = new BoundingBoxOverlayButton(
            "fit",
            "Fit selection",
            BoundingBoxOverlayButton.IconVariants.normal("icons/fit.png"),
            10,
            () -> { }
        );
        final BoundingBoxOverlayButton second = new BoundingBoxOverlayButton(
            "warp",
            "Warp",
            BoundingBoxOverlayButton.IconVariants.normal("icons/warp.png"),
            20,
            () -> { }
        );

        final Registration registration = provider.apply(7, List.of(new EditorUiContribution<>(
            new EditorUiContributionIdentity(
                "plugin.overlay",
                EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON,
                first.id()
            ),
            first.order(),
            first
        )));

        assertTrue(provider.supportsIncrementalReconcile());
        final Registration reconciled = provider.reconcile(
            7,
            List.of(
                new EditorUiContribution<>(
                    new EditorUiContributionIdentity(
                        "plugin.overlay",
                        EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON,
                        first.id()
                    ),
                    first.order(),
                    first
                ),
                new EditorUiContribution<>(
                    new EditorUiContributionIdentity(
                        "plugin.overlay",
                        EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON,
                        second.id()
                    ),
                    second.order(),
                    second
                )
            ),
            registration
        );
        assertSame(registration, reconciled);
        assertEquals(2, host.descriptors.size());
        assertEquals("warp", host.descriptors.get(1).button().id());
        assertEquals(0, host.closeCount, "reconcile must retain the live registration");
        registration.close();
    }

    private static EditorUiProviderAdmission admission(long generation) {
        return EditorUiProviderAdmission.admitted(
            EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON,
            generation,
            new EditorUiProviderAdmission.VerificationEvidence(
                "5.3.02",
                41_922_739L,
                "9".repeat(64),
                "adapter.editor-ui.bounding-box-overlay-button",
                "a".repeat(64)
            )
        );
    }

    @Test
    void rejectedNinthSnapshotValidatesBeforeMutationAndDoesNotCloseTheExistingRegistration() {
        final RecordingHost host = new RecordingHost();
        final BoundingBoxOverlayButtonContributionProvider provider =
            new BoundingBoxOverlayButtonContributionProvider(admission(7), host);
        final List<EditorUiContribution<?>> eight = new java.util.ArrayList<>();
        for (int index = 0; index < 8; index++) {
            eight.add(contribution("button-" + index, index));
        }
        final Registration registration = provider.apply(7, eight);
        assertEquals(8, host.descriptors.size());

        final List<EditorUiContribution<?>> nine = new java.util.ArrayList<>(eight);
        nine.add(contribution("button-9", 9));
        final IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> provider.reconcile(7, nine, registration)
        );
        assertTrue(failure.getMessage().contains("8"));

        // Validation happens before mutation: the previous snapshot is untouched and the
        // existing native registration was neither closed nor replaced.
        assertEquals(8, host.descriptors.size(), "previous snapshot retained");
        assertEquals(0, host.closeCount, "existing registration untouched");
        registration.close();
        assertEquals(1, host.closeCount);
    }

    private static EditorUiContribution<?> contribution(final String id, final int order) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity(
                "plugin.overlay",
                EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON,
                id
            ),
            order,
            new BoundingBoxOverlayButton(
                id,
                "Overlay button " + id,
                BoundingBoxOverlayButton.IconVariants.normal("icons/fit.png"),
                order,
                () -> { }
            )
        );
    }

    private static final class RecordingHost implements BoundingBoxOverlayButtonHostOperations {
        private static final int MAX_CUSTOM_BUTTONS = 8;

        private List<BoundingBoxOverlayButtonDescriptor> descriptors = List.of();
        private int closeCount;

        @Override
        public Registration install(List<BoundingBoxOverlayButtonDescriptor> descriptors) {
            final List<BoundingBoxOverlayButtonDescriptor> requested = List.copyOf(descriptors);
            if (requested.size() > MAX_CUSTOM_BUTTONS) {
                throw new IllegalArgumentException(
                    "bounding-box overlay button contributions exceed the hard limit of "
                        + MAX_CUSTOM_BUTTONS
                );
            }
            this.descriptors = requested;
            return () -> closeCount++;
        }

        @Override
        public Registration reconcile(
            final List<BoundingBoxOverlayButtonDescriptor> descriptors,
            final Registration existing
        ) {
            final List<BoundingBoxOverlayButtonDescriptor> requested = List.copyOf(descriptors);
            if (requested.size() > MAX_CUSTOM_BUTTONS) {
                throw new IllegalArgumentException(
                    "bounding-box overlay button contributions exceed the hard limit of "
                        + MAX_CUSTOM_BUTTONS
                );
            }
            this.descriptors = requested;
            return existing;
        }
    }
}

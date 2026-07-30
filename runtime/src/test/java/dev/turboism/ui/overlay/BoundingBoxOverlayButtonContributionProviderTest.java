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

    private static final class RecordingHost implements BoundingBoxOverlayButtonHostOperations {
        private List<BoundingBoxOverlayButtonDescriptor> descriptors = List.of();
        private int closeCount;

        @Override
        public Registration install(List<BoundingBoxOverlayButtonDescriptor> descriptors) {
            this.descriptors = List.copyOf(descriptors);
            return () -> closeCount++;
        }
    }
}

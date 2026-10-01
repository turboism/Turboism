package dev.turboism.ui.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MeshToolbarContributionDescriptorTest {

    @Test
    void toolIdentityCarriesExactPluginGenerationAndStateIconsDefaultToNormal() {
        MeshToolbarContributionDescriptor descriptor =
                new MeshToolbarContributionDescriptor("plugin.a", 7L, "brush", "Brush", "icons/brush.png", 12);
        EditorUiContribution<MeshToolbarContributionDescriptor> contribution = descriptor.contribution();

        assertEquals(EditorUiFamily.MESH_TOOLBAR, contribution.identity().family());
        assertEquals("7:tool:brush", contribution.identity().contributionId());
        assertEquals("icons/brush.png", descriptor.activeIconResourcePath());
        assertEquals("icons/brush.png", descriptor.rollOverIconResourcePath());
        assertEquals("icons/brush.png", descriptor.selectedIconResourcePath());
        assertEquals("icons/brush.png", descriptor.disabledIconResourcePath());
        assertEquals("icons/brush.png", descriptor.disabledSelectedIconResourcePath());
        assertEquals(descriptor, MeshToolbarContributionDescriptor.from(contribution));
    }

    @Test
    void toolDescriptorRejectsMismatchedIdentity() {
        MeshToolbarContributionDescriptor descriptor =
                new MeshToolbarContributionDescriptor("plugin.a", 2L, "brush", "Brush", "icons/brush.png", 0);
        EditorUiContribution<MeshToolbarContributionDescriptor> mismatched = new EditorUiContribution<>(
                new EditorUiContributionIdentity("plugin.a", EditorUiFamily.MESH_TOOLBAR, "1:tool:brush"),
                0,
                descriptor);

        assertThrows(IllegalArgumentException.class, () -> MeshToolbarContributionDescriptor.from(mismatched));
    }

    @Test
    void sliderValidatesBoundsAndForwardsOnlyChangedValues() {
        AtomicInteger value = new AtomicInteger(32);
        MeshToolbarSliderContributionDescriptor descriptor = new MeshToolbarSliderContributionDescriptor(
                "plugin.a", 7L, "radius", "Radius", 8, 128, value.get(), 20, value::set);

        assertEquals("7:slider:radius", descriptor.contribution().identity().contributionId());
        assertEquals(32, descriptor.currentValue());
        descriptor.changed(64);
        assertEquals(64, value.get());
        assertEquals(64, descriptor.currentValue());
        assertThrows(IllegalArgumentException.class, () -> descriptor.changed(129));
        assertEquals(64, descriptor.currentValue());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshToolbarSliderContributionDescriptor(
                        "plugin.a", 7L, "radius", "Radius", 128, 8, 32, 0, ignored -> {}));
    }
}

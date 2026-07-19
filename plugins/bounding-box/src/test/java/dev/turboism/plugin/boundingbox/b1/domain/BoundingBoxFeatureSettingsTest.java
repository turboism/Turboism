package dev.turboism.plugin.boundingbox.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BoundingBoxFeatureSettingsTest {

    @Test
    void freezesLegacyDefaultsAndImmutableUpdates() {
        final BoundingBoxFeatureSettings defaults = BoundingBoxFeatureSettings.defaults();
        assertTrue(defaults.overlayButtonsEnabled());
        assertTrue(defaults.workspaceButtonsEnabled());
        assertEquals(false, defaults.mirrorAndShrinkSuppressed());
        assertEquals(new BoundingBoxFeatureSettings(false, true, true),
            defaults.withOverlayButtonsEnabled(false).withMirrorAndShrinkSuppressed(true));
    }
}

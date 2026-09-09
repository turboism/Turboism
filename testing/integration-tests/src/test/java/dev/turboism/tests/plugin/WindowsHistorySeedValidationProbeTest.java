package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.model.Color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WindowsHistorySeedValidationProbeTest {

    @Test
    void choosesFiniteDistinctValueInsideRange() {
        final float selected = WindowsHistorySeedValidationProbe.alternate(0.0F, -1.0F, 1.0F);
        assertNotEquals(0.0F, selected);
        assertEquals(-0.26F, selected, 0.0001F);
    }

    @Test
    void fallsBackWhenFirstCandidateMatches() {
        assertEquals(0.63F, WindowsHistorySeedValidationProbe.alternate(0.37F, 0.0F, 1.0F), 0.0001F);
    }

    @Test
    void selectsDeterministicDistinctHistoryValues() {
        assertEquals(-6.6F, WindowsHistorySeedValidationProbe.valueAt(-10.0F, 10.0F, 0.17F), 0.0001F);
        assertEquals(-1.4F, WindowsHistorySeedValidationProbe.valueAt(-10.0F, 10.0F, 0.43F), 0.0001F);
        assertEquals(4.2F, WindowsHistorySeedValidationProbe.valueAt(-10.0F, 10.0F, 0.71F), 0.0001F);
    }

    @Test
    void usesTwoDistinctDeterministicArtmeshColors() {
        final Color first = WindowsHistorySeedValidationProbe.artMeshProbeColorA();
        final Color second = WindowsHistorySeedValidationProbe.artMeshProbeColorB();

        assertNotEquals(first, second);
        assertEquals("#224466", WindowsHistorySeedValidationProbe.rgb(first));
        assertEquals("#6688aa", WindowsHistorySeedValidationProbe.rgb(second));
        assertEquals(1.0F, first.alpha());
        assertEquals(1.0F, second.alpha());
    }
}

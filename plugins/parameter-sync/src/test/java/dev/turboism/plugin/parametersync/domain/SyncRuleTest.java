package dev.turboism.plugin.parametersync.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SyncRuleTest {

    @Test
    void rejectsSelfLinking() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SyncRule("id1", "ParamA", "ParamA", SyncMode.NORMALIZED_RANGE, 1.0f, 0.0f, true, true));
    }

    @Test
    void rejectsBlankIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SyncRule("id1", "  ", "ParamB", SyncMode.NORMALIZED_RANGE, 1.0f, 0.0f, true, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SyncRule("id1", "ParamA", "", SyncMode.NORMALIZED_RANGE, 1.0f, 0.0f, true, true));
    }

    @Test
    void createsDefaultWithExpectedProperties() {
        final SyncRule rule = SyncRule.createDefault("ParamEyeL", "ParamEyeR");
        assertNotNull(rule.id());
        assertEquals("ParamEyeL", rule.sourceId());
        assertEquals("ParamEyeR", rule.targetId());
        assertEquals(SyncMode.NORMALIZED_RANGE, rule.mode());
        assertTrue(rule.bidirectional());
        assertTrue(rule.enabled());
    }

    @Test
    void multiTargetRuleSupportsMultipleFollowers() {
        final SyncRule rule = new SyncRule(
                "multi-id",
                "ParamA",
                List.of(
                        new SyncTarget("ParamB", SyncMode.NORMALIZED_RANGE, 1.0f, 0.0f),
                        new SyncTarget("ParamC", SyncMode.NORMALIZED_INVERTED, 1.0f, 0.0f)),
                true,
                true);

        assertEquals("ParamA", rule.sourceId());
        assertEquals(2, rule.targets().size());
        assertTrue(rule.hasTarget("ParamB"));
        assertTrue(rule.hasTarget("ParamC"));
        assertFalse(rule.hasTarget("ParamD"));
    }
}

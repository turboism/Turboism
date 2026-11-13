package dev.turboism.plugin.parametersync.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SyncRuleSetTest {

    /**
     * A load that completes after the operator checked parameters must not discard that edit.
     * The check and the write are one step, so a concurrent load cannot land between them.
     */
    @Test
    void aLateLoadDoesNotDiscardAnOperatorEdit() {
        final SyncRuleSet ruleSet = new SyncRuleSet();
        ruleSet.setSynchronized("ParamA", true);

        final boolean applied = ruleSet.applyLoadedState(Set.of(), true);

        assertFalse(applied, "a load must not win over an operator edit");
        assertEquals(Set.of("ParamA"), ruleSet.synchronizedIds());
    }

    /** A load applies when the operator has not edited the model in this session. */
    @Test
    void aLoadAppliesWhenNothingWasEdited() {
        final SyncRuleSet ruleSet = new SyncRuleSet();

        final boolean applied = ruleSet.applyLoadedState(Set.of("ParamB", "ParamC"), false);

        assertTrue(applied);
        assertEquals(Set.of("ParamB", "ParamC"), ruleSet.synchronizedIds());
        assertFalse(ruleSet.isGlobalEnabled(), "the loaded enabled state must apply too");
    }

    /** A persisted paused state survives a restart even with no selected parameters. */
    @Test
    void anEmptyPausedLoadStillApplies() {
        final SyncRuleSet ruleSet = new SyncRuleSet(Set.of("ParamA"), true);

        assertTrue(ruleSet.applyLoadedState(Set.of(), false));
        assertTrue(ruleSet.synchronizedIds().isEmpty());
        assertFalse(ruleSet.isGlobalEnabled());
    }
}

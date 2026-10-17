package dev.turboism.plugin.parameterlock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParameterLockStateTest {

    @Test
    void lockUnlockAndToggleOperations() {
        final ParameterLockState state = new ParameterLockState();
        final String model = "model-123";

        assertFalse(state.isLocked(model, "PARAM_ANGLE_X"));

        state.setLocked(model, "PARAM_ANGLE_X", true);
        assertTrue(state.isLocked(model, "PARAM_ANGLE_X"));
        assertEquals(Set.of("PARAM_ANGLE_X"), state.getLockedParameters(model));

        // Toggle to unlock
        final boolean newState1 = state.toggleLock(model, "PARAM_ANGLE_X");
        assertFalse(newState1);
        assertFalse(state.isLocked(model, "PARAM_ANGLE_X"));

        // Toggle to lock
        final boolean newState2 = state.toggleLock(model, "PARAM_ANGLE_X");
        assertTrue(newState2);
        assertTrue(state.isLocked(model, "PARAM_ANGLE_X"));

        // Unlock
        state.setLocked(model, "PARAM_ANGLE_X", false);
        assertFalse(state.isLocked(model, "PARAM_ANGLE_X"));
    }

    @Test
    void batchLockAndUnlock() {
        final ParameterLockState state = new ParameterLockState();
        final String model = "model-batch";

        state.lockAll(model, List.of("PARAM_A", "PARAM_B", "PARAM_C"));
        assertTrue(state.isLocked(model, "PARAM_A"));
        assertTrue(state.isLocked(model, "PARAM_B"));
        assertTrue(state.isLocked(model, "PARAM_C"));

        state.unlockAll(model, List.of("PARAM_A", "PARAM_C"));
        assertFalse(state.isLocked(model, "PARAM_A"));
        assertTrue(state.isLocked(model, "PARAM_B"));
        assertFalse(state.isLocked(model, "PARAM_C"));
    }

    @Test
    void crossModelIsolation() {
        final ParameterLockState state = new ParameterLockState();
        final String modelA = "guid-model-alpha";
        final String modelB = "guid-model-beta";

        // Locking PARAM_1 on Model A does not lock it on Model B
        state.setLocked(modelA, "PARAM_SHARED", true);
        assertTrue(state.isLocked(modelA, "PARAM_SHARED"));
        assertFalse(state.isLocked(modelB, "PARAM_SHARED"));

        // Locking PARAM_2 on Model B does not affect Model A
        state.setLocked(modelB, "PARAM_2", true);
        assertTrue(state.isLocked(modelB, "PARAM_2"));
        assertFalse(state.isLocked(modelA, "PARAM_2"));

        assertEquals(Set.of("PARAM_SHARED"), state.getLockedParameters(modelA));
        assertEquals(Set.of("PARAM_2"), state.getLockedParameters(modelB));
    }

    @Test
    void mergeLoadedLocksPreservesUserDeltasWhileLoadInFlight() {
        final ParameterLockState state = new ParameterLockState();
        final String model = "model-race";

        // User locks PARAM_USER_NEW while disk read is pending
        state.setLocked(model, "PARAM_USER_NEW", true);
        // User explicitly unlocked PARAM_STORED_1 while disk read is pending
        state.setLocked(model, "PARAM_STORED_1", false);

        // Disk finishes reading with stored IDs: PARAM_STORED_1, PARAM_STORED_2
        state.mergeLoadedLocks(model, List.of("PARAM_STORED_1", "PARAM_STORED_2"));

        // PARAM_STORED_1 should NOT be re-locked because user unlocked it
        assertFalse(state.isLocked(model, "PARAM_STORED_1"));
        // PARAM_STORED_2 should be locked
        assertTrue(state.isLocked(model, "PARAM_STORED_2"));
        // PARAM_USER_NEW should still be locked
        assertTrue(state.isLocked(model, "PARAM_USER_NEW"));

        assertTrue(state.hasLoaded(model));
    }

    @Test
    void clearAllClearsMemory() {
        final ParameterLockState state = new ParameterLockState();
        state.setLocked("m1", "p1", true);
        state.setActiveModel("m1");

        assertTrue(state.isLocked("m1", "p1"));
        assertEquals("m1", state.activeModel());

        state.clearAll();
        assertFalse(state.isLocked("m1", "p1"));
        assertNull(state.activeModel());
    }
}

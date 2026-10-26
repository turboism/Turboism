package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Check;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Operation;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class OrdinaryKeyformWriteSupportTest {
    @Test
    void legacyConstructorAndMissingOperationPreserveUnknownRatherThanInferringAvailability() {
        var support = new OrdinaryKeyformWriteSupport(
                "5.3.04", Check.PRESENT, Check.PRESENT, Check.PRESENT, Check.PRESENT, Check.PRESENT);
        assertTrue(support.operationSelectors().isEmpty());
        for (var operation : Operation.values()) {
            assertEquals(Check.UNKNOWN, support.selectorsFor(operation));
            assertEquals(Check.UNKNOWN, OrdinaryKeyformWriteSupport.unknown().selectorsFor(operation));
        }
        assertThrows(NullPointerException.class, () -> support.selectorsFor(null));
    }

    @Test
    void operationMapIsDetachedAndPermissionDecorationDoesNotDiscardIt() {
        var observations = new EnumMap<Operation, Check>(Operation.class);
        observations.put(Operation.ART_MESH_POSITIONS, Check.PRESENT);
        observations.put(Operation.ART_MESH_VALUES_WITH_OPACITY, Check.ABSENT);
        var support = new OrdinaryKeyformWriteSupport(
                "5.3.04", Check.PRESENT, Check.PRESENT, Check.PRESENT, Check.PRESENT, Check.UNKNOWN, observations);
        observations.clear();
        assertEquals(Check.ABSENT, support.selectorsFor(Operation.ART_MESH_VALUES_WITH_OPACITY));
        assertThrows(
                UnsupportedOperationException.class,
                () -> support.operationSelectors().clear());
        var denied = support.withWritePermission(Check.ABSENT);
        assertEquals(Check.ABSENT, denied.writePermission());
        assertEquals(support.operationSelectors(), denied.operationSelectors());
        assertEquals(Check.PRESENT, denied.selectorsFor(Operation.ART_MESH_POSITIONS));
        assertThrows(
                NullPointerException.class,
                () -> new OrdinaryKeyformWriteSupport(
                        "", Check.UNKNOWN, Check.UNKNOWN, Check.UNKNOWN, Check.UNKNOWN, Check.UNKNOWN, null));
        var malformed = new EnumMap<Operation, Check>(Operation.class);
        malformed.put(Operation.ART_MESH_VALUES, null);
        assertThrows(
                NullPointerException.class,
                () -> new OrdinaryKeyformWriteSupport(
                        "", Check.UNKNOWN, Check.UNKNOWN, Check.UNKNOWN, Check.UNKNOWN, Check.UNKNOWN, malformed));
    }
}

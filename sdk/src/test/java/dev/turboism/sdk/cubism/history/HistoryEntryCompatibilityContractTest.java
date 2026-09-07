package dev.turboism.sdk.cubism.history;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HistoryEntryCompatibilityContractTest {

    @Test
    void retainsExistingConstructorDescriptors() throws ReflectiveOperationException {
        assertNotNull(HistoryEntry.class.getConstructor(
            int.class,
            String.class,
            boolean.class
        ));
        assertNotNull(HistoryEntry.class.getConstructor(
            int.class,
            String.class,
            boolean.class,
            Optional.class
        ));
        assertNotNull(HistoryEntry.class.getConstructor(
            int.class,
            String.class,
            boolean.class,
            Optional.class,
            Optional.class,
            Optional.class
        ));
        assertNotNull(HistoryChange.class.getConstructor(
            HistoryChange.Operation.class,
            Optional.class,
            Optional.class,
            Optional.class,
            Optional.class
        ));
        assertNotNull(HistoryChange.class.getConstructor(
            HistoryChange.Operation.class,
            Optional.class,
            Optional.class,
            Optional.class,
            Optional.class,
            HistoryEditContext.class
        ));
    }

    @Test
    void oldConstructionPathsReceiveConservativeSemanticDefaults() {
        final HistoryEntry labelOnly = new HistoryEntry(0, "", true);
        final HistoryAction action = new HistoryAction(
            HistoryAction.Kind.SET_PARAMETER_VALUE,
            "PARAMETER",
            "ParamAngleX",
            "value",
            Optional.of("0.0"),
            Optional.of("1.0"),
            HistoryAction.DetailLevel.FULL
        );
        final HistoryEntry structured = new HistoryEntry(1, "Set value", true, Optional.of(action));
        final HistoryEntry stable = new HistoryEntry(
            2,
            "Set value",
            true,
            Optional.of(action),
            Optional.of(new HistoryEntryId("entry-2")),
            Optional.of("transaction-2")
        );

        assertEquals(HistoryAction.DetailLevel.LABEL_ONLY, labelOnly.detailLevel());
        assertEquals("History entry", labelOnly.detail().summary());
        assertEquals(HistoryAction.DetailLevel.FULL, structured.detailLevel());
        assertEquals(action, stable.action().orElseThrow());
        assertEquals("transaction-2", stable.transactionId().orElseThrow());
    }
}

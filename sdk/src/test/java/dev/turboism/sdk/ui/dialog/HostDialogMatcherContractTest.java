package dev.turboism.sdk.ui.dialog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lane A: pure data-model contract for the host dialog automation SDK surface. */
class HostDialogMatcherContractTest {

    @Test
    void anyConfirmationMatchesEverything() {
        final HostDialogMatcher matcher = HostDialogMatcher.anyConfirmation();
        assertEquals(Optional.empty(), matcher.windowClassPrefix());
        assertEquals(Optional.empty(), matcher.optionType());
    }

    @Test
    void prefixIsStrippedAndBlankBecomesEmpty() {
        assertEquals(
            Optional.of("com.live2d.ui.window"),
            new HostDialogMatcher(Optional.of("  com.live2d.ui.window  "), Optional.empty())
                .windowClassPrefix()
        );
        assertEquals(
            Optional.empty(),
            new HostDialogMatcher(Optional.of("   "), Optional.empty()).windowClassPrefix()
        );
    }

    @Test
    void optionTypeMustBeWithinZeroToThree() {
        for (int optionType = 0; optionType <= 3; optionType++) {
            assertEquals(
                Optional.of(optionType),
                new HostDialogMatcher(Optional.empty(), Optional.of(optionType)).optionType()
            );
        }
        assertThrows(IllegalArgumentException.class,
            () -> new HostDialogMatcher(Optional.empty(), Optional.of(-1)));
        assertThrows(IllegalArgumentException.class,
            () -> new HostDialogMatcher(Optional.empty(), Optional.of(4)));
    }

    @Test
    void nullComponentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new HostDialogMatcher(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new HostDialogMatcher(Optional.empty(), null));
    }

    @Test
    void actionsAndOutcomesMatchTheSpecifiedVocabulary() {
        assertEquals(List.of("OK", "YES", "NO", "CANCEL", "CLOSE"),
            List.of(
                HostDialogAction.OK.name(),
                HostDialogAction.YES.name(),
                HostDialogAction.NO.name(),
                HostDialogAction.CANCEL.name(),
                HostDialogAction.CLOSE.name()
            ));
        assertEquals(List.of("ACTED", "NOT_FOUND", "TIMEOUT", "AMBIGUOUS", "UNSUPPORTED"),
            List.of(
                HostDialogOutcome.ACTED.name(),
                HostDialogOutcome.NOT_FOUND.name(),
                HostDialogOutcome.TIMEOUT.name(),
                HostDialogOutcome.AMBIGUOUS.name(),
                HostDialogOutcome.UNSUPPORTED.name()
            ));
    }

    @Test
    void snapshotDefensivelyCopiesButtonLabels() {
        final List<String> labels = new ArrayList<>(List.of("OK", "Cancel"));
        final HostDialogSnapshot snapshot = new HostDialogSnapshot("a.b.C", true, 3, labels);
        labels.add("No");
        assertEquals(List.of("OK", "Cancel"), snapshot.buttonLabels());
        assertEquals("a.b.C", snapshot.windowClassName());
        assertTrue(snapshot.modal());
        assertEquals(3, snapshot.optionType());
    }
}

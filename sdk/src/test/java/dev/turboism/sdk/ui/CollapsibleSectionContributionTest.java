package dev.turboism.sdk.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollapsibleSectionContributionTest {

    private static final EmbeddedPanelId TARGET = EmbeddedPanelId.of("turboism.panel.main");

    @Test
    void validContributionExposesAllAccessors() {
        PanelView content = PanelView.column(PanelView.text("state"));
        CollapsibleSectionContribution contribution = new CollapsibleSectionContribution(
            TARGET, "status", "Status", 3, false, content);

        assertEquals(TARGET, contribution.targetPanelId());
        assertEquals("status", contribution.sectionId());
        assertEquals("Status", contribution.title());
        assertEquals(3, contribution.order());
        assertEquals(false, contribution.expandedByDefault());
        assertEquals(content, contribution.content());
    }

    @Test
    void nullTargetPanelIdIsRejected() {
        assertThrows(NullPointerException.class, () -> new CollapsibleSectionContribution(
            null, "status", "Status", 0, true, PanelView.text("x")));
    }

    @Test
    void blankSectionIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CollapsibleSectionContribution(
            TARGET, "  ", "Status", 0, true, PanelView.text("x")));
    }

    @Test
    void nullSectionIdIsRejected() {
        assertThrows(NullPointerException.class, () -> new CollapsibleSectionContribution(
            TARGET, null, "Status", 0, true, PanelView.text("x")));
    }

    @Test
    void blankTitleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CollapsibleSectionContribution(
            TARGET, "status", "", 0, true, PanelView.text("x")));
    }

    @Test
    void negativeOrderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CollapsibleSectionContribution(
            TARGET, "status", "Status", -1, true, PanelView.text("x")));
    }

    @Test
    void nullContentIsRejected() {
        assertThrows(NullPointerException.class, () -> new CollapsibleSectionContribution(
            TARGET, "status", "Status", 0, true, null));
    }

    @Test
    void zeroOrderIsAccepted() {
        CollapsibleSectionContribution contribution = new CollapsibleSectionContribution(
            TARGET, "status", "Status", 0, true, PanelView.text("x"));
        assertTrue(contribution.order() == 0);
    }
}

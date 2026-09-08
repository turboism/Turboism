package dev.turboism.sdk.ui;

import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiInlineLabelContractTest {

    @Test
    void snapshotsRunsAndExposesOnlyAnImmutableView() {
        final UiInlineLabel.TextRun text = UiInlineLabel.textRun("<literal>");
        final List<UiInlineLabel.Run> mutable = new ArrayList<>(List.of(text));
        final UiInlineLabel label = new UiInlineLabel(mutable);

        mutable.add(UiInlineLabel.textRun("later"));

        assertEquals(List.of(text), label.runs());
        assertEquals("<literal>", label.fallbackText());
        assertThrows(
            UnsupportedOperationException.class,
            () -> label.runs().add(UiInlineLabel.textRun("mutate"))
        );
    }

    @Test
    void concatenatesLiteralTextAndMandatoryIconFallbackForAccessibility() {
        final UiIconRef artmesh = new UiIconRef(CubismIcon.ART_MESH);
        final UiInlineLabel label = UiInlineLabel.of(
            UiInlineLabel.textRun("<Move> \"quoted\"\n"),
            UiInlineLabel.iconRun(artmesh, "图形网格"),
            UiInlineLabel.textRun(" 左眼皮")
        );

        assertEquals("<Move> \"quoted\"\n图形网格 左眼皮", label.fallbackText());
        assertEquals(label.fallbackText(), label.accessibleText());
        assertFalse(label.isTextOnly());
        assertTrue(UiInlineLabel.text("plain").isTextOnly());
        assertEquals(artmesh, ((UiInlineLabel.IconRun) label.runs().get(1)).reference());
    }

    @Test
    void enforcesRunAndTotalTextBounds() {
        final String maxRun = "x".repeat(UiInlineLabel.MAX_RUN_TEXT_LENGTH);
        assertDoesNotThrow(() -> new UiInlineLabel.TextRun(maxRun));
        assertThrows(
            IllegalArgumentException.class,
            () -> new UiInlineLabel.TextRun(maxRun + "x")
        );
        assertDoesNotThrow(
            () -> new UiInlineLabel.IconRun(new UiIconRef(CubismIcon.WARP_DEFORMER), maxRun)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new UiInlineLabel.IconRun(new UiIconRef(CubismIcon.WARP_DEFORMER), maxRun + "x")
        );

        final List<UiInlineLabel.Run> maxRuns = new ArrayList<>();
        for (int index = 0; index < UiInlineLabel.MAX_RUNS; index++) {
            maxRuns.add(UiInlineLabel.textRun("x"));
        }
        assertDoesNotThrow(() -> new UiInlineLabel(maxRuns));
        maxRuns.add(UiInlineLabel.textRun("x"));
        assertThrows(IllegalArgumentException.class, () -> new UiInlineLabel(maxRuns));

        final List<UiInlineLabel.Run> total = new ArrayList<>();
        for (int index = 0; index < UiInlineLabel.MAX_TOTAL_TEXT_LENGTH / UiInlineLabel.MAX_RUN_TEXT_LENGTH; index++) {
            total.add(UiInlineLabel.textRun(maxRun));
        }
        assertDoesNotThrow(() -> new UiInlineLabel(total));
        total.add(UiInlineLabel.textRun("x"));
        assertThrows(IllegalArgumentException.class, () -> new UiInlineLabel(total));
    }

    @Test
    void rejectsNullEmptyAndMissingFallbackValues() {
        final UiIconRef icon = new UiIconRef(CubismIcon.ROTATION_DEFORMER);

        assertThrows(
            NullPointerException.class,
            () -> new UiInlineLabel((List<UiInlineLabel.Run>) null)
        );
        assertThrows(
            NullPointerException.class,
            () -> new UiInlineLabel((UiInlineLabel.Run[]) null)
        );
        assertThrows(
            NullPointerException.class,
            () -> new UiInlineLabel(new UiInlineLabel.Run[]{null})
        );
        assertThrows(NullPointerException.class, () -> new UiInlineLabel.TextRun(null));
        assertThrows(NullPointerException.class, () -> new UiInlineLabel.IconRun(null, "icon"));
        assertThrows(NullPointerException.class, () -> new UiInlineLabel.IconRun(icon, null));
        assertThrows(IllegalArgumentException.class, () -> new UiInlineLabel(List.of()));
        assertThrows(IllegalArgumentException.class, () -> UiInlineLabel.text(" \n"));
        assertThrows(IllegalArgumentException.class, () -> new UiInlineLabel.IconRun(icon, " \n"));
    }
}

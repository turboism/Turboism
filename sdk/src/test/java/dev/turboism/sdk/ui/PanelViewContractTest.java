package dev.turboism.sdk.ui;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.action.UiActionEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PanelViewContractTest {

    @Test
    void functionalPanelUsesOnlyTurboismOwnedImmutableViewValues() {
        PanelView view = PanelView.column(
            PanelView.text("Ready"),
            PanelView.row(
                PanelView.textInput("name", "Name", "Alice", "profile.name.changed"),
                PanelView.select(
                    "mode",
                    "Mode",
                    List.of(
                        PanelView.option("fast", "Fast"),
                        PanelView.option("safe", "Safe")
                    ),
                    "safe",
                    "profile.mode.changed"
                )
            ),
            PanelView.toggle("enabled", "Enabled", true, "profile.enabled.changed"),
            PanelView.separator(),
            PanelView.button("run", "Run", "profile.run")
        );

        EmbeddedPanelContribution contribution = new EmbeddedPanelContribution(
            "profile",
            "Profile",
            "right",
            10,
            view
        );

        assertSame(view, contribution.content());
        PanelView.Column column = assertInstanceOf(PanelView.Column.class, view);
        assertEquals(5, column.children().size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> column.children().add(PanelView.text("mutate"))
        );
    }

    @Test
    void imageNodeCarriesDecodablePngAndDefensiveAltText() {
        final byte[] png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        );
        final PanelView.Image image = PanelView.image(png, "thumbnail");
        assertEquals("thumbnail", image.altText());
        assertArrayEquals(png, image.pngBytes());
        final byte[] mutated = image.pngBytes();
        mutated[0] = 9;
        assertArrayEquals(png, image.pngBytes());
        assertThrows(IllegalArgumentException.class, () -> PanelView.image(new byte[]{1, 2, 3}, "bad"));
        assertThrows(NullPointerException.class, () -> PanelView.image(png, null));
        assertThrows(NullPointerException.class, () -> PanelView.image(null, "bad"));
    }

    @Test
    void uiActionEventsCarryTypedControlValuesThroughActionContext() {
        UiActionEvent text = UiActionEvent.text("name", "Alice");
        UiActionEvent selection = UiActionEvent.selection("mode", "safe");
        UiActionEvent toggle = UiActionEvent.toggle("enabled", true);
        ActionRegistry.ActionContext context = new ActionRegistry.ActionContext() {
            @Override
            public Optional<UiActionEvent> uiEvent() {
                return Optional.of(selection);
            }
        };

        assertEquals("Alice", assertInstanceOf(UiActionEvent.TextValue.class, text.value()).value());
        assertEquals("safe", assertInstanceOf(UiActionEvent.SelectionValue.class, selection.value()).value());
        assertEquals(true, assertInstanceOf(UiActionEvent.ToggleValue.class, toggle.value()).value());
        assertEquals(Optional.of(selection), context.uiEvent());
    }

    @Test
    void invalidControlIdentityAndSelectionFailBeforeRuntimeRegistration() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PanelView.textInput(" ", "Name", "", "profile.name.changed")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PanelView.select(
                "mode",
                "Mode",
                List.of(PanelView.option("fast", "Fast")),
                "missing",
                "profile.mode.changed"
            )
        );
    }
}

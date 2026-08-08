package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelAppearanceContractTest {

    @Test
    void uiColorIsIndependentAndUnitBounded() {
        final UiColor color = new UiColor(0.0F, 0.25F, 0.5F, 1.0F);
        assertEquals(0.25F, color.green());
        assertThrows(IllegalArgumentException.class,
            () -> new UiColor(Float.NaN, 0.0F, 0.0F, 1.0F));
        assertThrows(IllegalArgumentException.class,
            () -> new UiColor(-0.01F, 0.0F, 0.0F, 1.0F));
        assertThrows(IllegalArgumentException.class,
            () -> new UiColor(0.0F, 0.0F, 1.01F, 1.0F));
    }

    @Test
    void paletteEntryStateHasExactlyFiveIndependentOptionalProperties() {
        final UiColor color = new UiColor(0.1F, 0.2F, 0.3F, 0.4F);
        final PaletteEntryState state = new PaletteEntryState(
            Optional.of(12.0F), Optional.of(true), Optional.of(false), Optional.of(color), Optional.empty()
        );

        assertEquals(12.0F, state.fontSize().orElseThrow());
        assertEquals(true, state.bold().orElseThrow());
        assertEquals(false, state.italic().orElseThrow());
        assertSame(color, state.textColor().orElseThrow());
        assertTrueNames(
            List.of("fontSize", "bold", "italic", "textColor", "backgroundColor"),
            PaletteEntryState.class.getRecordComponents()
        );
        assertThrows(IllegalArgumentException.class, () -> new PaletteEntryState(
            Optional.of(5.0F), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
        ));
        assertThrows(NullPointerException.class, () -> new PaletteEntryState(
            null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
        ));
    }

    @Test
    void nativeLabelColorPreservesSemanticIdentity() {
        final UiColor color = new UiColor(0.1F, 0.2F, 0.3F, 0.4F);
        final NativeLabelColor custom = new NativeLabelColor.Custom(color);
        final NativeLabelColorState state = new NativeLabelColorState(custom, Optional.of(color));

        assertSame(custom, state.labelColor());
        assertSame(color, state.actualColor().orElseThrow());
        assertEquals(new NativeLabelColor.Preset(PresetColor.GRAY),
            new NativeLabelColor.Preset(PresetColor.GRAY));
        assertThrows(NullPointerException.class, () -> new NativeLabelColor.Custom(null));
    }

    @Test
    void unavailableAppearanceProjectionsFailClosed() throws Exception {
        assertEquals(Optional.empty(), PartAppearance.unavailable().partPaletteEntry());
        assertEquals(Optional.empty(), DeformerAppearance.unavailable().deformerPaletteEntry());
        assertEquals(Optional.empty(), DrawableAppearance.unavailable().partPaletteEntry());
        assertEquals(Optional.empty(), ParameterAppearance.unavailable().parameterPaletteEntry());
        assertEquals(Optional.empty(), ParameterGroupAppearance.unavailable().parameterPaletteEntry());
        assertEquals(Optional.empty(), ParameterGroupAppearance.unavailable().nativeLabelColor());
        assertThrows(UnsupportedOperationException.class,
            () -> PartAppearance.unavailable().setNativeLabelColor(new NativeLabelColor.Default()));

        final PaletteEntry entry = PaletteEntry.unavailable();
        assertEquals(Optional.empty(), entry.actual());
        assertEquals(Optional.empty(), entry.resolved().fontSize());
        assertThrows(UnsupportedOperationException.class, () -> entry.overrideBold(true));
        assertThrows(UnsupportedOperationException.class,
            () -> entry.overrideTextColor(new UiColor(0.0F, 0.0F, 0.0F, 1.0F)));

        assertThrows(NoSuchMethodException.class,
            () -> ParameterAppearance.class.getDeclaredMethod("nativeLabelColor"));
        assertFalse(Arrays.stream(DeformerAppearance.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().contains("ControlRow")));
    }

    @Test
    void modelInterfacesExposeOnlyTheirTypedUiFacades() throws Exception {
        assertEquals(PartAppearance.class, Part.class.getMethod("ui").getReturnType());
        assertEquals(DeformerAppearance.class, Deformer.class.getMethod("ui").getReturnType());
        assertEquals(DrawableAppearance.class, Drawable.class.getMethod("ui").getReturnType());
        assertEquals(ParameterAppearance.class, Parameter.class.getMethod("ui").getReturnType());
        assertEquals(ParameterGroupAppearance.class, ParameterGroup.class.getMethod("ui").getReturnType());
    }

    private static void assertTrueNames(final List<String> expected, final RecordComponent[] components) {
        assertEquals(expected, Arrays.stream(components).map(RecordComponent::getName).toList());
    }
}

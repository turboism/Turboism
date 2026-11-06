package dev.turboism.ui.appearance.control;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Objects;
import java.util.function.BiFunction;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Reads exact native editor components, without modifying native model state or layout. */
public final class VerifiedParameterEditingSource implements BiFunction<Object, Boolean, ParameterEditorControls> {
    private final VerifiedMemberResolver resolver;

    public VerifiedParameterEditingSource(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public ParameterEditorControls apply(final Object row, final Boolean secondary) {
        if (row == null || !SwingUtilities.isEventDispatchThread()) return null;
        try {
            final boolean isSingle = row.getClass()
                    .getName()
                    .equals(resolver.verifiedSelector("cubism.editor-model.parameter-single-row.slider")
                            .ownerInternalName()
                            .replace('/', '.'));
            final String slider = isSingle
                    ? "cubism.editor-model.parameter-single-row.slider"
                    : secondary
                            ? "cubism.editor-model.parameter-double-row.secondary-slider"
                            : "cubism.editor-model.parameter-double-row.slider";
            final String paint = isSingle
                    ? "cubism.editor-model.parameter-single-row.paint"
                    : "cubism.editor-model.parameter-double-row.paint";
            final Object sliderWidget = resolver.invoke(slider, row);
            final Object drag = resolver.readField(paint, row);
            return new ParameterEditorControls(component(row), component(drag), component(sliderWidget));
        } catch (RuntimeException unavailable) {
            // Missing exact evidence or detached controls must not disable unrelated widgets.
            return null;
        }
    }

    private JComponent component(final Object widget) {
        return (JComponent) resolver.invoke("cubism.editor-model.widget.jcomponent", widget);
    }
}

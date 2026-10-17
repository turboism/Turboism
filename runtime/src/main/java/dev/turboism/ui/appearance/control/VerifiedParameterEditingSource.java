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
        final String single = "cubism.editor-model.parameter-single-row.";
        final String dual = "cubism.editor-model.parameter-double-row.";
        try {
            final boolean isSingle = row.getClass()
                    .getName()
                    .equals(resolver.verifiedSelector(single + "slider")
                            .ownerInternalName()
                            .replace('/', '.'));
            final String prefix = isSingle ? single : dual;
            final Object slider = resolver.invoke(prefix + (secondary ? "secondary-slider" : "slider"), row);
            final Object drag = resolver.readField(prefix + "paint", row);
            return new ParameterEditorControls(component(row), component(drag), component(slider));
        } catch (RuntimeException unavailable) {
            // Missing exact evidence or detached controls must not disable unrelated widgets.
            return null;
        }
    }

    private JComponent component(final Object widget) {
        return (JComponent) resolver.invoke("cubism.editor-model.widget.jcomponent", widget);
    }
}

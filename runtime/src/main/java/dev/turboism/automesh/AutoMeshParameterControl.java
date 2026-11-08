package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import java.awt.BorderLayout;
import java.awt.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;

/**
 * One rendered parameter control, with its own read and write.
 *
 * <p>A control owns how its widget shows a canonical value and how it reports an edit back. That is
 * what lets the dialog reset every row without knowing which widget it built: it asks each control
 * to show the model's value again.</p>
 *
 * <p>Showing a value is never an edit. Every Swing widget here reports a programmatic change as an
 * event, so the control suppresses its own reports while it writes. Without that rule a reset would
 * re-apply the value it just restored and drop the preset name the user chose.</p>
 */
final class AutoMeshParameterControl {

    private static final int FIELD_COLUMNS = 8;
    private static final int SLIDER_TICKS = 1000;
    private static final int GAP = 6;

    private final Component component;
    private final Consumer<String> write;
    private final AtomicBoolean showing = new AtomicBoolean();

    private AutoMeshParameterControl(final Component component, final Consumer<String> write) {
        this.component = component;
        this.write = write;
    }

    /** @return the widget the dialog adds to its row */
    Component component() {
        return component;
    }

    /**
     * Shows one canonical value without reporting it back as an edit.
     *
     * @param value the canonical value to show
     */
    void show(final String value) {
        showing.set(true);
        try {
            write.accept(value);
        } finally {
            showing.set(false);
        }
    }

    /** Reports one user edit unless the control is writing a value itself. */
    private void report(final Consumer<String> onEdit, final String value) {
        if (!showing.get()) {
            onEdit.accept(value);
        }
    }

    /**
     * Builds the control a descriptor asks for.
     *
     * @param descriptor the declared parameter
     * @param value the canonical value to show first
     * @param onEdit receives every edit the user makes
     * @return the control of that descriptor
     */
    static AutoMeshParameterControl of(
            final AlgorithmParameterDescriptor descriptor, final String value, final Consumer<String> onEdit) {
        if (descriptor instanceof AlgorithmParameterDescriptor.IntParameter integer) {
            return stepper(integer, value, onEdit);
        }
        if (descriptor instanceof AlgorithmParameterDescriptor.DoubleParameter decimal) {
            return slider(decimal, value, onEdit);
        }
        if (descriptor instanceof AlgorithmParameterDescriptor.BoolParameter) {
            return checkbox(value, onEdit);
        }
        if (descriptor instanceof AlgorithmParameterDescriptor.EnumParameter enumeration) {
            return combo(enumeration, value, onEdit);
        }
        return field((AlgorithmParameterDescriptor.TextParameter) descriptor, value, onEdit);
    }

    private static AutoMeshParameterControl stepper(
            final AlgorithmParameterDescriptor.IntParameter descriptor,
            final String value,
            final Consumer<String> onEdit) {
        final SpinnerNumberModel model =
                new SpinnerNumberModel(Integer.parseInt(value), descriptor.min(), descriptor.max(), descriptor.step());
        final JSpinner spinner = new JSpinner(model);
        final AutoMeshParameterControl[] self = new AutoMeshParameterControl[1];
        spinner.addChangeListener(event -> self[0].report(onEdit, String.valueOf(model.getNumber())));
        self[0] = new AutoMeshParameterControl(spinner, next -> model.setValue(Integer.parseInt(next)));
        return self[0];
    }

    private static AutoMeshParameterControl slider(
            final AlgorithmParameterDescriptor.DoubleParameter descriptor,
            final String value,
            final Consumer<String> onEdit) {
        final JPanel line = new JPanel(new BorderLayout(GAP, 0));
        final JSlider slider = new JSlider(SwingConstants.HORIZONTAL, 0, SLIDER_TICKS, positionOf(descriptor, value));
        final JTextField field = new JTextField(value, FIELD_COLUMNS);
        final AutoMeshParameterControl[] self = new AutoMeshParameterControl[1];
        slider.addChangeListener(event -> {
            if (slider.getValueIsAdjusting()) {
                return;
            }
            final String text = textAt(descriptor, slider.getValue());
            field.setText(text);
            self[0].report(onEdit, text);
        });
        field.addActionListener(event -> self[0].report(onEdit, field.getText().strip()));
        line.add(slider, BorderLayout.CENTER);
        line.add(field, BorderLayout.EAST);
        self[0] = new AutoMeshParameterControl(line, next -> {
            slider.setValue(positionOf(descriptor, next));
            field.setText(next);
        });
        return self[0];
    }

    private static AutoMeshParameterControl checkbox(final String value, final Consumer<String> onEdit) {
        final JCheckBox check = new JCheckBox();
        check.setSelected(Boolean.parseBoolean(value));
        final AutoMeshParameterControl[] self = new AutoMeshParameterControl[1];
        check.addActionListener(event -> self[0].report(onEdit, Boolean.toString(check.isSelected())));
        self[0] = new AutoMeshParameterControl(check, next -> check.setSelected(Boolean.parseBoolean(next)));
        return self[0];
    }

    private static AutoMeshParameterControl combo(
            final AlgorithmParameterDescriptor.EnumParameter descriptor,
            final String value,
            final Consumer<String> onEdit) {
        final List<String> labels = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        for (final AlgorithmParameterDescriptor.EnumParameter.Option option : descriptor.options()) {
            values.add(option.value());
            labels.add(option.label());
        }
        final JComboBox<String> box = new JComboBox<>(labels.toArray(new String[0]));
        box.setSelectedIndex(Math.max(0, values.indexOf(value)));
        final AutoMeshParameterControl[] self = new AutoMeshParameterControl[1];
        box.addActionListener(event -> self[0].report(onEdit, values.get(Math.max(0, box.getSelectedIndex()))));
        self[0] = new AutoMeshParameterControl(box, next -> box.setSelectedIndex(Math.max(0, values.indexOf(next))));
        return self[0];
    }

    private static AutoMeshParameterControl field(
            final AlgorithmParameterDescriptor.TextParameter descriptor,
            final String value,
            final Consumer<String> onEdit) {
        final JTextField text = new JTextField(value, FIELD_COLUMNS);
        text.setDocument(new BoundedPlainDocument(descriptor.maxLength()));
        final AutoMeshParameterControl[] self = new AutoMeshParameterControl[1];
        text.addActionListener(event -> self[0].report(onEdit, text.getText()));
        text.addFocusListener(new CommitOnFocusLoss(text, committed -> self[0].report(onEdit, committed)));
        self[0] = new AutoMeshParameterControl(text, text::setText);
        return self[0];
    }

    private static int positionOf(final AlgorithmParameterDescriptor.DoubleParameter descriptor, final String value) {
        final double span = descriptor.max() - descriptor.min();
        final double offset = Double.parseDouble(value) - descriptor.min();
        return (int) Math.round(offset / span * SLIDER_TICKS);
    }

    private static String textAt(final AlgorithmParameterDescriptor.DoubleParameter descriptor, final int position) {
        final double span = descriptor.max() - descriptor.min();
        final double raw = descriptor.min() + span * position / SLIDER_TICKS;
        final double stepped =
                descriptor.min() + Math.round((raw - descriptor.min()) / descriptor.step()) * descriptor.step();
        final double bounded = Math.min(descriptor.max(), Math.max(descriptor.min(), stepped));
        return BigDecimal.valueOf(bounded)
                .setScale(descriptor.decimals(), RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}

package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;

/**
 * Widget-level contract of the parameter dialog controls.
 *
 * <p>Every control is a plain Swing component, and every one of them constructs without a display
 * on this JVM. That makes the two properties the dialog depends on testable headlessly: a control
 * shows a canonical value, and showing a value again is never reported as an edit — otherwise the
 * dialog would re-apply the value it just restored and drop the preset name the user chose.</p>
 */
class AutoMeshParameterControlTest {

    private final List<String> edits = new ArrayList<>();

    @Test
    void anIntegerControlIsASpinnerShowingTheValue() {
        final AutoMeshParameterControl control = AutoMeshParameterControl.of(
                new AlgorithmParameterDescriptor.IntParameter("density", "Density", "", 8, 128, 8, 48),
                "48",
                edits::add);

        assertTrue(control.component() instanceof JSpinner, "an integer parameter renders as a spinner");
        assertEquals(48, ((JSpinner) control.component()).getValue());

        control.show("96");

        assertEquals(96, ((JSpinner) control.component()).getValue(), "show must update the widget");
        assertTrue(edits.isEmpty(), "show must not report an edit");
    }

    @Test
    void aDecimalControlIsASliderWithAField() {
        final AutoMeshParameterControl control = AutoMeshParameterControl.of(
                new AlgorithmParameterDescriptor.DoubleParameter("smoothing", "Smoothing", "", 0.0, 1.0, 0.25, 2, 0.5),
                "0.5",
                edits::add);

        assertTrue(control.component() instanceof JPanel, "a decimal parameter renders as a slider row");
        final List<JTextField> fields = fieldsOf((JPanel) control.component());
        assertEquals(1, fields.size(), "the slider row must carry one numeric field");
        assertEquals("0.5", fields.get(0).getText());

        control.show("0.75");

        assertEquals("0.75", fields.get(0).getText(), "show must update the field");
        assertTrue(edits.isEmpty(), "show must not report an edit");
    }

    @Test
    void aBooleanControlIsACheckbox() {
        final AutoMeshParameterControl control = AutoMeshParameterControl.of(
                new AlgorithmParameterDescriptor.BoolParameter("weld", "Weld", "", true), "true", edits::add);

        assertTrue(control.component() instanceof JCheckBox, "a boolean parameter renders as a checkbox");
        assertTrue(((JCheckBox) control.component()).isSelected());

        control.show("false");

        assertTrue(!((JCheckBox) control.component()).isSelected(), "show must update the checkbox");
        assertTrue(edits.isEmpty(), "show must not report an edit");
    }

    @Test
    void anEnumControlIsAComboOfTheDeclaredLabels() {
        final AutoMeshParameterControl control = AutoMeshParameterControl.of(
                new AlgorithmParameterDescriptor.EnumParameter(
                        "mode",
                        "Mode",
                        "",
                        List.of(
                                new AlgorithmParameterDescriptor.EnumParameter.Option("fast", "Fast"),
                                new AlgorithmParameterDescriptor.EnumParameter.Option("dense", "Dense")),
                        "fast"),
                "fast",
                edits::add);

        assertTrue(control.component() instanceof JComboBox, "an enum parameter renders as a combo box");
        final JComboBox<?> box = (JComboBox<?>) control.component();
        assertEquals(List.of("Fast", "Dense"), itemsOf(box), "the combo must show the declared labels");
        assertEquals(0, box.getSelectedIndex());

        control.show("dense");

        assertEquals(1, box.getSelectedIndex(), "show must select the matching option");
        assertTrue(edits.isEmpty(), "show must not report an edit");
    }

    @Test
    void aTextControlIsAFieldBoundedByItsDeclaredLength() {
        final AutoMeshParameterControl control = AutoMeshParameterControl.of(
                new AlgorithmParameterDescriptor.TextParameter("tag", "Tag", "", 3, ""), "", edits::add);

        assertTrue(control.component() instanceof JTextField, "a text parameter renders as a field");

        control.show("abcdef");

        assertEquals("abc", ((JTextField) control.component()).getText(), "the document must truncate");
        assertTrue(edits.isEmpty(), "show must not report an edit");
    }

    @Test
    void everyControlExposesItsWidget() {
        for (final AlgorithmParameterDescriptor descriptor : descriptors()) {
            assertNotNull(
                    AutoMeshParameterControl.of(descriptor, descriptor.canonicalDefaultValue(), edits::add)
                            .component(),
                    "every control must expose a widget: " + descriptor.id());
        }
    }

    private static List<AlgorithmParameterDescriptor> descriptors() {
        return List.of(
                new AlgorithmParameterDescriptor.IntParameter("density", "Density", "", 8, 128, 8, 48),
                new AlgorithmParameterDescriptor.DoubleParameter("smoothing", "Smoothing", "", 0.0, 1.0, 0.25, 2, 0.5),
                new AlgorithmParameterDescriptor.BoolParameter("weld", "Weld", "", true),
                new AlgorithmParameterDescriptor.EnumParameter(
                        "mode",
                        "Mode",
                        "",
                        List.of(new AlgorithmParameterDescriptor.EnumParameter.Option("fast", "Fast")),
                        "fast"),
                new AlgorithmParameterDescriptor.TextParameter("tag", "Tag", "", 5, ""));
    }

    private static List<JTextField> fieldsOf(final JPanel panel) {
        final List<JTextField> fields = new ArrayList<>();
        for (final Component component : panel.getComponents()) {
            if (component instanceof JTextField field) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static List<String> itemsOf(final JComboBox<?> box) {
        final List<String> items = new ArrayList<>();
        for (int index = 0; index < box.getItemCount(); index++) {
            items.add(String.valueOf(box.getItemAt(index)));
        }
        return items;
    }
}

package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.plugin.CancellationToken;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Sizing contract of the parameter dialog: the content decides, the screen bounds it.
 *
 * <p>A dialog that opens below its content's preferred size clips rows, and one that opens at an
 * unbounded long list grows past a small display. These cases pin both sides without showing a
 * window, so they run on every headless build.</p>
 */
class AutoMeshParameterDialogTest {

    private static final String LONG_LABEL =
            "A parameter label that is deliberately wider than any fixed column would allow";

    @Test
    void theWindowSizeFollowsTheContentAndStaysOnScreen() {
        final Dimension content = new Dimension(780, 640);
        final Rectangle screen = new Rectangle(0, 0, 1920, 1080);

        assertEquals(content, AutoMeshParameterDialog.boundedDialogSize(content, screen));

        final Dimension bounded = AutoMeshParameterDialog.boundedDialogSize(
                new Dimension(2_000, 2_000), new Rectangle(0, 0, 1_000, 1_000));
        assertEquals(new Dimension(900, 900), bounded);
        assertEquals(
                content,
                AutoMeshParameterDialog.boundedDialogSize(content, null),
                "a headless environment keeps the packed size");
    }

    @Test
    void aLongLabelWidensTheContentInsteadOfClippingIt() throws Exception {
        final AutoMeshSelectorController controller = controllerWithLongLabel();
        final Dimension[] contentSize = new Dimension[1];
        final List<JLabel> labels = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            final JPanel preview = AutoMeshParameterDialog.buildPreviewPanel(controller, Locale.ENGLISH);
            final Container content = (Container) preview.getComponent(0);
            contentSize[0] = content.getPreferredSize();
            collectLabels(content, labels);
        });

        final JLabel longLabel = labels.stream()
                .filter(label -> LONG_LABEL.equals(label.getText()))
                .findFirst()
                .orElse(null);
        assertNotNull(longLabel, "the parameter row must render the declared label");
        final int textWidth = longLabel.getFontMetrics(longLabel.getFont()).stringWidth(LONG_LABEL);
        assertTrue(
                longLabel.getPreferredSize().width >= textWidth,
                "the label column must not clip its text: " + longLabel.getPreferredSize().width + " < " + textWidth);
        assertTrue(
                contentSize[0].width >= longLabel.getPreferredSize().width,
                "the content must reserve the label column: " + contentSize[0]);
    }

    private static void collectLabels(final Container container, final List<JLabel> labels) {
        for (final Component child : container.getComponents()) {
            if (child instanceof JLabel label) {
                labels.add(label);
            }
            if (child instanceof Container nested) {
                collectLabels(nested, labels);
            }
        }
    }

    private static AutoMeshSelectorController controllerWithLongLabel() {
        final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
        final AutoMeshSelectionState selection = new AutoMeshSelectionState(catalog);
        final AutoMeshAlgorithm algorithm = new AutoMeshAlgorithm(
                "plugin.long-label",
                "Long label plan",
                "Declares one parameter with a wide label.",
                List.of(new AlgorithmParameterDescriptor.IntParameter("segments", LONG_LABEL, "", 1, 9, 1, 3)),
                List.of(),
                false,
                (AutoMeshRequest request, CancellationToken cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
        catalog.register(algorithm, "plugin", 1L, () -> true);
        selection.selectAlgorithm(algorithm.id());
        final AutoMeshSelectorSource source = new AutoMeshSelectorSource() {
            @Override
            public List<AutoMeshAlgorithm> algorithms() {
                return catalog.algorithms();
            }

            @Override
            public AutoMeshSelectionState selection() {
                return selection;
            }
        };
        return new AutoMeshSelectorController(source, Locale.ENGLISH);
    }
}

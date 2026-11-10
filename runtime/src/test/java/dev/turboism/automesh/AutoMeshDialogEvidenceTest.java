package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contracts of the parameter-dialog evidence artifact.
 *
 * <p>The artifact is the only proof that the preset bar renders, because a plugin-hosted probe cannot
 * paint runtime Swing content. These cases pin the file, its size, its pixel content and the
 * controls it must show, so a dialog that loses its preset bar cannot keep producing a green
 * capture.</p>
 */
class AutoMeshDialogEvidenceTest {

    private static final int MINIMUM_DISTINCT_COLORS = 8;
    private static final String EVIDENCE_PRESET = AutoMeshDialogEvidence.EVIDENCE_PRESET_NAME;
    private static final String ARTIFACT_PREVIEW_DIRECTORY = "build";
    private static final List<String> PRESET_BUTTON_TEXT =
            List.of("Add…", "Save", "Rename…", "Delete", "Restore defaults");

    private static final AlgorithmParameterDescriptor.IntParameter DENSITY =
            new AlgorithmParameterDescriptor.IntParameter("density", "Density", "", 1, 9, 2, 3);
    private static final AlgorithmPreset FINE = new AlgorithmPreset("Fine", Map.of("density", "5"));
    private static final AlgorithmPreset COARSE = new AlgorithmPreset("Coarse", Map.of("density", "1"));

    @TempDir
    Path directory;

    @AfterEach
    void clearProperty() {
        System.clearProperty(AutoMeshDialogEvidence.DUMP_DIR_PROPERTY);
    }

    @Test
    void capturePaintsThePresetBarOfTheProbedAlgorithm() throws Exception {
        System.setProperty(AutoMeshDialogEvidence.DUMP_DIR_PROPERTY, directory.toString());

        final Optional<Path> file =
                AutoMeshDialogEvidence.capture(sourceOf(algorithm()), Locale.ENGLISH, message -> {});

        assertTrue(file.isPresent(), "the capture must write the artifact");
        assertTrue(Files.size(file.get()) > 0L, "the artifact must carry image bytes");
        final BufferedImage image = ImageIO.read(file.get().toFile());
        assertEquals(AutoMeshParameterDialog.PREVIEW_WIDTH, image.getWidth());
        assertEquals(AutoMeshParameterDialog.PREVIEW_HEIGHT, image.getHeight());
        assertTrue(
                distinctColors(image) > MINIMUM_DISTINCT_COLORS,
                "a painted dialog must carry text and control pixels, not one flat fill");
        // The build directory keeps a copy a reviewer can open, which is what the artifact is for.
        Files.createDirectories(Path.of(ARTIFACT_PREVIEW_DIRECTORY));
        ImageIO.write(
                image,
                "png",
                Path.of(ARTIFACT_PREVIEW_DIRECTORY, AutoMeshDialogEvidence.PARAMETER_DIALOG_FILE)
                        .toFile());
    }

    @Test
    void captureStaysSilentWithoutTheDumpPropertyOrASubject() {
        assertEquals(
                Optional.empty(),
                AutoMeshDialogEvidence.capture(sourceOf(algorithm()), Locale.ENGLISH, message -> {}),
                "a production session must not paint evidence");

        System.setProperty(AutoMeshDialogEvidence.DUMP_DIR_PROPERTY, directory.toString());
        assertEquals(
                Optional.empty(),
                AutoMeshDialogEvidence.capture(sourceOf(), Locale.ENGLISH, message -> {}),
                "an empty listing has nothing to preview");
    }

    @Test
    void previewPanelShowsTheUserPresetNextToTheDeclaredOnes() throws Exception {
        final AutoMeshSelectionState selection = new AutoMeshSelectionState(new FixedSession(algorithm()));
        final AutoMeshSelectorController controller =
                new AutoMeshSelectorController(new FixedSource(List.of(algorithm()), selection), Locale.ENGLISH);
        controller.savePreset(EVIDENCE_PRESET, Map.of("density", "7"));

        final JPanel preview = EdtDispatch.call(
                "auto mesh dialog evidence test",
                () -> AutoMeshParameterDialog.buildPreviewPanel(controller, Locale.ENGLISH));

        assertEquals(
                List.of("Custom", "Fine", "Coarse", EVIDENCE_PRESET),
                comboItems(preview),
                "the combo must lead with the hand-edit placeholder and then list the user presets");
        assertTrue(
                buttonTexts(preview).containsAll(PRESET_BUTTON_TEXT),
                "the preset bar must render every action, found: " + buttonTexts(preview));
    }

    private static int distinctColors(final BufferedImage image) {
        final Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                colors.add(image.getRGB(x, y));
                if (colors.size() > MINIMUM_DISTINCT_COLORS) {
                    return colors.size();
                }
            }
        }
        return colors.size();
    }

    private static List<String> comboItems(final Component root) {
        final JComboBox<?> combo = comboOf(root);
        if (combo == null) {
            return List.of();
        }
        final List<String> items = new ArrayList<>(combo.getItemCount());
        for (int index = 0; index < combo.getItemCount(); index++) {
            items.add(String.valueOf(combo.getItemAt(index)));
        }
        return items;
    }

    private static JComboBox<?> comboOf(final Component root) {
        if (root instanceof JComboBox<?> combo) {
            return combo;
        }
        if (root instanceof Container container) {
            for (final Component child : container.getComponents()) {
                final JComboBox<?> found = comboOf(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> buttonTexts(final Component root) {
        final List<AbstractButton> buttons = new ArrayList<>();
        collectButtons(root, buttons);
        return buttons.stream().map(AbstractButton::getText).toList();
    }

    private static void collectButtons(final Component root, final List<AbstractButton> found) {
        if (root instanceof AbstractButton button) {
            found.add(button);
        }
        if (root instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectButtons(child, found);
            }
        }
    }

    private static AutoMeshSelectorSource sourceOf(final AutoMeshAlgorithm... algorithms) {
        final List<AutoMeshAlgorithm> listed = List.of(algorithms);
        final AutoMeshSessionAlgorithms session = new FixedSession(listed.isEmpty() ? null : listed.get(0));
        return new FixedSource(listed, new AutoMeshSelectionState(session));
    }

    private static AutoMeshAlgorithm algorithm() {
        return new AutoMeshAlgorithm(
                "mesh",
                "Mesh",
                "",
                List.of(DENSITY),
                List.of(FINE, COARSE),
                false,
                (request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
    }

    /** Session view a test drives directly: one algorithm, selected by construction. */
    private record FixedSession(AutoMeshAlgorithm algorithm) implements AutoMeshSessionAlgorithms {

        @Override
        public Optional<String> selectedAlgorithmId() {
            return Optional.ofNullable(algorithm).map(AutoMeshAlgorithm::id);
        }

        @Override
        public Optional<AutoMeshAlgorithm> find(final String algorithmId) {
            return algorithm != null && algorithm.id().equals(algorithmId) ? Optional.of(algorithm) : Optional.empty();
        }

        @Override
        public void select(final String algorithmId) {
            // A fixed session answers one algorithm, so a selection request changes nothing.
        }
    }

    private record FixedSource(List<AutoMeshAlgorithm> algorithms, AutoMeshSelectionState selection)
            implements AutoMeshSelectorSource {}
}

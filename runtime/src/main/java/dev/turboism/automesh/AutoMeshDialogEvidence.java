package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JPanel;

/**
 * Paints the Auto Mesh parameter dialog into a validation dump directory.
 *
 * <p>A host validation probe runs as a plugin, and a plugin loader cannot link runtime classes. The
 * runtime therefore paints its own dialog content when a validation run names a dump directory, and
 * the probe reads the artifact back. The preview uses a detached selection state, so capturing
 * evidence never writes into the user's preset file.</p>
 *
 * <p>The artifact is the evidence that the preset bar renders: it carries the declared preset of the
 * probed algorithm and one added preset, both through the production dialog content.</p>
 */
final class AutoMeshDialogEvidence {

    /** Property naming the dump directory of a validation run. */
    static final String DUMP_DIR_PROPERTY = "turboism.automeshValidation.dumpDir";

    /** Artifact name of the painted parameter dialog. */
    static final String PARAMETER_DIALOG_FILE = "automesh-parameter-dialog.png";

    /** Preset the evidence capture adds, so the artifact shows a user-owned entry as well. */
    static final String EVIDENCE_PRESET_NAME = "Validation preset";

    private static final String PNG_FORMAT = "png";
    private static final String NO_DUMP_DIRECTORY = "";

    private AutoMeshDialogEvidence() {}

    /**
     * Paints the parameter dialog of the selected algorithm into the validation dump directory.
     *
     * <p>Runs on the event dispatch thread, because the preview builds Swing widgets. A run without
     * the dump property, a listing without a parameterised algorithm, or a refused write all answer
     * empty.</p>
     *
     * @param source the session listing and selection the preview picks its subject from
     * @param locale the host-effective locale for the framework's own text
     * @param diagnostics sink for a refused write; it must not throw
     * @return the written file, or empty when no validation run asked for one
     */
    static Optional<Path> capture(
            final AutoMeshSelectorSource source, final Locale locale, final Consumer<String> diagnostics) {
        final String configured = System.getProperty(DUMP_DIR_PROPERTY, NO_DUMP_DIRECTORY);
        if (configured.isBlank()) {
            return Optional.empty();
        }
        final List<AutoMeshAlgorithm> candidates = source.algorithms().stream()
                .filter(algorithm -> !algorithm.isNative())
                .filter(algorithm -> !algorithm.parameters().isEmpty())
                .toList();
        final Optional<String> selected = source.selection().selectedAlgorithmId();
        final Optional<AutoMeshAlgorithm> subject = candidates.stream()
                .filter(algorithm -> Optional.of(algorithm.id()).equals(selected))
                .findFirst()
                .or(() -> candidates.stream().findFirst());
        if (subject.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(EdtDispatch.call(
                    "auto mesh dialog evidence",
                    () -> paint(subject.get(), Path.of(configured.trim()), locale, diagnostics)));
        } catch (RuntimeException | LinkageError refused) {
            diagnostics.accept(
                    "auto mesh dialog evidence: " + refused.getClass().getSimpleName() + ": " + refused.getMessage());
            return Optional.empty();
        }
    }

    private static Path paint(
            final AutoMeshAlgorithm algorithm,
            final Path dumpRoot,
            final Locale locale,
            final Consumer<String> diagnostics) {
        final AutoMeshSelectionState selection =
                new AutoMeshSelectionState(new DetachedSession(algorithm), AutoMeshPresetStore.inMemory());
        final AutoMeshSelectorController controller =
                new AutoMeshSelectorController(new DetachedSource(List.of(algorithm), selection), locale);
        final Optional<AutoMeshParameterModel> model = controller.parameterModel();
        if (model.isEmpty()) {
            return null;
        }
        if (!algorithm.presets().isEmpty()) {
            controller.selectPreset(1);
        }
        controller.savePreset(EVIDENCE_PRESET_NAME, model.get().values());
        final JPanel preview = AutoMeshParameterDialog.buildPreviewPanel(controller, locale);
        final Dimension size = preview.getPreferredSize();
        preview.setSize(size);
        layout(preview);
        final BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        try {
            preview.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        try {
            Files.createDirectories(dumpRoot);
            final Path file = dumpRoot.resolve(PARAMETER_DIALOG_FILE);
            if (!ImageIO.write(image, PNG_FORMAT, file.toFile())) {
                diagnostics.accept("auto mesh dialog evidence: no PNG writer accepted the image");
                return null;
            }
            return file;
        } catch (IOException refused) {
            diagnostics.accept(
                    "auto mesh dialog evidence: " + refused.getClass().getSimpleName() + ": " + refused.getMessage());
            return null;
        }
    }

    /**
     * Lays out a widget tree that no window has laid out yet.
     *
     * <p>An offscreen panel has zero child bounds until every container distributes its own size, and
     * an unlaid-out scroll pane paints an empty viewport.</p>
     */
    private static void layout(final Component component) {
        if (!(component instanceof Container container)) {
            return;
        }
        container.doLayout();
        for (final Component child : container.getComponents()) {
            layout(child);
        }
        container.validate();
    }

    /** Session view over the one algorithm the evidence preview shows. */
    private static final class DetachedSession implements AutoMeshSessionAlgorithms {

        private final AutoMeshAlgorithm algorithm;
        private String selected;

        private DetachedSession(final AutoMeshAlgorithm algorithm) {
            this.algorithm = algorithm;
            this.selected = algorithm.id();
        }

        @Override
        public Optional<String> selectedAlgorithmId() {
            return Optional.ofNullable(selected);
        }

        @Override
        public Optional<AutoMeshAlgorithm> find(final String algorithmId) {
            return algorithm.id().equals(algorithmId) ? Optional.of(algorithm) : Optional.empty();
        }

        @Override
        public void select(final String algorithmId) {
            selected = algorithmId;
        }
    }

    /** Listing view the evidence controller reads: the one algorithm plus its detached selection. */
    private record DetachedSource(List<AutoMeshAlgorithm> algorithms, AutoMeshSelectionState selection)
            implements AutoMeshSelectorSource {}
}

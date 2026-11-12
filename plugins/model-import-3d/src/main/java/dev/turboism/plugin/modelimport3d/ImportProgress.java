package dev.turboism.plugin.modelimport3d;

import dev.turboism.sdk.ui.window.TurboismProgressDialog;
import java.util.List;
import java.util.function.Function;

/**
 * Progress surface for one 3D import.
 *
 * <p>The import writes the whole model inside one authoring transaction on the event thread, so no
 * queued repaint can run while it works. Every update therefore paints the window itself.</p>
 *
 * <p>One stage dominates the write: creating the native meshes. That stage reports every mesh it
 * finishes, so the bar keeps moving inside it instead of sitting on one step.</p>
 */
final class ImportProgress implements AutoCloseable {

    /** Stages the native character pipeline reports, in write order. */
    private static final List<String> STAGES = List.of(
            "source-geometry",
            "texture-atlas",
            "source-controls",
            "rotation-interpolation-cache",
            "rotation-keyforms",
            "source-control-hierarchy");

    /** Bar resolution per stage, so a stage can report progress inside itself. */
    private static final int SUBSTEPS = 100;

    private final TurboismProgressDialog dialog;
    private final Function<int[], String> counterText;
    private final Function<int[], String> meshText;
    private final String writingText;
    private final int total;
    private int completedStages;

    /**
     * @param title localized window title
     * @param preparingText localized text shown before the first stage arrives
     * @param counterText builds the localized step text from {@code {done, total}}
     * @param meshText builds the localized mesh text from {@code {done, total}}
     * @param writingText localized text for a pipeline that reports no stages
     * @param total number of stages the confirmed import will report, or zero when unknown
     */
    ImportProgress(
            final String title,
            final String preparingText,
            final Function<int[], String> counterText,
            final Function<int[], String> meshText,
            final String writingText,
            final int total) {
        this.dialog = new TurboismProgressDialog(title, preparingText);
        this.counterText = counterText;
        this.meshText = meshText;
        this.writingText = writingText;
        this.total = Math.max(0, total);
    }

    /** @return the number of stages one character import reports */
    static int characterStages() {
        return STAGES.size();
    }

    void show() {
        dialog.show();
    }

    /** Switches to the writing message while the progress bar stays indeterminate. */
    void writing() {
        if (total > 0) return;
        dialog.setMessage(writingText);
        dialog.flush();
    }

    /** Records one named stage of the character pipeline. */
    void stage(final String rawStage) {
        final int index = STAGES.indexOf(rawStage);
        if (index >= 0) advance(index + 1);
    }

    /** Advances inside the current stage while the native pipeline creates meshes. */
    void meshCreated(final int done, final int count) {
        if (total <= 0 || count <= 0 || completedStages <= 0) return;
        final int within = Math.min(SUBSTEPS, Math.max(0, done * SUBSTEPS / count));
        dialog.setProgress((completedStages - 1) * SUBSTEPS + within, total * SUBSTEPS);
        dialog.setMessage(meshText.apply(new int[] {done, count}));
        dialog.flush();
    }
    /**
     * Records one enum stage by position.
     *
     * @param zeroBasedIndex position of the completed stage
     * @param stages number of stages the pipeline defines
     */
    void step(final int zeroBasedIndex, final int stages) {
        if (stages <= 0 || zeroBasedIndex < 0 || zeroBasedIndex >= stages) return;
        advance(zeroBasedIndex + 1);
    }

    /** Publishes one monotonic stage boundary and repaints the window the transaction is blocking. */
    private void advance(final int started) {
        if (total <= 0) return;
        final int next = Math.min(total, Math.max(completedStages, started));
        if (next == completedStages) return;
        completedStages = next;
        // A stage is announced when it starts, so it fills the bar from its own boundary to the next.
        // The last announced stage is the final one, and it completes the bar.
        final int filled = completedStages == total ? total * SUBSTEPS : (completedStages - 1) * SUBSTEPS;
        dialog.setProgress(filled, total * SUBSTEPS);
        dialog.setMessage(counterText.apply(new int[] {completedStages, total}));
        dialog.flush();
    }

    @Override
    public void close() {
        dialog.close();
    }
}

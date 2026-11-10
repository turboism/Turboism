package dev.turboism.plugin.modelimport3d;

import dev.turboism.sdk.ui.window.TurboismProgressDialog;
import java.util.List;
import java.util.function.Function;

/**
 * Progress surface for one 3D import.
 *
 * <p>The import writes the whole model inside one authoring transaction, and the editor shows no
 * feedback while that runs. Pipelines report either a named stage or an enum stage, so both map onto
 * one step counter; an unknown name leaves the current step alone.</p>
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

    private final TurboismProgressDialog dialog;
    private final Function<int[], String> counterText;

    /**
     * @param title localized window title
     * @param preparingText localized text shown before the first stage arrives
     * @param counterText builds the localized step text from {@code {done, total}}
     */
    ImportProgress(final String title, final String preparingText, final Function<int[], String> counterText) {
        this.dialog = new TurboismProgressDialog(title, preparingText);
        this.counterText = counterText;
    }

    void show() {
        dialog.show();
    }

    /** Records one named stage of the character pipeline. */
    void stage(final String rawStage) {
        final int index = STAGES.indexOf(rawStage);
        if (index >= 0) step(index, STAGES.size());
    }

    /**
     * Records one enum stage by position.
     *
     * @param zeroBasedIndex position of the completed stage
     * @param total number of stages the pipeline defines
     */
    void step(final int zeroBasedIndex, final int total) {
        if (total <= 0 || zeroBasedIndex < 0 || zeroBasedIndex >= total) return;
        dialog.setProgress(zeroBasedIndex + 1, total);
        dialog.setMessage(counterText.apply(new int[] {zeroBasedIndex + 1, total}));
    }

    @Override
    public void close() {
        dialog.close();
    }
}

package dev.turboism.plugin.modelimport3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ImportProgressTest {
    private static final String TITLE = "ImportProgress test";
    private static final int STAGES = ImportProgress.characterStages();

    @Test
    void stageCounterIsMonotonicAndClampedToTheDeclaredTotal() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var progress = progress(STAGES)) {
            progress.show();
            // The transaction owns the event thread; every update must paint inside this call.
            SwingUtilities.invokeAndWait(() -> {
                progress.stage("texture-atlas");
                assertEquals("2/" + STAGES, label());
                final int afterSecondStage = bar().getValue();
                progress.stage("source-geometry");
                assertEquals("2/" + STAGES, label(), "an earlier stage must not move the counter backwards");
                assertEquals(afterSecondStage, bar().getValue());
                progress.stage("unknown-stage");
                assertEquals("2/" + STAGES, label(), "an unknown stage leaves the counter alone");
                progress.step(5, STAGES);
                assertEquals(STAGES + "/" + STAGES, label());
                assertEquals(bar().getMaximum(), bar().getValue(), "the last stage completes the bar");
                progress.step(9, STAGES);
                assertEquals(STAGES + "/" + STAGES, label(), "an out-of-range step is ignored");
            });
        }
    }

    @Test
    void meshReportsAdvanceInsideTheCurrentStage() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var progress = progress(STAGES)) {
            progress.show();
            SwingUtilities.invokeAndWait(() -> {
                progress.stage("source-geometry");
                final int afterFirstStage = bar().getValue();
                progress.meshCreated(1, 4);
                final int afterFirstMesh = bar().getValue();
                progress.meshCreated(3, 4);
                final int afterThirdMesh = bar().getValue();
                assertTrue(afterFirstMesh > afterFirstStage, "a finished mesh must move the bar");
                assertTrue(afterThirdMesh > afterFirstMesh, "later meshes must keep the bar moving");
                assertEquals("Creating meshes 3/4", label());
                // A finished stage still wins: it jumps past every sub-step of the stage it closes.
                progress.stage("texture-atlas");
                assertTrue(bar().getValue() > afterThirdMesh);
                assertEquals("2/" + STAGES, label());
            });
        }
    }

    @Test
    void meshReportsBeforeTheFirstStageAreIgnored() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var progress = progress(STAGES)) {
            progress.show();
            SwingUtilities.invokeAndWait(() -> {
                progress.meshCreated(1, 4);
                assertTrue(bar().isIndeterminate(), "no stage has started yet");
            });
        }
    }

    @Test
    void pipelineWithoutStagesStaysIndeterminate() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var progress = progress(0)) {
            progress.show();
            SwingUtilities.invokeAndWait(() -> {
                progress.writing();
                assertTrue(bar().isIndeterminate());
                assertEquals("Writing", label());
            });
        }
    }

    private static ImportProgress progress(final int stages) {
        return new ImportProgress(
                TITLE,
                "Preparing",
                step -> step[0] + "/" + step[1],
                mesh -> "Creating meshes " + mesh[0] + "/" + mesh[1],
                "Writing",
                stages);
    }

    private static JProgressBar bar() {
        return (JProgressBar) dialog().getContentPane().getComponent(1);
    }

    private static String label() {
        return ((JLabel) dialog().getContentPane().getComponent(0)).getText();
    }

    private static JDialog dialog() {
        return (JDialog) Arrays.stream(java.awt.Window.getWindows())
                .filter(w ->
                        w instanceof JDialog d && d.isShowing() && d.getTitle().equals(TITLE))
                .findFirst()
                .orElseThrow();
    }
}

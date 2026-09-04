package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused coverage for the exact-host plugin chooser probe contract. */
class PluginManagementChooserValidationProbeTest {

    @TempDir
    Path temporary;

    @Test
    void jarOnlyChooserPasses() {
        final JFileChooser chooser = new JFileChooser();
        chooser.resetChoosableFileFilters();
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Turboism plugin JAR (*.jar)", "jar"));

        final var observation = PluginManagementChooserValidationProbe.observeChooser(chooser);

        assertTrue(observation.passed());
        assertTrue(observation.jarAccepted());
        assertFalse(observation.tpluginAccepted());
        assertFalse(observation.acceptAllEnabled());
    }

    @Test
    void legacyChooserFailsAndWritesStructuredResult() throws Exception {
        final JFileChooser chooser = new JFileChooser();
        chooser.resetChoosableFileFilters();
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Turboism plugin package (*.tplugin)", "tplugin"));
        final var observation = PluginManagementChooserValidationProbe.observeChooser(chooser);
        final Path result = temporary.resolve(PluginManagementChooserValidationProbe.RESULT_RELATIVE);

        PluginManagementChooserValidationProbe.writeResult(
            result, "run-1", "5302", observation, false, 42L
        );

        assertFalse(observation.passed());
        final String content = Files.readString(result);
        assertTrue(content.contains("jarAccepted=false\n"));
        assertTrue(content.contains("tpluginAccepted=true\n"));
        assertTrue(content.contains("status=FAIL\n"));
    }
}

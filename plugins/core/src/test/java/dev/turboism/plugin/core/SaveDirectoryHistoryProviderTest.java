package dev.turboism.plugin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveDirectoryHistoryProviderTest {

    @TempDir
    Path configDir;

    private SaveDirectoryHistoryProvider provider() {
        return new SaveDirectoryHistoryProvider(configDir);
    }

    @Test
    void missingFileLoadsEmpty() {
        assertTrue(provider().loadProjectDirectory().isEmpty());
        assertTrue(provider().loadExportDirectory().isEmpty());
    }

    @Test
    void saveRoundTripsBothSlotsIndependently() {
        final SaveDirectoryHistoryProvider provider = provider();
        final Path project = configDir.resolve("project-saves");
        final Path export = configDir.resolve("export-saves");

        provider.saveProjectDirectory(project);
        assertEquals(project, provider.loadProjectDirectory().orElseThrow());
        assertTrue(provider.loadExportDirectory().isEmpty());

        provider.saveExportDirectory(export);
        assertEquals(project, provider.loadProjectDirectory().orElseThrow());
        assertEquals(export, provider.loadExportDirectory().orElseThrow());

        // A fresh provider instance reads the persisted file again.
        final SaveDirectoryHistoryProvider reloaded = provider();
        assertEquals(project, reloaded.loadProjectDirectory().orElseThrow());
        assertEquals(export, reloaded.loadExportDirectory().orElseThrow());
    }

    @Test
    void persistsUnderExpectedFileName() {
        provider().saveProjectDirectory(configDir.resolve("saves"));

        final Path file = configDir.resolve(SaveDirectoryHistoryProvider.FILE_NAME);
        assertTrue(Files.isRegularFile(file));
        final String content;
        try {
            content = Files.readString(file, StandardCharsets.ISO_8859_1);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        assertTrue(content.contains("projectRecentDirectory="));
    }

    @Test
    void windowsBackslashesRoundTripThroughPropertiesEscaping() {
        final SaveDirectoryHistoryProvider provider = provider();
        final Path windowsPath = Path.of("C:\\Users\\rain\\My Documents\\saves");

        provider.saveExportDirectory(windowsPath);

        assertEquals(windowsPath, provider.loadExportDirectory().orElseThrow());
    }

    @Test
    void corruptedLinesAreIgnoredOnLoad() throws IOException {
        final Path file = configDir.resolve(SaveDirectoryHistoryProvider.FILE_NAME);
        Files.writeString(file,
            "projectRecentDirectory=C:/good-saves\n"
                + "exportRecentDirectory=\\u12zz-broken-escape\n"
                + "garbage line without separator\n",
            StandardCharsets.ISO_8859_1);

        assertEquals(Path.of("C:/good-saves"), provider().loadProjectDirectory().orElseThrow());
        assertTrue(provider().loadExportDirectory().isEmpty());
    }

    @Test
    void nullDirectoryIsRejected() {
        final SaveDirectoryHistoryProvider provider = provider();
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> provider.saveProjectDirectory(null));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> provider.saveExportDirectory(null));
    }
}

package dev.turboism.adapter.cubism.filechooser;

import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeFileChooserHistoryBridgeTest {

    private static final String EXPORT_CONTEXT =
        NativeFileChooserHistoryBridgeTest.class.getName();

    @TempDir
    Path tempDir;

    private NativeFileChooserHistoryBridge installedBridge;

    @AfterEach
    void uninstall() {
        if (installedBridge != null) {
            NativeFileChooserHistoryBridge.uninstall(installedBridge);
            installedBridge = null;
        }
    }

    private static FileChooserHistoryHostProfile profile(final String... contextClasses) {
        return new FileChooserHistoryHostProfile(
            "5.3.02",
            List.of(
                new FileChooserHistoryHostProfile.SaveDialogMethod(
                    "c", "(Ljava/lang/Object;)Ljava/io/File;"
                )
            ),
            List.of(contextClasses)
        );
    }

    private static FileChooserHistoryService disabledService() {
        return new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() { return Optional.empty(); }
            @Override public Optional<Path> exportRecentDirectory() { return Optional.empty(); }
            @Override public void setProjectRecentDirectory(final Path dir) { }
            @Override public void setExportRecentDirectory(final Path dir) { }
            @Override public boolean exportSeparationEnabled() { return false; }
            @Override public Registration registerProvider(final Provider provider) { return () -> { }; }
        };
    }

    private void install(
        final FileChooserHistoryService service,
        final FileChooserHistoryHostProfile profile
    ) {
        installedBridge = new NativeFileChooserHistoryBridge(service, profile);
        NativeFileChooserHistoryBridge.install(installedBridge);
    }

    @Test
    void noInstalledBridgeIsNoOp() {
        NativeFileChooserHistoryBridge.onSaveDialogPreparing(new Object());
        NativeFileChooserHistoryBridge.onSaveDialogFinished(new Object());
    }

    @Test
    void disabledServiceIsNoOp() {
        install(disabledService(), profile(EXPORT_CONTEXT));
        final FakeChooser chooser = new FakeChooser(new ArrayList<>(), new FakeChooserImpl());

        NativeFileChooserHistoryBridge.onSaveDialogPreparing(chooser);
        NativeFileChooserHistoryBridge.onSaveDialogFinished(chooser);

        assertTrue(chooser.b.isEmpty());
        assertNull(chooser.d.currentDirectory);
    }

    @Test
    void exportContextAppliesAndCapturesHistory() {
        final Path exportDir = tempDir.resolve("export-saves");
        assertTrue(exportDir.toFile().mkdirs());
        final AtomicReference<Path> captured = new AtomicReference<>();
        final FileChooserHistoryService service = new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() { return Optional.empty(); }
            @Override public Optional<Path> exportRecentDirectory() { return Optional.of(exportDir); }
            @Override public void setProjectRecentDirectory(final Path dir) { }
            @Override public void setExportRecentDirectory(final Path dir) { captured.set(dir); }
            @Override public boolean exportSeparationEnabled() { return true; }
            @Override public Registration registerProvider(final Provider provider) { return () -> { }; }
        };
        install(service, profile(EXPORT_CONTEXT));

        final FakeChooserImpl impl = new FakeChooserImpl();
        final FakeChooser chooser = new FakeChooser(new ArrayList<>(), impl);

        NativeFileChooserHistoryBridge.onSaveDialogPreparing(chooser);

        assertEquals(List.of(exportDir.toFile()), chooser.b);
        assertEquals(exportDir.toFile(), impl.currentDirectory);

        NativeFileChooserHistoryBridge.onSaveDialogFinished(chooser);

        assertEquals(exportDir, captured.get());
    }

    @Test
    void projectContextAppliesAndCapturesProjectDirectory() {
        final Path projectDir = tempDir.resolve("project-saves");
        assertTrue(projectDir.toFile().mkdirs());
        final AtomicReference<Path> captured = new AtomicReference<>();
        final FileChooserHistoryService service = new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() { return Optional.of(projectDir); }
            @Override public Optional<Path> exportRecentDirectory() { return Optional.empty(); }
            @Override public void setProjectRecentDirectory(final Path dir) { captured.set(dir); }
            @Override public void setExportRecentDirectory(final Path dir) {
                throw new AssertionError("must not persist export directory outside export context");
            }
            @Override public boolean exportSeparationEnabled() { return true; }
            @Override public Registration registerProvider(final Provider provider) { return () -> { }; }
        };
        install(service, profile("com.example.never.ExportContext"));

        final FakeChooserImpl impl = new FakeChooserImpl();
        final FakeChooser chooser = new FakeChooser(new ArrayList<>(), impl);

        NativeFileChooserHistoryBridge.onSaveDialogPreparing(chooser);

        assertEquals(List.of(projectDir.toFile()), chooser.b);
        assertEquals(projectDir.toFile(), impl.currentDirectory);

        NativeFileChooserHistoryBridge.onSaveDialogFinished(chooser);

        assertEquals(projectDir, captured.get());
    }

    @Test
    void captureFallsBackToSelectedFileWhenHistoryIsEmpty() {
        final File selected = tempDir.resolve("selected.cmo3").toFile();
        try {
            assertTrue(selected.createNewFile());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        final AtomicReference<Path> captured = new AtomicReference<>();
        final FileChooserHistoryService service = new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() { return Optional.empty(); }
            @Override public Optional<Path> exportRecentDirectory() { return Optional.empty(); }
            @Override public void setProjectRecentDirectory(final Path dir) { }
            @Override public void setExportRecentDirectory(final Path dir) { captured.set(dir); }
            @Override public boolean exportSeparationEnabled() { return true; }
            @Override public Registration registerProvider(final Provider provider) { return () -> { }; }
        };
        install(service, profile(EXPORT_CONTEXT));

        final FakeChooserImpl impl = new FakeChooserImpl();
        impl.selectedFile = selected;
        final FakeChooser chooser = new FakeChooser(null, impl);

        NativeFileChooserHistoryBridge.onSaveDialogFinished(chooser);

        assertEquals(tempDir.toAbsolutePath(), captured.get());
    }

    @Test
    void applyIsSkippedWhenExportDirectoryIsUnset() {
        final FileChooserHistoryService service = new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() { return Optional.empty(); }
            @Override public Optional<Path> exportRecentDirectory() { return Optional.empty(); }
            @Override public void setProjectRecentDirectory(final Path dir) { }
            @Override public void setExportRecentDirectory(final Path dir) { }
            @Override public boolean exportSeparationEnabled() { return true; }
            @Override public Registration registerProvider(final Provider provider) { return () -> { }; }
        };
        install(service, profile(EXPORT_CONTEXT));

        final FakeChooserImpl impl = new FakeChooserImpl();
        final FakeChooser chooser = new FakeChooser(new ArrayList<>(), impl);

        NativeFileChooserHistoryBridge.onSaveDialogPreparing(chooser);

        assertTrue(chooser.b.isEmpty());
        assertNull(impl.currentDirectory);
    }

    @Test
    void captureIsSkippedWhenNothingWasChosen() {
        final AtomicReference<Path> captured = new AtomicReference<>();
        final FileChooserHistoryService service = new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() { return Optional.empty(); }
            @Override public Optional<Path> exportRecentDirectory() { return Optional.empty(); }
            @Override public void setProjectRecentDirectory(final Path dir) { captured.set(dir); }
            @Override public void setExportRecentDirectory(final Path dir) { }
            @Override public boolean exportSeparationEnabled() { return true; }
            @Override public Registration registerProvider(final Provider provider) { return () -> { }; }
        };
        install(service, profile("com.example.never.ExportContext"));

        // Empty history and no selected file: nothing is captured and no slot is cleared.
        final FakeChooser chooser = new FakeChooser(new ArrayList<>(), new FakeChooserImpl());

        NativeFileChooserHistoryBridge.onSaveDialogFinished(chooser);

        assertNull(captured.get());
    }

    @Test
    void malformedChooserFailsClosed() {
        final AtomicReference<Path> captured = new AtomicReference<>();
        final FileChooserHistoryService service = new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() { return Optional.empty(); }
            @Override public Optional<Path> exportRecentDirectory() {
                return Optional.of(tempDir);
            }
            @Override public void setProjectRecentDirectory(final Path dir) { }
            @Override public void setExportRecentDirectory(final Path dir) { captured.set(dir); }
            @Override public boolean exportSeparationEnabled() { return true; }
            @Override public Registration registerProvider(final Provider provider) { return () -> { }; }
        };
        install(service, profile(EXPORT_CONTEXT));

        // Object without the expected chooser fields: adapter throws, bridge swallows.
        NativeFileChooserHistoryBridge.onSaveDialogPreparing(new Object());
        NativeFileChooserHistoryBridge.onSaveDialogFinished(new Object());

        assertNull(captured.get());
    }

    /** Chooser stand-in exposing the reviewed host shape: fields {@code b} and {@code d}. */
    static final class FakeChooser {
        List<File> b;
        FakeChooserImpl d;

        FakeChooser(final List<File> b, final FakeChooserImpl d) {
            this.b = b;
            this.d = d;
        }
    }

    static final class FakeChooserImpl {
        File currentDirectory;
        File selectedFile;

        public void setCurrentDirectory(final File directory) {
            currentDirectory = directory;
        }

        public File getSelectedFile() {
            return selectedFile;
        }
    }
}

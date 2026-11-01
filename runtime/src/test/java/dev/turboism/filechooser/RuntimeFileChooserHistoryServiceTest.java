package dev.turboism.filechooser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.RuntimeConfigRepository;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.runtime.RuntimeSettings;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeFileChooserHistoryServiceTest {

    @TempDir
    Path home;

    /** Records provider calls; loads empty unless a slot was saved. */
    private static final class RecordingProvider implements FileChooserHistoryService.Provider {
        final AtomicReference<Path> project = new AtomicReference<>();
        final AtomicReference<Path> export = new AtomicReference<>();

        @Override
        public Optional<Path> loadProjectDirectory() {
            return Optional.ofNullable(project.get());
        }

        @Override
        public Optional<Path> loadExportDirectory() {
            return Optional.ofNullable(export.get());
        }

        @Override
        public void saveProjectDirectory(final Path dir) {
            project.set(dir);
        }

        @Override
        public void saveExportDirectory(final Path dir) {
            export.set(dir);
        }
    }

    @Test
    void availabilityFollowsProviderRegistration() {
        final RuntimeFileChooserHistoryService service = service(true);
        assertFalse(service.isAvailable(), "without a provider the service fails closed");

        final FileChooserHistoryService.Registration registration = service.registerProvider(new RecordingProvider());
        assertTrue(service.isAvailable());

        registration.close();
        assertFalse(service.isAvailable(), "a withdrawn provider must flip the probe back");
    }

    private RuntimeFileChooserHistoryService service(final boolean enabled) {
        return new RuntimeFileChooserHistoryService(() -> enabled);
    }

    @Test
    void withoutProviderReadsAreEmptyAndWritesAreNoOp() {
        final RuntimeFileChooserHistoryService service = service(true);

        assertTrue(service.projectRecentDirectory().isEmpty());
        assertTrue(service.exportRecentDirectory().isEmpty());

        service.setProjectRecentDirectory(home.resolve("project-saves"));
        service.setExportRecentDirectory(home.resolve("export-saves"));
        assertTrue(service.projectRecentDirectory().isEmpty());
        assertTrue(service.exportRecentDirectory().isEmpty());
    }

    @Test
    void afterRegisterProviderReadWritesDelegate() {
        final RuntimeFileChooserHistoryService service = service(true);
        final RecordingProvider provider = new RecordingProvider();
        service.registerProvider(provider);

        final Path project = home.resolve("project-saves");
        final Path export = home.resolve("export-saves");
        service.setProjectRecentDirectory(project);
        service.setExportRecentDirectory(export);

        assertEquals(project, service.projectRecentDirectory().orElseThrow());
        assertEquals(export, service.exportRecentDirectory().orElseThrow());
        assertEquals(project, provider.project.get());
        assertEquals(export, provider.export.get());
    }

    @Test
    void afterUnregisterServiceFailsClosedAgain() {
        final RuntimeFileChooserHistoryService service = service(true);
        final RecordingProvider provider = new RecordingProvider();
        final FileChooserHistoryService.Registration registration = service.registerProvider(provider);

        service.setProjectRecentDirectory(home.resolve("project-saves"));
        assertTrue(service.projectRecentDirectory().isPresent());

        registration.unregister();
        assertTrue(service.projectRecentDirectory().isEmpty());
        service.setExportRecentDirectory(home.resolve("export-saves"));
        assertTrue(service.exportRecentDirectory().isEmpty());
    }

    @Test
    void nullProviderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service(true).registerProvider(null));
    }

    @Test
    void duplicateRegistrationThrowsIllegalState() {
        final RuntimeFileChooserHistoryService service = service(true);
        service.registerProvider(new RecordingProvider());

        assertThrows(IllegalStateException.class, () -> service.registerProvider(new RecordingProvider()));
    }

    @Test
    void registrationIsReusableAfterUnregister() {
        final RuntimeFileChooserHistoryService service = service(true);
        final FileChooserHistoryService.Registration first = service.registerProvider(new RecordingProvider());
        first.unregister();

        final RecordingProvider second = new RecordingProvider();
        service.registerProvider(second);
        service.setProjectRecentDirectory(home.resolve("project-saves"));
        assertEquals(home.resolve("project-saves"), second.project.get());
    }

    @Test
    void exportSeparationFollowsSettingsFileServiceWrites() {
        final RuntimeConfigRepository config = new RuntimeConfigRepository(home, ignored -> {});
        final RuntimeFileChooserHistoryService fileChooser = new RuntimeFileChooserHistoryService(() -> config.read()
                .path("hooks")
                .path("startup")
                .path("separateExportSaveDirectory")
                .asBoolean(false));
        final dev.turboism.config.RuntimeSettingsFileService settings =
                new dev.turboism.config.RuntimeSettingsFileService(
                        config, new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator());

        assertFalse(fileChooser.exportSeparationEnabled());
        settings.save(new RuntimeSettings(false, "INFO", 100, false, false, false, true));
        assertTrue(fileChooser.exportSeparationEnabled());
    }

    @Test
    void nullDirectoryIsRejected() {
        final RuntimeFileChooserHistoryService service = service(true);
        service.registerProvider(new RecordingProvider());
        assertThrows(NullPointerException.class, () -> service.setProjectRecentDirectory(null));
        assertThrows(NullPointerException.class, () -> service.setExportRecentDirectory(null));
    }

    @Test
    void unavailableInstanceIsFailClosed() {
        final FileChooserHistoryService unavailable = FileChooserHistoryService.unavailable();

        assertTrue(unavailable.projectRecentDirectory().isEmpty());
        assertTrue(unavailable.exportRecentDirectory().isEmpty());
        assertFalse(unavailable.exportSeparationEnabled());
        assertThrows(UnsupportedOperationException.class, () -> unavailable.setProjectRecentDirectory(home));
        assertThrows(UnsupportedOperationException.class, () -> unavailable.setExportRecentDirectory(home));
        assertThrows(UnsupportedOperationException.class, () -> unavailable.registerProvider(new RecordingProvider()));
    }

    @Test
    void pluginIdentityGetsAReadOnlyViewThatDelegatesReads() {
        final RuntimeFileChooserHistoryService service = service(true);
        final RecordingProvider provider = new RecordingProvider();
        service.registerProvider(provider);
        provider.project.set(Path.of("/shell/project"));
        provider.export.set(Path.of("/shell/export"));

        final FileChooserHistoryService pluginView =
                FileChooserHistoryAdmission.forCaller("dev.turboism.plugin.someone", service);

        assertEquals(Optional.of(Path.of("/shell/project")), pluginView.projectRecentDirectory());
        assertEquals(Optional.of(Path.of("/shell/export")), pluginView.exportRecentDirectory());
        assertTrue(pluginView.exportSeparationEnabled(), "the separation flag stays readable");
        assertTrue(pluginView.isAvailable(), "reads stay available while the shell provider is registered");
    }

    @Test
    void pluginIdentityCannotWriteThePersistedHistory() {
        final RuntimeFileChooserHistoryService service = service(true);
        final RecordingProvider provider = new RecordingProvider();
        service.registerProvider(provider);
        final FileChooserHistoryService pluginView =
                FileChooserHistoryAdmission.forCaller("dev.turboism.plugin.someone", service);

        final UnsupportedOperationException projectWrite = assertThrows(
                UnsupportedOperationException.class,
                () -> pluginView.setProjectRecentDirectory(Path.of("/tmp/plugin-chosen")));
        final UnsupportedOperationException exportWrite = assertThrows(
                UnsupportedOperationException.class,
                () -> pluginView.setExportRecentDirectory(Path.of("/tmp/plugin-chosen")));

        assertEquals(FileChooserHistoryAdmission.WRITE_REFUSED_MESSAGE, projectWrite.getMessage());
        assertEquals(FileChooserHistoryAdmission.WRITE_REFUSED_MESSAGE, exportWrite.getMessage());
        assertEquals(null, provider.project.get(), "a refused write must not reach the provider");
        assertEquals(null, provider.export.get(), "a refused write must not reach the provider");
    }

    @Test
    void pluginIdentityCannotRegisterAProvider() {
        final RuntimeFileChooserHistoryService service = service(true);
        final FileChooserHistoryService pluginView =
                FileChooserHistoryAdmission.forCaller("dev.turboism.plugin.someone", service);

        final UnsupportedOperationException refusal = assertThrows(
                UnsupportedOperationException.class,
                () -> pluginView.registerProvider(new RecordingProvider()));
        assertTrue(refusal.getMessage().contains(FileChooserHistoryAdmission.WRITER_IDENTITY), refusal.getMessage());
        assertFalse(service.isAvailable(), "the refused registration must not install a provider");
    }

    @Test
    void shellIdentityKeepsTheWritableSurface() {
        final RuntimeFileChooserHistoryService service = service(true);
        final RecordingProvider provider = new RecordingProvider();
        service.registerProvider(provider);

        final FileChooserHistoryService shellView =
                FileChooserHistoryAdmission.forCaller(FileChooserHistoryAdmission.WRITER_IDENTITY, service);

        assertEquals(service, shellView, "the shell keeps the shared writable instance");
        shellView.setProjectRecentDirectory(Path.of("/shell/project"));
        assertEquals(Path.of("/shell/project"), provider.project.get());
    }

    @Test
    void enabledFlagIsReadOnEveryCall() {
        final AtomicBoolean flag = new AtomicBoolean(false);
        final RuntimeFileChooserHistoryService service = new RuntimeFileChooserHistoryService(flag::get);

        assertFalse(service.exportSeparationEnabled());
        flag.set(true);
        assertTrue(service.exportSeparationEnabled());
    }
}

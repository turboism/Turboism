package dev.turboism.plugin.core.service;

import dev.turboism.plugin.core.CorePluginManagement;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HexFormat;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainToolbarHomeEntryServiceTest {

    @Test
    void packagedToolbarIconsMatchReviewedAssets() throws Exception {
        assertEquals(
            "79ce45cc0ff477224ba5b3484fd5c0175c869ece823baeb62ff3c777a4e45586",
            sha256Resource("/icons/main-toolbar-home.png")
        );
        assertEquals(
            "85b256dc4a5d2b6a0c9d6db8c119b14c0afd5634e8d0b66db7fe5cb17b53ec68",
            sha256Resource("/icons/main-toolbar-home-hover.png")
        );
        assertEquals(
            "2a0017255b5e310c48d2725a25e4e9665e0b26af11272d53dfda7c45f7e2b3f4",
            sha256Resource("/icons/main-toolbar-installer.png")
        );
        try (InputStream stream = MainToolbarHomeEntryService.class.getResourceAsStream("/icons/main-toolbar-installer.png")) {
            assertNotNull(stream, "missing packaged installer toolbar icon");
            final BufferedImage icon = ImageIO.read(stream);
            assertNotNull(icon, "installer toolbar icon must decode as a PNG");
            assertEquals(32, icon.getWidth(), "installer toolbar icon width");
            assertEquals(32, icon.getHeight(), "installer toolbar icon height");
            final int[] bounds = alphaBounds(icon);
            assertEquals(7, bounds[0], "installer icon left padding");
            assertEquals(6, bounds[1], "installer icon top padding");
            assertEquals(18, bounds[2], "installer icon visible width");
            assertEquals(20, bounds[3], "installer icon visible height");
        }
    }

    @Test
    void registerHomeEntry_usesInstallerIconByDefault() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost();
        MainToolbarHomeEntryService service = service(uiHost);

        // When
        service.registerHomeEntry();

        // Then
        assertEquals(
            List.of(new MainToolbarRegistry.MainToolbarButtonContribution(
                "turboism.core.home-entry",
                "turboism.core.open",
                "main-toolbar.home.aria-label",
                "main-toolbar.home.tooltip",
                MainToolbarRegistry.IconVariants.normal("icons/main-toolbar-installer.png"),
                MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                10
            )),
            uiHost.buttonContributions()
        );
    }

    @Test
    void registerHomeEntry_usesTextIconsWhenPreferenceEnabled() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost();
        MainToolbarHomeEntryService service = service(uiHost, new RuntimeSettings(
            false, "INFO", 100, false, false, false, false, "system", true
        ));

        // When
        service.registerHomeEntry();

        // Then
        assertEquals(1, uiHost.buttonContributions().size());
        final MainToolbarRegistry.IconVariants icons = uiHost.buttonContributions().get(0).icons();
        assertEquals("icons/main-toolbar-home.png", icons.normal());
        assertEquals(Optional.of("icons/main-toolbar-home-hover.png"), icons.hover());
        assertTrue(icons.selected().isEmpty());
        assertTrue(icons.disabled().isEmpty());
        assertTrue(icons.light().isEmpty());
        assertTrue(icons.dark().isEmpty());
    }

    @Test
    void registerHomeEntry_registrationRemovesToolbarContributionWhenClosed() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost();
        MainToolbarHomeEntryService service = service(uiHost);

        // When
        Registration registration = service.registerHomeEntry();
        registration.close();

        // Then
        assertTrue(uiHost.buttonContributions().isEmpty());
    }

    @Test
    void openTurboismPanel_requestsTypedPanelActivation() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost();
        MainToolbarHomeEntryService service = service(uiHost);

        // When
        service.openTurboismPanel();

        // Then
        assertEquals(
            List.of(EmbeddedPanelId.of("turboism.panel.main")),
            uiHost.activatedPanels()
        );
        assertTrue(uiHost.notifications().isEmpty());
    }


    private static String sha256Resource(final String path) throws Exception {
        try (InputStream stream = MainToolbarHomeEntryService.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing packaged toolbar icon " + path);
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())
            );
        }
    }

    private static int[] alphaBounds(final BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertTrue(maxX >= minX && maxY >= minY, "icon must contain visible pixels");
        return new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
    }
    private static MainToolbarHomeEntryService service(final RecordingUiHost uiHost) {
        return service(uiHost, new RuntimeSettings(false, "INFO", false, false, false));
    }

    private static MainToolbarHomeEntryService service(
        final RecordingUiHost uiHost,
        final RuntimeSettings settings
    ) {
        final MenuRegistry menus = contribution -> () -> { };
        final PluginLocalization localization = new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return "Settings";
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return text(key);
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }
        };
        return new MainToolbarHomeEntryService(
            uiHost, toolbar(uiHost), menus, localization,
            new RuntimeSettingsService() {
                @Override
                public RuntimeSettings read() {
                    return settings;
                }

                @Override
                public RuntimeSettings save(final RuntimeSettings value) {
                    return value;
                }

                @Override
                public RuntimeSettingsService.DockCleanupResult cleanEmptyDocks() {
                    return new RuntimeSettingsService.DockCleanupResult("Empty dock cleanup completed.");
                }
            },
            new CorePluginManagement() {
                @Override
                public List<PluginInfo> plugins() {
                    return List.of();
                }

                @Override
                public OperationResult install() {
                    return OperationResult.rejected("Unavailable");
                }

                @Override
                public OperationResult uninstall(final String id) {
                    return OperationResult.rejected("Unavailable");
                }

                @Override
                public OperationResult setEnabled(final String id, final boolean enabled) {
                    return OperationResult.rejected("Unavailable");
                }
            }
        );
    }

    private static MainToolbarRegistry toolbar(final RecordingUiHost uiHost) {
        return new MainToolbarRegistry() {
            @Override
            public Registration contribute(final MainToolbarContribution contribution) {
                return uiHost.contributeMainToolbar(contribution);
            }

            @Override
            public Registration contributeButton(final MainToolbarButtonContribution contribution) {
                uiHost.buttonContributions.add(contribution);
                return () -> uiHost.buttonContributions.remove(contribution);
            }
        };
    }

    private static final class ProjectWithoutWorkspace extends EmptyProjectRead {
        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.of(new ProjectSnapshot(
                "project-1",
                "Demo Project",
                Optional.of(Path.of("projects/demo")),
                List.of(new DocumentSnapshot("doc-1", "Model A", "model-a.cmo3", Optional.empty(), Optional.empty()))
            ));
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            return Optional.empty();
        }
    }

    private static final class ProjectReadFixtures extends EmptyProjectRead {
        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.of(new ProjectSnapshot(
                "project-1",
                "Demo Project",
                Optional.of(Path.of("projects/demo")),
                List.of(
                    new DocumentSnapshot("doc-1", "Model A", "model-a.cmo3", Optional.empty(), Optional.empty()),
                    new DocumentSnapshot("doc-2", "Model B", "model-b.cmo3", Optional.empty(), Optional.empty())
                )
            ));
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            return Optional.of(new WorkspaceSnapshot(
                "workspace-1",
                "Modeling",
                "layouts/workspace-1",
                List.of("project-1", "project-0")
            ));
        }
    }

    private static class EmptyProjectRead implements CubismReadCapabilityService {
        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            throw unsupported();
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            throw unsupported();
        }

        @Override
        public SelectionSnapshot selection() {
            throw unsupported();
        }

        @Override
        public List<ParameterSnapshot> parameters() {
            throw unsupported();
        }

        @Override
        public List<ModelObjectSnapshot> modelObjects() {
            throw unsupported();
        }

        @Override
        public List<ArtMeshSnapshot> meshes() {
            throw unsupported();
        }

        @Override
        public List<DeformerSnapshot> deformers() {
            throw unsupported();
        }

        @Override
        public List<PsdDocumentSnapshot> psdDocuments() {
            throw unsupported();
        }

        @Override
        public List<ClipMaskSnapshot> clipMasks() {
            throw unsupported();
        }

        @Override
        public List<TextureAtlasSnapshot> textureAtlases() {
            throw unsupported();
        }

        @Override
        public Optional<RenderStatusSnapshot> renderStatus() {
            throw unsupported();
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            return Optional.empty();
        }

        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this behavior test");
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<MainToolbarRegistry.MainToolbarButtonContribution> buttonContributions = new ArrayList<>();
        private final List<EmbeddedPanelId> activatedPanels = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        List<MainToolbarRegistry.MainToolbarButtonContribution> buttonContributions() {
            return buttonContributions;
        }

        List<EmbeddedPanelId> activatedPanels() {
            return activatedPanels;
        }

        List<StatusNotification> notifications() {
            return notifications;
        }

        @Override
        public Registration contributeOverlay(OverlayContribution contribution) {
            throw new UnsupportedOperationException("overlay contributions are not used by this service");
        }

        @Override
        public Registration contributeBoundingBoxOverlayButton(
            final dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution
        ) {
            throw new UnsupportedOperationException("bounding-box buttons are not used by this service");
        }

        @Override
        public ContextSourceSnapshot contextSource() {
            throw new UnsupportedOperationException("context source is not used by this service");
        }

        @Override
        public ViewportSnapshot viewport() {
            throw new UnsupportedOperationException("viewport is not used by this service");
        }

        @Override
        public Registration openDialog(DialogRequest request) {
            throw new UnsupportedOperationException("dialogs are not used by this service");
        }

        @Override
        public boolean confirmDialog(DialogRequest request) {
            throw new UnsupportedOperationException("dialogs are not used by this service");
        }

        @Override
        public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) {
            throw new UnsupportedOperationException("embedded panels are not used by this service");
        }

        @Override
        public void activateEmbeddedPanel(final EmbeddedPanelId panelId) {
            activatedPanels.add(panelId);
        }

        @Override
        public Optional<String> requestFile(FileChooserRequest request) {
            throw new UnsupportedOperationException("file requests are not used by this service");
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }

        @Override
        public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) {
            throw new UnsupportedOperationException("context menus are not used by this service");
        }

        @Override
        public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) {
            throw new UnsupportedOperationException("legacy main toolbar contributions are not used by this service");
        }

        @Override
        public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            throw new UnsupportedOperationException("palette toolbar is not used by this service");
        }
    }
}

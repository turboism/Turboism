package dev.turboism.plugin.core;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
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
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainToolbarPluginTest {

    @Test
    void enableRegistersHomeActionAndMainToolbarContribution() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();

        assertTrue(context.actions().actions().stream()
            .map(ActionRegistry.Action::id)
            .toList()
            .containsAll(List.of(
                "turboism.core.open", "turboism.core.logs.open", "turboism.core.about.open"
            )));
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
            context.mainToolbar().buttonContributions()
        );
        assertEquals(2, context.uiHost().settingsContributions().size());
        assertEquals(
            List.of("cubism-graalvm-path", "cubism-jvm"),
            context.uiHost().settingsContributions().stream()
                .map(dev.turboism.sdk.ui.settings.SettingsContribution::id)
                .toList()
        );
        assertTrue(context.uiHost().settingsContributions().stream()
            .allMatch(contribution -> contribution.tab().id().equals("performance")));
        assertEquals(1, context.uiHost().panelContributions().size());
        final EmbeddedPanelContribution panel = context.uiHost().panelContributions().get(0);
        assertEquals("turboism.panel.main", panel.id());
        assertTrue(!panel.content().toString().contains("Turboism"));
        assertTrue(!panel.content().toString().contains("open-settings"));
        assertTrue(!panel.content().toString().contains("open-plugin-management"));
        assertTrue(!panel.content().toString().contains("open-logs"));
        assertTrue(!panel.content().toString().contains("open-about"));
        assertTrue(!panel.content().toString().contains("Settings"));
        assertTrue(!panel.content().toString().contains("Plugin Management"));
        assertTrue(!panel.content().toString().contains("Logs"));
        assertTrue(!panel.content().toString().contains("Safe Mode"));
        assertEquals(
            List.of(
                "Turboism/Settings:turboism.core.settings.open:10",
                "Turboism/Plugin Management:turboism.core.plugins.open:11",
                "Turboism/Logs:turboism.core.logs.open:12",
                "Turboism/About:turboism.core.about.open:13"
            ),
            context.menus().contributions().stream()
                .map(value -> value.menuPath() + ":" + value.actionId() + ":" + value.order())
                .toList()
        );
    }

    @Test
    void enabledTextIconPreferenceKeepsMainToolbarContributionContract() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        MainToolbarPlugin plugin = plugin(true);

        plugin.init(context);
        plugin.enable();

        assertEquals(
            List.of(new MainToolbarRegistry.MainToolbarButtonContribution(
                "turboism.core.home-entry",
                "turboism.core.open",
                "main-toolbar.home.aria-label",
                "main-toolbar.home.tooltip",
                new MainToolbarRegistry.IconVariants(
                    "icons/main-toolbar-home.png", Optional.of("icons/main-toolbar-home-hover.png"),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
                ),
                MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                10
            )),
            context.mainToolbar().buttonContributions()
        );
    }

    @Test
    void homeActionRoutesToSettingsWindow_whenInvoked() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("turboism.core.open");

        // The toolbar home action routes to the settings window (CoreDialogs
        // dispatches window construction to the EDT), never activating the
        // blank Turboism panel tab, and never emitting a status notification.
        assertTrue(context.uiHost().activatedPanels().isEmpty());
        assertTrue(context.uiHost().notifications().isEmpty());
    }

    @Test
    void cleanEmptyDocksActionUsesRuntimeSettingsService() throws Exception {
        final int[] cleanups = {0};
        final RuntimeSettingsService settings = new RuntimeSettingsService() {
            private RuntimeSettings value =
                new RuntimeSettings(false, "INFO", false, false, false);

            @Override public RuntimeSettings read() { return value; }
            @Override public RuntimeSettings save(final RuntimeSettings next) {
                value = next;
                return value;
            }
            @Override public DockCleanupResult cleanEmptyDocks() {
                cleanups[0]++;
                return new DockCleanupResult("Empty dock cleanup completed.");
            }
        };
        final MainToolbarPlugin plugin = CorePluginServices.instantiate(
            new CorePluginServices(settings, plugins()),
            MainToolbarPlugin::new
        );
        final RecordingPluginContext context = new RecordingPluginContext();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("settings.clean-empty-docks");

        assertEquals(1, cleanups[0]);
    }

    @Test
    void disposableScopeClosesActionAndToolbarContribution() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();

        assertTrue(context.actions().actions().isEmpty());
        assertTrue(context.mainToolbar().buttonContributions().isEmpty());
        assertTrue(context.uiHost().panelContributions().isEmpty());
        assertTrue(context.uiHost().settingsContributions().isEmpty());
        assertTrue(context.menus().contributions().isEmpty());
    }

    @Test
    void installActionUsesInteractiveRequestAndScopeClosesManagement() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext();
        final RecordingPluginManagement management = new RecordingPluginManagement();
        final MainToolbarPlugin plugin = plugin(management);

        plugin.init(context);
        plugin.enable();
        context.actions().execute("turboism.core.plugins.install");

        assertEquals(1, management.requests);
        assertEquals(0, management.synchronousInstalls);
        context.disposableScope().close();
        assertTrue(management.closed);
    }

    @Test
    void pluginActionFailureIsReportedAndPanelRefreshesWithoutThrowing() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext();
        final CorePluginManagement management = new CorePluginManagement() {
            @Override public List<PluginInfo> plugins() {
                return List.of(new PluginInfo(
                    "example.plugin", "Example", "1.0.0", "", "ENABLED", "ENABLED", false, Optional.empty(),
                    "other", List.of()
                ));
            }
            @Override public OperationResult install() { return OperationResult.rejected("Unavailable"); }
            @Override public OperationResult uninstall(final String id) { return OperationResult.rejected("Unavailable"); }
            @Override public OperationResult setEnabled(final String id, final boolean enabled) {
                throw new IllegalStateException("duplicate desired-state write");
            }
        };
        final MainToolbarPlugin plugin = plugin(management);

        plugin.init(context);
        plugin.enable();
        context.actions().execute("turboism.core.plugins.disable.example.plugin");

        assertEquals(1, context.uiHost().panelContributions().size());
        assertTrue(context.recordedLogger().warnings.contains("Plugin operation failed safely."));
    }

    @Test
    void enableAllowsMainToolbar_whenPermissionGranted() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(true, true));
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(1, context.mainToolbar().buttonContributions().size());
    }

    @Test
    void enableDeniesMainToolbar_whenPermissionMissing() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(false, true));
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        CubismPermissionException denied = assertThrows(
            CubismPermissionException.class,
            plugin::enable
        );
        assertTrue(denied.getMessage().contains(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE));
        assertTrue(context.mainToolbar().buttonContributions().isEmpty());
    }

    @Test
    void homeActionDoesNotRequireStatusNotificationPermission() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(true, false));
        MainToolbarPlugin plugin = plugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("turboism.core.open");

        assertTrue(context.uiHost().activatedPanels().isEmpty());
        assertEquals(List.of(), context.uiHost().notifications());
    }

    @Test
    void anAvailableUpdateIsShownAsAClickableCanvasHintAndRenewsWhileItPersists() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();
        context.useInlineUiScheduler();

        plugin.init(context);
        plugin.enable();
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UPDATE_AVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.of("0.43.10"),
            java.util.OptionalLong.of(5L),
            false,
            true
        ));

        // The update is presented in the host's own drawing-area hint, not in the docked panel.
        assertTrue(context.uiHost().panelContributions().stream().noneMatch(
            contribution -> contribution.content().toString().contains("updates")
        ));
        assertEquals(List.of(), context.uiHost().notifications());

        final RecordedHint hint = context.uiHost().lastCanvasHint();
        assertNotNull(hint, "an available update must issue a canvas hint");
        assertEquals("turboism-update-available", hint.notification.id());
        assertTrue(hint.notification.message().contains("0.43.10 (Build 5)"));
        assertTrue(hint.notification.onClick().isPresent(), "the hint must be clickable");
        assertEquals(
            dev.turboism.sdk.ui.CanvasHintNotification.UNTIL_DISMISSED,
            hint.notification.durationSeconds()
        );

        // The condition watch keeps it alive while the update is still offered.
        assertTrue(context.hasDelayedUiWork());
        context.stepDelayedUiWork();
        assertTrue(hint.renewals >= 1, "the persistent hint must be renewed");
        assertFalse(hint.closed);
    }

    @Test
    void theCanvasHintClearsItselfOnceTheUpdateIsNoLongerOffered() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();
        context.useInlineUiScheduler();

        plugin.init(context);
        plugin.enable();
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UPDATE_AVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.of("0.43.10"),
            java.util.OptionalLong.of(5L),
            false,
            true
        ));
        final RecordedHint hint = context.uiHost().lastCanvasHint();
        assertNotNull(hint);

        // The condition now fails, so the watch releases the hint instead of renewing it.
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UP_TO_DATE,
            "0.43.10 (stable, Build 4)",
            Optional.empty(),
            java.util.OptionalLong.empty(),
            false,
            false
        ));
        assertTrue(hint.closed, "a resolved update must clear its hint");
    }

    @Test
    void aCheckInFlightLeavesTheExistingHintAloneInsteadOfFlickering() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();
        context.useInlineUiScheduler();

        plugin.init(context);
        plugin.enable();
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UPDATE_AVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.of("0.43.10"),
            java.util.OptionalLong.of(5L),
            false,
            true
        ));
        final RecordedHint hint = context.uiHost().lastCanvasHint();
        assertNotNull(hint);

        // Starting another check says nothing about the update, so the message stays readable.
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.CHECKING,
            "0.43.10 (stable, Build 4)",
            Optional.empty(),
            java.util.OptionalLong.empty(),
            true,
            false
        ));
        assertFalse(hint.closed);
        assertEquals(1, context.uiHost().canvasHints.size());
        // The watch agrees: an in-flight check keeps the message up.
        context.stepDelayedUiWork();
        assertTrue(hint.renewals >= 1);
        assertFalse(hint.closed);
    }

    @Test
    void aNewerOfferedBuildReplacesTheHintMessageInsteadOfLeavingTheOldOne() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();
        context.useInlineUiScheduler();

        plugin.init(context);
        plugin.enable();
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UPDATE_AVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.of("0.43.10"),
            java.util.OptionalLong.of(5L),
            false,
            true
        ));
        final RecordedHint first = context.uiHost().lastCanvasHint();
        assertNotNull(first);

        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UPDATE_AVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.of("0.43.11"),
            java.util.OptionalLong.of(6L),
            false,
            true
        ));

        assertTrue(first.closed, "the previous build's message must not stay on screen");
        final RecordedHint second = context.uiHost().lastCanvasHint();
        assertNotNull(second);
        assertTrue(second.notification.message().contains("0.43.11 (Build 6)"));
        assertEquals(2, context.uiHost().canvasHints.size());
    }

    @Test
    void aQuietAutomaticFailureShowsNothingWhileAManualCheckReportsItsResult() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();
        context.useInlineUiScheduler();

        plugin.init(context);
        plugin.enable();

        // An automatic failure is silent: no hint, no notification.
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UNAVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.empty(),
            java.util.OptionalLong.empty(),
            false,
            false
        ));
        assertEquals(List.of(), context.uiHost().notifications());
        assertNull(context.uiHost().lastDismissibleCanvasHint());
        assertNull(context.uiHost().lastCanvasHint());

        // The same failure, but the user asked for it, must say so.
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UNAVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.empty(),
            java.util.OptionalLong.empty(),
            true,
            false
        ));
        final RecordedHint result = context.uiHost().lastDismissibleCanvasHint();
        assertNotNull(result, "a user-requested failure must be reported");
        assertEquals("Turboism updates are currently unavailable.", result.notification.message());
        assertTrue(result.notification.onClick().isPresent(), "the result hint must dismiss on click");
        assertEquals(List.of(), context.uiHost().notifications());
    }

    @Test
    void anUpToDateManualCheckIsReportedButAnAutomaticOneIsNot() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();
        context.useInlineUiScheduler();

        plugin.init(context);
        plugin.enable();
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UP_TO_DATE,
            "0.43.10 (stable, Build 4)",
            Optional.empty(),
            java.util.OptionalLong.empty(),
            false,
            false
        ));
        assertNull(context.uiHost().lastDismissibleCanvasHint());

        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UP_TO_DATE,
            "0.43.10 (stable, Build 4)",
            Optional.empty(),
            java.util.OptionalLong.empty(),
            true,
            false
        ));
        final RecordedHint result = context.uiHost().lastDismissibleCanvasHint();
        assertNotNull(result);
        assertEquals(
            "Turboism 0.43.10 (stable, Build 4) is up to date.",
            result.notification.message()
        );
    }

    @Test
    void clickingTheCanvasHintDismissesItWithoutOpeningAnything() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();
        context.useInlineUiScheduler();
        final List<String> opened = new ArrayList<>();
        dev.turboism.plugin.core.CoreWindows.setTestUpdateUrlObserver(opened::add);
        try {
            plugin.init(context);
            plugin.enable();
            updates.publish(new CoreUpdateService.Snapshot(
                CoreUpdateService.Status.UPDATE_AVAILABLE,
                "0.43.10 (stable, Build 4)",
                Optional.of("0.43.11"),
                java.util.OptionalLong.of(7L),
                false,
                true
            ));
            final RecordedHint hint = context.uiHost().lastCanvasHint();
            assertNotNull(hint);
            hint.notification.onClick().orElseThrow().run();
            assertTrue(hint.closed, "clicking the hint must dismiss it");
        } finally {
            dev.turboism.plugin.core.CoreWindows.clearTestUpdateUrlObserver();
        }

        assertEquals(List.of(), opened, "the hint must not open anything");
    }

    @Test
    void aDismissedBuildDoesNotComeStraightBackButANewerOneDoes() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();
        context.useInlineUiScheduler();

        plugin.init(context);
        plugin.enable();
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UPDATE_AVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.of("0.43.11"),
            java.util.OptionalLong.of(7L),
            false,
            true
        ));
        final RecordedHint dismissed = context.uiHost().lastCanvasHint();
        assertNotNull(dismissed);
        dismissed.notification.onClick().orElseThrow().run();

        // The same offered build must not reappear merely because another snapshot arrived.
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UPDATE_AVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.of("0.43.11"),
            java.util.OptionalLong.of(7L),
            false,
            true
        ));
        assertEquals(1, context.uiHost().canvasHints.size(), "the dismissed build reappeared");

        // A newer build is new information, so it is shown even though the last one was dismissed.
        updates.publish(new CoreUpdateService.Snapshot(
            CoreUpdateService.Status.UPDATE_AVAILABLE,
            "0.43.10 (stable, Build 4)",
            Optional.of("0.43.12"),
            java.util.OptionalLong.of(8L),
            false,
            true
        ));
        assertEquals(2, context.uiHost().canvasHints.size());
        assertTrue(context.uiHost().lastCanvasHint().notification.message().contains("0.43.12 (Build 8)"));
    }

    @Test
    void aCheckForUpdatesMenuItemIsContributedWithoutAddingToTheDockedPanel() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();

        plugin.init(context);
        plugin.enable();

        assertTrue(context.menus().contributions().stream().anyMatch(
            contribution -> CoreUpdateService.MANUAL_CHECK_ACTION_ID.equals(contribution.actionId())
        ));
        assertTrue(context.uiHost().panelContributions().stream().noneMatch(
            contribution -> contribution.content().toString().contains("updates")
        ));
    }

    @Test
    void updatePreferenceToggleIsContributedForTheRuntimeUpdateService() throws Exception {
        final FakeUpdateService updates = new FakeUpdateService();
        final MainToolbarPlugin plugin = plugin(updates);
        final RecordingPluginContext context = new RecordingPluginContext();

        plugin.init(context);
        plugin.enable();

        assertTrue(context.uiHost().settingsContributions().stream().anyMatch(
            contribution -> "turboism-updates-automatic".equals(contribution.id())
        ));
    }

    private static MainToolbarPlugin plugin() {
        return plugin(false);
    }

    private static MainToolbarPlugin plugin(final boolean useTextIcon) {
        return CorePluginServices.instantiate(
            new CorePluginServices(settings(useTextIcon), plugins()),
            MainToolbarPlugin::new
        );
    }

    private static MainToolbarPlugin plugin(final CoreUpdateService updates) {
        return CorePluginServices.instantiate(
            new CorePluginServices(
                settings(),
                CubismJvmSettingsService.unavailable(),
                dev.turboism.sdk.ui.settings.SettingsContributionSource.empty(),
                plugins(),
                CorePluginServices.FloatingPanelActions.unavailable(),
                dev.turboism.sdk.runtime.RuntimeLogReader.unavailable(),
                updates
            ),
            MainToolbarPlugin::new
        );
    }

    /** Scripted update service used to drive core UI behaviour without any network access. */
    private static final class FakeUpdateService implements CoreUpdateService {
        private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Snapshot>> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile Snapshot current = Snapshot.idle("0.43.10 (stable, Build 4)");
        private volatile Preferences stored = new Preferences(true);
        private boolean started;
        private boolean closed;
        private int manualChecks;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Snapshot snapshot() {
            return current;
        }

        @Override
        public Preferences preferences() {
            return stored;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public java.util.concurrent.CompletionStage<Snapshot> checkManual() {
            manualChecks++;
            return java.util.concurrent.CompletableFuture.completedFuture(current);
        }

        @Override
        public PreferenceSaveResult savePreferences(final Preferences preferences) {
            stored = preferences;
            return PreferenceSaveResult.success();
        }

        @Override
        public Registration subscribe(final java.util.function.Consumer<Snapshot> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public void close() {
            closed = true;
        }

        void publish(final Snapshot snapshot) {
            current = snapshot;
            for (final java.util.function.Consumer<Snapshot> listener : listeners) {
                listener.accept(snapshot);
            }
        }
    }

    private static MainToolbarPlugin plugin(final CorePluginManagement management) {
        return CorePluginServices.instantiate(
            new CorePluginServices(settings(), management),
            MainToolbarPlugin::new
        );
    }

    private static dev.turboism.sdk.runtime.RuntimeSettingsService settings() {
        return settings(false);
    }

    private static dev.turboism.sdk.runtime.RuntimeSettingsService settings(final boolean useTextIcon) {
        return new dev.turboism.sdk.runtime.RuntimeSettingsService() {
            private dev.turboism.sdk.runtime.RuntimeSettings value =
                new dev.turboism.sdk.runtime.RuntimeSettings(
                    false, "INFO", 100, false, false, false, false, "system", useTextIcon
                );
            @Override public dev.turboism.sdk.runtime.RuntimeSettings read() { return value; }
            @Override public dev.turboism.sdk.runtime.RuntimeSettings save(
                dev.turboism.sdk.runtime.RuntimeSettings settings
            ) { value = settings; return value; }
            @Override public DockCleanupResult cleanEmptyDocks() {
                return new DockCleanupResult("Empty dock cleanup completed.");
            }
        };
    }

    private static CorePluginManagement plugins() {
        return new CorePluginManagement() {
            @Override public List<PluginInfo> plugins() { return List.of(); }
            @Override public OperationResult install() { return OperationResult.rejected("Unavailable"); }
            @Override public OperationResult uninstall(String id) { return OperationResult.rejected("Unavailable"); }
            @Override public OperationResult setEnabled(String id, boolean enabled) {
                return OperationResult.rejected("Unavailable");
            }
        };
    }

    private static final class RecordingPluginManagement implements CorePluginManagement {
        private int requests;
        private int synchronousInstalls;
        private boolean closed;

        @Override public List<PluginInfo> plugins() { return List.of(); }
        @Override public OperationResult install() {
            synchronousInstalls++;
            return OperationResult.rejected("Unavailable");
        }
        @Override public void requestInstall(final java.util.function.Consumer<OperationResult> completion) {
            requests++;
            completion.accept(OperationResult.rejected("Cancelled"));
        }
        @Override public OperationResult uninstall(final String id) { return OperationResult.rejected("Unavailable"); }
        @Override public OperationResult setEnabled(final String id, final boolean enabled) {
            return OperationResult.rejected("Unavailable");
        }
        @Override public void close() { closed = true; }
    }

    private static final class PendingPluginManagement implements CorePluginManagement {
        private boolean disabled;
        @Override public List<PluginInfo> plugins() {
            return List.of(new PluginInfo(
                "example.plugin", "Example", "1.0.0", "", "ENABLED",
                disabled ? "DISABLED" : "ENABLED", false,
                disabled ? Optional.of("DISABLE") : Optional.empty(),
                "other", List.of()
            ));
        }
        @Override public OperationResult install() { return OperationResult.rejected("Unavailable"); }
        @Override public OperationResult uninstall(final String id) { return OperationResult.rejected("Unavailable"); }
        @Override public OperationResult setEnabled(final String id, final boolean enabled) {
            disabled = !enabled;
            return OperationResult.accepted(
                enabled ? "PLUGIN_ENABLE_PENDING" : "PLUGIN_DISABLE_PENDING",
                (enabled ? "Enable" : "Disable") + " is pending; restart Cubism to apply it."
            );
        }
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingMenuRegistry menus = new RecordingMenuRegistry();
        private final RecordingUiHost uiHost;
        private final ContextMenuRegistry contextMenu = new ContextMenuRegistry() {
            @Override
            public Registration contribute(final ContextMenuContribution contribution) {
                return () -> { };
            }
        };
        private final RecordingMainToolbarRegistry mainToolbar;
        private final DisposableScope disposableScope = new DisposableScope();
        private final PluginLogger logger = new NoopPluginLogger();
        private final CubismReadCapabilityService cubismRead = new ProjectReadCapabilityService();

        RecordingPluginContext() {
            this(new RecordingUiHost());
        }

        RecordingPluginContext(final RecordingUiHost uiHost) {
            this.uiHost = uiHost;
            this.mainToolbar = new RecordingMainToolbarRegistry(uiHost);
        }

        NoopPluginLogger recordedLogger() { return (NoopPluginLogger) logger; }

        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public CubismFacade cubism() {
            return null;
        }

        @Override
        public CubismReadCapabilityService cubismRead() {
            return cubismRead;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return null;
        }

        @Override
        public RecordingActionRegistry actions() {
            return actions;
        }

        @Override
        public RecordingMenuRegistry menus() {
            return menus;
        }

        @Override
        public RecordingMainToolbarRegistry mainToolbar() {
            return mainToolbar;
        }

        @Override
        public ContextMenuRegistry contextMenu() {
            return contextMenu;
        }

        @Override
        public RecordingUiHost uiHost() {
            return uiHost;
        }

        @Override
        public PluginLocalization localization() {
            return new PluginLocalization() {
                @Override
                public Locale locale() {
                    return Locale.ENGLISH;
                }

                @Override
                public String text(final String key) {
                    return switch (key) {
                        case "main-toolbar.settings-menu.label" -> "Settings";
                        case "main-toolbar.plugins-menu.label" -> "Plugin Management";
                        case "context-menu.panel-tab.float" -> "Float";
                        case "main-toolbar.logs-menu.label" -> "Logs";
                        case "main-toolbar.about-menu.label" -> "About";
                        default -> key;
                    };
                }

                @Override
                public String format(final String key, final Object... arguments) {
                    return text(key);
                }

                @Override
                public boolean contains(final String key) {
                    return key.equals("main-toolbar.settings-menu.label")
                        || key.equals("main-toolbar.plugins-menu.label")
                        || key.equals("context-menu.panel-tab.float")
                        || key.equals("main-toolbar.logs-menu.label")
                        || key.equals("main-toolbar.about-menu.label");
                }
            };
        }

        @Override
        public PluginConfigRegistry config() {
            return null;
        }


        @Override
        public dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings() {
            return new dev.turboism.sdk.runtime.RuntimeSettingsService() {
                private dev.turboism.sdk.runtime.RuntimeSettings settings =
                    new dev.turboism.sdk.runtime.RuntimeSettings(false, "INFO", false, false, false);
                @Override public dev.turboism.sdk.runtime.RuntimeSettings read() { return settings; }
                @Override public dev.turboism.sdk.runtime.RuntimeSettings save(
                    final dev.turboism.sdk.runtime.RuntimeSettings value
                ) { settings = value; return settings; }
                @Override public DockCleanupResult cleanEmptyDocks() {
                    return new DockCleanupResult("Empty dock cleanup completed.");
                }
            };
        }


        private UiScheduler uiScheduler;
        private final List<Runnable> delayed = new ArrayList<>();

        /**
         * Runs immediate UI work inline so snapshots can be asserted deterministically, while
         * holding delayed work back. A condition watch re-arms itself from its own delayed tick, so
         * running that inline would recurse; the test steps it explicitly instead.
         */
        void useInlineUiScheduler() {
            uiScheduler = new UiScheduler() {
                @Override
                public Registration runOnUiThread(final Runnable work) {
                    work.run();
                    return () -> { };
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final java.time.Duration delay) {
                    delayed.add(work);
                    return () -> delayed.remove(work);
                }
            };
        }

        /** Runs the oldest pending delayed task, as the real scheduler would on its next tick. */
        void stepDelayedUiWork() {
            if (delayed.isEmpty()) throw new IllegalStateException("no delayed UI work is pending");
            final Runnable next = delayed.remove(0);
            next.run();
        }

        boolean hasDelayedUiWork() {
            return !delayed.isEmpty();
        }

        @Override
        public UiScheduler uiScheduler() {
            return uiScheduler;
        }

        @Override
        public DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<Action> actions = new ArrayList<>();

        List<Action> actions() {
            return actions;
        }

        @Override
        public Registration register(String id, Action action) {
            actions.add(action);
            return () -> actions.remove(action);
        }

        void execute(String id) {
            actions.stream()
                .filter(action -> action.id().equals(id))
                .findFirst()
                .orElseThrow()
                .handler()
                .accept(new ActionContext() {
                });
        }
    }

    private static final class RecordingMenuRegistry implements MenuRegistry {
        private final List<MenuContribution> contributions = new ArrayList<>();

        List<MenuContribution> contributions() {
            return contributions;
        }

        @Override
        public Registration contribute(final MenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingMainToolbarRegistry implements MainToolbarRegistry {
        private final RecordingUiHost uiHost;
        private final List<MainToolbarButtonContribution> buttonContributions = new ArrayList<>();

        private RecordingMainToolbarRegistry(final RecordingUiHost uiHost) {
            this.uiHost = uiHost;
        }

        List<MainToolbarButtonContribution> buttonContributions() {
            return buttonContributions;
        }

        @Override
        public Registration contribute(final MainToolbarContribution contribution) {
            return uiHost.contributeMainToolbar(contribution);
        }

        @Override
        public Registration contributeButton(final MainToolbarButtonContribution contribution) {
            uiHost.requireMainToolbarPermission();
            buttonContributions.add(contribution);
            return () -> buttonContributions.remove(contribution);
        }
    }

    private static class RecordingUiHost implements UiHostCapabilityService {
        private final List<EmbeddedPanelContribution> panelContributions = new ArrayList<>();
        final List<RecordedHint> canvasHints = new ArrayList<>();
        final List<RecordedHint> dismissibleCanvasHints = new ArrayList<>();
        private final List<EmbeddedPanelId> activatedPanels = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();
        private final List<dev.turboism.sdk.ui.settings.SettingsContribution> settingsContributions =
            new ArrayList<>();

        void requireMainToolbarPermission() {
        }

        List<EmbeddedPanelContribution> panelContributions() {
            return panelContributions;
        }

        List<EmbeddedPanelId> activatedPanels() {
            return activatedPanels;
        }

        List<StatusNotification> notifications() {
            return notifications;
        }

        List<dev.turboism.sdk.ui.settings.SettingsContribution> settingsContributions() {
            return settingsContributions;
        }

        @Override
        public Registration contributeOverlay(OverlayContribution contribution) {
            throw new UnsupportedOperationException("overlay contributions are not used by this plugin test");
        }

        @Override
        public Registration contributeBoundingBoxOverlayButton(
            final dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution
        ) {
            throw new UnsupportedOperationException("bounding-box buttons are not used by this plugin test");
        }

        @Override
        public ContextSourceSnapshot contextSource() {
            throw new UnsupportedOperationException("context source is not used by this plugin test");
        }

        @Override
        public ViewportSnapshot viewport() {
            throw new UnsupportedOperationException("viewport is not used by this plugin test");
        }

        @Override
        public Registration openDialog(DialogRequest request) {
            throw new UnsupportedOperationException("dialogs are not used by this plugin test");
        }

        @Override
        public boolean confirmDialog(DialogRequest request) {
            throw new UnsupportedOperationException("dialogs are not used by this plugin test");
        }

        @Override
        public Registration contributeSettings(
            final dev.turboism.sdk.ui.settings.SettingsContribution contribution
        ) {
            settingsContributions.add(contribution);
            return () -> settingsContributions.remove(contribution);
        }

        @Override
        public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
            panelContributions.add(contribution);
            return () -> panelContributions.remove(contribution);
        }

        @Override
        public void activateEmbeddedPanel(final EmbeddedPanelId panelId) {
            activatedPanels.add(panelId);
        }

        @Override
        public Optional<String> requestFile(FileChooserRequest request) {
            throw new UnsupportedOperationException("file requests are not used by this plugin test");
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }

        @Override
        public dev.turboism.sdk.ui.CanvasHintHandle notifyCanvasHint(
            final dev.turboism.sdk.ui.CanvasHintNotification notification
        ) {
            final RecordedHint recorded = new RecordedHint(notification);
            canvasHints.add(recorded);
            return recorded;
        }

        @Override
        public dev.turboism.sdk.ui.CanvasHintHandle notifyDismissibleCanvasHint(
            final dev.turboism.sdk.ui.CanvasHintNotification notification
        ) {
            final RecordedHint recorded = new RecordedHint(notification.withOnClick(() -> { }));
            dismissibleCanvasHints.add(recorded);
            return recorded;
        }

        RecordedHint lastCanvasHint() {
            return canvasHints.isEmpty() ? null : canvasHints.get(canvasHints.size() - 1);
        }

        RecordedHint lastDismissibleCanvasHint() {
            return dismissibleCanvasHints.isEmpty()
                ? null : dismissibleCanvasHints.get(dismissibleCanvasHints.size() - 1);
        }

        @Override
        public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) {
            throw new UnsupportedOperationException("context menus are not used by this plugin test");
        }

        @Override
        public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) {
            requireMainToolbarPermission();
            return () -> { };
        }

        @Override
        public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            throw new UnsupportedOperationException("palette toolbar is not used by this plugin test");
        }
    }

    /** One recorded canvas hint plus whether the plugin still holds it open. */
    private static final class RecordedHint implements dev.turboism.sdk.ui.CanvasHintHandle {
        final dev.turboism.sdk.ui.CanvasHintNotification notification;
        int renewals;
        boolean closed;

        RecordedHint(final dev.turboism.sdk.ui.CanvasHintNotification notification) {
            this.notification = notification;
        }

        @Override
        public void renew() {
            if (!closed) renewals++;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class PermissionGatedUiHost extends RecordingUiHost {
        private final boolean allowMainToolbar;
        private final boolean allowStatusNotify;

        PermissionGatedUiHost(final boolean allowMainToolbar, final boolean allowStatusNotify) {
            this.allowMainToolbar = allowMainToolbar;
            this.allowStatusNotify = allowStatusNotify;
        }

        @Override
        void requireMainToolbarPermission() {
            if (!allowMainToolbar) {
                throw new CubismPermissionException(
                    "Missing required permission " + PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE
                        + " for ui.main-toolbar.contribute"
                );
            }
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            if (!allowStatusNotify) {
                throw new CubismPermissionException(
                    "Missing required permission " + PermissionIds.TURBOISM_UI_STATUS_NOTIFY + " for ui.status.notify"
                );
            }
            return super.notifyStatus(notification);
        }
    }

    private static final class ProjectReadCapabilityService implements CubismReadCapabilityService {
        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.of(new ProjectSnapshot(
                "project-1",
                "Demo Project",
                Optional.of(Path.of("project/demo")),
                List.of(new DocumentSnapshot("doc-1", "Model", "model.cmo3", Optional.empty(), Optional.empty()))
            ));
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
            return Optional.of(new WorkspaceSnapshot(
                "workspace-1",
                "Modeling",
                "layouts/workspace-1",
                List.of("project-1")
            ));
        }

        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this plugin test");
        }
    }

    private static final class NoopPluginLogger implements PluginLogger {
        private final List<String> warnings = new ArrayList<>();
        @Override public void debug(String message) { }
        @Override public void info(String message) { }
        @Override public void warn(String message) { warnings.add(message); }
        @Override public void error(String message) { }
        @Override public void error(String message, Throwable throwable) { }
    }
}

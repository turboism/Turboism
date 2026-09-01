package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.sdk.ui.PanelView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PsdClipMaskImportPluginTest {

    @Test
    void enableRegistersActionAndPanelInTheDisposableScope() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PsdClipMaskImportPlugin plugin = new PsdClipMaskImportPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(List.of(PsdClipMaskImportService.ACTION_ID),
            context.registeredActions().stream().map(ActionRegistry.Action::id).toList());
        assertEquals(List.of(PsdClipMaskImportService.SECTION_ID),
            context.registeredSections().stream().map(CollapsibleSectionContribution::sectionId).toList());
        assertEquals(PsdClipMaskImportService.TURBOISM_PANEL_ID,
            context.registeredSections().get(0).targetPanelId().value());
        assertTrue(plugin.isEnabled());
    }

    @Test
    void closingTheDisposableScopeRemovesEveryRegistration() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PsdClipMaskImportPlugin plugin = new PsdClipMaskImportPlugin();

        plugin.init(context);
        plugin.enable();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> context.scope.close());
        plugin.disable();
        plugin.shutdown();

        assertFalse(plugin.isEnabled());
        assertEquals(0, context.registeredActions().size());
        assertEquals(0, context.registeredSections().size());
    }

    @Test
    void enableRollsBackTheScopeWhenAPanelContributionFails() {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.failPanelContribution = true;
        final PsdClipMaskImportPlugin plugin = new PsdClipMaskImportPlugin();

        plugin.init(context);
        assertThrows(RuntimeException.class, plugin::enable);

        assertFalse(plugin.isEnabled());
        assertEquals(0, context.registeredActions().size());
        assertThrows(IllegalStateException.class, () -> context.scope.register(() -> { }),
            "the rolled-back disposable scope must be closed");
    }

    @Test
    void sectionContentIsOneColumnContainingOnlyTheLocalizedImportButton() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PsdClipMaskImportPlugin plugin = new PsdClipMaskImportPlugin();

        plugin.init(context);
        plugin.enable();

        final PanelView content = context.registeredSections().get(0).content();
        assertTrue(content instanceof PanelView.Column,
            "the section content must be a column");
        final List<PanelView> children = ((PanelView.Column) content).children();
        assertEquals(1, children.size(),
            "the section column must contain exactly one child and no leading Text");
        assertTrue(children.get(0) instanceof PanelView.Button,
            "the only child must be the import button");
        final PanelView.Button button = (PanelView.Button) children.get(0);
        assertEquals("import-clip-masks", button.id(), "button id must stay unchanged");
        assertEquals(PsdClipMaskImportService.ACTION_ID, button.actionId(),
            "button action must stay unchanged");
        assertEquals("psd.clip-mask-import.button.import", button.label(),
            "the button label must route through PluginLocalization");
    }

    @Test
    void importActionReturnsImmediatelyAndIgnoresClicksWhileTheImportIsRunning() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.blockModelRead = true;
        final PsdClipMaskImportPlugin plugin = new PsdClipMaskImportPlugin();
        plugin.init(context);
        plugin.enable();
        final ActionRegistry.Action action = context.registeredActions().get(0);

        assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> action.handler().accept(new ActionRegistry.ActionContext() { })
        );
        assertTrue(context.modelReadStarted.await(2, TimeUnit.SECONDS));

        action.handler().accept(new ActionRegistry.ActionContext() { });
        context.allowModelRead.countDown();
        assertTrue(context.modelReadFinished.await(2, TimeUnit.SECONDS));

        assertEquals(1, context.modelReadCount.get(),
            "a repeated click must not start a second import while the first is active");
        plugin.disable();
        plugin.shutdown();
    }

    private static final class RecordingPluginContext implements PluginContext {

        @Override public dev.turboism.sdk.i18n.PluginLocalization localization() {
            return new FakeLocalization();
        }
        final DisposableScope scope = new DisposableScope();
        private final List<ActionRegistry.Action> actions = new ArrayList<>();
        private final List<CollapsibleSectionContribution> sections = new ArrayList<>();
        final AtomicInteger modelReadCount = new AtomicInteger();
        final CountDownLatch modelReadStarted = new CountDownLatch(1);
        final CountDownLatch allowModelRead = new CountDownLatch(1);
        final CountDownLatch modelReadFinished = new CountDownLatch(1);
        boolean failPanelContribution;
        boolean blockModelRead;

        List<ActionRegistry.Action> registeredActions() { return actions; }
        List<CollapsibleSectionContribution> registeredSections() { return sections; }

        @Override public PluginDescriptor descriptor() { return new TestPluginDescriptor(); }
        @Override public PluginLogger logger() {
            return new PluginLogger() {
                @Override public void debug(String message) { }
                @Override public void info(String message) { }
                @Override public void warn(String message) { }
                @Override public void error(String message) { }
                @Override public void error(String message, Throwable throwable) { }
            };
        }
        @Override public PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public CubismFacade cubism() {
            return new CubismFacade() {
                @Override public CubismRuntimeSnapshot runtime() { throw new UnsupportedOperationException(); }
                @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
                @Override public Optional<DocumentSnapshot> activeDocument() { return Optional.empty(); }
                @Override public Optional<ModelSnapshot> activeModel() { return Optional.empty(); }
                @Override public boolean isHostPresent() { return false; }
                @Override public CubismModelAccess model() {
                    return () -> {
                        modelReadCount.incrementAndGet();
                        modelReadStarted.countDown();
                        if (blockModelRead) {
                            try {
                                allowModelRead.await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        modelReadFinished.countDown();
                        throw new UnsupportedOperationException("model unavailable");
                    };
                }
                @Override public TransactionManager transactionManager() {
                    return (ctx, docId) -> { throw new UnsupportedOperationException("transactions unavailable"); };
                }
            };
        }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public ActionRegistry actions() {
            return new ActionRegistry() {
                @Override public Registration register(final String id, final Action action) {
                    actions.add(action);
                    return () -> actions.remove(action);
                }
            };
        }
        @Override public MenuRegistry menus() { throw new UnsupportedOperationException(); }
        @Override public UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public DisposableScope disposableScope() { return scope; }
        @Override public UiHostCapabilityService uiHost() {
            return new UiHostCapabilityService() {
                @Override public Registration contributeOverlay(final OverlayContribution contribution) {
                    throw new UnsupportedOperationException();
                }
                @Override public Registration contributeBoundingBoxOverlayButton(final BoundingBoxOverlayButton contribution) {
                    throw new UnsupportedOperationException();
                }
                @Override public ContextSourceSnapshot contextSource() { throw new UnsupportedOperationException(); }
                @Override public ViewportSnapshot viewport() { throw new UnsupportedOperationException(); }
                @Override public Registration openDialog(final DialogRequest request) {
                    throw new UnsupportedOperationException();
                }
                @Override public boolean confirmDialog(final DialogRequest request) {
                    throw new UnsupportedOperationException();
                }
                @Override public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
                    throw new UnsupportedOperationException();
                }
                @Override public Registration contributeCollapsibleSection(
                    final CollapsibleSectionContribution contribution
                ) {
                    if (failPanelContribution) {
                        throw new IllegalStateException("panel contribution unavailable");
                    }
                    sections.add(contribution);
                    return () -> sections.remove(contribution);
                }
                @Override public Optional<String> requestFile(final FileChooserRequest request) {
                    throw new UnsupportedOperationException();
                }
                @Override public Registration notifyStatus(final StatusNotification notification) {
                    return () -> { };
                }
                @Override public Registration contributeContextMenu(
                    final ContextMenuRegistry.ContextMenuContribution contribution
                ) {
                    throw new UnsupportedOperationException();
                }
                @Override public Registration contributeMainToolbar(
                    final MainToolbarRegistry.MainToolbarContribution contribution
                ) {
                    throw new UnsupportedOperationException();
                }
                @Override public Registration contributePaletteToolbar(
                    final PaletteToolbarRegistry.PaletteToolbarContribution contribution
                ) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    private static final class FakeLocalization implements dev.turboism.sdk.i18n.PluginLocalization {
        @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
        @Override public String text(final String key) { return key; }
        @Override public String format(final String key, final Object... arguments) { return key; }
        @Override public boolean contains(final String key) { return true; }
    }
}

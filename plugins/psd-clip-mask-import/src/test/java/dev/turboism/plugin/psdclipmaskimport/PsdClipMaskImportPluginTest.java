package dev.turboism.plugin.psdclipmaskimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.ui.RecordingUiHost;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PsdClipMaskImportPluginTest {

    @Test
    void enableRegistersActionAndPanelInTheDisposableScope() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PsdClipMaskImportPlugin plugin = new PsdClipMaskImportPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(
                List.of(PsdClipMaskImportService.ACTION_ID),
                context.registeredActions().stream()
                        .map(ActionRegistry.Action::id)
                        .toList());
        assertEquals(
                List.of(PsdClipMaskImportService.SECTION_ID),
                context.registeredSections().stream()
                        .map(CollapsibleSectionContribution::sectionId)
                        .toList());
        assertEquals(
                PsdClipMaskImportService.TURBOISM_PANEL_ID,
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
        assertThrows(
                IllegalStateException.class,
                () -> context.scope.register(() -> {}),
                "the rolled-back disposable scope must be closed");
    }

    @Test
    void sectionContentIsOneColumnContainingOnlyTheLocalizedImportButton() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PsdClipMaskImportPlugin plugin = new PsdClipMaskImportPlugin();

        plugin.init(context);
        plugin.enable();

        final PanelView content = context.registeredSections().get(0).content();
        assertTrue(content instanceof PanelView.Column, "the section content must be a column");
        final List<PanelView> children = ((PanelView.Column) content).children();
        assertEquals(1, children.size(), "the section column must contain exactly one child and no leading Text");
        assertTrue(children.get(0) instanceof PanelView.Button, "the only child must be the import button");
        final PanelView.Button button = (PanelView.Button) children.get(0);
        assertEquals("import-clip-masks", button.id(), "button id must stay unchanged");
        assertEquals(PsdClipMaskImportService.ACTION_ID, button.actionId(), "button action must stay unchanged");
        assertEquals(
                "psd.clip-mask-import.button.import",
                button.label(),
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
                Duration.ofSeconds(1), () -> action.handler().accept(new ActionRegistry.ActionContext() {}));
        assertTrue(context.modelReadStarted.await(2, TimeUnit.SECONDS));

        action.handler().accept(new ActionRegistry.ActionContext() {});
        context.allowModelRead.countDown();
        assertTrue(context.modelReadFinished.await(2, TimeUnit.SECONDS));

        assertEquals(
                1,
                context.modelReadCount.get(),
                "a repeated click must not start a second import while the first is active");
        plugin.disable();
        plugin.shutdown();
    }

    private static final class RecordingPluginContext extends FakePluginContext {

        @Override
        public dev.turboism.sdk.i18n.PluginLocalization localization() {
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

        RecordingPluginContext() {
            super(FakePluginContext.builder().descriptor(new TestPluginDescriptor()));
        }

        List<ActionRegistry.Action> registeredActions() {
            return actions;
        }

        List<CollapsibleSectionContribution> registeredSections() {
            return sections;
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }

        @Override
        public ActionRegistry actions() {
            return new ActionRegistry() {
                @Override
                public Registration register(final String id, final Action action) {
                    actions.add(action);
                    return () -> actions.remove(action);
                }
            };
        }

        @Override
        public CubismFacade cubism() {
            return new CubismFacade() {
                @Override
                public CubismRuntimeSnapshot runtime() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<ProjectSnapshot> activeProject() {
                    return Optional.empty();
                }

                @Override
                public Optional<DocumentSnapshot> activeDocument() {
                    return Optional.empty();
                }

                @Override
                public Optional<ModelSnapshot> activeModel() {
                    return Optional.empty();
                }

                @Override
                public boolean isHostPresent() {
                    return false;
                }

                @Override
                public CubismModelAccess model() {
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
            };
        }

        @Override
        public RecordingUiHost uiHost() {
            return new RecordingUiHost() {
                @Override
                public Registration contributeCollapsibleSection(final CollapsibleSectionContribution contribution) {
                    if (failPanelContribution) {
                        throw new IllegalStateException("panel contribution unavailable");
                    }
                    RecordingPluginContext.this.sections.add(contribution);
                    return () -> RecordingPluginContext.this.sections.remove(contribution);
                }
            };
        }

        @Override
        public dev.turboism.sdk.plugin.PluginServiceDirectory services() {
            return dev.turboism.sdk.plugin.PluginServices.builder()
                    .supply(dev.turboism.sdk.ui.UiHostCapabilityService.class, this::uiHost)
                    .fallback(dev.turboism.sdk.plugin.PluginServices.of(this))
                    .build();
        }
    }

    private static final class FakeLocalization implements dev.turboism.sdk.i18n.PluginLocalization {
        @Override
        public java.util.Locale locale() {
            return java.util.Locale.ENGLISH;
        }

        @Override
        public String text(final String key) {
            return key;
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return key;
        }

        @Override
        public boolean contains(final String key) {
            return true;
        }
    }
}

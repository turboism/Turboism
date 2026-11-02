package dev.turboism.plugin.textureatlasstats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.test.plugin.FakePluginContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TextureAtlasStatisticsPluginTest {

    private static final String INITIAL_TEXT = "texture-atlas-stats.line";

    @Test
    void enableAttachesOneLinePanelFedByTheEditorSession() {
        final RecordingUi ui = new RecordingUi();
        final CountingSession session = new CountingSession();
        final TextureAtlasStatisticsPlugin plugin = new TextureAtlasStatisticsPlugin();
        plugin.init(new ShellPluginContext(session, ui));
        plugin.enable();

        assertNotNull(ui.attached);
        assertEquals(1, ui.attachCount);
        assertEquals(1, ui.liveCount);
        assertEquals(INITIAL_TEXT, ui.attached.text, "enable attaches the localized line before the first tick");

        ui.attached.visible = true;
        plugin.refresh();
        assertEquals("texture-atlas-stats.line[849,37]", ui.attached.text, "a visible panel shows the live counts");

        plugin.disable();
        assertEquals(0, ui.liveCount);

        plugin.enable();
        assertEquals(2, ui.attachCount);
        assertEquals(1, ui.liveCount);
        ui.attached.visible = true;
        plugin.refresh();
        assertEquals("texture-atlas-stats.line[849,37]", ui.attached.text, "a reopened panel is polled again");

        plugin.shutdown();
        assertEquals(0, ui.liveCount);
    }

    @Test
    void hiddenPanelIsNeverReadAndClosedPanelStopsPolling() {
        final RecordingUi ui = new RecordingUi();
        final CountingSession session = new CountingSession();
        final TextureAtlasStatisticsPlugin plugin = new TextureAtlasStatisticsPlugin();
        plugin.init(new ShellPluginContext(session, ui));
        plugin.enable();
        final FakePanel panel = ui.attached;
        // enable() sets the localized line once; only poll ticks are counted from here.
        panel.dispatches.set(0);

        plugin.refresh();

        assertEquals(0, session.reads.get(), "a panel the user cannot see must not read the host session");
        assertEquals(0, panel.dispatches.get(), "a hidden panel must not receive UI dispatches");
        assertEquals(INITIAL_TEXT, panel.text);

        panel.visible = true;
        plugin.refresh();

        assertEquals(2, session.reads.get(), "one tick reads the whole-atlas and selected-texture summaries");
        assertEquals(1, panel.dispatches.get());

        plugin.disable();
        final int readsAfterDisable = session.reads.get();
        panel.visible = true;
        plugin.refresh();

        assertEquals(
                readsAfterDisable, session.reads.get(), "a closed plugin stops polling even if a host label survives");
        assertEquals(1, panel.dispatches.get());
    }

    @Test
    void unchangedCountsAreNotDispatchedTwice() {
        final RecordingUi ui = new RecordingUi();
        final CountingSession session = new CountingSession();
        final TextureAtlasStatisticsPlugin plugin = new TextureAtlasStatisticsPlugin();
        plugin.init(new ShellPluginContext(session, ui));
        plugin.enable();
        final FakePanel panel = ui.attached;
        panel.dispatches.set(0);
        panel.visible = true;

        plugin.refresh();
        plugin.refresh();

        assertEquals(4, session.reads.get(), "both ticks still read the host while the panel is visible");
        assertEquals(1, panel.dispatches.get(), "an unchanged line must not re-dispatch to the UI thread");

        session.whole++;
        plugin.refresh();

        assertEquals(2, panel.dispatches.get(), "a changed count dispatches once more");
        assertEquals("texture-atlas-stats.line[850,37]", panel.text);
        plugin.disable();
    }

    @Test
    void pollTicksSurviveInvisibleCyclesWithoutLosingTheVisibilityGate() {
        final RecordingUi ui = new RecordingUi();
        final CountingSession session = new CountingSession();
        final TextureAtlasStatisticsPlugin plugin = new TextureAtlasStatisticsPlugin();
        plugin.init(new ShellPluginContext(session, ui));
        plugin.enable();
        final FakePanel panel = ui.attached;
        panel.dispatches.set(0);

        panel.visible = true;
        plugin.refresh();
        panel.visible = false;
        session.whole = 900;
        plugin.refresh();

        assertEquals("texture-atlas-stats.line[849,37]", panel.text, "a hidden panel keeps the last shown line");
        assertEquals(2, session.reads.get(), "hidden ticks perform no host read");

        panel.visible = true;
        plugin.refresh();

        assertTrue(panel.text.endsWith("[900,37]"), "showing the panel again resumes the live counts");
        plugin.disable();
    }

    private static final class CountingSession implements TextureAtlasEditorSession {
        final AtomicInteger reads = new AtomicInteger();
        int whole = 849;

        @Override
        public Optional<TextureAtlasSummary> summary() {
            reads.incrementAndGet();
            return Optional.of(new TextureAtlasSummary(whole, 6, List.of()));
        }

        @Override
        public Optional<TextureAtlasSummary> selectedTexture() {
            reads.incrementAndGet();
            return Optional.of(new TextureAtlasSummary(37, 1, List.of()));
        }
    }

    private static final class FakePanel implements TextureAtlasEditorPanel {
        final AtomicInteger dispatches = new AtomicInteger();
        volatile boolean visible;
        String text;
        boolean closed;

        @Override
        public void setText(final String next) {
            if (next.equals(text)) return;
            dispatches.incrementAndGet();
            text = next;
        }

        @Override
        public boolean isVisible() {
            return visible && !closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingUi implements TextureAtlasEditorUi {
        FakePanel attached;
        int attachCount;
        int liveCount;

        @Override
        public TextureAtlasEditorPanel attach() {
            attachCount++;
            liveCount++;
            attached = new FakePanel();
            final FakePanel panel = attached;
            return new TextureAtlasEditorPanel() {
                @Override
                public void setText(final String text) {
                    panel.setText(text);
                }

                @Override
                public boolean isVisible() {
                    return panel.isVisible();
                }

                @Override
                public void close() {
                    if (panel.closed) return;
                    panel.close();
                    liveCount--;
                }
            };
        }
    }

    /** Formats the counted line so a changed count produces visibly different text. */
    private static PluginLocalization countedLocalization() {
        return new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ROOT;
            }

            @Override
            public String text(final String key) {
                return key;
            }

            @Override
            public String format(final String key, final Object... arguments) {
                final StringBuilder formatted = new StringBuilder(key).append('[');
                for (int index = 0; index < arguments.length; index++) {
                    if (index > 0) formatted.append(',');
                    formatted.append(arguments[index]);
                }
                return formatted.append(']').toString();
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }
        };
    }

    private static final class ShellPluginContext extends FakePluginContext {
        private final TextureAtlasEditorSession session;
        private final TextureAtlasEditorUi ui;

        private ShellPluginContext(final TextureAtlasEditorSession session, final TextureAtlasEditorUi ui) {
            super(FakePluginContext.builder());
            this.session = session;
            this.ui = ui;
        }

        @Override
        public CubismFacade cubism() {
            return new CubismFacade() {
                @Override
                public CubismRuntimeSnapshot runtime() {
                    throw unused();
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
                public TextureAtlasLayoutService textureAtlasLayouts() {
                    throw unused();
                }

                @Override
                public TextureAtlasEditorSession textureAtlasEditorSession() {
                    return session;
                }

                @Override
                public TextureAtlasEditorUi textureAtlasEditorUi() {
                    return ui;
                }
            };
        }

        @Override
        public PluginLocalization localization() {
            return countedLocalization();
        }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by this test");
        }
    }
}

package dev.turboism.plugin.textureatlasstats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
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
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.test.plugin.FakePluginContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TextureAtlasStatisticsPluginTest {

    @Test
    void enableAttachesOneLinePanelFedByTheEditorSession() {
        final RecordingUi ui = new RecordingUi();
        final TextureAtlasEditorSession session = new TextureAtlasEditorSession() {
            @Override
            public Optional<TextureAtlasSummary> summary() {
                return Optional.of(new TextureAtlasSummary(849, 6, List.of()));
            }

            @Override
            public Optional<TextureAtlasSummary> selectedTexture() {
                return Optional.of(new TextureAtlasSummary(37, 1, List.of()));
            }
        };
        final TextureAtlasStatisticsPlugin plugin = new TextureAtlasStatisticsPlugin();
        plugin.init(new ShellPluginContext(session, ui));
        plugin.enable();

        assertNotNull(ui.attached);
        assertEquals(1, ui.attachCount);
        assertEquals(1, ui.liveCount);

        plugin.disable();
        assertEquals(0, ui.liveCount);

        plugin.enable();
        assertEquals(2, ui.attachCount);
        assertEquals(1, ui.liveCount);

        plugin.shutdown();
        assertEquals(0, ui.liveCount);
    }

    private static final class RecordingUi implements TextureAtlasEditorUi {
        javax.swing.JLabel attached;
        int attachCount;
        int liveCount;

        @Override
        public TextureAtlasEditorPanel attach() {
            attachCount++;
            liveCount++;
            attached = new javax.swing.JLabel();
            final javax.swing.JLabel label = attached;
            return new TextureAtlasEditorPanel() {
                private boolean closed;

                @Override
                public void setText(final String text) {
                    if (!closed) label.setText(text);
                }

                @Override
                public void close() {
                    if (closed) return;
                    closed = true;
                    liveCount--;
                }
            };
        }
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
            return passThroughLocalization();
        }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by this test");
        }
    }
}

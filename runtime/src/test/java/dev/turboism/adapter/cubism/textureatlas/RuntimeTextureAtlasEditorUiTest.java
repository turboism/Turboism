package dev.turboism.adapter.cubism.textureatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class RuntimeTextureAtlasEditorUiTest {

    @Test
    void bindingWithoutPluginPanelsDoesNotDisturbNativeLayout() {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final RecordingPanel view = new RecordingPanel();
        view.add(new JLabel("native"), BorderLayout.CENTER);
        view.reset();

        ui.ingress().accept(view);

        assertSame(view, ui.view());
        assertEquals(1, view.getComponentCount());
        assertEquals(0, view.revalidations);
        assertEquals(0, view.repaints);
        ui.close();
        assertEquals(0, view.revalidations);
        assertEquals(0, view.repaints);
    }

    @Test
    void bindingCapturesTheGenerationResolverAndView() {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final JPanel view = new JPanel(new BorderLayout());

        ui.ingress().accept(view);

        final var binding = ui.binding();
        assertNotNull(binding);
        assertSame(view, binding.view());
        ui.close();
    }

    @Test
    void closeBeforeBindingRejectsLateIngress() {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final JPanel view = new JPanel(new BorderLayout());

        ui.close();
        ui.ingress().accept(view);

        assertNull(ui.view());
        assertNull(ui.binding());
    }

    @Test
    void deactivateDetachesTheViewAndAcceptsTheNextHostGeneration() {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final JPanel first = new JPanel(new BorderLayout());
        final JPanel second = new JPanel(new BorderLayout());
        ui.ingress().accept(first);
        assertSame(first, ui.view());

        ui.deactivate();

        assertNull(ui.view());
        assertNull(ui.binding());
        ui.bind(2, resolver());
        ui.ingress().accept(second);
        assertSame(second, ui.view());
        ui.close();
    }

    private static RuntimeTextureAtlasEditorUi boundUi() {
        final RuntimeTextureAtlasEditorUi ui = new RuntimeTextureAtlasEditorUi();
        ui.bind(1, resolver());
        return ui;
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
                "5.3.02",
                VerifiedTextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                Set.of(VerifiedTextureAtlasSelectorContract.CAPABILITY_ID),
                List.of(StaticSelector.classSelector(
                        "test.ui", Object.class.getName().replace('.', '/'))),
                RuntimeTextureAtlasEditorUiTest.class.getClassLoader());
    }

    private static final class RecordingPanel extends JPanel {
        private int revalidations;
        private int repaints;

        private RecordingPanel() {
            super(new BorderLayout());
        }

        @Override
        public void revalidate() {
            revalidations++;
        }

        @Override
        public void repaint() {
            repaints++;
        }

        private void reset() {
            revalidations = 0;
            repaints = 0;
        }
    }
}

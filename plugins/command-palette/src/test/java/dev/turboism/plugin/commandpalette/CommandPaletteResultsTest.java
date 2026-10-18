package dev.turboism.plugin.commandpalette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.DefaultListModel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class CommandPaletteResultsTest {

    @Test
    void filteringDoesNotRenderEveryResultToMeasureListWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final DefaultListModel<CommandMatcher.Match> model = new DefaultListModel<>();
            final var list = CommandPaletteDialog.createResultList(model);
            final var renderer = list.getCellRenderer();
            final AtomicInteger renders = new AtomicInteger();
            list.setCellRenderer((view, match, index, selected, focused) -> {
                renders.incrementAndGet();
                return renderer.getListCellRendererComponent(view, match, index, selected, focused);
            });
            final JScrollPane scroll = new JScrollPane(list);
            scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setSize(new Dimension(540, 240));
            final List<CommandMatcher.Entry> entries = new ArrayList<>();
            for (int index = 0; index < 300; index++) {
                entries.add(CommandMatcher.Entry.action("test.plugin", "command." + index, "Command " + index));
            }
            for (final String query : List.of("c", "co", "com", "comm")) {
                model.clear();
                model.addAll(CommandMatcher.match(query, entries));
                assertEquals(300, model.size());
                list.getPreferredSize();
                scroll.doLayout();
                scroll.getViewport().doLayout();
                assertTrue(list.getScrollableTracksViewportWidth());
                assertEquals(scroll.getViewport().getExtentSize().width, list.getWidth());
            }
            // Layout must not HTML-render hundreds of offscreen rows on each keystroke.
            assertEquals(0, renders.get());
        });
    }
}

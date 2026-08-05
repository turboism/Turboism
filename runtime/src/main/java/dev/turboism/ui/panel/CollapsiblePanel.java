package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 纵向堆叠多个 {@link CollapsibleSection} 的容器面板，{@link CollapsibleSectionRegistry}
 * 的 Swing 实现。子组件顺序 = 注册顺序；线程模型按 EDT 使用约定，synchronized 仅守卫内部 map。
 */
public final class CollapsiblePanel extends JPanel implements CollapsibleSectionRegistry {

    private final Map<String, JPanel> sections = new LinkedHashMap<>();

    public CollapsiblePanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    @Override
    public synchronized Registration register(final CollapsibleSectionContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        final String id = Objects.requireNonNull(contribution.id(), "id");
        if (sections.containsKey(id)) {
            throw new IllegalStateException("Collapsible section is already registered: " + id);
        }
        final JPanel section = CollapsibleSection.create(
            contribution.title(), contribution.content(), contribution.expandedByDefault());
        sections.put(id, section);
        add(section);
        revalidate();
        repaint();
        return () -> unregister(id);
    }

    private synchronized void unregister(final String id) {
        final JPanel section = sections.remove(id);
        if (section != null) {
            remove(section);
            revalidate();
            repaint();
        }
    }

    @Override
    public synchronized List<String> sectionIds() {
        return List.copyOf(sections.keySet());
    }

    @Override
    public synchronized boolean isExpanded(final String sectionId) {
        return CollapsibleSection.isExpanded(requireSection(sectionId));
    }

    @Override
    public synchronized void setExpanded(final String sectionId, final boolean expanded) {
        CollapsibleSection.setExpanded(requireSection(sectionId), expanded);
        revalidate();
        repaint();
    }

    private JPanel requireSection(final String sectionId) {
        final JPanel section = sections.get(sectionId);
        if (section == null) {
            throw new IllegalArgumentException("unknown collapsible section id: " + sectionId);
        }
        return section;
    }
}

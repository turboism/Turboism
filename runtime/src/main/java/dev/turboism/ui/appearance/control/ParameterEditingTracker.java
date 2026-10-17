package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Reversible input blocking and disabled rendering of native parameter editors. EDT only. */
final class ParameterEditingTracker {
    static final String MARKER = "turboism.parameterEditing.disabled";

    private final Map<JComponent, Overlay> rows = new WeakHashMap<>();

    void apply(final Component owner, final ParameterEditorControls controls, final boolean disabled) {
        if (controls == null) return;
        Overlay overlay = rows.get(controls.row());
        if (overlay == null && !disabled) return;
        if (overlay == null) {
            overlay = new Overlay(controls.row());
            rows.put(controls.row(), overlay);
        }
        overlay.set(owner, controls, disabled);
        if (overlay.empty()) {
            overlay.restore();
            rows.remove(controls.row());
        }
    }

    void restoreAll() {
        for (Overlay overlay : new ArrayList<>(rows.values())) overlay.restore();
        rows.clear();
    }

    private static final class Overlay extends JComponent {
        private final WeakReference<JComponent> row;
        private final Map<Component, List<WeakReference<JComponent>>> owners = new WeakHashMap<>();
        private final Map<JComponent, Boolean> originalEnabled = new WeakHashMap<>();
        private final ComponentAdapter resize = new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent event) {
                fit();
            }
        };

        Overlay(final JComponent row) {
            this.row = new WeakReference<>(row);
            setOpaque(false);
            // A real hit-test surface intercepts pointer events before native mouse listeners run.
            addMouseListener(new java.awt.event.MouseAdapter() {});
            addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {});
            addMouseWheelListener(event -> event.consume());
            row.add(this);
            row.setComponentZOrder(this, 0);
            row.addComponentListener(resize);
            fit();
        }

        void set(final Component owner, final ParameterEditorControls controls, final boolean disabled) {
            if (disabled)
                owners.put(owner, List.of(new WeakReference<>(controls.drag()), new WeakReference<>(controls.value())));
            else owners.remove(owner);
            final Set<JComponent> locked = Collections.newSetFromMap(new IdentityHashMap<>());
            for (var targets : owners.values()) {
                for (var ref : targets) {
                    final JComponent target = ref.get();
                    if (target != null) collect(target, locked);
                }
            }
            for (var entry : new ArrayList<>(originalEnabled.entrySet())) {
                if (!locked.contains(entry.getKey())) {
                    entry.getKey().setEnabled(entry.getValue());
                    entry.getKey().putClientProperty(MARKER, null);
                    originalEnabled.remove(entry.getKey());
                }
            }
            for (JComponent target : locked) {
                originalEnabled.putIfAbsent(target, target.isEnabled());
                target.setEnabled(false);
                target.putClientProperty(MARKER, Boolean.TRUE);
                if (target.isFocusOwner()) target.transferFocus();
            }
            fit();
            repaint();
        }

        private static void collect(final JComponent target, final Set<JComponent> components) {
            if (!components.add(target)) return;
            for (Component child : target.getComponents())
                if (child instanceof JComponent component) collect(component, components);
        }

        boolean empty() {
            return owners.isEmpty();
        }

        private void fit() {
            final JComponent current = row.get();
            if (current != null) setBounds(0, 0, current.getWidth(), current.getHeight());
        }

        @Override
        public boolean contains(final int x, final int y) {
            for (var targets : owners.values())
                for (var ref : targets) {
                    final JComponent target = ref.get();
                    if (target != null
                            && attached(target)
                            && target.isVisible()
                            && bounds(target).contains(x, y)) return true;
                }
            return false;
        }

        private boolean attached(final JComponent target) {
            final JComponent current = row.get();
            return current != null && SwingUtilities.isDescendingFrom(target, current);
        }

        private Rectangle bounds(final JComponent target) {
            return SwingUtilities.convertRectangle(
                    target, new Rectangle(0, 0, target.getWidth(), target.getHeight()), this);
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            final Set<JComponent> painted = Collections.newSetFromMap(new IdentityHashMap<>());
            for (var targets : owners.values())
                for (var ref : targets) {
                    final JComponent target = ref.get();
                    if (target == null
                            || !attached(target)
                            || !target.isVisible()
                            || !painted.add(target)
                            || target.getWidth() <= 0
                            || target.getHeight() <= 0) continue;
                    final Rectangle rect = bounds(target);
                    final java.awt.geom.AffineTransform transform = graphics instanceof java.awt.Graphics2D g2
                            ? g2.getTransform()
                            : new java.awt.geom.AffineTransform();
                    final double sx = Math.max(1.0, Math.hypot(transform.getScaleX(), transform.getShearY()));
                    final double sy = Math.max(1.0, Math.hypot(transform.getScaleY(), transform.getShearX()));
                    final BufferedImage image = new BufferedImage(
                            (int) Math.ceil(rect.width * sx),
                            (int) Math.ceil(rect.height * sy),
                            BufferedImage.TYPE_INT_RGB);
                    final java.awt.Graphics2D nativeGraphics = image.createGraphics();
                    try {
                        nativeGraphics.scale(sx, sy);
                        final java.awt.Color background = target.getBackground();
                        nativeGraphics.setColor(background == null ? getBackground() : background);
                        nativeGraphics.fillRect(0, 0, rect.width, rect.height);
                        target.printAll(nativeGraphics);
                    } finally {
                        nativeGraphics.dispose();
                    }
                    // The native drag surface paints its own colored keyframes even when disabled.
                    // Desaturate that real rendering, without replacing native geometry or assets.
                    final BufferedImage gray =
                            new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null).filter(image, null);
                    graphics.drawImage(gray, rect.x, rect.y, rect.width, rect.height, null);
                    gray.flush();
                    image.flush();
                }
        }

        void restore() {
            for (var entry : originalEnabled.entrySet()) {
                entry.getKey().setEnabled(entry.getValue());
                entry.getKey().putClientProperty(MARKER, null);
            }
            originalEnabled.clear();
            owners.clear();
            final JComponent current = row.get();
            if (current != null) {
                current.removeComponentListener(resize);
                current.remove(this);
                current.repaint();
            }
        }
    }
}

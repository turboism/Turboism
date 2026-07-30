package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.plugin.Registration;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.FlowLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime registry and lifecycle owner for the native-position mirror-angle control. */
public final class RuntimeMeshEditUiService implements MeshEditUiService {
    private final AtomicReference<MirrorAxisAngleControl> contribution = new AtomicReference<>();
    private final List<Attachment> attachments = new ArrayList<>();

    @Override
    public Registration contributeMirrorAxisAngleControl(final MirrorAxisAngleControl requested) {
        Objects.requireNonNull(requested, "contribution");
        if (!contribution.compareAndSet(null, requested)) {
            throw new IllegalStateException("mesh mirror-axis angle control is already registered");
        }
        return () -> clear(requested);
    }

    public MirrorAxisAngleControl contribution() {
        return contribution.get();
    }

    void attachNative(
        final Object panel,
        final Object widget,
        final RuntimeMeshMirrorAxisService axis
    ) {
        final MirrorAxisAngleControl active = contribution.get();
        if (active == null || panel == null || widget == null) return;
        runOnEdt(() -> {
            if (contribution.get() != active || find(panel) != null) return;
            final Container container = container(widget);
            if (container == null) return;
            final JComponent root = build(active, axis);
            container.add(root, 0);
            container.revalidate();
            container.repaint();
            synchronized (attachments) {
                if (contribution.get() == active) attachments.add(new Attachment(panel, container, root));
                else remove(container, root);
            }
        });
    }

    private JComponent build(
        final MirrorAxisAngleControl active,
        final RuntimeMeshMirrorAxisService axis
    ) {
        final int scale = Math.max(1, Math.round(1.0f / active.stepDegrees()));
        final JSlider slider = new JSlider(
            Math.round(active.minimumDegrees() * scale),
            Math.round(active.maximumDegrees() * scale),
            Math.round(axis.currentAngleDegrees() * scale)
        );
        slider.addChangeListener(ignored -> {
            final float value = slider.getValue() / (float) scale;
            active.onAngleChanged().accept(value);
        });
        final JButton reset = new JButton("0");
        reset.setToolTipText("Reset mirror axis to 0°");
        reset.addActionListener(ignored -> slider.setValue(0));
        final JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setName(active.contributionId());
        row.add(new JLabel(active.label()), BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(reset);
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    private void clear(final MirrorAxisAngleControl expected) {
        if (!contribution.compareAndSet(expected, null)) return;
        final List<Attachment> stale;
        synchronized (attachments) {
            stale = List.copyOf(attachments);
            attachments.clear();
        }
        runOnEdt(() -> stale.forEach(attachment -> remove(attachment.container, attachment.root)));
    }

    private Attachment find(final Object panel) {
        synchronized (attachments) {
            return attachments.stream().filter(value -> value.panel == panel).findFirst().orElse(null);
        }
    }

    private static Container container(final Object widget) {
        if (widget instanceof Container container) return container;
        for (String methodName : new String[] {"getJComponent", "getComponent", "getChild"}) {
            try {
                final Method method = widget.getClass().getMethod(methodName);
                final Object value = method.invoke(widget);
                if (value instanceof Container container) return container;
            } catch (ReflectiveOperationException ignored) {
                // The exact host provider may expose one of the reviewed wrapper accessors.
            }
        }
        return null;
    }

    private static void remove(final Container container, final JComponent root) {
        container.remove(root);
        container.revalidate();
        container.repaint();
    }

    private static void runOnEdt(final Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else {
            try {
                SwingUtilities.invokeAndWait(action);
            } catch (Exception failure) {
                throw new IllegalStateException("mesh mirror UI dispatch failed", failure);
            }
        }
    }

    private record Attachment(Object panel, Container container, JComponent root) { }
}

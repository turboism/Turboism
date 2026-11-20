package dev.turboism.adapter.cubism.textureatlas;

import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasNativeInvocationAdapter.CONTAINER_CHILDREN;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasNativeInvocationAdapter.EDIT_LAYER_DRAW_DATA_SHAPES;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasNativeInvocationAdapter.IMPL_CONTAINER;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasNativeInvocationAdapter.LAYER_REF_LAYER;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasNativeInvocationAdapter.LAYER_REF_TRANSFORM;

import dev.turboism.adapter.cubism.lifecycle.ProjectContentIdentity;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;
import dev.turboism.sdk.ui.resource.UiResourceService;
import dev.turboism.ui.resource.RuntimeUiResourceService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/** Adapts the native canvas selection and draws editor-only scale indicators. */
final class TextureAtlasScaleLockEditor implements AutoCloseable {
    static final String CANVAS_PAINT = "cubism.texture-atlas.scale-lock.canvas-paint";
    static final String DATA_MODEL_DOCUMENT = "cubism.texture-atlas.data-model.document";
    static final String MARK_DIRTY = "cubism.editor-model.modeling-document.mark-dirty";
    private static final String CANVAS_VIEW = "cubism.texture-atlas.scale-lock.canvas-page-view";
    private static final String VIEW_PAGE = "cubism.texture-atlas.scale-lock.page-view-state";
    private static final String CANVAS_TRANSFORM = "cubism.texture-atlas.scale-lock.canvas-transform";
    private static final String PAGE_SELECTION = "cubism.texture-atlas.scale-lock.page-selection";
    private static final String SELECTED_LAYERS = "cubism.texture-atlas.scale-lock.selected-layers";
    private static final String EDIT_LAYER_IMAGE = "cubism.texture-atlas.scale-lock.edit-layer-image";
    private static final String EDIT_LAYER_DATA = "cubism.texture-atlas.scale-lock.edit-layer-data";
    private static final String EDIT_LAYER_REF = "cubism.texture-atlas.scale-lock.edit-layer-ref";
    private static final String DATA_MODEL_SOURCE = "cubism.texture-atlas.data-model.model-source";
    private static final String MODEL_GUID = "cubism.editor-model.model-source.guid";
    private static final String IMAGE_GUID = "cubism.texture-atlas.image.guid";
    private static final String GUID_VALUE = "cubism.editor-model.guid.value";
    private static final String IMAGE_WIDTH = "cubism.texture-atlas.image.width";
    private static final String IMAGE_HEIGHT = "cubism.texture-atlas.image.height";
    private static final String MESSAGES = "dev.turboism.adapter.cubism.textureatlas.messages";
    private static final String LOCK = "scale-lock.lock";
    private static final String UNLOCK = "scale-lock.unlock";
    private static final String STORAGE_FAILURE = "scale-lock.storage-failure";
    private static final String LAYOUT_FAILURE = "scale-lock.layout-failure";
    private static final Color OVERLAY = new Color(112, 112, 112, 125);
    private static final UiIconRef LOCK_ICON = new UiIconRef(CubismIcon.LOCK);
    private static final MouseAdapter POPUP = new MouseAdapter() {
        @Override
        public void mousePressed(final MouseEvent event) {
            open(event);
        }

        @Override
        public void mouseReleased(final MouseEvent event) {
            open(event);
        }

        private void open(final MouseEvent event) {
            if (event.isPopupTrigger()
                    && event.getComponent() instanceof JComponent canvas
                    && canvas.getClientProperty(TextureAtlasScaleLockEditor.class)
                            instanceof TextureAtlasScaleLockEditor editor) {
                editor.menu(canvas, event);
            }
        }
    };

    private final VerifiedMemberResolver resolver;
    private final TextureAtlasScaleLocks locks;
    private final UiResourceService resources;
    private final ResourceBundle messages;
    private final Set<JComponent> canvases = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private volatile boolean closed;

    TextureAtlasScaleLockEditor(
            final VerifiedMemberResolver resolver,
            final TextureAtlasScaleLocks locks,
            final UiResourceService resources,
            final Locale locale) {
        this.resolver = resolver;
        this.locks = locks;
        this.resources = resources;
        messages = ResourceBundle.getBundle(MESSAGES, locale);
    }

    static TextureAtlasScaleLocks.Document lockDocument(final VerifiedMemberResolver resolver, final Object layer) {
        final Object data = resolver.invoke(EDIT_LAYER_DATA, layer);
        return lockDocument(resolver, data, resolver.invoke(DATA_MODEL_DOCUMENT, data));
    }

    private static TextureAtlasScaleLocks.Document lockDocument(
            final VerifiedMemberResolver resolver, final Object data, final Object document) {
        final Object source = resolver.invoke(DATA_MODEL_SOURCE, data);
        return new TextureAtlasScaleLocks.Document(
                ProjectContentIdentity.forLifecycleContent(ProjectContentKind.MODEL, document),
                guid(resolver, MODEL_GUID, source));
    }

    static String imageId(final VerifiedMemberResolver resolver, final Object layer) {
        return guid(resolver, IMAGE_GUID, resolver.invoke(EDIT_LAYER_IMAGE, layer));
    }

    private static String guid(final VerifiedMemberResolver resolver, final String alias, final Object owner) {
        return (String) resolver.invoke(GUID_VALUE, resolver.invoke(alias, owner));
    }

    void paint(final Object target, final Object graphics) {
        if (closed
                || !SwingUtilities.isEventDispatchThread()
                || !(target instanceof JComponent canvas)
                || !(graphics instanceof Graphics2D surface)) return;
        try {
            if (canvases.add(canvas)) {
                canvas.putClientProperty(TextureAtlasScaleLockEditor.class, this);
                canvas.addMouseListener(POPUP);
            }
            final Object[] refs =
                    (Object[]) resolver.invoke(CONTAINER_CHILDREN, resolver.invoke(IMPL_CONTAINER, page(canvas)));
            if (refs.length == 0) return;
            final Object firstLayer = resolver.invoke(LAYER_REF_LAYER, refs[0]);
            final Map<String, Double> scales = locks.snapshot(lockDocument(resolver, firstLayer));
            if (scales.isEmpty()) return;
            final AffineTransform viewport = (AffineTransform) resolver.invoke(CANVAS_TRANSFORM, canvas);
            final var icon = RuntimeUiResourceService.resolveForPresentation(resources, LOCK_ICON, false);
            final Graphics2D overlay = (Graphics2D) surface.create();
            try {
                overlay.setColor(OVERLAY);
                for (final Object ref : refs) {
                    final Object layer = resolver.invoke(LAYER_REF_LAYER, ref);
                    if (!scales.containsKey(imageId(resolver, layer))) continue;
                    final AffineTransform transform = new AffineTransform(viewport);
                    transform.concatenate((AffineTransform) resolver.invoke(LAYER_REF_TRANSFORM, ref));
                    final Shape mask = transform.createTransformedShape(outline(layer));
                    overlay.fill(mask);
                    final Rectangle2D bounds = mask.getBounds2D();
                    icon.ifPresent(value -> value.paintIcon(
                            canvas, overlay, (int) Math.round(bounds.getCenterX() - value.getIconWidth() / 2D), (int)
                                    Math.round(bounds.getCenterY() - value.getIconHeight() / 2D)));
                }
            } finally {
                overlay.dispose();
            }
        } catch (RuntimeException failure) {
            report("Cannot render texture atlas scale locks.", failure);
        }
    }

    private Object page(final JComponent canvas) {
        return resolver.invoke(VIEW_PAGE, resolver.readField(CANVAS_VIEW, canvas));
    }

    private Shape outline(final Object layer) {
        final var shapes = (List<?>) resolver.invoke(EDIT_LAYER_DRAW_DATA_SHAPES, layer);
        final Area area = new Area();
        for (final Object shape : shapes) area.add(new Area((Shape) shape));
        if (!area.isEmpty()) return area;
        final Object image = resolver.invoke(EDIT_LAYER_IMAGE, layer);
        final Number width = (Number) resolver.invoke(IMAGE_WIDTH, image);
        final Number height = (Number) resolver.invoke(IMAGE_HEIGHT, image);
        return new Rectangle2D.Double(0, 0, width.doubleValue(), height.doubleValue());
    }

    private void menu(final JComponent canvas, final MouseEvent event) {
        if (closed) return;
        try {
            final Object selector = resolver.invoke(PAGE_SELECTION, page(canvas));
            final Object[] selected = (Object[]) resolver.invoke(SELECTED_LAYERS, selector);
            if (selected.length == 0) return;
            final Object data = resolver.invoke(EDIT_LAYER_DATA, selected[0]);
            final Object document = resolver.invoke(DATA_MODEL_DOCUMENT, data);
            final var model = lockDocument(resolver, data, document);
            final Map<String, Double> existing = locks.snapshot(model);
            final Map<String, Double> additions = new LinkedHashMap<>();
            final Set<String> removals = new java.util.LinkedHashSet<>();
            for (final Object layer : selected) {
                final String image = imageId(resolver, layer);
                if (existing.containsKey(image)) {
                    removals.add(image);
                } else {
                    final Object ref = resolver.invoke(EDIT_LAYER_REF, layer);
                    final var transform = (AffineTransform) resolver.invoke(LAYER_REF_TRANSFORM, ref);
                    additions.put(image, TextureAtlasScaleLocks.scale(transform));
                }
            }
            final JPopupMenu popup = new JPopupMenu();
            if (!additions.isEmpty())
                addCommand(popup, LOCK, () -> update(canvas, document, model, additions, Set.of()));
            if (!removals.isEmpty())
                addCommand(popup, UNLOCK, () -> update(canvas, document, model, Map.of(), removals));
            popup.show(canvas, event.getX(), event.getY());
        } catch (RuntimeException failure) {
            report("Cannot open texture atlas scale lock commands.", failure);
            showMessage(canvas, STORAGE_FAILURE);
        }
    }

    private void addCommand(final JPopupMenu popup, final String label, final Runnable action) {
        final var item = new JMenuItem(messages.getString(label));
        item.addActionListener(event -> action.run());
        popup.add(item);
    }

    private void update(
            final JComponent canvas,
            final Object document,
            final TextureAtlasScaleLocks.Document model,
            final Map<String, Double> additions,
            final Set<String> removals) {
        if (closed) return;
        try {
            locks.update(model, additions, removals);
            resolver.invoke(MARK_DIRTY, document);
            canvas.repaint();
        } catch (RuntimeException failure) {
            report("Cannot update texture atlas scale locks.", failure);
            showMessage(canvas, STORAGE_FAILURE);
        }
    }

    void layoutRejected() {
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            canvases.stream()
                    .filter(JComponent::isShowing)
                    .findFirst()
                    .ifPresent(canvas -> showMessage(canvas, LAYOUT_FAILURE));
        });
    }

    private void showMessage(final JComponent canvas, final String message) {
        JOptionPane.showMessageDialog(canvas, messages.getString(message));
    }

    private static void report(final String message, final RuntimeException failure) {
        dev.turboism.runtime.log.RuntimeDiagnostics.error("texture-atlas", message, failure);
    }

    @Override
    public void close() {
        closed = true;
        SwingUtilities.invokeLater(() -> {
            for (final JComponent canvas : canvases) {
                canvas.removeMouseListener(POPUP);
                canvas.putClientProperty(TextureAtlasScaleLockEditor.class, null);
                canvas.repaint();
            }
            canvases.clear();
        });
    }
}

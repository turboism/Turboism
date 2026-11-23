package dev.turboism.adapter.cubism.textureatlas.interactive;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.Color;
import java.awt.Point;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.JScrollPane;

/**
 * Resolves reviewed atlas-editor host members once via {@link HostHandles} so runtime callbacks
 * read overlay geometry, drag state, and camera viewports with exact contract verification.
 */
final class AtlasInteractiveRenderHostAccess {

    private static final Color LEGACY_FILL_COLOR = new Color(AtlasInteractiveRenderTarget.LEGACY_FILL_ARGB, true);
    private static final Color LEGACY_STROKE_COLOR = new Color(AtlasInteractiveRenderTarget.LEGACY_STROKE_ARGB, true);

    private final MethodHandle drawDataShapesGetter;
    private final MethodHandle texRelocModelGetter;
    private final MethodHandle dataModelFillColorGetter;
    private final MethodHandle dataModelStrokeColorGetter;
    private final MethodHandle colorJColorGetter;
    private final Color defaultFillColor;
    private final Color defaultStrokeColor;

    private final MethodHandle eventDraggingGetter;
    private final MethodHandle cameraScrollPaneGetter;
    private final MethodHandle cameraViewportCanvasGetter;
    private final MethodHandle viewportSetZoom;

    AtlasInteractiveRenderHostAccess(final ClassLoader loader, final AtlasInteractiveRenderTarget target)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(target, "target");

        final AtlasInteractiveRenderTarget.Generation gen = target.generation();
        final Class<?> editLayerClass =
                Class.forName(toBinary(AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER), false, loader);
        final Class<?> eventClass =
                Class.forName(toBinary(AtlasInteractiveRenderTarget.LAYER_UPDATE_EVENT_OWNER), false, loader);
        final Class<?> cameraClass = Class.forName(toBinary(gen.cameraOwner()), false, loader);
        final Class<?> viewportClass =
                Class.forName(toBinary(AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER), false, loader);

        this.drawDataShapesGetter = HostHandles.getter(
                editLayerClass,
                AtlasInteractiveRenderTarget.DRAW_DATA_SHAPES_FIELD,
                AtlasInteractiveRenderTarget.ARRAY_LIST_DESCRIPTOR,
                MethodType.methodType(ArrayList.class, Object.class));
        this.eventDraggingGetter = HostHandles.getter(
                eventClass,
                AtlasInteractiveRenderTarget.EVENT_DRAGGING_FIELD,
                AtlasInteractiveRenderTarget.BOOLEAN_DESCRIPTOR,
                MethodType.methodType(boolean.class, Object.class));
        this.cameraScrollPaneGetter = HostHandles.virtual(
                cameraClass,
                AtlasInteractiveRenderTarget.SCROLL_PANE_GETTER_METHOD,
                AtlasInteractiveRenderTarget.SCROLL_PANE_GETTER_DESCRIPTOR,
                MethodType.methodType(JScrollPane.class, Object.class));
        this.cameraViewportCanvasGetter = HostHandles.virtual(
                cameraClass,
                AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_GETTER_METHOD,
                AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_GETTER_DESCRIPTOR,
                MethodType.methodType(JComponent.class, Object.class));
        this.viewportSetZoom = HostHandles.virtual(
                viewportClass,
                AtlasInteractiveRenderTarget.VIEWPORT_SET_ZOOM_METHOD,
                AtlasInteractiveRenderTarget.VIEWPORT_SET_ZOOM_DESCRIPTOR,
                MethodType.methodType(double.class, Object.class, Point.class, double.class));

        if (gen.staticOverlayColors()) {
            this.texRelocModelGetter = null;
            this.dataModelFillColorGetter = null;
            this.dataModelStrokeColorGetter = null;
            this.colorJColorGetter = null;
            this.defaultFillColor = LEGACY_FILL_COLOR;
            this.defaultStrokeColor = LEGACY_STROKE_COLOR;
        } else {
            final Class<?> dataModelClass =
                    Class.forName(toBinary(AtlasInteractiveRenderTarget.DATA_MODEL_OWNER), false, loader);
            final Class<?> companionClass =
                    Class.forName(toBinary(AtlasInteractiveRenderTarget.DATA_MODEL_COMPANION_OWNER), false, loader);
            final Class<?> colorClass =
                    Class.forName(toBinary(AtlasInteractiveRenderTarget.COLOR_OWNER), false, loader);
            final String colorDescriptor = "L" + AtlasInteractiveRenderTarget.COLOR_OWNER + ";";
            final String colorGetterDescriptor = "()" + colorDescriptor;

            this.texRelocModelGetter = HostHandles.getter(
                    editLayerClass,
                    AtlasInteractiveRenderTarget.TEX_RELOC_MODEL_FIELD,
                    AtlasInteractiveRenderTarget.TEX_RELOC_MODEL_DESCRIPTOR,
                    MethodType.methodType(Object.class, Object.class));
            this.dataModelFillColorGetter = HostHandles.virtual(
                    dataModelClass, "i", colorGetterDescriptor, MethodType.methodType(Object.class, Object.class));
            this.dataModelStrokeColorGetter = HostHandles.virtual(
                    dataModelClass, "j", colorGetterDescriptor, MethodType.methodType(Object.class, Object.class));
            this.colorJColorGetter = HostHandles.virtual(
                    colorClass, "getJColor", "()Ljava/awt/Color;", MethodType.methodType(Color.class, Object.class));

            try {
                final Object companion = HostHandles.field(dataModelClass, "a").get(null);
                final MethodHandle companionFill = HostHandles.virtual(
                        companionClass, "b", colorGetterDescriptor, MethodType.methodType(Object.class, Object.class));
                final MethodHandle companionStroke = HostHandles.virtual(
                        companionClass, "c", colorGetterDescriptor, MethodType.methodType(Object.class, Object.class));
                this.defaultFillColor = toJColor(companionFill.invoke(companion));
                this.defaultStrokeColor = toJColor(companionStroke.invoke(companion));
            } catch (final Throwable t) {
                FatalErrors.rethrowIfFatal(t);
                throw new IllegalStateException("unable to resolve default atlas overlay colors", t);
            }
        }
    }

    ArrayList<?> drawDataShapes(final Object layer) {
        try {
            return (ArrayList<?>) drawDataShapesGetter.invoke(layer);
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            return null;
        }
    }

    Color fillColor(final Object layer) {
        if (texRelocModelGetter == null) {
            return defaultFillColor;
        }
        try {
            final Object model = texRelocModelGetter.invoke(layer);
            if (model == null) {
                return defaultFillColor;
            }
            final Color color = toJColor(dataModelFillColorGetter.invoke(model));
            return color != null ? color : defaultFillColor;
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            return defaultFillColor;
        }
    }

    Color strokeColor(final Object layer) {
        if (texRelocModelGetter == null) {
            return defaultStrokeColor;
        }
        try {
            final Object model = texRelocModelGetter.invoke(layer);
            if (model == null) {
                return defaultStrokeColor;
            }
            final Color color = toJColor(dataModelStrokeColorGetter.invoke(model));
            return color != null ? color : defaultStrokeColor;
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            return defaultStrokeColor;
        }
    }

    boolean isEventDragging(final Object layerUpdateEvent) {
        try {
            return (boolean) eventDraggingGetter.invoke(layerUpdateEvent);
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            return false;
        }
    }

    JScrollPane cameraScrollPane(final Object cameraPanel) {
        try {
            return (JScrollPane) cameraScrollPaneGetter.invoke(cameraPanel);
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            return null;
        }
    }

    JComponent cameraViewportCanvas(final Object cameraPanel) {
        try {
            return (JComponent) cameraViewportCanvasGetter.invoke(cameraPanel);
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            return null;
        }
    }

    void applyCameraZoom(final Object cameraPanel, final Point anchor, final double zoom) {
        try {
            final Object viewportCanvas = cameraViewportCanvasGetter.invoke(cameraPanel);
            if (viewportCanvas != null && anchor != null) {
                viewportSetZoom.invoke(viewportCanvas, anchor, zoom);
            }
            final JScrollPane scrollPane =
                    cameraScrollPaneGetter.invoke(cameraPanel) instanceof JScrollPane sp ? sp : null;
            if (scrollPane != null) {
                scrollPane.repaint();
            }
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
        }
    }

    private Color toJColor(final Object cColor) throws Throwable {
        if (cColor == null) {
            return null;
        }
        return (Color) colorJColorGetter.invoke(cColor);
    }

    private static String toBinary(final String internalName) {
        return internalName.replace('/', '.');
    }
}

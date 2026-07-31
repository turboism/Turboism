package dev.turboism.ui.overlay;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact-host operations for red-box overlay buttons; invoked only through the verified hook. */
public final class VerifiedBoundingBoxOverlayButtonHostOperations
    implements BoundingBoxOverlayButtonHostOperations {

    private static final float BUTTON_SIZE = 24.0f;
    private static final float BUTTON_STEP = 28.0f;

    private static final String BUTTON_CREATE = "cubism.ui-bounding-box-overlay.button.create";
    private static final String BUTTONS = "cubism.ui-bounding-box-overlay.bounding-box.buttons";
    private static final String LAST_BOUNDING_BOX =
        "cubism.ui-bounding-box-overlay.bounding-box.last-bounding-box";
    private static final String HIDE_BUTTON_POSITION =
        "cubism.ui-bounding-box-overlay.bounding-box.hide-button-position";
    private static final String ACTION_VIEW_CONTEXT =
        "cubism.ui-bounding-box-overlay.action.view-context";
    private static final String ACTION_SCALE = "cubism.ui-bounding-box-overlay.action.scale";
    private static final String VIEW_CAMERA = "cubism.ui-bounding-box-overlay.view.camera";
    private static final String VIEW_COMPLETE_PACK =
        "cubism.ui-bounding-box-overlay.view.complete-pack";
    private static final String COMPLETE_PACK_MAIN_VIEW =
        "cubism.ui-bounding-box-overlay.complete-pack.main-view";
    private static final String MAIN_VIEW_DPI_SCALE =
        "cubism.ui-bounding-box-overlay.main-view.dpi-scale";
    private static final String DOCUMENT_TO_COMPONENT =
        "cubism.ui-bounding-box-overlay.camera.document-to-component";
    private static final String VECTOR_X = "cubism.ui-bounding-box-overlay.vector.x";
    private static final String VECTOR_PLUS = "cubism.ui-bounding-box-overlay.vector.plus";
    private static final String VECTOR_TIMES = "cubism.ui-bounding-box-overlay.vector.times";
    private static final String VECTOR_CREATE = "cubism.ui-bounding-box-overlay.vector.create";
    private static final String RECT_CREATE = "cubism.ui-bounding-box-overlay.rect.create";
    private static final String BUTTON_SET_BOUNDS =
        "cubism.ui-bounding-box-overlay.button.set-bounds";
    private static final String BUTTON_SET_ENABLED =
        "cubism.ui-bounding-box-overlay.button.set-enabled";
    private static final String SCENE_COMPONENT_OBJECTS =
        "cubism.ui-bounding-box-overlay.scene.component-objects";
    private static final String ENTITY_CHILDREN = "cubism.ui-bounding-box-overlay.entity.children";
    private static final String ENTITIES_ADD = "cubism.ui-bounding-box-overlay.entities.add";
    private static final String SCENE_VOLATILE = "cubism.ui-bounding-box-overlay.scene.volatile";
    private static final String WRITABLE_IMAGE_CREATE =
        "cubism.ui-bounding-box-overlay.writable-image.create";
    private static final String ICON_SET_CREATE = "cubism.ui-bounding-box-overlay.icon-set.create";

    private final VerifiedMemberResolver resolver;
    private final EditorUiPluginResourceRegistry resources;
    private final Map<Object, List<Object>> buttonsByBoundingBox = new IdentityHashMap<>();
    private volatile List<BoundingBoxOverlayButtonDescriptor> descriptors = List.of();

    public VerifiedBoundingBoxOverlayButtonHostOperations(
        final VerifiedMemberResolver resolver,
        final EditorUiPluginResourceRegistry resources
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    @Override
    public Registration install(final List<BoundingBoxOverlayButtonDescriptor> descriptors) {
        final List<BoundingBoxOverlayButtonDescriptor> requested = List.copyOf(descriptors);
        this.descriptors = requested;
        final Registration bridge = NativeBoundingBoxOverlayButtonBridge.install(this);
        return () -> {
            this.descriptors = List.of();
            synchronized (buttonsByBoundingBox) {
                for (List<Object> buttons : buttonsByBoundingBox.values()) {
                    buttons.forEach(button -> resolver.invoke(BUTTON_SET_ENABLED, button, false));
                }
                buttonsByBoundingBox.clear();
            }
            bridge.close();
        };
    }

    void afterUpdate(final Object boundingBox, final Object actionPack, final Object sceneGraph) {
        final List<BoundingBoxOverlayButtonDescriptor> current = descriptors;
        if (current.isEmpty()) {
            return;
        }
        final Object lastBoundingBox = resolver.invoke(LAST_BOUNDING_BOX, boundingBox);
        if (lastBoundingBox == null) {
            return;
        }
        final List<Object> hostButtons = requiredList(resolver.invoke(BUTTONS, boundingBox));
        final List<Object> customButtons;
        synchronized (buttonsByBoundingBox) {
            customButtons = buttonsByBoundingBox.computeIfAbsent(
                boundingBox,
                ignored -> createButtons(boundingBox, current)
            );
        }
        final boolean visible = hostButtons.stream().anyMatch(this::enabled);
        final int firstCustomSlot = hostButtons.size() - customButtons.size();
        for (int index = 0; index < customButtons.size(); index++) {
            final Object button = customButtons.get(index);
            resolver.invoke(BUTTON_SET_ENABLED, button, visible);
            if (visible) {
                positionButton(
                    lastBoundingBox,
                    actionPack,
                    sceneGraph,
                    hostButtons,
                    button,
                    firstCustomSlot + index
                );
            }
        }
    }

    private List<Object> createButtons(
        final Object boundingBox,
        final List<BoundingBoxOverlayButtonDescriptor> current
    ) {
        final List<Object> hostButtons = requiredList(resolver.invoke(BUTTONS, boundingBox));
        final List<Object> created = new ArrayList<>();
        try {
            for (BoundingBoxOverlayButtonDescriptor descriptor : current) {
                final Object callback = resolver.createFunctionalArgumentProxy(
                    BUTTON_CREATE,
                    1,
                    ignored -> {
                        descriptor.button().onClick().run();
                        return kotlinUnit();
                    }
                );
                final Object button = resolver.invoke(
                    BUTTON_CREATE,
                    boundingBox,
                    iconSet(descriptor),
                    callback
                );
                hostButtons.add(button);
                created.add(button);
            }
            return List.copyOf(created);
        } catch (RuntimeException | Error failure) {
            hostButtons.removeAll(created);
            throw failure;
        }
    }

    private Object iconSet(final BoundingBoxOverlayButtonDescriptor descriptor) {
        final BoundingBoxOverlayButton.IconVariants icons = descriptor.button().icons();
        final Object normal = writableImage(descriptor.pluginId(), icons.normal());
        final Object hover = writableImage(
            descriptor.pluginId(),
            icons.hover().orElse(icons.normal())
        );
        final Object pressed = writableImage(
            descriptor.pluginId(),
            icons.pressed().orElse(icons.normal())
        );
        final Object disabled = writableImage(
            descriptor.pluginId(),
            icons.disabled().orElse(icons.normal())
        );
        return resolver.construct(
            ICON_SET_CREATE,
            normal,
            hover,
            pressed,
            disabled,
            normal,
            hover,
            pressed
        );
    }

    private Object writableImage(final String pluginId, final String path) {
        final URL resource = resources.resource(pluginId, path)
            .orElseThrow(() -> new IllegalStateException("overlay icon resource is unavailable"));
        try {
            final BufferedImage image = ImageIO.read(resource);
            if (image == null) {
                throw new IllegalStateException("overlay icon resource is not a supported image");
            }
            return resolver.construct(WRITABLE_IMAGE_CREATE, image);
        } catch (IOException exception) {
            throw new IllegalStateException("overlay icon resource could not be read", exception);
        }
    }

    private void positionButton(
        final Object lastBoundingBox,
        final Object actionPack,
        final Object sceneGraph,
        final List<Object> hostButtons,
        final Object button,
        final int slot
    ) {
        final Object view = resolver.invoke(ACTION_VIEW_CONTEXT, actionPack);
        final Object pack = resolver.invoke(VIEW_COMPLETE_PACK, view);
        final Object panel = resolver.invoke(COMPLETE_PACK_MAIN_VIEW, pack);
        final Object dpi = resolver.invoke(MAIN_VIEW_DPI_SCALE, panel);
        final float scale = ((Number) resolver.invoke(ACTION_SCALE, actionPack)).floatValue()
            * ((Number) resolver.invoke(VECTOR_X, dpi)).floatValue();
        final Object offset = resolver.construct(VECTOR_CREATE, 0.0f, BUTTON_STEP * slot);
        final Object scaledOffset = resolver.invoke(VECTOR_TIMES, offset, scale);
        final Object base = resolver.invoke(HIDE_BUTTON_POSITION, lastBoundingBox, actionPack, hostButtons);
        final Object documentPosition = resolver.invoke(VECTOR_PLUS, base, scaledOffset);
        final Object camera = resolver.invoke(VIEW_CAMERA, view);
        final Object componentPosition = resolver.invoke(DOCUMENT_TO_COMPONENT, camera, documentPosition);
        final float x = ((Number) resolver.invoke(VECTOR_X, componentPosition)).floatValue();
        final float y = ((Number) resolver.invoke("cubism.ui-bounding-box-overlay.vector.y", componentPosition))
            .floatValue();
        final Object bounds = resolver.construct(RECT_CREATE, x, y, BUTTON_SIZE, BUTTON_SIZE);
        resolver.invoke(BUTTON_SET_BOUNDS, button, bounds, 1.0f);
        final Object objects = resolver.invoke(SCENE_COMPONENT_OBJECTS, sceneGraph);
        final Object children = resolver.invoke(ENTITY_CHILDREN, objects);
        resolver.invoke(ENTITIES_ADD, children, button, 0);
        resolver.invoke(SCENE_VOLATILE, sceneGraph, button);
    }

    private boolean enabled(final Object button) {
        final Object result = resolver.invoke("cubism.ui-bounding-box-overlay.entity.enabled", button);
        return result instanceof Boolean value && value;
    }

    private Object kotlinUnit() {
        try {
            return Class.forName("kotlin.Unit", false, resolver.hostClassLoader())
                .getField("INSTANCE")
                .get(null);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Kotlin Unit is unavailable for overlay callback", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requiredList(final Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("bounding-box button list is unavailable");
        }
        return (List<Object>) list;
    }
}

package dev.turboism.ui.overlay;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact-host operations for red-box overlay buttons; invoked only through the verified
 * call-site augmentation's JDK callback.
 *
 * <p>The native {@code buttons} list is never mutated, replaced or wrapped. Custom buttons
 * are created once through the verified native {@code createButton} selector, cached in
 * stable contribution order, reused across contribution snapshots by button identity, and
 * set up by the host's own {@code update$setupButton} helper invoked from transformed
 * bytecode at the third native call site.</p>
 */
public final class VerifiedBoundingBoxOverlayButtonHostOperations
    implements BoundingBoxOverlayButtonHostOperations {

    private static final int MAX_CUSTOM_BUTTONS = 8;
    private static final Object[] EMPTY_BUTTONS = new Object[0];

    private static final String BUTTON_CREATE = "cubism.ui-bounding-box-overlay.button.create";
    private static final String BUTTON_SET_ENABLED =
        "cubism.ui-bounding-box-overlay.button.set-enabled";
    private static final String SCENE_COMPONENT_OBJECTS =
        "cubism.ui-bounding-box-overlay.scene.component-objects";
    private static final String ENTITY_CHILDREN =
        "cubism.ui-bounding-box-overlay.entity.children";
    private static final String SCENE_REMOVE_VOLATILE =
        "cubism.ui-bounding-box-overlay.scene.remove-volatile";
    private static final String ENTITIES_REMOVE =
        "cubism.ui-bounding-box-overlay.entities.remove";
    private static final String WRITABLE_IMAGE_CREATE =
        "cubism.ui-bounding-box-overlay.writable-image.create";
    private static final String ICON_SET_CREATE = "cubism.ui-bounding-box-overlay.icon-set.create";

    private final VerifiedMemberResolver resolver;
    private final EditorUiPluginResourceRegistry resources;
    private final Map<Object, CachedButtons> buttonsByOverlay = new IdentityHashMap<>();
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
        if (requested.size() > MAX_CUSTOM_BUTTONS) {
            throw new IllegalArgumentException(
                "bounding-box overlay button contributions exceed the hard limit of "
                    + MAX_CUSTOM_BUTTONS
            );
        }
        final Registration bridge = NativeBoundingBoxOverlayButtonBridge.install(
            this::customButtonEntities
        );
        this.descriptors = requested;
        return () -> {
            // 1. Mark callbacks inert first: the augmentation then receives no buttons and
            //    stale listeners stop invoking removed plugin callbacks.
            this.descriptors = List.of();
            // 2. Disable and detach every custom entity, clearing side-table references even
            //    when individual operations fail; every failure is aggregated.
            final RuntimeException cleanupFailure = cleanupCustomEntities();
            // 3. Identity-remove the callback properties and clear the bridge handler.
            //    (The transformer removal and original-byte restoration are owned by the
            //    bootstrap hook registration, which closes before runtime/contribution
            //    close during full shutdown.)
            RuntimeException failure = cleanupFailure;
            try {
                bridge.close();
            } catch (RuntimeException | Error bridgeFailure) {
                failure = append(failure, bridgeFailure);
            }
            if (failure != null) {
                throw failure;
            }
        };
    }

    @Override
    public Registration reconcile(
        final List<BoundingBoxOverlayButtonDescriptor> descriptors,
        final Registration existing
    ) {
        final List<BoundingBoxOverlayButtonDescriptor> requested = List.copyOf(descriptors);
        if (requested.size() > MAX_CUSTOM_BUTTONS) {
            throw new IllegalArgumentException(
                "bounding-box overlay button contributions exceed the hard limit of "
                    + MAX_CUSTOM_BUTTONS
            );
        }
        Objects.requireNonNull(existing, "existing");
        // Retain the live native registration and bridge; the next update callback rebuilds
        // the per-overlay cache and reuses unchanged button identities. Removed
        // contributions become click-inert immediately: their existing listener checks the
        // current snapshot by identity and no longer invokes the plugin callback. Scene
        // entities are never mutated from this arbitrary plugin/reconcile thread; physical
        // disable/detach remains on the next native callback/host-thread path.
        this.descriptors = requested;
        return existing;
    }

    /**
     * JDK-callback entry point called from transformed host bytecode on the visible update
     * path. Returns the cached {@code Object[]} of current native custom button entities in
     * stable contribution order; never allocates a new array per update.
     */
    public Object[] customButtonEntities(final Object overlay, final Object sceneGraph) {
        final List<BoundingBoxOverlayButtonDescriptor> current = descriptors;
        if (current.isEmpty()) {
            // Cleanup of every cached entity with the side table cleared; an aggregated
            // failure is observable once through the augmentation's fail-open diagnostic
            // channel instead of being silently swallowed.
            final RuntimeException cleanupFailure = cleanupCustomEntities();
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
            return EMPTY_BUTTONS;
        }
        final CachedButtons cached;
        synchronized (buttonsByOverlay) {
            final CachedButtons existing = buttonsByOverlay.get(overlay);
            if (existing != null && existing.snapshot == current) {
                cached = existing;
            } else {
                cached = rebuild(existing, overlay, current, sceneGraph);
                buttonsByOverlay.put(overlay, cached);
            }
            cached.scene = sceneGraph;
        }
        // The native update$setupButton helper is the sole enabler/setup/positioning path;
        // no proactive setEnabled(true) is emitted here. setEnabled(false) is reserved for
        // detach/cleanup below.
        return cached.array;
    }

    /**
     * Rebuilds one overlay's cached buttons, reusing unchanged button identities and
     * detaching buttons of removed contributions so they become click-inert immediately.
     */
    private CachedButtons rebuild(
        final CachedButtons previous,
        final Object overlay,
        final List<BoundingBoxOverlayButtonDescriptor> current,
        final Object sceneGraph
    ) {
        final Map<BoundingBoxOverlayButton, Object> reused =
            previous == null ? Map.of() : previous.byIdentity;
        final Map<BoundingBoxOverlayButton, Object> byIdentity = new IdentityHashMap<>();
        final List<Object> buttons = new ArrayList<>(current.size());
        final List<Object> created = new ArrayList<>();
        try {
            for (BoundingBoxOverlayButtonDescriptor descriptor : current) {
                final Object button = reused.get(descriptor.button());
                if (button != null) {
                    byIdentity.put(descriptor.button(), button);
                } else {
                    final Object createdButton = createButton(overlay, descriptor);
                    created.add(createdButton);
                    byIdentity.put(descriptor.button(), createdButton);
                }
                buttons.add(byIdentity.get(descriptor.button()));
            }
            if (previous != null) {
                RuntimeException first = null;
                for (Map.Entry<BoundingBoxOverlayButton, Object> removed : previous.byIdentity.entrySet()) {
                    if (!byIdentity.containsKey(removed.getKey())) {
                        first = append(first, detachButton(removed.getValue(), previous.scene));
                    }
                }
                if (first != null) {
                    // Every removed entity was attempted; the aggregated detach failure is
                    // surfaced through the fail-open native failure channel instead of
                    // silently returning success. Newly created entities of this rebuild
                    // are cleaned below and the prior cached state stays retryable.
                    throw first;
                }
            }
            return new CachedButtons(current, List.copyOf(buttons), buttons.toArray(), byIdentity);
        } catch (RuntimeException | Error failure) {
            for (Object button : created) {
                final RuntimeException cleanupFailure = detachButton(button, sceneGraph);
                if (cleanupFailure != null) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * Best-effort disable/volatile/component-child removal: one failed operation never
     * prevents the remaining operations; the first failure is returned for aggregation.
     */
    private RuntimeException detachButton(final Object button, final Object sceneGraph) {
        RuntimeException first = null;
        try {
            resolver.invoke(BUTTON_SET_ENABLED, button, Boolean.FALSE);
        } catch (RuntimeException | Error failure) {
            first = append(first, failure);
        }
        if (sceneGraph != null) {
            try {
                resolver.invoke(SCENE_REMOVE_VOLATILE, sceneGraph, button);
            } catch (RuntimeException | Error failure) {
                first = append(first, failure);
            }
            try {
                final Object objects = resolver.invoke(SCENE_COMPONENT_OBJECTS, sceneGraph);
                final Object children = resolver.invoke(ENTITY_CHILDREN, objects);
                resolver.invoke(ENTITIES_REMOVE, children, button);
            } catch (RuntimeException | Error failure) {
                first = append(first, failure);
            }
        }
        return first;
    }

    private Object createButton(
        final Object overlay,
        final BoundingBoxOverlayButtonDescriptor descriptor
    ) {
        final long generation = NativeBoundingBoxOverlayButtonBridge.activeGeneration();
        final Object callback = resolver.createFunctionalArgumentProxy(
            BUTTON_CREATE,
            1,
            ignored -> {
                // Click-inert by generation and identity: hook close invalidates every proxy
                // from that installation, while reconcile immediately invalidates removed items.
                if (NativeBoundingBoxOverlayButtonBridge.isGenerationActive(generation)
                    && isCurrent(descriptor.button())) {
                    descriptor.button().onClick().run();
                }
                return kotlinUnit();
            }
        );
        return resolver.invoke(BUTTON_CREATE, overlay, iconSet(descriptor), callback);
    }

    private boolean isCurrent(final BoundingBoxOverlayButton button) {
        for (BoundingBoxOverlayButtonDescriptor descriptor : descriptors) {
            if (descriptor.button() == button) {
                return true;
            }
        }
        return false;
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
            return resolver.construct(WRITABLE_IMAGE_CREATE, normalizeArgb(image));
        } catch (IOException exception) {
            throw new IllegalStateException("overlay icon resource could not be read", exception);
        }
    }

    /**
     * The host's {@code WritableImage} constructor accepts only {@code TYPE_INT_ARGB}
     * images; ImageIO decodes common 8-bit RGBA PNGs as {@code TYPE_4BYTE_ABGR}, which
     * the host constructor rejects. The original image is returned unchanged when it is
     * already {@code TYPE_INT_ARGB}.
     */
    private static BufferedImage normalizeArgb(final BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        final BufferedImage normalized = new BufferedImage(
            image.getWidth(),
            image.getHeight(),
            BufferedImage.TYPE_INT_ARGB
        );
        final Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    /**
     * Disables every custom entity and removes it from the scene's volatile set and
     * component children through exact verified selectors, using the last scene graph
     * observed by the update augmentation, then clears the side table.
     */
    /**
     * Disables every custom entity and removes it from the scene's volatile set and
     * component children through exact verified selectors, using the last scene graph
     * observed by the update augmentation. Every cached overlay/button is attempted; the
     * side table is always cleared and the first failure is returned for aggregation.
     */
    private RuntimeException cleanupCustomEntities() {
        synchronized (buttonsByOverlay) {
            RuntimeException first = null;
            try {
                for (CachedButtons cached : buttonsByOverlay.values()) {
                    final Object scene = cached.scene;
                    for (Object button : cached.buttons) {
                        first = append(first, detachButton(button, scene));
                    }
                }
            } finally {
                buttonsByOverlay.clear();
            }
            return first;
        }
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

    private static RuntimeException append(
        final RuntimeException first,
        final Throwable next
    ) {
        if (next == null) {
            return first;
        }
        final RuntimeException failure = first == null
            ? new IllegalStateException(
                "bounding-box overlay cleanup failed safely: " + next.getClass().getName()
            )
            : first;
        failure.addSuppressed(next);
        return failure;
    }

    /** One overlay's cached buttons: snapshot reference, identity reuse map and array. */
    private static final class CachedButtons {
        private final List<BoundingBoxOverlayButtonDescriptor> snapshot;
        private final Map<BoundingBoxOverlayButton, Object> byIdentity;
        private final List<Object> buttons;
        private final Object[] array;
        private Object scene;

        private CachedButtons(
            final List<BoundingBoxOverlayButtonDescriptor> snapshot,
            final List<Object> buttons,
            final Object[] array,
            final Map<BoundingBoxOverlayButton, Object> byIdentity
        ) {
            this.snapshot = snapshot;
            this.buttons = buttons;
            this.array = array;
            this.byIdentity = byIdentity;
        }
    }
}

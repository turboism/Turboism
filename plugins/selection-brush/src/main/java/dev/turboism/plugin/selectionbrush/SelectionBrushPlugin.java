package dev.turboism.plugin.selectionbrush;

import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.cubism.mesh.MeshToolbarSlider;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.util.Objects;

/** Official SDK-only Selection Brush plugin. */
public final class SelectionBrushPlugin implements TurboismPlugin {
    private static final int DEFAULT_RADIUS = 32;

    private final Object monitor = new Object();
    private PluginContext context;
    private MeshBrush activeBrush;
    private int radius = DEFAULT_RADIUS;
    private boolean enabled;

    private final MeshTool tool = new MeshTool() {
        @Override
        public String id() {
            return "selection-brush";
        }

        @Override
        public String label() {
            return labelText("mesh.selection-brush.label", "Selection Brush");
        }

        @Override
        public String iconResourcePath() {
            return "icons/selection-brush.png";
        }

        @Override
        public String activeIconResourcePath() {
            return "icons/selection-brush-active.png";
        }

        @Override
        public String rollOverIconResourcePath() {
            return "icons/selection-brush-rollover.png";
        }

        @Override
        public String selectedIconResourcePath() {
            return "icons/selection-brush-selected.png";
        }

        @Override
        public String disabledIconResourcePath() {
            return "icons/selection-brush-disabled.png";
        }

        @Override
        public String disabledSelectedIconResourcePath() {
            return "icons/selection-brush-disabled-selected.png";
        }

        @Override
        public int order() {
            return 100;
        }

        @Override
        public SelectionMode strokeSelectionMode(
                final boolean shiftDown, final boolean controlDown, final boolean altDown) {
            // Native-consistent gestures: Ctrl (deselect) wins over Shift (add), otherwise replace.
            if (controlDown) return SelectionMode.REMOVE;
            return shiftDown ? SelectionMode.ADD : SelectionMode.REPLACE;
        }

        @Override
        public void activate(final MeshToolContext toolContext) {
            final MeshBrush candidate = Objects.requireNonNull(
                    Objects.requireNonNull(toolContext, "toolContext").selectionBrush(), "selectionBrush");
            final int currentRadius;
            final MeshBrush previous;
            synchronized (monitor) {
                previous = activeBrush;
                activeBrush = null;
                currentRadius = radius;
            }
            if (previous != null) previous.close();
            try {
                candidate.setRadiusPixels(currentRadius);
            } catch (RuntimeException | Error failure) {
                candidate.close();
                throw failure;
            }
            synchronized (monitor) {
                activeBrush = candidate;
            }
        }

        @Override
        public void deactivate() {
            closeActiveBrush();
        }
    };

    private final MeshToolbarSlider slider = new MeshToolbarSlider() {
        @Override
        public String id() {
            return "selection-brush.radius";
        }

        @Override
        public String label() {
            return labelText("mesh.selection-brush.radius.label", "Brush Radius");
        }

        @Override
        public int minimum() {
            return MeshBrush.MIN_RADIUS_PIXELS;
        }

        @Override
        public int maximum() {
            return MeshBrush.MAX_RADIUS_PIXELS;
        }

        @Override
        public int value() {
            synchronized (monitor) {
                return radius;
            }
        }

        @Override
        public void setValue(final int value) {
            if (value < minimum() || value > maximum()) {
                throw new IllegalArgumentException("value must be within [8, 128]");
            }
            final MeshBrush brush;
            synchronized (monitor) {
                radius = value;
                brush = activeBrush;
            }
            if (brush != null) brush.setRadiusPixels(value);
        }

        @Override
        public int order() {
            return 110;
        }
    };

    @Override
    public void init(final PluginContext context) {
        synchronized (monitor) {
            if (this.context != null) throw new IllegalStateException("Selection Brush is already initialized");
            this.context = Objects.requireNonNull(context, "context");
        }
    }

    @Override
    public void enable() {
        final PluginContext current;
        synchronized (monitor) {
            if (enabled) return;
            current = Objects.requireNonNull(context, "Selection Brush is not initialized");
        }
        Registration toolRegistration = null;
        Registration sliderRegistration = null;
        try {
            final MeshToolRegistry registry = current.services().require(MeshToolRegistry.class);
            toolRegistration = registry.register(tool);
            sliderRegistration = registry.contributeSlider(slider);
            current.disposableScope().register(toolRegistration);
            current.disposableScope().register(sliderRegistration);
            synchronized (monitor) {
                enabled = true;
            }
        } catch (RuntimeException | Error failure) {
            closeSuppressing(sliderRegistration, failure);
            closeSuppressing(toolRegistration, failure);
            throw failure;
        }
    }

    @Override
    public void disable() {
        closeActiveBrush();
    }

    @Override
    public void shutdown() {
        closeActiveBrush();
    }

    private String labelText(final String key, final String fallback) {
        final PluginContext current;
        synchronized (monitor) {
            current = context;
        }
        if (current == null) return fallback;
        final var localization = current.services().get(dev.turboism.sdk.i18n.PluginLocalization.class);
        return localization == null ? fallback : localization.text(key);
    }

    private void closeActiveBrush() {
        final MeshBrush brush;
        synchronized (monitor) {
            brush = activeBrush;
            activeBrush = null;
        }
        if (brush != null) brush.close();
    }

    private static void closeSuppressing(final Registration registration, final Throwable failure) {
        if (registration == null) return;
        try {
            registration.close();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}

package dev.turboism.adapter.ui;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.awt.Color;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Updates both Cubism's lazy background color and the material copies in existing views. */
public final class OffCanvasBackgroundHostAccess implements Consumer<Color> {

    private final Field background;
    private final Method appInstance;
    private final Method completePack;
    private final Method allViews;
    private final Method appearance;
    private final Method backgroundPanel;
    private final Method meshRenderer;
    private final Method material;
    private final Constructor<?> color;
    private final Method setColor;
    private final Method updateManager;
    private final Method repaintCanvas;

    /**
     * Binds the background contract only to an exact reviewed artifact and its defining loader.
     * Host classes are resolved without initialization; binding never constructs a scene.
     *
     * @param artifact the admitted Editor JAR
     * @param loader its defining class loader
     * @throws IOException if the artifact cannot be read
     * @throws ReflectiveOperationException if the reviewed member contract is unavailable
     */
    public OffCanvasBackgroundHostAccess(final Path artifact, final ClassLoader loader)
            throws IOException, ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        final String version = ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.from(artifact))
                .orElseThrow(() -> new IllegalArgumentException("Unreviewed off-canvas appearance artifact"));
        final Path source = artifact.toRealPath();
        final Class<?> colors = type("com.live2d.cubism.view.gl.SGFramework.h$d", loader, source);
        background = colors.getDeclaredField("d");
        if (!Modifier.isStatic(background.getModifiers())
                || !background.getType().getName().equals("kotlin.Lazy")) {
            throw new IllegalArgumentException("Off-canvas lazy color contract changed");
        }
        background.setAccessible(true);
        final Class<?> app = type("com.live2d.cubism.CEAppCtrl", loader, source);
        final Class<?> pack = type("com.live2d.cubism.pack.CECompletePack", loader, source);
        final Class<?> view = type("com.live2d.cubism.view.context.CEViewContext", loader, source);
        final Class<?> settings = type(appearanceClass(version), loader, source);
        final Class<?> panel = type("com.live2d.graphics3d.geometry.GSimpleMeshEntity", loader, source);
        final Class<?> renderer = type("com.live2d.graphics3d.component.GMeshRenderer", loader, source);
        final Class<?> rendererBase = type("com.live2d.graphics3d.component.AGRenderer", loader, source);
        final Class<?> nativeMaterial = type("com.live2d.graphics3d.material.GMaterial", loader, source);
        final Class<?> nativeColor = type("com.live2d.type.CColor", loader, source);
        final Class<?> updates = type("com.live2d.cubism.CEUpdateManager", loader, source);
        appInstance = app.getMethod("access$get_instance$cp");
        completePack = app.getMethod("getCompletePack");
        allViews = pack.getMethod("getAllViewContext");
        appearance = view.getMethod("getAppearanceSetting");
        if (appearance.getReturnType() != settings) {
            throw new IllegalArgumentException("Off-canvas appearance settings contract changed");
        }
        backgroundPanel = settings.getMethod("f");
        meshRenderer = panel.getMethod("getMeshRenderer");
        material = rendererBase.getMethod("getMaterial");
        color = nativeColor.getConstructor(Color.class);
        // Cubism 5.3 added the optional uniform array index; 5.0–5.2 use two arguments.
        setColor = version.startsWith("5.3.")
                ? nativeMaterial.getMethod("setColor", String.class, nativeColor, Integer.class)
                : nativeMaterial.getMethod("setColor", String.class, nativeColor);
        updateManager = pack.getMethod("getUpdateManager");
        repaintCanvas = updates.getMethod("repaintCanvas", boolean.class);
        if (backgroundPanel.getReturnType() != panel || meshRenderer.getReturnType() != renderer) {
            throw new IllegalArgumentException("Off-canvas background panel contract changed");
        }
    }

    /** Synchronizes the color and all view materials, then requests a native canvas repaint. */
    @Override
    public void accept(final Color value) {
        Objects.requireNonNull(value, "value");
        try {
            updateCachedColor(background.get(null), value);
            final Object app = appInstance.invoke(null);
            if (app == null) {
                return;
            }
            final Object pack = completePack.invoke(app);
            if (pack == null) {
                return;
            }
            for (Object view : (List<?>) allViews.invoke(pack)) {
                updateAppearance(appearance.invoke(view), value);
            }
            repaintCanvas.invoke(updateManager.invoke(pack), true);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not refresh the off-canvas background", exception);
        }
    }

    // The material is a copy made by the appearance constructor, not a live reference to the Lazy.
    void updateAppearance(final Object settings, final Color value) throws ReflectiveOperationException {
        final Object panel = backgroundPanel.invoke(settings);
        final Object renderer = meshRenderer.invoke(panel);
        final Object target = material.invoke(renderer);
        if (target != null) {
            if (setColor.getParameterCount() == 3) {
                setColor.invoke(target, "baseColor", color.newInstance(value), null);
            } else {
                setColor.invoke(target, "baseColor", color.newInstance(value));
            }
        }
    }

    static void updateCachedColor(final Object lazy, final Color value) throws ReflectiveOperationException {
        final Class<?> type = lazy.getClass();
        if (!type.getName().equals("kotlin.SynchronizedLazyImpl")) {
            throw new IllegalArgumentException("Unexpected off-canvas color Lazy implementation");
        }
        final Field lock = type.getDeclaredField("lock");
        final Field cached = type.getDeclaredField("_value");
        if (cached.getType() != Object.class || !Modifier.isVolatile(cached.getModifiers())) {
            throw new IllegalArgumentException("Off-canvas color Lazy storage contract changed");
        }
        lock.setAccessible(true);
        cached.setAccessible(true);
        final Method getValue = type.getMethod("getValue");
        getValue.setAccessible(true);
        // Use Kotlin's own lock and finish normal initialization before replacing its volatile value.
        // This avoids racing a first scene construction and leaves its initializer lifecycle intact.
        synchronized (lock.get(lazy)) {
            if (!(getValue.invoke(lazy) instanceof Color)) {
                throw new IllegalArgumentException("Off-canvas Lazy did not resolve to a color");
            }
            cached.set(lazy, value);
        }
    }

    private static Class<?> type(final String name, final ClassLoader loader, final Path artifact)
            throws ReflectiveOperationException, IOException {
        final Class<?> result = Class.forName(name, false, loader);
        try {
            if (result.getClassLoader() != loader
                    || result.getProtectionDomain().getCodeSource() == null
                    || !Path.of(result.getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())
                            .toRealPath()
                            .equals(artifact)) {
                throw new IllegalArgumentException("Off-canvas host source mismatch: " + name);
            }
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid off-canvas host source", exception);
        }
        return result;
    }

    private static final Map<java.util.Set<String>, String> APPEARANCE_CLASSES = Map.ofEntries(
            Map.entry(ReviewedVersions.between("5.0.00", "5.0.06"), "bG"),
            Map.entry(ReviewedVersions.between("5.1.00", "5.1.03"), "bJ"),
            Map.entry(ReviewedVersions.between("5.2.00", "5.2.02"), "bO"),
            Map.entry(ReviewedVersions.between("5.2.03", "5.3.02"), "bR"),
            Map.entry(ReviewedVersions.between("5.3.03", "5.3.03"), "bS"),
            Map.entry(ReviewedVersions.between("5.3.04", "5.3.04"), "bT"));

    private static String appearanceClass(final String version) {
        final String owner = APPEARANCE_CLASSES.entrySet().stream()
                .filter(entry -> entry.getKey().contains(version))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported off-canvas appearance version"));
        return "com.live2d.cubism.view.context." + owner + "$a";
    }
}

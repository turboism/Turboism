package dev.turboism.ui.appearance;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/**
 * Framework-owned refresher for the Cubism off-canvas (GL viewport) background.
 *
 * <p>Cubism caches the off-canvas color in a singleton Lazy on first GL scene
 * access, so UIManager changes alone never repaint it. This refresher walks the
 * host's public appearance API — {@code CEAppCtrl.currentViewContext →
 * appearanceSetting → viewAreaBackgroundPanel → meshRenderer → current material → baseColor}
 * — and pushes the current color onto the same uniform used when Cubism creates
 * the background plane.</p>
 *
 * <p>All host access is through public methods and getters; no private fields
 * are touched. Unknown or changed host structures fail closed.</p>
 */
public final class OffCanvasAppearanceRefresher {

    private static final String CE_APP_CTRL = "com.live2d.cubism.CEAppCtrl";
    private static final String COLOR_SLOT = "baseColor";

    public OffCanvasAppearanceRefresher() {
    }

    /**
     * Applies the current off-canvas background color to the host GL scene.
     *
     * @return {@code true} when the mesh was refreshed, {@code false} when the
     *     host structure is unavailable (fail closed).
     */
    public boolean refresh(final String colorHex) {
        final Color color = parse(colorHex);
        if (color == null) {
            return false;
        }
        try {
            final Object appCtrl = currentAppCtrl().orElse(null);
            if (appCtrl == null) {
                return false;
            }
            final Object viewContext = invoke(appCtrl, "getCurrentViewContext");
            if (viewContext == null) {
                return false;
            }
            final Object appearance = invoke(viewContext, "getAppearanceSetting");
            if (appearance == null) {
                return false;
            }
            final Object mesh = invoke(appearance, "f");
            if (mesh == null) {
                return false;
            }
            final Object renderer = invoke(mesh, "getMeshRenderer");
            if (renderer == null) {
                return false;
            }
            final Object material = invoke(renderer, "getCurMaterial");
            if (material == null) {
                return false;
            }
            final Object cColor = newCColor(color);
            final Class<?> materialClass = material.getClass();
            if (!setMaterialColor(material, cColor, color)) {
                return false;
            }
            forceRepaintCanvas(appCtrl);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private Optional<Object> currentAppCtrl() {
        try {
            final Class<?> appCtrl = Class.forName(CE_APP_CTRL);
            final Object companion = appCtrl.getField("Companion").get(null);
            if (companion == null) {
                return Optional.empty();
            }
            // Companion.c() returns the singleton _instance; Companion.b() is its
            // non-null alias. Companion.a() constructs a brand new CEAppCtrl and
            // must never be used.
            for (String methodName : new String[] { "c", "b" }) {
                try {
                    final Object instance = companion.getClass().getMethod(methodName).invoke(companion);
                    if (instance != null) {
                        return Optional.of(instance);
                    }
                } catch (ReflectiveOperationException ignored) {
                    // try the next accessor
                }
            }
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Object invoke(final Object target, final String methodName)
        throws ReflectiveOperationException {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static Object newCColor(final Color color) throws ReflectiveOperationException {
        final Class<?> cColor = Class.forName("com.live2d.type.CColor");
        return cColor.getConstructor(int.class, int.class, int.class).newInstance(
            color.getRed(), color.getGreen(), color.getBlue()
        );
    }


    /**
     * Applies the color to the material. Cubism 5.3.02 exposes
     * {@code setColor(String, CColor, Integer)} while 5.2.03 exposes the
     * two-argument {@code setColor(String, CColor)}; probe both so the same
     * refresher works across versions.
     */
    private static boolean setMaterialColor(
        final Object material,
        final Object cColor,
        final Color color
    ) {
        try {
            final Class<?> materialClass = material.getClass();
            try {
                materialClass.getMethod("setColor", String.class, cColor.getClass(), Integer.class)
                    .invoke(material, COLOR_SLOT, cColor, null);
                return true;
            } catch (NoSuchMethodException ignored) {
                materialClass.getMethod("setColor", String.class, cColor.getClass())
                    .invoke(material, COLOR_SLOT, cColor);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /** Forces the Cubism canvas to re-render so the updated mesh color is drawn. */
    private static void forceRepaintCanvas(final Object appCtrl) {
        try {
            appCtrl.getClass().getMethod("forceRepaintCanvas$cubism").invoke(appCtrl);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Repaint is best-effort.
        }
    }

    private static Color parse(final String value) {
        if (value == null || !value.matches("#[0-9A-Fa-f]{6}")) {
            return null;
        }
        try {
            return Color.decode(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

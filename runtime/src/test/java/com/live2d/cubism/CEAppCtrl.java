package com.live2d.cubism;

import com.live2d.type.CColor;

/** Test-only Cubism host shape used by the off-canvas reflection test. */
public final class CEAppCtrl {
    public static final Companion Companion = new Companion();
    private static final CEAppCtrl INSTANCE = new CEAppCtrl();

    private final ViewContext currentViewContext = new ViewContext();
    private boolean repainted;

    private CEAppCtrl() {
    }

    public ViewContext getCurrentViewContext() {
        return currentViewContext;
    }

    public void forceRepaintCanvas$cubism() {
        repainted = true;
    }

    private static boolean twoArgumentMaterialOnly;

    public static void useTwoArgumentMaterialOnly() {
        twoArgumentMaterialOnly = true;
    }

    public static void reset() {
        twoArgumentMaterialOnly = false;
        INSTANCE.repainted = false;
        INSTANCE.currentViewContext.appearance.renderer.material = null;
        INSTANCE.currentViewContext.appearance.renderer.sharedMaterial =
            twoArgumentMaterialOnly ? new TwoArgumentMaterial() : new Material();
    }

    public static Material material() {
        return INSTANCE.currentViewContext.appearance.renderer.sharedMaterial;
    }

    public static boolean repainted() {
        return INSTANCE.repainted;
    }

    public static final class Companion {
        public CEAppCtrl c() {
            return INSTANCE;
        }

        public CEAppCtrl b() {
            return INSTANCE;
        }
    }

    public static final class ViewContext {
        private final AppearanceSetting appearance = new AppearanceSetting();

        public AppearanceSetting getAppearanceSetting() {
            return appearance;
        }
    }

    public static final class AppearanceSetting {
        private final Mesh mesh = new Mesh();
        private final Renderer renderer = mesh.renderer;

        public Mesh f() {
            return mesh;
        }
    }

    public static final class Mesh {
        private final Renderer renderer = new Renderer();

        public Renderer getMeshRenderer() {
            return renderer;
        }
    }

    public static final class Renderer {
        private Material material;
        private Material sharedMaterial = new Material();

        public Material getMaterial() {
            return material;
        }

        public Material getCurMaterial() {
            return material == null ? sharedMaterial : material;
        }
    }

    public static class Material {
        private String slot;
        private CColor color;

        public void setColor(final String slot, final CColor color, final Integer pass) {
            this.slot = slot;
            this.color = color;
        }

        public void setColor(final String slot, final CColor color) {
            this.slot = slot;
            this.color = color;
        }

        public String slot() {
            return slot;
        }

        public CColor color() {
            return color;
        }
    }

    /** Material that only exposes the two-argument setColor (Cubism 5.2 shape). */
    public static final class TwoArgumentMaterial extends Material {
        @Override
        public void setColor(final String slot, final CColor color, final Integer pass) {
            throw new UnsupportedOperationException("three-argument setColor is not available");
        }
    }
}

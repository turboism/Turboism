package dev.turboism.adapter.cubism.optimization.atlas.interactive;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Defines reviewed host classes, fields, and method descriptors for interactive Texture Atlas
 * Editor canvas, drag, and viewport optimizations across reviewed Cubism builds.
 */
public record AtlasInteractiveRenderTarget(HostArtifactDigest digest, String version, Generation generation) {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.atlasInteractiveRender";

    /** All reviewed Cubism releases carrying the interactive atlas editor. */
    public static final Set<String> SUPPORTED_VERSIONS = ReviewedVersions.since("5.0.00");

    private static final Set<String> EARLY_FIVE_ZERO = ReviewedVersions.between("5.0.00", "5.0.04");
    private static final Set<String> LATE_FIVE_ZERO_TO_ONE = ReviewedVersions.between("5.0.05", "5.1.03");
    private static final Set<String> MODERN_FIVE_TWO_TO_THREE_EARLY = ReviewedVersions.between("5.2.00", "5.3.02");

    private static final String ATLAS_IMPL_PREFIX = "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/";

    /** Shared model-image layer class across every reviewed Cubism build. */
    public static final String EDIT_LAYER_OWNER = ATLAS_IMPL_PREFIX + "TAE__EditLayer_ModelImage";

    /** Shared inspector panel class across every reviewed Cubism build. */
    public static final String INSPECTOR_OWNER = ATLAS_IMPL_PREFIX + "v";

    /** Shared layer edit controller class across every reviewed Cubism build. */
    public static final String EDIT_CONTROLLER_OWNER = ATLAS_IMPL_PREFIX + "b/a";

    /** Shared layer update event class delivered to the unit panel listener. */
    public static final String LAYER_UPDATE_EVENT_OWNER = ATLAS_IMPL_PREFIX + "b/a/a";

    /** Shared atlas data-model owner holding overlay fill and stroke colors. */
    public static final String DATA_MODEL_OWNER = ATLAS_IMPL_PREFIX + "TAE_DataModel";

    /** Companion owner for default overlay fill and stroke colors. */
    public static final String DATA_MODEL_COMPANION_OWNER = ATLAS_IMPL_PREFIX + "TAE_DataModel$a";

    /** Render-mode enum owner separating interactive display passes from final atlas bakes. */
    public static final String RENDER_MODE_OWNER = "com/live2d/cubism/doc/modeling/ui/atlasEditor/f$c";

    /** Viewport zoom panel owner shared across reviewed builds. */
    public static final String VIEWPORT_CANVAS_OWNER = "jp/noids/graphics/f/w";

    /** Color wrapper owner storing Java AWT colors. */
    public static final String COLOR_OWNER = "com/live2d/type/CColor";

    /** Cursor-warp Robot helper owner. */
    public static final String ROBOT_OWNER = "com/live2d/ui/h";

    /** Method name of the model-image layer draw routine. */
    public static final String DRAW_EXE_METHOD = "draw_exe";

    /** Method descriptor of {@link #DRAW_EXE_METHOD}. */
    public static final String DRAW_EXE_DESCRIPTOR = "(Ljp/noids/design/layer/core/a;)V";

    /** Canvas Swing paint override name. */
    public static final String CANVAS_PAINT_METHOD = "paint";

    /** Method descriptor of {@link #CANVAS_PAINT_METHOD}. */
    public static final String CANVAS_PAINT_DESCRIPTOR = "(Ljava/awt/Graphics;)V";

    /** Canvas internal render helper name. */
    public static final String CANVAS_RENDER_METHOD = "a";

    /** Method descriptor of {@link #CANVAS_RENDER_METHOD}. */
    public static final String CANVAS_RENDER_DESCRIPTOR = "(Ljava/awt/Graphics;Z)V";

    /** Unit-panel layer-update listener method name. */
    public static final String UNIT_LAYER_UPDATED_METHOD = "a";

    /** Method descriptor of {@link #UNIT_LAYER_UPDATED_METHOD}. */
    public static final String UNIT_LAYER_UPDATED_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/b/a/a;)V";

    /** Mouse-dragged handler on the atlas scroll-pan listener. */
    public static final String MOUSE_DRAGGED_METHOD = "mouseDragged";

    /** Method descriptor of {@link #MOUSE_DRAGGED_METHOD}. */
    public static final String MOUSE_DRAGGED_DESCRIPTOR = "(Ljava/awt/event/MouseEvent;)V";

    /** Shared void zero-argument descriptor. */
    public static final String VOID_METHOD_DESCRIPTOR = "()V";

    /** Linked camera list accessor method name. */
    public static final String LINKED_CAMERAS_METHOD = "i";

    /** Method descriptor returning linked camera panels. */
    public static final String ARRAY_LIST_GETTER_DESCRIPTOR = "()Ljava/util/ArrayList;";

    /** Viewport zoom-application method name on {@link #VIEWPORT_CANVAS_OWNER}. */
    public static final String VIEWPORT_SET_ZOOM_METHOD = "a";

    /** Method descriptor of {@link #VIEWPORT_SET_ZOOM_METHOD}. */
    public static final String VIEWPORT_SET_ZOOM_DESCRIPTOR = "(Ljava/awt/Point;D)D";

    /** Layer transform setter whose round-trip overwrite runs in inspector sync. */
    public static final String LAYER_REF_OWNER = "jp/noids/design/layer/core/LayerRef";

    /** Method name on {@link #LAYER_REF_OWNER}. */
    public static final String SET_TRANSFORM_TO_PARENT_METHOD = "setTransformToParent";

    /** Method descriptor of {@link #SET_TRANSFORM_TO_PARENT_METHOD}. */
    public static final String SET_TRANSFORM_TO_PARENT_DESCRIPTOR = "(Ljp/live2d/type_editor/a;)V";

    /** Shape list field on {@link #EDIT_LAYER_OWNER}. */
    public static final String DRAW_DATA_SHAPES_FIELD = "drawDataShapes";

    /** Field descriptor of {@link #DRAW_DATA_SHAPES_FIELD}. */
    public static final String ARRAY_LIST_DESCRIPTOR = "Ljava/util/ArrayList;";

    /** Data model field on {@link #EDIT_LAYER_OWNER}. */
    public static final String TEX_RELOC_MODEL_FIELD = "texRelocModel";

    /** Field descriptor of {@link #TEX_RELOC_MODEL_FIELD}. */
    public static final String TEX_RELOC_MODEL_DESCRIPTOR = "L" + DATA_MODEL_OWNER + ";";

    /** Legacy boolean field holding active drag state on {@link #LAYER_UPDATE_EVENT_OWNER}. */
    public static final String EVENT_DRAGGING_FIELD = "a";

    /** Field descriptor of {@link #EVENT_DRAGGING_FIELD}. */
    public static final String BOOLEAN_DESCRIPTOR = "Z";

    /** Trailing 500 ms repaint task key emitted by {@code paint(Graphics)}. */
    public static final String TRAILING_REPAINT_TIMER_KEY = "TexReloc3_UI_LayerUnitPanel_invokelater";

    /** Trailing icon update timer key emitted after inspector sync. */
    public static final String ICON_UPDATE_TIMER_KEY = "TEX_RELOC_UPDATE_ICON";

    /** Viewport scroll-pane accessor name on modern/mid camera panels. */
    public static final String SCROLL_PANE_GETTER_METHOD = "d";

    /** Scroll pane descriptor. */
    public static final String SCROLL_PANE_GETTER_DESCRIPTOR = "()Ljavax/swing/JScrollPane;";

    /** Viewport canvas getter on camera panels. */
    public static final String VIEWPORT_CANVAS_GETTER_METHOD = "c";

    /** Viewport canvas getter descriptor. */
    public static final String VIEWPORT_CANVAS_GETTER_DESCRIPTOR = "()Ljp/noids/graphics/f/w;";

    /** Total number of rewritten host classes required across all reviewed builds. */
    public static final int EXPECTED_MATCHES = 6;

    /** Total number of injected weave sites required across all reviewed builds. */
    public static final int EXPECTED_SITES = 14;

    /** Default legacy overlay fill ARGB used when no customizable color exists. */
    public static final int LEGACY_FILL_ARGB = 0x4001FFFF;

    /** Default legacy overlay stroke ARGB used when no customizable color exists. */
    public static final int LEGACY_STROKE_ARGB = 0x20FF0000;

    /** Groups reviewed Cubism releases by obfuscated atlas-editor class and method names. */
    public enum Generation {
        /** Early 5.0 releases without customizable overlay colors or the image table list. */
        LEGACY_EARLY(
                ATLAS_IMPL_PREFIX + "P",
                ATLAS_IMPL_PREFIX + "R",
                ATLAS_IMPL_PREFIX + "D",
                ATLAS_IMPL_PREFIX + "F",
                "r",
                null,
                "s",
                "a",
                "(L" + ATLAS_IMPL_PREFIX + "D;Ljava/awt/event/MouseWheelEvent;)V",
                "g",
                "h",
                "a",
                "(II)V",
                false,
                true),
        /** Late 5.0 through 5.1 releases with customizable overlay colors and no image table list. */
        DATA_MODEL_COLOR(
                ATLAS_IMPL_PREFIX + "R",
                ATLAS_IMPL_PREFIX + "T",
                ATLAS_IMPL_PREFIX + "F",
                ATLAS_IMPL_PREFIX + "H",
                "t",
                null,
                "u",
                "a",
                "(L" + ATLAS_IMPL_PREFIX + "F;Ljava/awt/event/MouseWheelEvent;)V",
                "h",
                "i",
                "a",
                "(II)V",
                false,
                false),
        /** 5.2 through early 5.3 releases with table sync and 2-arg Robot helper. */
        TABLE_SYNC_CLASSIC_ROBOT(
                ATLAS_IMPL_PREFIX + "T",
                ATLAS_IMPL_PREFIX + "V",
                ATLAS_IMPL_PREFIX + "H",
                ATLAS_IMPL_PREFIX + "J",
                "x",
                "j",
                "y",
                "a",
                "(Ljava/awt/event/MouseWheelEvent;)V",
                "h",
                "i",
                "a",
                "(II)V",
                false,
                false),
        /** 5.3.03+ releases with table sync and HiDPI 3-arg Robot helper. */
        TABLE_SYNC_HIDPI_ROBOT(
                ATLAS_IMPL_PREFIX + "T",
                ATLAS_IMPL_PREFIX + "V",
                ATLAS_IMPL_PREFIX + "H",
                ATLAS_IMPL_PREFIX + "J",
                "x",
                "j",
                "y",
                "a",
                "(Ljava/awt/event/MouseWheelEvent;)V",
                "h",
                "i",
                "a",
                "(IILjava/lang/Float;)V",
                true,
                false);

        private final String canvasOwner;
        private final String unitPanelOwner;
        private final String cameraOwner;
        private final String scrollDragOwner;
        private final String inspectorSyncMethod;
        private final String inspectorTableSyncMethod;
        private final String inspectorIconTimerMethod;
        private final String wheelZoomMethod;
        private final String wheelZoomDescriptor;
        private final String cameraCanvasField;
        private final String cameraScrollField;
        private final String robotMoveMethod;
        private final String robotMoveDescriptor;
        private final boolean hiDpiRobotHelper;
        private final boolean staticOverlayColors;

        Generation(
                final String canvasOwner,
                final String unitPanelOwner,
                final String cameraOwner,
                final String scrollDragOwner,
                final String inspectorSyncMethod,
                final String inspectorTableSyncMethod,
                final String inspectorIconTimerMethod,
                final String wheelZoomMethod,
                final String wheelZoomDescriptor,
                final String cameraCanvasField,
                final String cameraScrollField,
                final String robotMoveMethod,
                final String robotMoveDescriptor,
                final boolean hiDpiRobotHelper,
                final boolean staticOverlayColors) {
            this.canvasOwner = canvasOwner;
            this.unitPanelOwner = unitPanelOwner;
            this.cameraOwner = cameraOwner;
            this.scrollDragOwner = scrollDragOwner;
            this.inspectorSyncMethod = inspectorSyncMethod;
            this.inspectorTableSyncMethod = inspectorTableSyncMethod;
            this.inspectorIconTimerMethod = inspectorIconTimerMethod;
            this.wheelZoomMethod = wheelZoomMethod;
            this.wheelZoomDescriptor = wheelZoomDescriptor;
            this.cameraCanvasField = cameraCanvasField;
            this.cameraScrollField = cameraScrollField;
            this.robotMoveMethod = robotMoveMethod;
            this.robotMoveDescriptor = robotMoveDescriptor;
            this.hiDpiRobotHelper = hiDpiRobotHelper;
            this.staticOverlayColors = staticOverlayColors;
        }

        /** Internal name of the atlas canvas subclass. */
        public String canvasOwner() {
            return canvasOwner;
        }

        /** Internal name of the unit panel owning drag event handling. */
        public String unitPanelOwner() {
            return unitPanelOwner;
        }

        /** Internal name of the camera viewport panel handling mouse-wheel zoom. */
        public String cameraOwner() {
            return cameraOwner;
        }

        /** Internal name of the middle-button drag pan handler. */
        public String scrollDragOwner() {
            return scrollDragOwner;
        }

        /** Inspector method synchronizing transforms, text fields, and icon timer. */
        public String inspectorSyncMethod() {
            return inspectorSyncMethod;
        }

        /** Inspector table rebuild method, or {@code null} on builds before the table UI. */
        public String inspectorTableSyncMethod() {
            return inspectorTableSyncMethod;
        }

        /** Inspector method scheduling {@code TEX_RELOC_UPDATE_ICON}. */
        public String inspectorIconTimerMethod() {
            return inspectorIconTimerMethod;
        }

        /** Camera wheel-zoom handler method name. */
        public String wheelZoomMethod() {
            return wheelZoomMethod;
        }

        /** Camera wheel-zoom handler method descriptor. */
        public String wheelZoomDescriptor() {
            return wheelZoomDescriptor;
        }

        /** Field storing {@code jp.noids.graphics.f.w} on the camera panel. */
        public String cameraCanvasField() {
            return cameraCanvasField;
        }

        /** Field storing {@code javax.swing.JScrollPane} on the camera panel. */
        public String cameraScrollField() {
            return cameraScrollField;
        }

        /** Cursor-warp helper method name. */
        public String robotMoveMethod() {
            return robotMoveMethod;
        }

        /** Cursor-warp helper descriptor when called virtually on {@code com/live2d/ui/h}. */
        public String robotMoveDescriptor() {
            return robotMoveDescriptor;
        }

        /** Whether wheel zoom uses the Kotlin default wrapper taking a {@code Float} scale. */
        public boolean hiDpiRobotHelper() {
            return hiDpiRobotHelper;
        }

        /** Whether shape overlay fill/stroke colors are fixed constants in {@code draw_exe}. */
        public boolean staticOverlayColors() {
            return staticOverlayColors;
        }
    }

    /** Dependency method checked before instrumenting host classes. */
    public record MethodDep(String owner, String name, String descriptor) {
        public MethodDep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Dependency field checked before instrumenting host classes. */
    public record FieldDep(String owner, String name, String descriptor) {
        public FieldDep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    public AtlasInteractiveRenderTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(generation, "generation");
    }

    /**
     * Resolves the reviewed interactive atlas target for an official artifact digest.
     *
     * @param digest exact host artifact digest
     * @return target definition when {@code digest} names a reviewed Cubism build
     */
    public static Optional<AtlasInteractiveRenderTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest).flatMap(version -> {
            if (!SUPPORTED_VERSIONS.contains(version)) {
                return Optional.empty();
            }
            return Optional.of(new AtlasInteractiveRenderTarget(digest, version, generationOf(version)));
        });
    }

    /**
     * Returns every reviewed target in artifact order.
     *
     * @return list of targets across all reviewed Cubism releases
     */
    public static List<AtlasInteractiveRenderTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    /**
     * Returns the six ordered host class internal names rewritten by this hook.
     *
     * @return internal class names matching transformer targets
     */
    public List<String> targetOwners() {
        return List.of(
                EDIT_LAYER_OWNER,
                generation.canvasOwner(),
                generation.unitPanelOwner(),
                generation.cameraOwner(),
                generation.scrollDragOwner(),
                INSPECTOR_OWNER);
    }

    /**
     * Returns host methods that {@link AtlasInteractiveRenderHostAccess} must resolve before arming.
     *
     * @return required method declarations
     */
    public List<MethodDep> methodDependencies() {
        final java.util.ArrayList<MethodDep> deps = new java.util.ArrayList<>(List.of(
                new MethodDep(generation.cameraOwner(), SCROLL_PANE_GETTER_METHOD, SCROLL_PANE_GETTER_DESCRIPTOR),
                new MethodDep(
                        generation.cameraOwner(), VIEWPORT_CANVAS_GETTER_METHOD, VIEWPORT_CANVAS_GETTER_DESCRIPTOR),
                new MethodDep(VIEWPORT_CANVAS_OWNER, VIEWPORT_SET_ZOOM_METHOD, VIEWPORT_SET_ZOOM_DESCRIPTOR)));
        if (!generation.staticOverlayColors()) {
            deps.add(new MethodDep(DATA_MODEL_OWNER, "i", "()L" + COLOR_OWNER + ";"));
            deps.add(new MethodDep(DATA_MODEL_OWNER, "j", "()L" + COLOR_OWNER + ";"));
            deps.add(new MethodDep(DATA_MODEL_COMPANION_OWNER, "b", "()L" + COLOR_OWNER + ";"));
            deps.add(new MethodDep(DATA_MODEL_COMPANION_OWNER, "c", "()L" + COLOR_OWNER + ";"));
            deps.add(new MethodDep(COLOR_OWNER, "getJColor", "()Ljava/awt/Color;"));
        }
        return List.copyOf(deps);
    }

    /**
     * Returns host fields that {@link AtlasInteractiveRenderHostAccess} must resolve before arming.
     *
     * @return required field declarations
     */
    public List<FieldDep> fieldDependencies() {
        final java.util.ArrayList<FieldDep> deps = new java.util.ArrayList<>(List.of(
                new FieldDep(EDIT_LAYER_OWNER, DRAW_DATA_SHAPES_FIELD, ARRAY_LIST_DESCRIPTOR),
                new FieldDep(LAYER_UPDATE_EVENT_OWNER, EVENT_DRAGGING_FIELD, BOOLEAN_DESCRIPTOR),
                new FieldDep(generation.cameraOwner(), generation.cameraScrollField(), "Ljavax/swing/JScrollPane;"),
                new FieldDep(
                        generation.cameraOwner(), generation.cameraCanvasField(), "L" + VIEWPORT_CANVAS_OWNER + ";")));
        if (!generation.staticOverlayColors()) {
            deps.add(new FieldDep(EDIT_LAYER_OWNER, TEX_RELOC_MODEL_FIELD, TEX_RELOC_MODEL_DESCRIPTOR));
            deps.add(new FieldDep(DATA_MODEL_OWNER, "a", "L" + DATA_MODEL_COMPANION_OWNER + ";"));
        }
        return List.copyOf(deps);
    }

    private static Generation generationOf(final String version) {
        if (EARLY_FIVE_ZERO.contains(version)) {
            return Generation.LEGACY_EARLY;
        }
        if (LATE_FIVE_ZERO_TO_ONE.contains(version)) {
            return Generation.DATA_MODEL_COLOR;
        }
        if (MODERN_FIVE_TWO_TO_THREE_EARLY.contains(version)) {
            return Generation.TABLE_SYNC_CLASSIC_ROBOT;
        }
        return Generation.TABLE_SYNC_HIDPI_ROBOT;
    }
}

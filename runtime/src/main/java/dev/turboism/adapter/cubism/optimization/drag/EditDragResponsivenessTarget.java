package dev.turboism.adapter.cubism.optimization.drag;

import dev.turboism.adapter.cubism.optimization.TargetClassTable;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed host contract for canvas mouse-drag coalescing, drag-scoped inspector/deformer-tree
 * deferral, and same-cursor elision across reviewed Cubism builds.
 *
 * @param digest official host artifact digest
 * @param version reviewed Cubism release version
 * @param targetClasses reviewed host internal class names for this build
 */
public record EditDragResponsivenessTarget(HostArtifactDigest digest, String version, List<String> targetClasses) {

    /** Shared preference property gating edit-mode acceleration features. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.warpDeformEditAcceleration";

    /** Explicit validation override property isolated from the persisted launcher preference. */
    public static final String VALIDATION_PROPERTY = "turboism.validation.editDragResponsiveness";

    /** Reviewed target-class pin table resource name. */
    public static final String TARGET_TABLE_NAME = "edit-drag-responsiveness";

    /** Expected count of transformed host classes per verified install. */
    public static final int EXPECTED_MATCHES = 6;

    /** Expected count of restored host owners on close. */
    public static final int EXPECTED_OWNERS = 6;

    /** Swing mouse listener adapter dispatching drag sessions on Cubism widgets. */
    public static final String MOUSE_LISTENER_OWNER = "com/live2d/ui/m$b";

    /** Swing mouse event delegate holding the target widget reference. */
    public static final String MOUSE_DELEGATE_OWNER = "com/live2d/ui/m";

    /** Canvas OpenGL widget wrapper class distinguishing canvas drags from control drags. */
    public static final String CANVAS_WIDGET_OWNER = "com/live2d/graphics3d/view/glWrapper/GLJPanelWrapper";

    /** Base widget class forwarding cursor assignments to Swing. */
    public static final String WIDGET_OWNER = "com/live2d/ui/CWidget";

    /** Cubism cursor wrapper unwrapping the native AWT cursor. */
    public static final String CURSOR_OWNER = "com/live2d/type/CCursor";

    /** Inspector palette view controller class. */
    public static final String INSPECTOR_VIEW_CTRL_OWNER =
            "com/live2d/cubism/view/palette/inspector/CEPaletteCtrl_Inspector$ViewCtrlInspector";

    /** Internal inspector palette coordinator scheduling and applying inspector rebuilds. */
    public static final String INSPECTOR_INTERNAL_OWNER = "com/live2d/cubism/view/palette/inspector/d";

    /** Inspector deformer-selector dropdown factory rebuilding the deformer hierarchy tree. */
    public static final String DEFORMER_SELECTOR_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/ParameterControllable_wrapperForInspector$DeformerSelectorUiFactory";

    /** Deformer palette view controller class. */
    public static final String DEFORMER_VIEW_CTRL_OWNER =
            "com/live2d/cubism/view/palette/deformer/CEPaletteCtrl_Deformer$ViewCtrlDeformer";

    /** Mouse pressed listener method name. */
    public static final String MOUSE_PRESSED_METHOD = "mousePressed";

    /** Mouse dragged listener method name. */
    public static final String MOUSE_DRAGGED_METHOD = "mouseDragged";

    /** Mouse released listener method name. */
    public static final String MOUSE_RELEASED_METHOD = "mouseReleased";

    /** Swing mouse listener method descriptor. */
    public static final String MOUSE_EVENT_DESCRIPTOR = "(Ljava/awt/event/MouseEvent;)V";

    /** Field name storing the enclosing mouse delegate on {@link #MOUSE_LISTENER_OWNER}. */
    public static final String LISTENER_DELEGATE_FIELD = "a";

    /** Field descriptor of {@link #LISTENER_DELEGATE_FIELD}. */
    public static final String LISTENER_DELEGATE_DESCRIPTOR = "Lcom/live2d/ui/m;";

    /** Field name storing active drag session state on {@link #MOUSE_LISTENER_OWNER}. */
    public static final String LISTENER_SESSION_FIELD = "b";

    /** Field descriptor of {@link #LISTENER_SESSION_FIELD}. */
    public static final String LISTENER_SESSION_DESCRIPTOR = "Lcom/live2d/ui/event/l;";

    /** Method name on {@link #MOUSE_DELEGATE_OWNER} returning the owning UI node. */
    public static final String DELEGATE_WIDGET_METHOD = "a";

    /** Method descriptor of {@link #DELEGATE_WIDGET_METHOD}. */
    public static final String DELEGATE_WIDGET_DESCRIPTOR = "()Lcom/live2d/ui/a;";

    /** Method name on {@link #WIDGET_OWNER} assigning a Cubism cursor. */
    public static final String SET_CURSOR_METHOD = "setCursor";

    /** Method descriptor of {@link #SET_CURSOR_METHOD}. */
    public static final String SET_CURSOR_DESCRIPTOR = "(Lcom/live2d/type/CCursor;)V";

    /** Method name on {@link #WIDGET_OWNER} returning the underlying Swing component. */
    public static final String GET_COMPONENT_METHOD = "getJComponent";

    /** Method descriptor of {@link #GET_COMPONENT_METHOD}. */
    public static final String GET_COMPONENT_DESCRIPTOR = "()Ljavax/swing/JComponent;";

    /** Method name on {@link #CURSOR_OWNER} returning the underlying AWT cursor. */
    public static final String GET_CURSOR_METHOD = "getJCursor";

    /** Method descriptor of {@link #GET_CURSOR_METHOD}. */
    public static final String GET_CURSOR_DESCRIPTOR = "()Ljava/awt/Cursor;";

    /** Update method name on both palette view controllers. */
    public static final String UPDATE_METHOD = "update";

    /** Method descriptor of {@link #INSPECTOR_VIEW_CTRL_OWNER}'s update method. */
    public static final String UPDATE_DELAY_DESCRIPTOR = "(I)V";

    /** Internal inspector schedule/apply method name on {@link #INSPECTOR_INTERNAL_OWNER}. */
    public static final String INSPECTOR_INTERNAL_METHOD = "a";

    /** Method descriptor of {@link #INSPECTOR_INTERNAL_OWNER} immediate list application. */
    public static final String INSPECTOR_APPLY_DESCRIPTOR = "(Ljava/util/List;)V";

    /** Method name on {@link #DEFORMER_SELECTOR_OWNER} rebuilding the deformer selector items. */
    public static final String UPDATE_WIDGET_METHOD = "updateWidget";

    /** Void zero-argument descriptor. */
    public static final String VOID_DESCRIPTOR = "()V";

    /** Method descriptor of {@link #DEFORMER_VIEW_CTRL_OWNER}'s update method. */
    public static final String UPDATE_DEFORMER_DESCRIPTOR = "(ZZ)V";

    private static final Map<String, List<String>> TARGET_CLASSES = TargetClassTable.load(TARGET_TABLE_NAME);

    /**
     * Validates and freezes an edit drag responsiveness target contract.
     *
     * @param digest official host artifact digest
     * @param version reviewed Cubism release version
     * @param targetClasses reviewed host internal class names for this build
     */
    public EditDragResponsivenessTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
        targetClasses = List.copyOf(Objects.requireNonNull(targetClasses, "targetClasses"));
    }

    /**
     * Resolves the reviewed contract for the supplied host artifact digest.
     *
     * @param digest candidate host artifact digest
     * @return resolved contract, or empty when unreviewed or unsupported
     */
    public static Optional<EditDragResponsivenessTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest).flatMap(version -> {
            final List<String> classes = TARGET_CLASSES.get(version);
            if (classes == null || classes.size() != EXPECTED_OWNERS) {
                return Optional.empty();
            }
            return Optional.of(new EditDragResponsivenessTarget(digest, version, classes));
        });
    }
}

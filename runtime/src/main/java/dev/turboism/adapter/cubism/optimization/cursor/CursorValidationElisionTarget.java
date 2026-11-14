package dev.turboism.adapter.cubism.optimization.cursor;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines the reviewed JDK AWT cursor-validation and pointer-bounds elision targets.
 *
 * <p>{@code java.awt.Container.validate()} invokes {@code updateCursorImmediately()} after layout
 * validation even when neither pointer coordinates nor component cursors changed. Under Wine and
 * Win32 peers, that call synchronously queries OS cursor position and walks the heavyweight window
 * hierarchy on the EDT. In addition, {@code java.awt.Container.getMousePosition(boolean)} allocates
 * a privileged action, queries the native pointer peer, and acquires the AWT tree lock for every
 * queried widget even when the pointer lies outside the container bounds.</p>
 */
public record CursorValidationElisionTarget(HostArtifactDigest digest, String version) {

    /** Bootstrap-loaded AWT container owner. */
    public static final String CONTAINER_OWNER = "java/awt/Container";

    /** Layout validation method on {@code Container}. */
    public static final String VALIDATE_METHOD = "validate";

    /** Descriptor of {@code Container.validate()}. */
    public static final String VALIDATE_DESCRIPTOR = "()V";

    /** Cursor refresh method invoked at the end of {@code Container.validate()}. */
    public static final String UPDATE_CURSOR_METHOD = "updateCursorImmediately";

    /** Descriptor of {@code updateCursorImmediately()}. */
    public static final String UPDATE_CURSOR_DESCRIPTOR = "()V";

    /** Pointer hit-test method on {@code Container}. */
    public static final String GET_MOUSE_POSITION_METHOD = "getMousePosition";

    /** Descriptor of {@code Container.getMousePosition(boolean)}. */
    public static final String GET_MOUSE_POSITION_DESCRIPTOR = "(Z)Ljava/awt/Point;";

    /** Privileged action inner class allocated by {@code Container.getMousePosition(boolean)}. */
    public static final String CONTAINER_POINTER_ACTION_PREFIX = "java/awt/Container$";

    /** Bootstrap-loaded AWT component owner. */
    public static final String COMPONENT_OWNER = "java/awt/Component";

    /** Programmatic cursor assignment method on {@code Component}. */
    public static final String SET_CURSOR_METHOD = "setCursor";

    /** Descriptor of {@code Component.setCursor(Cursor)}. */
    public static final String SET_CURSOR_DESCRIPTOR = "(Ljava/awt/Cursor;)V";

    /** Visibility transition method on {@code Component}. */
    public static final String SHOW_METHOD = "show";

    /** Descriptor of {@code Component.show()}. */
    public static final String SHOW_DESCRIPTOR = "()V";

    /** Visibility hide method on {@code Component}. */
    public static final String HIDE_METHOD = "hide";

    /** Descriptor of {@code Component.hide()}. */
    public static final String HIDE_DESCRIPTOR = "()V";

    /** JRT module path for {@code java.awt.Container}. */
    public static final String CONTAINER_JRT_PATH = "/modules/java.desktop/java/awt/Container.class";

    /** JRT module path for {@code java.awt.Component}. */
    public static final String COMPONENT_JRT_PATH = "/modules/java.desktop/java/awt/Component.class";

    public CursorValidationElisionTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /** Resolves the reviewed target for an admitted host artifact digest. */
    public static Optional<CursorValidationElisionTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .map(version -> new CursorValidationElisionTarget(digest, version));
    }

    /** Returns every reviewed target in version order. */
    public static List<CursorValidationElisionTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }
}

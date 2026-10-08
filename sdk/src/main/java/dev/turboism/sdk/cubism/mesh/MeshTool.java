package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.Incubating;

/**
 * Plugin-defined native mesh-toolbar tool.
 *
 * <p>Icon paths are plugin-generation resource paths. Alternate visual states default to the
 * normal icon. Runtime lifecycle callbacks execute on the EDT without runtime locks held.</p>
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@Incubating
public interface MeshTool {
    /** Returns the plugin-generation-local tool identifier. */
    String id();

    /** Returns the user-visible toolbar label. */
    String label();

    /** Returns the normalized plugin resource path for the normal icon. */
    String iconResourcePath();

    /** Returns the plugin resource path for the pressed/active icon. */
    default String activeIconResourcePath() {
        return iconResourcePath();
    }

    /** Returns the plugin resource path for the rollover icon. */
    default String rollOverIconResourcePath() {
        return iconResourcePath();
    }

    /** Returns the plugin resource path for the selected icon. */
    default String selectedIconResourcePath() {
        return iconResourcePath();
    }

    /** Returns the plugin resource path for the disabled icon. */
    default String disabledIconResourcePath() {
        return iconResourcePath();
    }

    /** Returns the plugin resource path for the disabled-selected icon. */
    default String disabledSelectedIconResourcePath() {
        return iconResourcePath();
    }

    /** Returns the deterministic toolbar ordering key. */
    default int order() {
        return 0;
    }

    /**
     * Returns the selection mode the runtime commits for the next brush stroke, given the
     * modifier keys held at primary press.
     *
     * <p>The runtime resolves this once at primary press and keeps the result for the whole
     * stroke, so modifiers pressed or released mid-stroke never change an in-flight stroke.
     * The default is {@link SelectionMode#ADD}, which preserves the behaviour of tools written
     * before this contract existed. Precedence, when a tool declares several gestures, belongs
     * to the tool; the runtime applies no modifier policy of its own.</p>
     *
     * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
     *
     * @param shiftDown whether shift was held at primary press
     * @param controlDown whether control was held at primary press
     * @param altDown whether alt was held at primary press
     * @return the mode committed by the next non-empty stroke
     */
    default SelectionMode strokeSelectionMode(
            final boolean shiftDown, final boolean controlDown, final boolean altDown) {
        return SelectionMode.ADD;
    }

    /**
     * Activates this tool for one exact native mesh session.
     *
     * @param context activation-bound services that fail closed after invalidation
     */
    void activate(MeshToolContext context);

    /** Called after the runtime has closed all activation-owned handles. */
    default void deactivate() {}
}

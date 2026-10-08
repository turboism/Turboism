package dev.turboism.sdk.cubism.modeling;

import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.cubism.mesh.SelectionMode;

/** SDK-only ordinary modeling tool. Callbacks run on the EDT without runtime locks held. */
@Incubating
public interface ModelingTool {
    /** Plugin-local identity. */
    String id();
    /** User-visible tool label. */
    String label();
    /** Normal icon's normalized plugin resource path. */
    String iconResourcePath();
    /** Pressed icon; defaults to normal. */
    default String activeIconResourcePath() {
        return iconResourcePath();
    }
    /** Hover icon; defaults to normal. */
    default String rollOverIconResourcePath() {
        return iconResourcePath();
    }
    /** Selected icon; defaults to normal. */
    default String selectedIconResourcePath() {
        return iconResourcePath();
    }
    /** Disabled icon; defaults to normal. */
    default String disabledIconResourcePath() {
        return iconResourcePath();
    }
    /** Disabled selected icon; defaults to normal. */
    default String disabledSelectedIconResourcePath() {
        return iconResourcePath();
    }
    /** Deterministic ordering key. */
    default int order() {
        return 0;
    }
    /**
     * Resolves the next stroke mode once, at primary press. The default is additive; modifier
     * precedence belongs to the contributing tool. Null also retains the additive default.
     */
    default SelectionMode strokeSelectionMode(boolean shiftDown, boolean controlDown, boolean altDown) {
        return SelectionMode.ADD;
    }
    /** Activates with services that become invalid when this exact activation ends. */
    void activate(ModelingToolContext context);
    /** Called after all activation-owned handles have been closed. */
    default void deactivate() {}
}

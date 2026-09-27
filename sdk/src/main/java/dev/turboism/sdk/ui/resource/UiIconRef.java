package dev.turboism.sdk.ui.resource;

import java.util.Objects;

/**
 * Immutable declarative icon reference, never a native handle or resource address.
 *
 * <p>Construction performs no host access and does not imply availability. A reference can
 * safely outlive a host session: renderers resolve it against their current verified binding.
 * Theme, DPI and disabled state belong to Runtime presentation, not to history identity.</p>
 *
 * @param icon the closed native object-icon key
 */
public record UiIconRef(CubismIcon icon) {
    public UiIconRef {
        Objects.requireNonNull(icon, "icon");
    }
}

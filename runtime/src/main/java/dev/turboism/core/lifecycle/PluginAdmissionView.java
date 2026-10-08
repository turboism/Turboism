package dev.turboism.core.lifecycle;

import java.util.List;

/**
 * The admission-relevant projection of a loaded plugin: identity, lifecycle state,
 * granted permissions and declared capabilities. Composition layers project their
 * richer load summaries onto this contract so admission checks below them stay
 * decoupled from the preview runtime's types.
 */
public interface PluginAdmissionView {

    /** Stable plugin identifier. */
    String id();

    /** Current lifecycle state of the plugin. */
    PluginLifecycleState state();

    /** Permission ids currently granted to the plugin. */
    List<String> permissionIds();

    /** Capability keys the plugin declares. */
    List<String> capabilities();
}

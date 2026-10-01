package dev.turboism.ui.mesh;

import java.util.Set;

/** Exact-version selector admission contract for ui.mesh-toolbar.contribute. */
public final class MeshToolbarSelectorContract {
    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.main-toolbar";
    public static final String CAPABILITY_ID = "ui.mesh-toolbar.contribute";
    public static final Set<String> SUPPORTED_VERSIONS = Set.of("5.2.03", "5.3.02", "5.3.03");
    public static final Set<String> REQUIRED_ALIASES = Set.of(
            "cubism.ui-main-toolbar.mesh-tool-mode.instance",
            "cubism.ui-main-toolbar.mesh-tool-mode.tool-panel",
            "cubism.ui-main-toolbar.mesh-tool-panel.arrow-button",
            "cubism.ui-main-toolbar.mesh-tool-panel.panel",
            "cubism.ui-main-toolbar.abstract-button.class",
            "cubism.ui-main-toolbar.abstract-button.get-jabstract-button",
            "cubism.ui-main-toolbar.abstract-button.set-selected",
            "cubism.ui-main-toolbar.abstract-button.set-icon",
            "cubism.ui-main-toolbar.abstract-button.set-pressed-icon",
            "cubism.ui-main-toolbar.abstract-button.set-selected-icon",
            "cubism.ui-main-toolbar.abstract-button.set-disabled-icon",
            "cubism.ui-main-toolbar.abstract-button.set-disabled-selected-icon",
            "cubism.ui-main-toolbar.icon-button.create",
            "cubism.ui-main-toolbar.icon-button.set-rollover-icon",
            "cubism.ui-main-toolbar.icon.class",
            "cubism.ui-main-toolbar.icon.create",
            "cubism.ui-main-toolbar.widget.class",
            "cubism.ui-main-toolbar.widget.parent",
            "cubism.ui-main-toolbar.widget.name",
            "cubism.ui-main-toolbar.widget.set-name",
            "cubism.ui-main-toolbar.widget.set-tooltip",
            "cubism.ui-main-toolbar.widget.set-pref-width",
            "cubism.ui-main-toolbar.widget.set-pref-height",
            "cubism.ui-main-toolbar.widget.revalidate",
            "cubism.ui-main-toolbar.widget.repaint",
            "cubism.ui-main-toolbar.container.class",
            "cubism.ui-main-toolbar.container.children",
            "cubism.ui-main-toolbar.container.add",
            "cubism.ui-main-toolbar.container.remove",
            "cubism.ui-main-toolbar.slider.class",
            "cubism.ui-main-toolbar.slider.create",
            "cubism.ui-main-toolbar.slider.value",
            "cubism.ui-main-toolbar.slider.set-value",
            "cubism.ui-main-toolbar.slider.min",
            "cubism.ui-main-toolbar.slider.set-min",
            "cubism.ui-main-toolbar.slider.max",
            "cubism.ui-main-toolbar.slider.set-max",
            "cubism.ui-main-toolbar.slider.set-on-changed");

    private MeshToolbarSelectorContract() {}

    /** Returns true only for a supported exact version and a complete admitted record. */
    public static boolean isAdmitted(
            final String version, final Set<String> capabilities, final Set<String> availableAliases) {
        return version != null
                && capabilities != null
                && availableAliases != null
                && SUPPORTED_VERSIONS.contains(version)
                && capabilities.contains(CAPABILITY_ID)
                && availableAliases.containsAll(REQUIRED_ALIASES);
    }
}

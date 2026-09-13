package dev.turboism.shell;

import dev.turboism.core.descriptor.CorePluginDescriptor;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreAuthor;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreEnvironment;
import dev.turboism.core.descriptor.CorePluginDescriptor.CoreI18n;
import dev.turboism.core.descriptor.CorePluginDescriptor.CorePermissionRef;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginDescriptor.PermissionRef;

import java.util.List;
import java.util.Optional;

/**
 * The framework shell's own descriptor, synthesized in code rather than loaded from a plugin
 * manifest. It feeds the same {@code PluginContext} assembly path external plugins use, so the
 * shell keeps its identity ({@link CorePluginManagement#CORE_PLUGIN_ID}), its message catalog, its
 * data directories, and its permission declarations — while never being discovered, packaged, or
 * installed as a plugin.
 */
public final class ShellManifest {

    /** The shell's reserved identity; external packages declaring it are rejected on discovery. */
    public static final String ID = CorePluginManagement.CORE_PLUGIN_ID;

    private static final List<PermissionRef> PERMISSIONS = List.of(
        new CorePermissionRef(PermissionIds.TURBOISM_ACTION_REGISTER, "application",
            Optional.of("Registers the main toolbar home entry action.")),
        new CorePermissionRef(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE, "application",
            Optional.of("Adds the home entry button to the main toolbar.")),
        new CorePermissionRef(PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE, "application",
            Optional.of("Publishes and activates the Turboism embedded panel.")),
        new CorePermissionRef(PermissionIds.TURBOISM_UI_SETTINGS_CONTRIBUTE, "application",
            Optional.of("Contributes the shell-owned Cubism JVM selector to the shared "
                + "Performance settings tab.")),
        new CorePermissionRef(PermissionIds.TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE, "application",
            Optional.of("Contributes the built-in panel-tab float and dock menu operations.")),
        new CorePermissionRef(PermissionIds.TURBOISM_UI_MENU_CONTRIBUTE, "application",
            Optional.of("Adds Settings and Plugin Management entries to the Turboism "
                + "top-level menu.")),
        new CorePermissionRef(PermissionIds.TURBOISM_UI_DIALOG_CONTRIBUTE, "application",
            Optional.of("Confirms plugin uninstall requests.")),
        new CorePermissionRef(PermissionIds.TURBOISM_UI_CANVAS_HINT, "application",
            Optional.of("Reports an available update in the host's own drawing-area hint."))
    );

    private static final PluginDescriptor DESCRIPTOR = new CorePluginDescriptor(
        ID,
        "Turboism Core",
        "0.1.0",
        "Built-in menu, toolbar, settings, tab, and plugin management.",
        List.of(),
        "[0.1.0,0.2.0)",
        List.of(new CoreAuthor("Turboism Contributors", Optional.empty())),
        "Project License",
        Optional.of("https://turboism.dev"),
        List.of(),
        new CoreI18n("META-INF.turboism.i18n.messages", List.of("base", "en", "ja", "zh")),
        List.of(),
        PERMISSIONS,
        List.of(),
        new CoreEnvironment(false, "none"),
        Optional.of("system"),
        List.of(),
        List.of(),
        List.of()
    );

    private ShellManifest() {
    }

    /**
     * @return the shell's fixed descriptor; callers must not mutate the lists, which are already
     *     defensively copied by {@link CorePluginDescriptor}
     */
    public static PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }
}

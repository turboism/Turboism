package dev.turboism.sdk.permission;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The canonical string identifiers for every permission the runtime recognises.
 *
 * <p>Plugins declare these ids in their manifests and the runtime matches grants against
 * them by exact string equality, so referencing these constants rather than literals keeps
 * declaration and enforcement in step. The constants are a typed view of {@link Vocabulary},
 * the resource the buildSrc plugin permission audit reads too, and {@code PermissionIdsContractTest}
 * fails when the two disagree. Not instantiable.
 */
public final class PermissionIds {

    public static final String TURBOISM_ACTION_REGISTER = "turboism.action.register";
    public static final String TURBOISM_ACTION_INVOKE = "turboism.action.invoke";
    public static final String TURBOISM_UI_MENU_CONTRIBUTE = "turboism.ui.menu.contribute";
    public static final String TURBOISM_UI_TOOLBAR_CONTRIBUTE = "turboism.ui.toolbar.contribute";
    public static final String TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE = "turboism.ui.toolbar.main.contribute";
    public static final String TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE = "turboism.ui.toolbar.palette.contribute";
    public static final String TURBOISM_UI_TOOLBAR_MESH_CONTRIBUTE = "turboism.ui.toolbar.mesh.contribute";
    public static final String TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE = "turboism.ui.context-menu.contribute";
    public static final String TURBOISM_UI_CONTEXT_SOURCE_READ = "turboism.ui.context-source.read";
    public static final String TURBOISM_UI_OVERLAY_CONTRIBUTE = "turboism.ui.overlay.contribute";
    public static final String TURBOISM_UI_VIEWPORT_READ = "turboism.ui.viewport.read";
    public static final String TURBOISM_UI_DIALOG_CONTRIBUTE = "turboism.ui.dialog.contribute";
    public static final String TURBOISM_UI_DIALOG_AUTOMATE = "turboism.ui.dialog.automate";
    public static final String TURBOISM_UI_PANEL_CONTRIBUTE = "turboism.ui.panel.contribute";
    public static final String TURBOISM_UI_SETTINGS_CONTRIBUTE = "turboism.ui.settings.contribute";
    public static final String TURBOISM_UI_FILE_CHOOSER_REQUEST = "turboism.ui.file-chooser.request";
    public static final String TURBOISM_UI_STATUS_NOTIFY = "turboism.ui.status.notify";
    public static final String TURBOISM_UI_CANVAS_HINT = "turboism.ui.canvas.hint";
    public static final String TURBOISM_UI_APPEARANCE_MODIFY = "turboism.ui.appearance.modify";
    public static final String TURBOISM_UI_APPEARANCE_OBSERVE = "turboism.ui.appearance.observe";
    public static final String TURBOISM_CONFIG_PLUGIN_READ = "turboism.config.plugin.read";
    public static final String TURBOISM_CONFIG_PLUGIN_WRITE = "turboism.config.plugin.write";
    public static final String TURBOISM_CUBISM_PROJECT_READ = "turboism.cubism.project.read";
    public static final String TURBOISM_CUBISM_MODEL_READ = "turboism.cubism.model.read";
    public static final String TURBOISM_CUBISM_MODEL_WRITE = "turboism.cubism.model.write";
    public static final String TURBOISM_CUBISM_EDIT = "turboism.cubism.edit";
    public static final String TURBOISM_CUBISM_PARAMETER_READ = "turboism.cubism.parameter.read";
    public static final String TURBOISM_CUBISM_MESH_READ = "turboism.cubism.mesh.read";
    public static final String TURBOISM_CUBISM_MESH_AUTOMESH_CONTRIBUTE = "turboism.cubism.mesh.automesh.contribute";
    public static final String TURBOISM_CUBISM_MODEL_OBSERVE = "turboism.cubism.model.observe";
    public static final String TURBOISM_CUBISM_MODEL_INTERCEPT = "turboism.cubism.model.intercept";
    public static final String TURBOISM_CUBISM_BACKUP_OBSERVE = "turboism.cubism.backup.observe";
    public static final String TURBOISM_CUBISM_SELECTION_OBSERVE = "turboism.cubism.selection.observe";
    public static final String TURBOISM_UI_SCENE_TABLE_OBSERVE = "turboism.ui.scene-table.observe";
    public static final String TURBOISM_CUBISM_LOG_OBSERVE = "turboism.cubism.log.observe";
    public static final String TURBOISM_PERFORMANCE_SAMPLE_OBSERVE = "turboism.performance.sample.observe";
    public static final String TURBOISM_ACTION_INVOCATION_OBSERVE = "turboism.action.invocation.observe";
    public static final String TURBOISM_PLUGIN_LIFECYCLE_OBSERVE = "turboism.plugin.lifecycle.observe";
    public static final String TURBOISM_CUBISM_RECENT_FILE_READ = "turboism.cubism.recent-file.read";
    public static final String TURBOISM_UI_RECENT_PREVIEW_CONTRIBUTE = "turboism.ui.recent-preview.contribute";
    public static final String TURBOISM_EVENT_PUBLISH = "turboism.event.publish";
    public static final String TURBOISM_EVENT_SUBSCRIBE = "turboism.event.subscribe";
    public static final String TURBOISM_FILE_READ = "turboism.file.read";
    public static final String TURBOISM_FILE_WRITE = "turboism.file.write";
    public static final String TURBOISM_PERFORMANCE_STATS_READ = "turboism.performance.stats.read";
    public static final String TURBOISM_HOST_UNSAFE = "turboism.host.unsafe";
    public static final String TURBOISM_NETWORK = "turboism.network.fetch";
    public static final String TURBOISM_PROCESS = "turboism.process.run";
    public static final String TURBOISM_MCP_CONNECTION_READ = "turboism.mcp.connection.read";
    public static final String TURBOISM_MCP_CONNECTION_PUBLISH = "turboism.mcp.connection.publish";

    /**
     * Every permission id the runtime recognises, read from {@link Vocabulary}.
     *
     * <p>The resource rejects a duplicated entry at class initialization, so a repeated id
     * fails closed instead of drifting.
     */
    public static final Set<String> KNOWN_IDS = Vocabulary.idSet();

    private PermissionIds() {}

    /**
     * The canonical id vocabulary, read from the single resource every gate shares.
     *
     * <p>{@code permission-ids.txt} on the SDK classpath is the one enumeration of the ids the
     * framework recognises; the buildSrc plugin permission audit reads the same tracked file. The
     * file is the contract and the constants above are a typed view of it, so the SDK catalog and
     * the manifest-as-audit gate cannot drift apart.
     *
     * <p>Reading fails closed. A missing, empty or duplicated entry raises
     * {@link IllegalStateException} at first use instead of yielding a partial vocabulary that
     * would silently admit fewer ids than the framework ships.
     */
    static final class Vocabulary {

        /** Classpath location of the canonical vocabulary, relative to the SDK jar root. */
        static final String RESOURCE = "dev/turboism/sdk/permission/permission-ids.txt";

        private static final String COMMENT_PREFIX = "#";

        private static final List<String> IDS = load();

        private Vocabulary() {}

        /**
         * Returns every recognised permission id in declaration order.
         *
         * @return the immutable canonical id list
         */
        static List<String> ids() {
            return IDS;
        }

        /**
         * Returns every recognised permission id as a set.
         *
         * @return the immutable canonical id set
         */
        static Set<String> idSet() {
            return Set.copyOf(IDS);
        }

        /**
         * Reads the canonical vocabulary from the SDK classpath.
         *
         * @return the ids in file order
         * @throws IllegalStateException when the resource is absent, unreadable, empty or duplicated
         */
        private static List<String> load() {
            final ClassLoader loader = Objects.requireNonNull(Vocabulary.class.getClassLoader(), "SDK class loader");
            try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
                if (stream == null) {
                    throw new IllegalStateException("Permission vocabulary resource is missing: " + RESOURCE);
                }
                return parse(stream);
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot read permission vocabulary " + RESOURCE, failure);
            }
        }

        /**
         * Parses one id per line, ignoring blank lines and {@code #} comments.
         *
         * @param stream the vocabulary bytes
         * @return the ids in file order
         * @throws IOException when the stream cannot be read
         * @throws IllegalStateException when the vocabulary is empty or repeats an id
         */
        static List<String> parse(final InputStream stream) throws IOException {
            final List<String> ids = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String id = line.trim();
                    if (id.isEmpty() || id.startsWith(COMMENT_PREFIX)) {
                        continue;
                    }
                    if (ids.contains(id)) {
                        throw new IllegalStateException("Permission vocabulary repeats id: " + id);
                    }
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                throw new IllegalStateException("Permission vocabulary declares no id: " + RESOURCE);
            }
            return List.copyOf(ids);
        }
    }
}

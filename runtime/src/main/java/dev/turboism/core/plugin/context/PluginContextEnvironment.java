package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.script.ScriptService;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;

import java.util.Objects;

/**
 * Immutable environment bundle encapsulating host adapters, services factory, and
 * optional plugin-scoped services for {@link CorePluginContext} composition.
 */
public final class PluginContextEnvironment {

    private final RuntimeHostAdapterAccess hostAccess;
    private final RuntimeHostAdapters hostAdapters;
    private final CubismServicesFactory servicesFactory;
    private final PluginLocalization localization;
    private final PluginTaskScheduler taskScheduler;
    private final PluginStorage pluginStorage;
    private final UserFileAccessService userFileAccessService;
    private final AsyncHostReadService asyncHostReadService;
    private final RuntimeSettingsService runtimeSettings;
    private final FileChooserHistoryService fileChooserHistory;
    private final ExportSettingsContributionService exportSettings;
    private final ScriptService scriptService;
    private final McpConnectionService mcpConnectionService;

    PluginContextEnvironment(
        final RuntimeHostAdapterAccess hostAccess,
        final RuntimeHostAdapters hostAdapters,
        final CubismServicesFactory servicesFactory,
        final PluginLocalization localization,
        final PluginTaskScheduler taskScheduler,
        final PluginStorage pluginStorage,
        final UserFileAccessService userFileAccessService,
        final AsyncHostReadService asyncHostReadService,
        final RuntimeSettingsService runtimeSettings,
        final FileChooserHistoryService fileChooserHistory,
        final ExportSettingsContributionService exportSettings,
        final ScriptService scriptService,
        final McpConnectionService mcpConnectionService
    ) {
        this.hostAccess = hostAccess;
        this.hostAdapters = Objects.requireNonNull(hostAdapters, "hostAdapters");
        this.servicesFactory = Objects.requireNonNull(servicesFactory, "servicesFactory");
        this.localization = localization;
        this.taskScheduler = taskScheduler;
        this.pluginStorage = pluginStorage;
        this.userFileAccessService = userFileAccessService;
        this.asyncHostReadService = asyncHostReadService;
        this.runtimeSettings = runtimeSettings;
        this.fileChooserHistory = fileChooserHistory;
        this.exportSettings = exportSettings;
        this.scriptService = scriptService;
        this.mcpConnectionService = mcpConnectionService;
    }

    /**
     * Creates a new builder configured for the given host adapter access.
     *
     * @param hostAccess verified host session access
     * @return a new mutable environment builder
     */
    public static Builder builder(final RuntimeHostAdapterAccess hostAccess) {
        return new Builder(Objects.requireNonNull(hostAccess, "hostAccess"));
    }

    /**
     * Creates a new builder configured for the given host adapters without direct host session access.
     *
     * @param hostAdapters verified host adapters
     * @return a new mutable environment builder
     */
    public static Builder builder(final RuntimeHostAdapters hostAdapters) {
        return new Builder(Objects.requireNonNull(hostAdapters, "hostAdapters"));
    }

    /**
     * Creates a safe-mode environment builder defaulting to inert safe-mode host adapters.
     *
     * @return a safe-mode environment builder
     */
    public static Builder safeMode() {
        return new Builder(RuntimeHostAdapters.safeMode());
    }

    /**
     * Returns the host adapter access, or {@code null} if safe-mode or headless.
     *
     * @return host adapter access or {@code null}
     */
    public RuntimeHostAdapterAccess hostAccess() {
        return hostAccess;
    }

    /**
     * Returns the verified runtime host adapters.
     *
     * @return host adapters
     */
    public RuntimeHostAdapters hostAdapters() {
        return hostAdapters;
    }

    /**
     * Returns the Cubism services factory.
     *
     * @return Cubism services factory
     */
    public CubismServicesFactory servicesFactory() {
        return servicesFactory;
    }

    /**
     * Returns the plugin localization service, or {@code null} if omitted.
     *
     * @return localization service or {@code null}
     */
    public PluginLocalization localization() {
        return localization;
    }

    /**
     * Returns the plugin task scheduler, or {@code null} if omitted.
     *
     * @return task scheduler or {@code null}
     */
    public PluginTaskScheduler taskScheduler() {
        return taskScheduler;
    }

    /**
     * Returns the plugin storage service, or {@code null} if omitted.
     *
     * @return storage service or {@code null}
     */
    public PluginStorage pluginStorage() {
        return pluginStorage;
    }

    /**
     * Returns the mediated user file access service, or {@code null} if omitted.
     *
     * @return user file access service or {@code null}
     */
    public UserFileAccessService userFileAccessService() {
        return userFileAccessService;
    }

    /**
     * Returns the asynchronous host read service, or {@code null} if omitted.
     *
     * @return host read service or {@code null}
     */
    public AsyncHostReadService asyncHostReadService() {
        return asyncHostReadService;
    }

    /**
     * Returns the runtime settings service, or {@code null} if omitted.
     *
     * @return runtime settings service or {@code null}
     */
    public RuntimeSettingsService runtimeSettings() {
        return runtimeSettings;
    }

    /**
     * Returns the file chooser history service, or {@code null} if omitted.
     *
     * @return file chooser history service or {@code null}
     */
    public FileChooserHistoryService fileChooserHistory() {
        return fileChooserHistory;
    }

    /**
     * Returns the export settings contribution service, or {@code null} if omitted.
     *
     * @return export settings service or {@code null}
     */
    public ExportSettingsContributionService exportSettings() {
        return exportSettings;
    }

    /**
     * Returns the script service, or {@code null} if omitted.
     *
     * @return script service or {@code null}
     */
    public ScriptService scriptService() {
        return scriptService;
    }

    /**
     * Returns the MCP connection service, or {@code null} if omitted.
     *
     * @return MCP connection service or {@code null}
     */
    public McpConnectionService mcpConnectionService() {
        return mcpConnectionService;
    }

    static DefaultCubismServicesFactory defaultServicesFactory(
        final RuntimeHostAdapterAccess hostAccess,
        final UserFileAccessService userFiles
    ) {
        return new DefaultCubismServicesFactory(
            hostAccess.adapters(),
            hostAccess::cubismEditorVersion,
            hostAccess::admittedCubismCapabilities,
            hostAccess::admittedCubismGeneration,
            hostAccess.modelAccess(),
            hostAccess.coreRuntimeInfo(),
            hostAccess.parameterLifecycle(),
            hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(),
            hostAccess.physicsEditorCoordinator(),
            hostAccess.modelAppearanceSource(),
            hostAccess.paletteAppearanceCoordinator(),
            hostAccess.textureAtlasLayouts(),
            hostAccess.textureAtlasNativeInvocations(),
            hostAccess.textureAtlasEditorUi(),
            hostAccess.textureAtlasEditorSession(),
            hostAccess.textureAtlasAlgorithms(),
            hostAccess.editorCommands(),
            userFiles instanceof dev.turboism.adapter.cubism.command.EditorFileCommandResolver resolver
                ? resolver
                : dev.turboism.adapter.cubism.command.EditorFileCommandResolver.unavailable(),
            hostAccess.adapters().autoBackup(),
            hostAccess.history()
        );
    }

    /**
     * Fluent builder for {@link PluginContextEnvironment}.
     */
    public static final class Builder {

        private final RuntimeHostAdapterAccess hostAccess;
        private final RuntimeHostAdapters hostAdapters;
        private CubismServicesFactory servicesFactory;
        private PluginLocalization localization;
        private PluginTaskScheduler taskScheduler;
        private PluginStorage pluginStorage;
        private UserFileAccessService userFiles;
        private AsyncHostReadService hostReads;
        private RuntimeSettingsService runtimeSettings;
        private FileChooserHistoryService fileChooserHistory;
        private ExportSettingsContributionService exportSettings;
        private ScriptService scriptService;
        private McpConnectionService mcpConnectionService;

        Builder(final RuntimeHostAdapterAccess hostAccess) {
            this.hostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
            this.hostAdapters = hostAccess.adapters();
        }

        Builder(final RuntimeHostAdapters hostAdapters) {
            this.hostAccess = null;
            this.hostAdapters = Objects.requireNonNull(hostAdapters, "hostAdapters");
        }

        /**
         * Sets the Cubism services factory.
         *
         * @param servicesFactory custom services factory
         * @return this builder
         */
        public Builder servicesFactory(final CubismServicesFactory servicesFactory) {
            this.servicesFactory = servicesFactory;
            return this;
        }

        /**
         * Sets the plugin localization service.
         *
         * @param localization localization catalog
         * @return this builder
         */
        public Builder localization(final PluginLocalization localization) {
            this.localization = localization;
            return this;
        }

        /**
         * Sets the plugin task scheduler.
         *
         * @param taskScheduler task scheduler
         * @return this builder
         */
        public Builder taskScheduler(final PluginTaskScheduler taskScheduler) {
            this.taskScheduler = taskScheduler;
            return this;
        }

        /**
         * Sets the plugin storage service.
         *
         * @param pluginStorage storage service
         * @return this builder
         */
        public Builder pluginStorage(final PluginStorage pluginStorage) {
            this.pluginStorage = pluginStorage;
            return this;
        }

        /**
         * Sets the mediated user file access service.
         *
         * @param userFiles user file access service
         * @return this builder
         */
        public Builder userFiles(final UserFileAccessService userFiles) {
            this.userFiles = userFiles;
            return this;
        }

        /**
         * Sets the asynchronous host read service.
         *
         * @param hostReads host read service
         * @return this builder
         */
        public Builder hostReads(final AsyncHostReadService hostReads) {
            this.hostReads = hostReads;
            return this;
        }

        /**
         * Sets the runtime settings service.
         *
         * @param runtimeSettings runtime settings service
         * @return this builder
         */
        public Builder runtimeSettings(final RuntimeSettingsService runtimeSettings) {
            this.runtimeSettings = runtimeSettings;
            return this;
        }

        /**
         * Sets the file chooser history service.
         *
         * @param fileChooserHistory file chooser history service
         * @return this builder
         */
        public Builder fileChooserHistory(final FileChooserHistoryService fileChooserHistory) {
            this.fileChooserHistory = fileChooserHistory;
            return this;
        }

        /**
         * Sets the export settings contribution service.
         *
         * @param exportSettings export settings service
         * @return this builder
         */
        public Builder exportSettings(final ExportSettingsContributionService exportSettings) {
            this.exportSettings = exportSettings;
            return this;
        }

        /**
         * Sets the script execution service.
         *
         * @param scriptService script service
         * @return this builder
         */
        public Builder scriptService(final ScriptService scriptService) {
            this.scriptService = scriptService;
            return this;
        }

        /**
         * Sets the MCP connection service.
         *
         * @param mcpConnectionService MCP connection service
         * @return this builder
         */
        public Builder mcpConnectionService(final McpConnectionService mcpConnectionService) {
            this.mcpConnectionService = mcpConnectionService;
            return this;
        }

        /**
         * Builds the immutable {@link PluginContextEnvironment}.
         *
         * @return assembled environment
         */
        public PluginContextEnvironment build() {
            final CubismServicesFactory factory = servicesFactory != null
                ? servicesFactory
                : (hostAccess != null
                    ? defaultServicesFactory(hostAccess, userFiles)
                    : new DefaultCubismServicesFactory(hostAdapters));
            return new PluginContextEnvironment(
                hostAccess,
                hostAdapters,
                factory,
                localization,
                taskScheduler,
                pluginStorage,
                userFiles,
                hostReads,
                runtimeSettings,
                fileChooserHistory,
                exportSettings,
                scriptService,
                mcpConnectionService
            );
        }
    }
}

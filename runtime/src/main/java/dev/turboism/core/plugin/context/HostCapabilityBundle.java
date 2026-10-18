package dev.turboism.core.plugin.context;

import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.runtime.CubismLogService;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;

/**
 * Immutable capability bundle holding host, environment and auxiliary runtime services for a plugin context.
 */
record HostCapabilityBundle(
        PluginLocalization localization,
        PluginTaskScheduler taskScheduler,
        PluginStorage pluginStorage,
        UserFileAccessService userFileAccessService,
        AsyncHostReadService asyncHostReadService,
        CubismLogService cubismLogService,
        RuntimeSettingsService runtimeSettings,
        FileChooserHistoryService fileChooserHistory,
        ExportSettingsContributionService exportSettings)
        implements PluginCapabilityBundle {

    static HostCapabilityBundle create(
            final RuntimeHostAdapterAccess hostAccess,
            final PluginContextEnvironment env,
            final CubismEditorApiAvailabilityInterceptor editorApiAvailability) {
        final PluginLocalization localization = env.localization();
        final PluginTaskScheduler taskScheduler = env.taskScheduler();
        final PluginStorage pluginStorage = env.pluginStorage();
        final UserFileAccessService userFileAccessService = env.userFileAccessService();
        final AsyncHostReadService asyncHostReadService = env.asyncHostReadService();
        final CubismLogService cubismLogService =
                hostAccess == null ? CubismLogService.unavailable() : hostAccess.cubismLog();
        final RuntimeSettingsService runtimeSettings = env.runtimeSettings();
        final FileChooserHistoryService fileChooserHistory = env.fileChooserHistory() == null
                ? null
                : editorApiAvailability.wrapForTesting(env.fileChooserHistory(), FileChooserHistoryService.class);
        final ExportSettingsContributionService exportSettings = env.exportSettings() == null
                ? ExportSettingsContributionService.unavailable()
                : editorApiAvailability.wrapForTesting(env.exportSettings(), ExportSettingsContributionService.class);

        return new HostCapabilityBundle(
                localization,
                taskScheduler,
                pluginStorage,
                userFileAccessService,
                asyncHostReadService,
                cubismLogService,
                runtimeSettings,
                fileChooserHistory,
                exportSettings);
    }

    @Override
    public void registerServices(final RuntimePluginServiceDirectory directory) {
        directory.install(PluginService.LOCALIZATION, () -> localization, PluginLocalization::unavailable);
        directory.install(PluginService.TASKS, () -> taskScheduler, PluginTaskScheduler::unavailable);
        directory.install(PluginService.HOST_READS, () -> asyncHostReadService, AsyncHostReadService::unavailable);
        directory.install(PluginService.STORAGE, () -> pluginStorage, PluginStorage::unavailable);
        directory.install(PluginService.USER_FILES, () -> userFileAccessService, UserFileAccessService::unavailable);
        directory.install(PluginService.CUBISM_LOG, () -> cubismLogService, CubismLogService::unavailable);
        directory.install(PluginService.RUNTIME_SETTINGS, () -> runtimeSettings, RuntimeSettingsService::unavailable);
        directory.install(
                PluginService.FILE_CHOOSER_HISTORY, () -> fileChooserHistory, FileChooserHistoryService::unavailable);
        directory.install(
                PluginService.EXPORT_SETTINGS, () -> exportSettings, ExportSettingsContributionService::unavailable);
    }
}

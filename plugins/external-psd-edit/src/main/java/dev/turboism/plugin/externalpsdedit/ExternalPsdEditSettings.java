package dev.turboism.plugin.externalpsdedit;

import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;
import java.util.List;

/** Plugin-owned editor preference; document paths remain owned by the runtime PSD handle. */
final class ExternalPsdEditSettings {
    static final String CONFIG = "external-editor.properties";
    static final String APPLICATION = "applicationPath";
    private final PluginContext context;

    ExternalPsdEditSettings(final PluginContext context) {
        this.context = context;
    }

    void enable(final PluginLocalization localization) {
        context.disposableScope().register(context.config().readScope(CONFIG));
        context.disposableScope().register(context.config().writeScope(CONFIG));
        final UiHostCapabilityService ui = context.services().require(UiHostCapabilityService.class);
        final SettingsTab tab =
                new SettingsTab("external-psd-edit", localization.text("external-psd-edit.settings.title"));
        context.disposableScope()
                .register(ui.contributeSettings(new SettingsContribution(
                        "external-psd-edit.application",
                        tab,
                        new SettingsControl.FilePath(
                                "external-psd-edit.application",
                                localization.text("external-psd-edit.settings.application"),
                                36,
                                SettingsBinding.of(this::applicationPath, this::saveApplicationPath),
                                localization.text("external-psd-edit.settings.browse"),
                                () -> ui.requestFile(new FileChooserRequest(
                                        "external-psd-edit.application",
                                        localization.text("external-psd-edit.settings.choose"),
                                        List.of("exe")))))));
        context.disposableScope()
                .register(ui.contributeSettings(new SettingsContribution(
                        "external-psd-edit.application-note",
                        tab,
                        new SettingsControl.Note(
                                "external-psd-edit.application-note",
                                localization.text("external-psd-edit.settings.note")))));
    }

    String applicationPath() {
        return context.config().readString(CONFIG, APPLICATION).orElse("").strip();
    }

    private void saveApplicationPath(final String value) {
        try {
            context.config().writeString(CONFIG, APPLICATION, value.strip());
        } catch (PluginConfigException failure) {
            throw new IllegalStateException("Could not save the external PSD editor", failure);
        }
    }
}

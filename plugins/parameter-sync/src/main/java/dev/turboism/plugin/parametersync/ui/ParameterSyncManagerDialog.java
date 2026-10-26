package dev.turboism.plugin.parametersync.ui;

import dev.turboism.plugin.parametersync.ParameterSyncPlugin;
import dev.turboism.plugin.parametersync.domain.SyncRuleSet;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.i18n.PluginLocalization;

/**
 * Unified parameter synchronization dialog.
 */
public class ParameterSyncManagerDialog extends ParameterSyncDialog {

    public ParameterSyncManagerDialog(
            final CubismModel model,
            final SyncRuleSet ruleSet,
            final ParameterSyncPlugin plugin,
            final PluginLocalization i18n) {
        super(model, null, ruleSet, plugin, i18n);
    }
}

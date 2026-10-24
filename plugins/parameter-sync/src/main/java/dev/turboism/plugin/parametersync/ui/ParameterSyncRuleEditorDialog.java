package dev.turboism.plugin.parametersync.ui;

import dev.turboism.plugin.parametersync.ParameterSyncPlugin;
import dev.turboism.plugin.parametersync.domain.SyncRule;
import dev.turboism.plugin.parametersync.domain.SyncRuleSet;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.Window;

/**
 * Unified parameter synchronization dialog.
 */
public class ParameterSyncRuleEditorDialog extends ParameterSyncDialog {

    public ParameterSyncRuleEditorDialog(
            final Window owner,
            final CubismModel model,
            final String initialSource,
            final String initialTarget,
            final SyncRuleSet ruleSet,
            final ParameterSyncPlugin plugin,
            final PluginLocalization i18n) {
        super(model, initialSource, ruleSet, plugin, i18n);
    }

    public ParameterSyncRuleEditorDialog(
            final Window owner,
            final CubismModel model,
            final SyncRule existingRule,
            final SyncRuleSet ruleSet,
            final ParameterSyncPlugin plugin,
            final PluginLocalization i18n) {
        super(model, existingRule != null ? existingRule.sourceId() : null, ruleSet, plugin, i18n);
    }
}

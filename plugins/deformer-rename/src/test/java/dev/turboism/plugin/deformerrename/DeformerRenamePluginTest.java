package dev.turboism.plugin.deformerrename;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.test.action.RecordingActionRegistry;
import dev.turboism.test.menu.RecordingMenuRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DeformerRenamePluginTest {
    @Test
    void registersLocalizedMenuAndDisposesContributions() throws Exception {
        var actions = new RecordingActionRegistry();
        var menus = new RecordingMenuRegistry();
        var context = FakePluginContext.builder()
                .actions(actions)
                .menus(menus)
                .localization(new PluginLocalization() {
                    @Override
                    public Locale locale() {
                        return Locale.SIMPLIFIED_CHINESE;
                    }

                    @Override
                    public String text(String key) {
                        return "一键变形器重命名";
                    }

                    @Override
                    public String format(String key, Object... args) {
                        return text(key);
                    }

                    @Override
                    public boolean contains(String key) {
                        return true;
                    }
                })
                .build();
        var plugin = new DeformerRenamePlugin();
        plugin.init(context);
        plugin.enable();
        assertEquals(1, actions.actions().size());
        assertEquals("一键变形器重命名", actions.require(DeformerRenamePlugin.ACTION_ID).label());
        assertEquals(1, menus.contributions().size());
        assertEquals("Turboism/一键变形器重命名", menus.contributions().get(0).menuPath());
        assertEquals(
                DeformerRenamePlugin.ACTION_ID, menus.contributions().get(0).actionId());
        plugin.disable();
        context.disposableScope().close();
        assertTrue(actions.actions().isEmpty());
        assertTrue(menus.contributions().isEmpty());
    }
}

package dev.turboism.plugin.atlasfolders;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.test.menu.RecordingMenuRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class FolderAtlasPluginTest {
    @Test
    void registersItsOwnMenuWithoutAnyAlgorithmPluginAndCleansUpWithItsScope() throws Exception {
        var menus = new RecordingMenuRegistry();
        var context = FakePluginContext.builder()
                .menus(menus)
                .localization(FakePluginContext.passThroughLocalization())
                .build();
        var plugin = new FolderAtlasPlugin();
        plugin.init(context);
        plugin.enable();
        assertEquals(
                List.of("texture-atlas.layout-folders"),
                context.actionRegistry().ids());
        assertEquals(1, menus.contributions().size());
        context.disposableScope().close();
        assertTrue(context.actionRegistry().ids().isEmpty());
        assertTrue(menus.contributions().isEmpty());
    }
}

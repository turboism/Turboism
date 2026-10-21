package dev.turboism.plugin.atlasmaxrectsbssf;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FolderAtlasDialogTest {
    private final PluginLocalization texts = (PluginLocalization) Proxy.newProxyInstance(
            PluginLocalization.class.getClassLoader(),
            new Class<?>[] {PluginLocalization.class},
            (proxy, method, args) -> args[0]);

    @Test
    void discoversEveryRegisteredAlgorithmUsingItsPublishedNameAndPreferredOrder() {
        var options = FolderAtlasDialog.algorithmOptions(
                texts,
                List.of(
                        algorithm("third-party", "Custom Packer"), algorithm("dalsoo", "Dalsoo"),
                        algorithm("another", "Another Packer"), algorithm("maxrects", "MaxRects-BSSF")));
        assertEquals(
                List.of("maxrects", "dalsoo", "third-party", "another", "native"),
                options.stream().map(FolderAtlasDialog.Algorithm::id).toList());
        assertEquals("Custom Packer", options.get(2).label());
        assertTrue(options.subList(0, 4).stream().allMatch(FolderAtlasDialog.Algorithm::available));
        assertFalse(options.get(4).available());
    }

    @Test
    void refreshesAfterPluginRegistrationAndRemovalAndDisablesPassThroughAlgorithms() {
        var registered = new ArrayList<TextureAtlasLayoutAlgorithm>();
        registered.add(algorithm("maxrects", "MaxRects-BSSF"));
        assertEquals(2, FolderAtlasDialog.algorithmOptions(texts, registered).size());
        registered.add(algorithm("late-plugin", "Late Packer"));
        assertEquals(
                "late-plugin",
                FolderAtlasDialog.algorithmOptions(texts, registered).get(1).id());
        registered.remove(1);
        registered.add(new TextureAtlasLayoutAlgorithm("pass-through", "Host Delegate", false, null));
        var options = FolderAtlasDialog.algorithmOptions(texts, registered);
        assertFalse(options.stream().anyMatch(a -> a.id().equals("late-plugin")));
        assertFalse(options.get(1).available());
        assertTrue(options.get(1).label().contains("folders.currentPageOnly"));
    }

    private static TextureAtlasLayoutAlgorithm algorithm(String id, String name) {
        return new TextureAtlasLayoutAlgorithm(id, name, false, (items, constraints) -> null);
    }
}

package dev.turboism.test.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LifecycleProbePluginTest {

    @Test
    void recordsLifecycleEvents() {
        LifecycleProbePlugin plugin = new LifecycleProbePlugin();
        assertTrue(plugin.events().isEmpty());

        plugin.init(null);
        plugin.enable();
        plugin.disable();
        plugin.shutdown();

        assertEquals(List.of("init", "enable", "disable", "shutdown"), plugin.events());
    }
}

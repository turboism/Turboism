package dev.turboism.test.plugin;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.ArrayList;
import java.util.List;

public class LifecycleProbePlugin implements TurboismPlugin {

    private final List<String> events = new ArrayList<>();

    @Override
    public void init(PluginContext context) {
        events.add("init");
    }

    @Override
    public void enable() {
        events.add("enable");
    }

    @Override
    public void disable() {
        events.add("disable");
    }

    @Override
    public void shutdown() {
        events.add("shutdown");
    }

    public List<String> events() {
        return List.copyOf(events);
    }
}

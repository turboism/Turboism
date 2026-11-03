package dev.turboism.validation.hotreload;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/** Compiled separately for each generation by run.ps1. */
public final class FixturePlugin implements TurboismPlugin {
    public static PluginContext LAST_CONTEXT;
    private JFrame window;

    @Override
    public void init(PluginContext context) throws Exception {
        LAST_CONTEXT = context;
        if ("@VERSION@".equals("0.3.0")) throw new IllegalStateException("Intentional broken update");
        SwingUtilities.invokeAndWait(() -> {
            window = new JFrame("Hot reload fixture @VERSION@");
            window.add(new JLabel("Active plugin generation: @VERSION@"));
            window.setSize(400, 100);
            window.setLocation(80, 80);
            window.setVisible(true);
        });
        context.disposableScope().register(() -> SwingUtilities.invokeLater(window::dispose));
        System.setProperty("turboism.validation.hotReload.version", "@VERSION@");
    }
}

package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * SDK-only PSD clip-mask import plugin. All action and panel registrations
 * are enrolled in the plugin disposable scope so disable/shutdown leaves no
 * duplicate controls behind.
 */
public final class PsdClipMaskImportPlugin implements TurboismPlugin {

    private PluginContext context;
    private PsdClipMaskImportService importService;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.importService = new PsdClipMaskImportService(
            context.cubism().model(),
            context,
            context.uiHost()
        );
        context.logger().info("PSD Clip Mask Import initialized");
    }

    @Override
    public void enable() {
        requireContext();
        try {
            context.disposableScope().register(registerImportAction());
            context.disposableScope().register(importService.registerSection());
            enabled = true;
        } catch (RuntimeException failure) {
            closeScopeQuietly();
            throw failure;
        }
    }

    @Override
    public void disable() {
        enabled = false;
    }

    @Override
    public void shutdown() {
        enabled = false;
        importService = null;
        context = null;
    }

    boolean isEnabled() {
        return enabled;
    }

    private Registration registerImportAction() {
        return context.actions().register(
            PsdClipMaskImportService.ACTION_ID,
            new ActionRegistry.Action() {
                @Override public String id() {
                    return PsdClipMaskImportService.ACTION_ID;
                }

                @Override public String label() {
                    return context.localization().text("psd.clip-mask-import.button.import");
                }

                @Override public Consumer<ActionRegistry.ActionContext> handler() {
                    return ignored -> importService.importClipMasks();
                }
            }
        );
    }

    private void closeScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            context.logger().warn(
                "PSD Clip Mask Import enable rollback close failed: " + closeFailure.getMessage()
            );
        }
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("PSD Clip Mask Import must be initialized before enable.");
        }
    }
}

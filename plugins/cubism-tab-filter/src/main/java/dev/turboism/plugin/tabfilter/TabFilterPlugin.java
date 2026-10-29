package dev.turboism.plugin.tabfilter;

import dev.turboism.sdk.plugin.AbstractTurboismPlugin;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import java.util.List;

/**
 * Adds keyword filter boxes to the Parameter, Deformer, Scene and Log palette
 * tabs. The framework owns host attachment and row filtering; this plugin only
 * declares which palette tabs receive a filter box and their placeholders.
 */
public final class TabFilterPlugin extends AbstractTurboismPlugin {

    private PluginLogger logger;
    private boolean enabled;

    @Override
    public synchronized void init(final PluginContext context) {
        super.init(context);
        this.logger = context.logger();
        logger.info("TabFilterPlugin initialized");
    }

    @Override
    public synchronized void enable() {
        super.enable();
        if (enabled) {
            return;
        }
        final PaletteFilterRegistry registry =
                context().services().find(PaletteFilterRegistry.class).orElse(null);
        if (registry == null || !registry.isAvailable()) {
            logger.warn("tab-filter: palette filter registry is unavailable; filter boxes are not installed");
            return;
        }
        final List<PaletteFilterRegistry.PaletteFilterContribution> contributions = contributions();
        try {
            for (PaletteFilterRegistry.PaletteFilterContribution contribution : contributions) {
                disposalScope().register(registry.contribute(contribution));
            }
            enabled = true;
        } catch (RuntimeException | Error failure) {
            // @containment-exempt: DisposableScope.close cleans up and rethrows fatal failure with priority
            try (DisposableScope rollback = new DisposableScope()) {
                rollback.register(super::disable);
                rollback.register(() -> {
                    throw failure;
                });
            } catch (RuntimeException rollbackFailure) {
                throw rollbackFailure;
            } catch (Exception rollbackFailure) {
                throw new IllegalStateException("TabFilter startup rollback failed", rollbackFailure);
            }
        }
        logger.info(
                "TabFilterPlugin enabled: palette filter boxes enrolled for " + contributions.size() + " palette tabs");
    }

    @Override
    public synchronized void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        try {
            super.disable();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Tab filter disposal failed", e);
        }
        logger.info("TabFilterPlugin disabled");
    }

    @Override
    public synchronized void shutdown() {
        disable();
        try {
            super.shutdown();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Tab filter shutdown failed", e);
        }
        logger.info("TabFilterPlugin shutdown");
    }

    private static List<PaletteFilterRegistry.PaletteFilterContribution> contributions() {
        return List.of(
                contribution(
                        "tab-filter.parameter",
                        PaletteFilterRegistry.PALETTE_PARAMETER,
                        "tab-filter.placeholder.parameter",
                        10),
                contribution(
                        "tab-filter.deformer",
                        PaletteFilterRegistry.PALETTE_DEFORMER,
                        "tab-filter.placeholder.deformer",
                        10),
                contribution(
                        "tab-filter.scene", PaletteFilterRegistry.PALETTE_SCENE, "tab-filter.placeholder.scene", 10),
                contribution("tab-filter.log", PaletteFilterRegistry.PALETTE_LOG, "tab-filter.placeholder.log", 10));
    }

    private static PaletteFilterRegistry.PaletteFilterContribution contribution(
            final String contributionId, final String paletteId, final String placeholderKey, final int order) {
        return new PaletteFilterRegistry.PaletteFilterContribution(contributionId, paletteId, placeholderKey, order);
    }
}

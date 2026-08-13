package dev.turboism.plugin.tabfilter;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds keyword filter boxes to the Parameter, Deformer, Scene and Log palette
 * tabs. The framework owns host attachment and row filtering; this plugin only
 * declares which palette tabs receive a filter box and their placeholders.
 */
public final class TabFilterPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private List<Registration> registrations = List.of();

    @Override
    public void init(final PluginContext context) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        logger.info("TabFilterPlugin initialized");
    }

    @Override
    public void enable() {
        if (context == null) {
            throw new IllegalStateException("TabFilterPlugin must be initialized before enable.");
        }
        final PaletteFilterRegistry registry;
        try {
            registry = context.paletteFilter();
        } catch (UnsupportedOperationException unavailable) {
            logger.warn("tab-filter: palette filter registry is unavailable; filter boxes are not installed");
            return;
        }
        final List<Registration> enrolled = new ArrayList<>(4);
        for (PaletteFilterRegistry.PaletteFilterContribution contribution : contributions()) {
            enrolled.add(registry.contribute(contribution));
        }
        registrations = List.copyOf(enrolled);
        logger.info("TabFilterPlugin enabled: palette filter boxes enrolled for "
            + registrations.size() + " palette tabs");
    }

    @Override
    public void disable() {
        for (Registration registration : registrations) {
            registration.close();
        }
        registrations = List.of();
        logger.info("TabFilterPlugin disabled");
    }

    @Override
    public void shutdown() {
        disable();
        logger.info("TabFilterPlugin shutdown");
    }

    private static List<PaletteFilterRegistry.PaletteFilterContribution> contributions() {
        return List.of(
            contribution(
                "tab-filter.parameter",
                PaletteFilterRegistry.PALETTE_PARAMETER,
                "tab-filter.placeholder.parameter",
                10
            ),
            contribution(
                "tab-filter.deformer",
                PaletteFilterRegistry.PALETTE_DEFORMER,
                "tab-filter.placeholder.deformer",
                10
            ),
            contribution(
                "tab-filter.scene",
                PaletteFilterRegistry.PALETTE_SCENE,
                "tab-filter.placeholder.scene",
                10
            ),
            contribution(
                "tab-filter.log",
                PaletteFilterRegistry.PALETTE_LOG,
                "tab-filter.placeholder.log",
                10
            )
        );
    }

    private static PaletteFilterRegistry.PaletteFilterContribution contribution(
        final String contributionId,
        final String paletteId,
        final String placeholderKey,
        final int order
    ) {
        return new PaletteFilterRegistry.PaletteFilterContribution(
            contributionId,
            paletteId,
            placeholderKey,
            order
        );
    }
}

package dev.turboism.config;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.settings.SettingsContribution;

/** Registers typed-config editor contributions into a host settings store. */
@FunctionalInterface
public interface SettingsContributionRegistrar {

    /** Registers one settings contribution for the given plugin and returns its revocation handle. */
    Registration register(String pluginId, SettingsContribution contribution);
}

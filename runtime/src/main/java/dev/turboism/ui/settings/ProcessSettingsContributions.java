package dev.turboism.ui.settings;

import java.util.Map;
import java.util.WeakHashMap;

/** Shares one aggregate between plugin contexts attached to the same host session. */
public final class ProcessSettingsContributions {
    private static final Map<Object, SettingsContributionStore> STORES = new WeakHashMap<>();

    private ProcessSettingsContributions() {
    }

    public static synchronized SettingsContributionStore forHost(final Object hostAccess) {
        if (hostAccess == null) return new SettingsContributionStore();
        return STORES.computeIfAbsent(hostAccess, ignored -> new SettingsContributionStore());
    }
}

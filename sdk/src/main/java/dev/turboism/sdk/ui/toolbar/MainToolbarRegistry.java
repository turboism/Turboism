package dev.turboism.sdk.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;

public interface MainToolbarRegistry {

    Registration contribute(MainToolbarContribution contribution);

    record MainToolbarContribution(
        String contributionId,
        String actionId,
        String labelKey,
        String iconResourcePath,
        String anchor,
        int order
    ) {}
}

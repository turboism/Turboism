package dev.turboism.core.lifecycle;

import java.util.Objects;
import java.util.Set;

/**
 * One admission row for a host hook or a single-owner runtime surface.
 *
 * <p>A plugin satisfies a row when it is {@link PluginLifecycleState#ENABLED}, declares at least
 * one of {@link #anyOfCapabilities()} and holds every id in {@link #allOfPermissions()}. The row
 * never names a plugin: a hook or surface is owned by whatever plugin declares the capability, so
 * first-party and third-party plugins are admitted by the same rule.
 *
 * @param hookId the stable runtime hook or surface id, unique across {@link CapabilityAdmission#rules()}
 * @param anyOfCapabilities the manifest capabilities of which one entitles the plugin
 * @param allOfPermissions the permission ids the plugin must hold as well
 */
public record CapabilityRule(String hookId, Set<String> anyOfCapabilities, Set<String> allOfPermissions) {

    /** Validates that a row names a hook, at least one capability and no null sets. */
    public CapabilityRule {
        if (hookId == null || hookId.isBlank()) {
            throw new IllegalArgumentException("hookId must not be blank");
        }
        anyOfCapabilities = Set.copyOf(Objects.requireNonNull(anyOfCapabilities, "anyOfCapabilities"));
        if (anyOfCapabilities.isEmpty()) {
            throw new IllegalArgumentException("anyOfCapabilities must not be empty for " + hookId);
        }
        allOfPermissions = Set.copyOf(Objects.requireNonNull(allOfPermissions, "allOfPermissions"));
    }
}

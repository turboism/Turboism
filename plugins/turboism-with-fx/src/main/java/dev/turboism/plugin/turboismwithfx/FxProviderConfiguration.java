package dev.turboism.plugin.turboismwithfx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Saved provider profiles plus process-local API keys for custom profiles. */
record FxProviderConfiguration(
    String activeProfileId,
    List<FxProviderProfile> customProfiles,
    Map<String, String> sessionApiKeys
) {
    FxProviderConfiguration {
        activeProfileId = Objects.requireNonNullElse(
            activeProfileId,
            FxProviderProfile.UNCONFIGURED_ID
        ).strip();
        if (activeProfileId.isEmpty()) activeProfileId = FxProviderProfile.UNCONFIGURED_ID;
        customProfiles = List.copyOf(Objects.requireNonNull(customProfiles, "customProfiles"));
        final LinkedHashMap<String, FxProviderProfile> unique = new LinkedHashMap<>();
        for (FxProviderProfile profile : customProfiles) {
            if (profile.kind() != FxProviderProfile.Kind.OPENAI_COMPATIBLE
                || profile.builtIn()
                || unique.putIfAbsent(profile.id(), profile) != null) {
                throw new IllegalArgumentException("custom provider profiles are invalid");
            }
        }
        final LinkedHashMap<String, String> keys = new LinkedHashMap<>();
        Objects.requireNonNull(sessionApiKeys, "sessionApiKeys").forEach((id, value) -> {
            if (unique.containsKey(id) && value != null && !value.isEmpty()) {
                keys.put(id, value);
            }
        });
        sessionApiKeys = Map.copyOf(keys);
        boolean activeExists = false;
        for (FxProviderProfile profile : profiles(customProfiles)) {
            if (profile.id().equals(activeProfileId)) {
                activeExists = true;
                break;
            }
        }
        if (!activeExists) activeProfileId = FxProviderProfile.UNCONFIGURED_ID;
    }

    FxProviderConfiguration() {
        this(FxProviderProfile.UNCONFIGURED_ID, List.of(), Map.of());
    }

    List<FxProviderProfile> profiles() {
        return profiles(customProfiles);
    }

    FxProviderProfile activeProfile() {
        return profiles().stream()
            .filter(profile -> profile.id().equals(activeProfileId))
            .findFirst()
            .orElseThrow();
    }

    FxCustomEndpointSettings customEndpoint() {
        final FxProviderProfile active = activeProfile();
        return active.customEndpoint(sessionApiKeys.getOrDefault(active.id(), ""));
    }

    FxProviderConfiguration withSessionApiKey(final String profileId, final String value) {
        final LinkedHashMap<String, String> keys = new LinkedHashMap<>(sessionApiKeys);
        if (value == null || value.isEmpty()) keys.remove(profileId);
        else keys.put(profileId, value);
        return new FxProviderConfiguration(activeProfileId, customProfiles, keys);
    }

    private static List<FxProviderProfile> profiles(final List<FxProviderProfile> custom) {
        final ArrayList<FxProviderProfile> result = new ArrayList<>(FxProviderProfile.builtIns());
        result.addAll(custom);
        return List.copyOf(result);
    }
}

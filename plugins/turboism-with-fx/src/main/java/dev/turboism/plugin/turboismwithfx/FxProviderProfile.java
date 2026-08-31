package dev.turboism.plugin.turboismwithfx;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** One selectable fx-native or Turboism-adapted provider profile. */
record FxProviderProfile(
    String id,
    String name,
    Kind kind,
    String nativeProvider,
    String endpoint,
    String apiKeyEnvironment,
    String defaultModel,
    List<String> manualModels
) {
    static final String VERCEL_ID = "fx-native-vercel";
    static final String CODEX_ID = "fx-native-codex";
    static final String GROK_ID = "fx-native-grok";

    FxProviderProfile {
        id = text(id, "provider profile id", 128);
        name = text(name, "provider profile name", 256);
        kind = Objects.requireNonNull(kind, "kind");
        nativeProvider = Objects.requireNonNullElse(nativeProvider, "").strip();
        endpoint = Objects.requireNonNullElse(endpoint, "").strip();
        apiKeyEnvironment = Objects.requireNonNullElse(apiKeyEnvironment, "").strip();
        defaultModel = Objects.requireNonNullElse(defaultModel, "").strip();
        manualModels = normalizedModels(manualModels);
        if (kind == Kind.FX_NATIVE) {
            if (nativeProvider.isEmpty() || !endpoint.isEmpty() || !apiKeyEnvironment.isEmpty()) {
                throw new IllegalArgumentException("fx-native provider profile is invalid");
            }
        } else {
            new FxCustomEndpointSettings(
                true,
                endpoint,
                defaultModel,
                apiKeyEnvironment,
                ""
            );
            if (!nativeProvider.isEmpty()) {
                throw new IllegalArgumentException("custom provider profile is invalid");
            }
        }
    }

    static List<FxProviderProfile> builtIns() {
        return List.of(
            new FxProviderProfile(
                VERCEL_ID,
                "Vercel AI Gateway",
                Kind.FX_NATIVE,
                "gateway",
                "",
                "",
                "",
                List.of()
            ),
            new FxProviderProfile(
                CODEX_ID,
                "Codex",
                Kind.FX_NATIVE,
                "codex",
                "",
                "",
                "",
                List.of()
            ),
            new FxProviderProfile(
                GROK_ID,
                "Grok",
                Kind.FX_NATIVE,
                "grok",
                "",
                "",
                "",
                List.of()
            )
        );
    }

    boolean builtIn() {
        return VERCEL_ID.equals(id) || CODEX_ID.equals(id) || GROK_ID.equals(id);
    }

    FxCustomEndpointSettings customEndpoint(final String sessionApiKey) {
        if (kind != Kind.OPENAI_COMPATIBLE) {
            return new FxCustomEndpointSettings(false, "", "", "", "");
        }
        return new FxCustomEndpointSettings(
            true,
            endpoint,
            defaultModel,
            apiKeyEnvironment,
            sessionApiKey
        );
    }

    List<String> models(final List<String> discovered) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(normalizedModels(discovered));
        result.addAll(manualModels);
        if (!defaultModel.isEmpty()) result.add(defaultModel);
        return List.copyOf(result);
    }

    enum Kind {
        FX_NATIVE,
        OPENAI_COMPATIBLE
    }

    private static List<String> normalizedModels(final List<String> values) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : Objects.requireNonNullElse(values, List.<String>of())) {
            final String model = Objects.requireNonNullElse(value, "").strip();
            if (!model.isEmpty()) result.add(text(model, "model id", 512));
        }
        return List.copyOf(result);
    }

    private static String text(final String value, final String name, final int maximum) {
        final String text = Objects.requireNonNull(value, name).strip();
        if (text.isEmpty() || text.length() > maximum
            || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }

    @Override public String toString() {
        return name;
    }
}

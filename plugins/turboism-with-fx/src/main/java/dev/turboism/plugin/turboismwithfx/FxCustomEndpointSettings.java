package dev.turboism.plugin.turboismwithfx;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/** Non-secret custom OpenAI-compatible endpoint metadata plus a session-only key value. */
record FxCustomEndpointSettings(
    boolean enabled,
    String endpoint,
    String model,
    String apiKeyEnvironment,
    String sessionApiKey
) {
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    FxCustomEndpointSettings {
        endpoint = Objects.requireNonNullElse(endpoint, "").strip();
        model = Objects.requireNonNullElse(model, "").strip();
        apiKeyEnvironment = Objects.requireNonNullElse(apiKeyEnvironment, "").strip();
        sessionApiKey = Objects.requireNonNullElse(sessionApiKey, "");
        if (endpoint.length() > 4096 || model.length() > 512 || sessionApiKey.length() > 4096) {
            throw new IllegalArgumentException("custom endpoint settings are too long");
        }
        if (!apiKeyEnvironment.isEmpty() && !ENVIRONMENT.matcher(apiKeyEnvironment).matches()) {
            throw new IllegalArgumentException("API key environment variable name is invalid");
        }
        if (enabled) {
            validateEndpoint(endpoint);
            if (model.isEmpty() || model.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("custom endpoint model is invalid");
            }
        }
    }

    FxCustomEndpointSettings persisted() {
        return new FxCustomEndpointSettings(enabled, endpoint, model, apiKeyEnvironment, "");
    }

    String resolveApiKey() {
        if (!sessionApiKey.isBlank()) return sessionApiKey;
        if (apiKeyEnvironment.isEmpty()) return "";
        return Objects.requireNonNullElse(System.getenv(apiKeyEnvironment), "").strip();
    }

    private static void validateEndpoint(final String value) {
        try {
            final URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("custom endpoint URL is invalid");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("custom endpoint URL is invalid", failure);
        }
    }
}

package dev.turboism.mapping.verification;

import java.util.Objects;

public record StaticSelectorResult(
    StaticSelector selector,
    StaticVerificationStatus status,
    String message
) {
    public StaticSelectorResult {
        selector = Objects.requireNonNull(selector, "selector");
        status = Objects.requireNonNull(status, "status");
        message = requireText(message, "message");
    }

    public String alias() {
        return selector.alias();
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

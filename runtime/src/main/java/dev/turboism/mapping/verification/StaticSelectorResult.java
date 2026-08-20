package dev.turboism.mapping.verification;

import java.util.Objects;

/**
 * Verdict for one selector in a static verification pass.
 *
 * @param selector the selector that was checked
 * @param status outcome; anything other than
 *     {@link StaticVerificationStatus#VERIFIED_STATIC} means the host does
 *     not carry the member as reviewed
 * @param message human-readable explanation; never blank
 */
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

    /**
     * @return the selector's alias, the stable name adapters use to request
     *     this host member
     */
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

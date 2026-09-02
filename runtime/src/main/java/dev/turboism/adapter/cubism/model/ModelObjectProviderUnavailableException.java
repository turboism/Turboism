package dev.turboism.adapter.cubism.model;

import java.util.Objects;

/** Typed runtime signal that no verified structural-create provider is active. */
public final class ModelObjectProviderUnavailableException
    extends UnsupportedOperationException {

    public ModelObjectProviderUnavailableException(final String message) {
        super(Objects.requireNonNull(message, "message"));
    }
}

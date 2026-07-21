package dev.turboism.adapter.cubism.core;

/** Internal marker for malformed or inconsistent public Core structural data. */
final class CoreStructuralValidationException extends IllegalStateException {

    CoreStructuralValidationException(final String message) {
        super(message);
    }
}

package dev.turboism.mapping.verification.fixture;

/** Cross-package fixture for an exactly verified non-public host constructor. */
public final class PackagePrivateConstructorHost {

    private final String value;

    PackagePrivateConstructorHost(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

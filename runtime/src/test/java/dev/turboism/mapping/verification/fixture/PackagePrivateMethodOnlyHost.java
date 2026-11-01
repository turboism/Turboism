package dev.turboism.mapping.verification.fixture;

/** Cross-package fixture for a method that must never be opened by verified invocation. */
public final class PackagePrivateMethodOnlyHost {

    private PackagePrivateMethodOnlyHost() {}

    public static PackagePrivateMethodOnlyHost create() {
        return new PackagePrivateMethodOnlyHost();
    }

    String hidden() {
        return "hidden";
    }
}

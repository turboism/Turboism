package dev.turboism.mapping.verification.fixture;

public final class PackagePrivateMethodHost {

    private PackagePrivateMethodHost() {
    }

    public static Class<?> type() {
        return Host.class;
    }

    public static Object create() {
        return new Host();
    }

    static final class Host {
        public String value() {
            return "package-private-owner";
        }
    }
}

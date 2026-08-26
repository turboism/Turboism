package dev.turboism.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TestPackageAccess {
    private TestPackageAccess() {}

    static PackageAccess replaceAfterHash(Path target, byte[] replacement) {
        return replacing(target, replacement, true);
    }

    static PackageAccess replaceAfterInspection(Path target, byte[] replacement) {
        return replacing(target, replacement, false);
    }

    private static PackageAccess replacing(Path target, byte[] replacement, boolean afterHash) {
        return new PackageAccess() {
            @Override public void afterInitialHash(Path path) throws IOException {
                if (afterHash) Files.write(target, replacement);
            }

            @Override public void afterInspection(Path path) throws IOException {
                if (!afterHash) Files.write(target, replacement);
            }
        };
    }
}

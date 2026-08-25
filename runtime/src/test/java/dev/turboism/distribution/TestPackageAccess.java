package dev.turboism.distribution;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
                if (afterHash) replace(target, replacement);
            }

            @Override public void afterInspection(Path path) throws IOException {
                if (!afterHash) replace(target, replacement);
            }
        };
    }

    private static void replace(Path target, byte[] replacement) throws IOException {
        Path temporary = Files.write(
            target.resolveSibling(target.getFileName() + ".replacement"), replacement);
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

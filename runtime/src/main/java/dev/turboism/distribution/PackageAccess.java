package dev.turboism.distribution;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipFile;

interface PackageAccess {
    PackageAccess FILE_SYSTEM = new PackageAccess() {};

    default BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    default InputStream open(Path path) throws IOException {
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
    }

    default ZipFile openZip(Path path) throws IOException {
        return new ZipFile(path.toFile());
    }

    default void afterInitialHash(Path path) throws IOException {}

    default void afterInspection(Path path) throws IOException {}
}

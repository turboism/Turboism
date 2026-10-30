package dev.turboism.validation.hotreload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Matches production plugin archives: files only, with no directory metadata. */
public final class PackFixture {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(Path.of(args[1])));
                var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }
}

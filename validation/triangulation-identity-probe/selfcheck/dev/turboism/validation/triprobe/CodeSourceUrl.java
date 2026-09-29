package dev.turboism.validation.triprobe;

import java.nio.file.Path;

/** Prints {@code Path.toUri().toURL().toExternalForm()} — the same form a URLClassLoader
 * ProtectionDomain CodeSource reports for a directory URL. Used to derive the fixture's
 * expected codeSource verbatim instead of hand-building it. */
public final class CodeSourceUrl {
    private CodeSourceUrl() {}
    public static void main(String[] args) throws Exception {
        System.out.println(Path.of(args[0]).toUri().toURL().toExternalForm());
    }
}

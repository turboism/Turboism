package dev.turboism.validation.atlasimage.t039;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Minimal test-only loader that defines the owned target with an exact CodeSource. */
public final class T039OwnedTargetLoader extends ClassLoader {
    private final Path fixtureJar;

    public T039OwnedTargetLoader(final Path fixtureJar, final ClassLoader parent) {
        super(parent);
        this.fixtureJar = fixtureJar;
    }

    public Class<?> defineTarget() throws IOException {
        final byte[] bytes;
        try (JarFile jar = new JarFile(fixtureJar.toFile())) {
            final JarEntry entry = jar.getJarEntry(T039ShadowAgent.ownedInternalName() + ".class");
            if (entry == null) {
                throw new IOException("owned target entry missing");
            }
            try (InputStream input = jar.getInputStream(entry)) {
                bytes = input.readAllBytes();
            }
        }
        try {
            final URL source = fixtureJar.toUri().toURL();
            final CodeSource codeSource = new CodeSource(
                source, (java.security.cert.Certificate[]) null);
            final ProtectionDomain domain = new ProtectionDomain(codeSource, null, this, null);
            return defineClass(
                T039ShadowAgent.ownedInternalName().replace('/', '.'), bytes, 0, bytes.length, domain);
        } catch (final IOException exception) {
            throw exception;
        }
    }
}

package dev.turboism.validation.atlasimage.t039;

import dev.turboism.validation.atlasimage.t035.T039ShadowPatchBridge;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds the owned target JAR used only by the T039 premain JVM test. */
public final class T039FixtureArtifact {
    private T039FixtureArtifact() {
    }

    public static void main(final String[] args) throws Exception {
        check(args.length == 2, "usage: fixture-jar metadata-file");
        final Path fixtureJar = Path.of(args[0]).toAbsolutePath().normalize();
        final Path metadata = Path.of(args[1]).toAbsolutePath().normalize();
        final byte[] classBytes = T039ShadowPatchBridge.ownedFixtureBytes();
        final T039ShadowPatchBridge.ShapeInfo shape =
            T039ShadowPatchBridge.ownedShape(classBytes);
        Files.createDirectories(fixtureJar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(fixtureJar))) {
            final JarEntry entry = new JarEntry(
                T039ShadowAgent.ownedInternalName() + ".class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(classBytes);
            output.closeEntry();
        }
        final String jarSha = sha256File(fixtureJar);
        final String content = String.join(
            "\n",
            "owner=" + shape.className(),
            "entry=" + T039ShadowAgent.ownedInternalName() + ".class",
            "descriptor=" + T039ShadowAgent.ownedDescriptor(),
            "classSha256=" + shape.classSha256(),
            "shapeSha256=" + shape.shapeSha256(),
            "jarSha256=" + jarSha,
            "fixtureJar=" + fixtureJar
        ) + "\n";
        Files.writeString(metadata, content, StandardCharsets.UTF_8);
        System.out.println("t039FixtureArtifact=PASS jar=" + fixtureJar);
        System.out.println("t039FixtureClassSha256=" + shape.classSha256());
        System.out.println("t039FixtureShapeSha256=" + shape.shapeSha256());
        System.out.println("t039FixtureJarSha256=" + jarSha);
    }

    private static String sha256File(final Path path) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return hex(digest.digest());
        } catch (final NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 unavailable", exception);
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder result = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}

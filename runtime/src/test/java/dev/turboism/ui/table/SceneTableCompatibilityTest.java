package dev.turboism.ui.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.CubismEditorReleaseDetector;
import dev.turboism.mapping.verification.CubismHostIdentity;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class SceneTableCompatibilityTest {
    @TempDir
    Path temporary;

    @Test
    void malformedArtifactNeverStartsPaletteDiscovery() throws Exception {
        final Path invalid = Files.writeString(temporary.resolve("not-cubism.jar"), "invalid");
        final SceneTableHostOperations operations = new SceneTableHostOperations();
        try {
            assertEquals(
                    SceneTableHostOperations.State.UNSUPPORTED,
                    operations.connect(invalid, getClass().getClassLoader()));
        } finally {
            operations.disconnect();
        }
    }

    @Test
    void realMembersBindAfterRepackingAndDeclarationChangeButRejectOtherSources() throws Exception {
        final String sample = System.getProperty("turboism.test.cubismEditorJar");
        assumeTrue(sample != null, "explicit real Editor sample required");
        final Path original = Path.of(sample);
        final CubismHostIdentity identity =
                CubismEditorReleaseDetector.probe(original).identity().orElseThrow();
        final Path repacked = copy(original, identity, "repacked");
        assertNotEquals(
                identity.artifact(),
                CubismEditorReleaseDetector.probe(repacked)
                        .identity()
                        .orElseThrow()
                        .artifact());
        try (URLClassLoader loader = loader(repacked, original)) {
            assertEquals(
                    identity.version(),
                    SceneTableHostProfile.bindArtifact(repacked, loader)
                            .orElseThrow()
                            .cubismVersion());
        }
        final Path unknown = copy(original, identity, "unknown");
        try (URLClassLoader loader = loader(unknown, original)) {
            assertEquals(
                    "5.3.99",
                    SceneTableHostProfile.bindArtifact(unknown, loader)
                            .orElseThrow()
                            .cubismVersion());
        }
        try (URLClassLoader wrongSource = loader(original, original)) {
            assertThrows(
                    IllegalArgumentException.class, () -> SceneTableHostProfile.bindArtifact(repacked, wrongSource));
            final Path missing = copy(original, identity, "missing-owner");
            assertFalse(SceneTableHostProfile.bindArtifact(missing, wrongSource).isPresent());
        }
    }

    private static URLClassLoader loader(final Path artifact, final Path original) throws Exception {
        final var urls = new ArrayList<URL>();
        urls.add(artifact.toUri().toURL());
        try (var files = Files.list(original.getParent())) {
            for (Path path : files.filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.equals(original))
                    .sorted()
                    .toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private Path copy(final Path original, final CubismHostIdentity identity, final String mode) throws Exception {
        final Path result = temporary.resolve(mode + ".jar");
        try (JarFile jar = new JarFile(original.toFile());
                JarOutputStream out = new JarOutputStream(Files.newOutputStream(result))) {
            final var entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                final String name = entry.getName();
                final String upper = name.toUpperCase(Locale.ROOT);
                if (upper.startsWith("META-INF/")
                        && (upper.endsWith(".SF")
                                || upper.endsWith(".RSA")
                                || upper.endsWith(".DSA")
                                || upper.endsWith(".EC"))) continue;
                if (mode.equals("missing-owner") && name.equals("com/live2d/cubism/view/palette/scene/b.class"))
                    continue;
                out.putNextEntry(new JarEntry(name));
                if (!entry.isDirectory()) {
                    try (var input = jar.getInputStream(entry)) {
                        if (mode.equals("unknown") && name.equals(identity.declarationClass() + ".class")) {
                            out.write(redeclare(input.readAllBytes(), identity));
                        } else {
                            input.transferTo(out);
                        }
                    }
                }
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("META-INF/scene-compatibility-fixture"));
            out.write(1);
            out.closeEntry();
        }
        return result;
    }

    private static byte[] redeclare(final byte[] bytes, final CubismHostIdentity identity) {
        final ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            private Object replace(final Object value) {
                                if (identity.version().equals(value)) return "5.3.99";
                                if (Integer.valueOf(identity.build()).equals(value)) return Integer.valueOf(503990001);
                                return value;
                            }

                            @Override
                            public FieldVisitor visitField(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final Object value) {
                                return super.visitField(access, name, descriptor, signature, replace(value));
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                return new MethodVisitor(
                                        Opcodes.ASM9,
                                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        super.visitLdcInsn(replace(value));
                                    }
                                };
                            }
                        },
                        0);
        return writer.toByteArray();
    }
}

package dev.turboism.automesh.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Shared plumbing for the guard tests: instruction capture and reviewed artifact lookup. */
final class AutoMeshFunnelTestSupport {

    private static final String ROOT_PROPERTY = "turboism.test.cubismRefDir";
    private static final String ROOT_ENVIRONMENT_VARIABLE = "TURBOISM_CUBISM_REF";
    private static final String ROOT_ENVIRONMENT_VARIABLE_ROOT = "TURBOISM_CUBISM_REF_ROOT";
    private static final String JAR_NAME = "Live2D_Cubism.jar";

    private AutoMeshFunnelTestSupport() {}

    /**
     * Resolves the reviewed artifact archive.
     *
     * @return the archive directory, or {@code null} when no archive is configured on this machine
     */
    static Path refRoot() {
        for (final String candidate : new String[] {
            System.getProperty(ROOT_PROPERTY),
            System.getenv(ROOT_ENVIRONMENT_VARIABLE),
            System.getenv(ROOT_ENVIRONMENT_VARIABLE_ROOT)
        }) {
            if (candidate != null && !candidate.isBlank()) {
                final Path root = Path.of(candidate).toAbsolutePath().normalize();
                return Files.isDirectory(root) ? root : null;
            }
        }
        return null;
    }

    /** @return the reviewed Editor jar of {@code version}, or {@code null} when it is absent */
    static Path jar(final Path root, final String version) {
        final Path jar = root.resolve(version).resolve(JAR_NAME);
        return Files.isRegularFile(jar) ? jar : null;
    }

    /** @return the class bytes of {@code internalName} inside {@code jar} */
    static byte[] classBytes(final Path jar, final String internalName) throws IOException {
        try (var archive = new java.util.jar.JarFile(jar.toFile())) {
            final var entry = archive.getJarEntry(internalName + ".class");
            if (entry == null) {
                throw new IOException(internalName + " is absent from " + jar);
            }
            try (InputStream input = archive.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    /** @return the first {@code limit} instruction events of one method, rendered as text */
    static List<String> firstInstructions(final byte[] bytes, final String methodName, final int limit) {
        final List<List<String>> captured = new ArrayList<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!methodName.equals(name)) {
                                    return null;
                                }
                                final List<String> instructions = new ArrayList<>();
                                captured.add(instructions);
                                return new InstructionRecorder(instructions, limit);
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return captured.isEmpty() ? List.of() : captured.get(0);
    }

    /** Records the leading instructions of one method as readable text. */
    private static final class InstructionRecorder extends MethodVisitor {

        private final List<String> instructions;
        private final int limit;

        private InstructionRecorder(final List<String> instructions, final int limit) {
            super(Opcodes.ASM9);
            this.instructions = instructions;
            this.limit = limit;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            record(mnemonic(opcode) + " " + owner + "." + name + descriptor);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            record("LDC " + value);
        }

        private void record(final String text) {
            if (instructions.size() < limit) {
                instructions.add(text);
            }
        }

        private static String mnemonic(final int opcode) {
            return switch (opcode) {
                case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
                case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
                case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
                default -> "INVOKE";
            };
        }
    }
}

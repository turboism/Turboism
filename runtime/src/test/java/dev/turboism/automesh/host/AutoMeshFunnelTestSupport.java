package dev.turboism.automesh.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
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
        final List<String> captured = capture(bytes, methodName, limit, false);
        return captured.isEmpty() ? List.of() : captured;
    }

    /**
     * Returns every instruction event of one method, rendered as text.
     *
     * <p>The full body is what a structural assertion needs: the decorated constructor keeps its
     * native code and gains the attach call, so the caller must see both.</p>
     *
     * @param bytes the class bytes to read
     * @param methodName the method to capture
     * @return every instruction event, in source order
     */
    static List<String> instructions(final byte[] bytes, final String methodName) {
        return capture(bytes, methodName, Integer.MAX_VALUE, true);
    }

    private static List<String> capture(
            final byte[] bytes, final String methodName, final int limit, final boolean structural) {
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
                                return new InstructionRecorder(instructions, limit, structural);
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return captured.isEmpty() ? List.of() : List.copyOf(captured.get(0));
    }

    /**
     * Records instruction events of one method as readable text.
     *
     * <p>Two modes exist because two questions are asked. The leading-instruction mode reports only
     * the code a caller injected, which is what an "is the guard first" assertion needs. The
     * structural mode also reports labels, try ranges and jumps, which is what a "the native body
     * survived and the injected call precedes the return" assertion needs.</p>
     */
    private static final class InstructionRecorder extends MethodVisitor {

        private final List<String> instructions;
        private final int limit;
        private final boolean structural;

        private InstructionRecorder(final List<String> instructions, final int limit, final boolean structural) {
            super(Opcodes.ASM9);
            this.instructions = instructions;
            this.limit = limit;
            this.structural = structural;
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

        @Override
        public void visitInsn(final int opcode) {
            record(
                    switch (opcode) {
                        case Opcodes.RETURN -> "RETURN";
                        case Opcodes.POP -> "POP";
                        case Opcodes.DUP -> "DUP";
                        case Opcodes.AASTORE -> "AASTORE";
                        case Opcodes.ICONST_0 -> "ICONST_0";
                        case Opcodes.ICONST_1 -> "ICONST_1";
                        case Opcodes.ICONST_2 -> "ICONST_2";
                        case Opcodes.ICONST_3 -> "ICONST_3";
                        case Opcodes.ICONST_4 -> "ICONST_4";
                        case Opcodes.ICONST_5 -> "ICONST_5";
                        default -> "INSN " + opcode;
                    });
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (!structural) {
                return;
            }
            record((opcode == Opcodes.ANEWARRAY ? "ANEWARRAY " : "TYPED " + opcode + " ") + type);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (structural) {
                record("VAR " + opcode + " " + varIndex);
            }
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (structural) {
                record("JUMP " + opcode);
            }
        }

        @Override
        public void visitLabel(final Label label) {
            if (structural) {
                record("LABEL");
            }
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            if (structural) {
                record("TRY " + type);
            }
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

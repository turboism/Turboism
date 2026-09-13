package dev.turboism.validation.atlasimage.t035;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassReader;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.FieldVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Opcodes;

/** Small raw-member and semantic metadata checks for the finite adapter. */
final class T035Members {
    private T035Members() {
    }

    static Map<String, byte[]> methodChunks(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        final char[] buffer = new char[reader.getMaxStringLength()];
        int cursor = reader.header + 6;
        final int interfaces = reader.readUnsignedShort(cursor);
        cursor += 2 + 2 * interfaces;
        final int fields = reader.readUnsignedShort(cursor);
        cursor += 2;
        for (int index = 0; index < fields; index++) {
            cursor = skipMember(reader, cursor);
        }
        final int methods = reader.readUnsignedShort(cursor);
        cursor += 2;
        final Map<String, byte[]> result = new LinkedHashMap<>();
        for (int index = 0; index < methods; index++) {
            final String key = reader.readUTF8(cursor + 2, buffer) + reader.readUTF8(cursor + 4, buffer);
            final int next = skipMember(reader, cursor);
            result.put(key, Arrays.copyOfRange(classBytes, cursor, next));
            cursor = next;
        }
        return result;
    }

    static byte[] targetNonCode(final byte[] classBytes, final String targetKey) {
        final ClassReader reader = new ClassReader(classBytes);
        final char[] buffer = new char[reader.getMaxStringLength()];
        int cursor = reader.header + 6;
        final int interfaces = reader.readUnsignedShort(cursor);
        cursor += 2 + 2 * interfaces;
        final int fields = reader.readUnsignedShort(cursor);
        cursor += 2;
        for (int index = 0; index < fields; index++) {
            cursor = skipMember(reader, cursor);
        }
        final int methods = reader.readUnsignedShort(cursor);
        cursor += 2;
        for (int index = 0; index < methods; index++) {
            final String key = reader.readUTF8(cursor + 2, buffer) + reader.readUTF8(cursor + 4, buffer);
            final int next = skipMember(reader, cursor);
            if (key.equals(targetKey)) {
                return nonCodeBytes(reader, classBytes, cursor, buffer);
            }
            cursor = next;
        }
        throw new IllegalArgumentException("target method_info is missing: " + targetKey);
    }

    static ClassMetadata metadata(final byte[] classBytes) {
        final MetadataVisitor visitor = new MetadataVisitor();
        new ClassReader(classBytes).accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ClassMetadata(
            visitor.version,
            visitor.access,
            visitor.name,
            visitor.superName,
            visitor.interfaces,
            visitor.fields
        );
    }

    private static byte[] nonCodeBytes(
        final ClassReader reader,
        final byte[] classBytes,
        final int member,
        final char[] buffer
    ) {
        final int attributeCount = reader.readUnsignedShort(member + 6);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(classBytes, member, 6);
        int nonCodeCount = 0;
        int cursor = member + 8;
        final List<byte[]> attributes = new ArrayList<>();
        for (int index = 0; index < attributeCount; index++) {
            final String name = reader.readUTF8(cursor, buffer);
            final int length = reader.readInt(cursor + 2);
            final int next = cursor + 6 + length;
            if (!name.equals("Code")) {
                nonCodeCount++;
                attributes.add(Arrays.copyOfRange(classBytes, cursor, next));
            }
            cursor = next;
        }
        output.write((nonCodeCount >>> 8) & 0xff);
        output.write(nonCodeCount & 0xff);
        for (final byte[] attribute : attributes) {
            output.writeBytes(attribute);
        }
        return output.toByteArray();
    }

    private static int skipMember(final ClassReader reader, final int member) {
        final int attributes = reader.readUnsignedShort(member + 6);
        int cursor = member + 8;
        for (int index = 0; index < attributes; index++) {
            cursor += 6 + reader.readInt(cursor + 2);
        }
        return cursor;
    }

    record ClassMetadata(
        int version,
        int access,
        String name,
        String superName,
        List<String> interfaces,
        List<FieldMetadata> fields
    ) {
        ClassMetadata {
            interfaces = List.copyOf(interfaces);
            fields = List.copyOf(fields);
        }
    }

    record FieldMetadata(int access, String name, String descriptor, String signature, Object value) {
    }

    private static final class MetadataVisitor extends ClassVisitor {
        private int version;
        private int access;
        private String name;
        private String superName;
        private List<String> interfaces = List.of();
        private final List<FieldMetadata> fields = new ArrayList<>();

        private MetadataVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
            final int version,
            final int access,
            final String name,
            final String signature,
            final String superName,
            final String[] interfaces
        ) {
            this.version = version;
            this.access = access;
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces.clone());
        }

        @Override
        public FieldVisitor visitField(
            final int access,
            final String name,
            final String descriptor,
            final String signature,
            final Object value
        ) {
            fields.add(new FieldMetadata(access, name, descriptor, signature, value));
            return null;
        }
    }
}

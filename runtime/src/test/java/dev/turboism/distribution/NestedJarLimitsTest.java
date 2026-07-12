package dev.turboism.distribution;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NestedJarLimitsTest {
    private static final String PATH = "artifacts[0]";

    @Test void acceptsEntryAtExactLimit() throws Exception {
        inspect(jar(new int[]{4}), new NestedJarInspector.Limits(4, 4, 1, 1000));
    }

    @Test void rejectsEntryAtLimitPlusOne() throws Exception {
        assertCode("NESTED_ENTRY_TOO_LARGE", jar(new int[]{5}),
            new NestedJarInspector.Limits(4, 10, 1, 1000));
    }

    @Test void acceptsTotalAtExactLimit() throws Exception {
        inspect(jar(new int[]{3, 4}), new NestedJarInspector.Limits(4, 7, 2, 1000));
    }

    @Test void rejectsTotalAtLimitPlusOne() throws Exception {
        assertCode("NESTED_TOTAL_TOO_LARGE", jar(new int[]{4, 4}),
            new NestedJarInspector.Limits(4, 7, 2, 1000));
    }

    private static void assertCode(String code, byte[] jar, NestedJarInspector.Limits limits) {
        DistributionValidationException error = assertThrows(DistributionValidationException.class,
            () -> inspect(jar, limits));
        assertEquals(code, error.code());
        assertEquals(PATH, error.problemPath());
    }

    private static void inspect(byte[] jar, NestedJarInspector.Limits limits)
        throws DistributionValidationException {
        new NestedJarInspector(limits).inspect("runtime", jar, PATH);
    }

    private static byte[] jar(int[] sizes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            for (int index = 0; index < sizes.length; index++) {
                add(jar, "dev/turboism/generated/C" + index + ".class", sizes[index]);
            }
        }
        return output.toByteArray();
    }

    private static void add(JarOutputStream jar, String name, int size) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(new byte[size]);
        jar.closeEntry();
    }
}

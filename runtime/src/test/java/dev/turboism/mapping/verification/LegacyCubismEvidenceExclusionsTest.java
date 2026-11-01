package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Asserts the licensed-evidence exclusions structurally, without a Cubism JAR.
 *
 * <p>{@code legacyEvidenceTests} in {@code runtime/build.gradle.kts} removes 35 entries from every
 * offline suite, so a stale entry excludes nothing and the suite still passes with zero executed
 * tests. Both Gradle and this test read {@code legacy-cubism-evidence-exclusions.txt}, so the entry
 * list has one owner. Each entry must still name a compiled test class and, for a method entry,
 * a method that still carries a JUnit Jupiter test annotation; otherwise the exclusion is dead
 * and this test fails while the offline gate runs.</p>
 *
 * <p>The classes are read as class files rather than through reflection: the evidence suites link
 * against licensed host types that are absent from the offline test classpath, and loading them
 * would fail for reasons unrelated to the exclusion list.</p>
 */
final class LegacyCubismEvidenceExclusionsTest {

    private static final String FIXTURE = "dev/turboism/mapping/verification/legacy-cubism-evidence-exclusions.txt";

    /** Every JUnit Jupiter annotation that makes a method an executable test. */
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Lorg/junit/jupiter/api/Test;",
            "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/TestTemplate;",
            "Lorg/junit/jupiter/params/ParameterizedTest;");

    /** Floors taken from the audited list; a mass deletion must not pass as a smaller suite. */
    private static final int MIN_CLASS_EXCLUSIONS = 30;

    private static final int MIN_METHOD_EXCLUSIONS = 5;

    @Test
    void everyExclusionNamesALiveTest() throws IOException {
        final List<String> entries = entries();
        final Set<String> seen = new LinkedHashSet<>();
        final TreeMap<String, Set<String>> testsByClass = new TreeMap<>();
        final List<String> classEntries = new ArrayList<>();
        final List<String> methodEntries = new ArrayList<>();
        for (final String entry : entries) {
            assertTrue(seen.add(entry), () -> "duplicate exclusion entry: " + entry);
            assertTrue(
                    entry.startsWith("dev.turboism."),
                    () -> "exclusion entry is outside the runtime test sources: " + entry);
            final String lastSegment = entry.substring(entry.lastIndexOf('.') + 1);
            assertTrue(!lastSegment.isBlank(), () -> "exclusion entry has an empty last segment: " + entry);
            if (Character.isLowerCase(lastSegment.charAt(0))) {
                methodEntries.add(entry);
                final String className = entry.substring(0, entry.lastIndexOf('.'));
                final Set<String> tests =
                        testsByClass.computeIfAbsent(className, LegacyCubismEvidenceExclusionsTest::testMethods);
                assertTrue(
                        tests.contains(lastSegment),
                        () -> "excluded test method is missing or lost its test annotation: " + entry);
            } else {
                classEntries.add(entry);
                final Set<String> tests =
                        testsByClass.computeIfAbsent(entry, LegacyCubismEvidenceExclusionsTest::testMethods);
                assertFalse(tests.isEmpty(), () -> "excluded test class declares no executable test: " + entry);
            }
        }
        assertTrue(
                classEntries.size() >= MIN_CLASS_EXCLUSIONS,
                () -> "class exclusions dropped to " + classEntries.size() + ", expected at least "
                        + MIN_CLASS_EXCLUSIONS);
        assertTrue(
                methodEntries.size() >= MIN_METHOD_EXCLUSIONS,
                () -> "method exclusions dropped to " + methodEntries.size() + ", expected at least "
                        + MIN_METHOD_EXCLUSIONS);
    }

    private static List<String> entries() throws IOException {
        try (InputStream in =
                LegacyCubismEvidenceExclusionsTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(in, () -> "missing exclusion fixture resource: " + FIXTURE);
            final List<String> entries = new ArrayList<>();
            for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                final String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    entries.add(trimmed);
                }
            }
            return entries;
        }
    }

    private static Set<String> testMethods(final String className) {
        final String resource = className.replace('.', '/') + ".class";
        final Set<String> tests = new TreeSet<>();
        try (InputStream in =
                LegacyCubismEvidenceExclusionsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, () -> "excluded test class is not in the compiled test output: " + className);
            new ClassReader(in.readAllBytes())
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String descriptor,
                                        final String signature,
                                        final String[] exceptions) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                                final String annotationDescriptor, final boolean visible) {
                                            if (TEST_ANNOTATIONS.contains(annotationDescriptor)) {
                                                tests.add(name);
                                            }
                                            return null;
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (final IOException failure) {
            throw new AssertionError("cannot read the compiled test class " + className, failure);
        }
        return tests;
    }
}

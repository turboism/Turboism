package dev.turboism.gradle.internal.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The duplicate-import scan, including the boundary of the import block.
 *
 * <p>The interesting cases are all about where the block ends: a second `import` after a field
 * declaration is an error the compiler reports itself, and a name that merely looks like an import
 * inside a comment or a text block must not be counted.
 */
class DuplicateImportsTest {

    private static final String FIXTURE = "runtime/src/main/java/dev/turboism/Fixture.java";

    @Test
    void reportsNothingForAUniqueImportBlock() {
        assertEquals(
                List.of(),
                DuplicateImports.duplicates(
                        FIXTURE,
                        List.of(
                                "package dev.turboism;",
                                "",
                                "import java.util.List;",
                                "import java.util.Map;",
                                "",
                                "final class Fixture {}")));
    }

    @Test
    void reportsTheSecondAndLaterOccurrences() {
        assertEquals(
                List.of(
                        FIXTURE + ":3 repeats import java.util.List; (first at line 1)",
                        FIXTURE + ":4 repeats import java.util.List; (first at line 1)"),
                DuplicateImports.duplicates(
                        FIXTURE,
                        List.of(
                                "import java.util.List;",
                                "import java.util.Map;",
                                "import java.util.List;",
                                "import java.util.List;",
                                "class Fixture {}")));
    }

    @Test
    void keepsTheStaticPrefixInTheDeclaration() {
        // `import X;` and `import static X;` are different declarations, and both spellings are
        // reported with the prefix that distinguishes them.
        assertEquals(
                List.of(FIXTURE + ":2 repeats import static java.util.List.of; (first at line 1)"),
                DuplicateImports.duplicates(
                        FIXTURE,
                        List.of(
                                "import static java.util.List.of;",
                                "import static java.util.List.of;",
                                "import java.util.List.of;",
                                "class Fixture {}")));
    }

    @Test
    void acceptsWildcardAndNestedTypeImports() {
        assertEquals(
                List.of(FIXTURE + ":2 repeats import java.util.*; (first at line 1)"),
                DuplicateImports.duplicates(
                        FIXTURE,
                        List.of("import java.util.*;", "import java.util.*;", "class Fixture {}")));
    }

    @Test
    void ignoresCommentsAndAnnotationsBetweenImports() {
        assertEquals(
                List.of(FIXTURE + ":6 repeats import java.util.List; (first at line 1)"),
                DuplicateImports.duplicates(
                        FIXTURE,
                        List.of(
                                "import java.util.List;",
                                "/*",
                                " * A block comment.",
                                " */",
                                "@SuppressWarnings(\"unused\")",
                                "import java.util.List;",
                                "class Fixture {}")));
    }

    @Test
    void closesTheImportBlockAtTheFirstDeclaration() {
        // The repeated import below the class declaration is the compiler's business, not ours.
        assertEquals(
                List.of(),
                DuplicateImports.duplicates(
                        FIXTURE,
                        List.of(
                                "import java.util.List;",
                                "class Fixture {",
                                "    import java.util.List;",
                                "}")));
    }

    @Test
    void keepsTheImportBlockOpenAcrossALineCommentAndAPackageLine() {
        assertEquals(
                List.of(FIXTURE + ":3 repeats import java.util.List; (first at line 1)"),
                DuplicateImports.duplicates(
                        FIXTURE,
                        List.of(
                                "import java.util.List;",
                                "// a note that survives imports",
                                "import java.util.List;",
                                "class Fixture {}")));
    }

    @Test
    void acceptsALicenceHeaderAboveThePackage() {
        assertEquals(
                List.of(),
                DuplicateImports.duplicates(
                        FIXTURE,
                        List.of(
                                "/*",
                                " * Copyright the Turboism contributors.",
                                " */",
                                "package dev.turboism;",
                                "import java.util.List;",
                                "class Fixture {}")));
    }

    @Test
    void ignoresAnUnterminatedBlockCommentSwallowingTheImportBlock() {
        assertTrue(DuplicateImports.duplicates(
                        FIXTURE, List.of("/*", "import java.util.List;", "import java.util.List;"))
                .isEmpty());
    }
}

package dev.turboism.gradle.internal.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The source-level rules and the lexer that decides what counts as code.
 *
 * <p>The fixtures are the source-shaped half of {@code scripts/test/test_module_boundaries.py},
 * moved in-process: each one used to cost a full Gradle build, and none of them needed a project
 * model. Messages are asserted whole, because the gate's output is the review surface.
 *
 * <p>The comment and string cases are not decoration: they are the proof that the quirks in
 * {@link JavaSourceScan#stripCommentsAndStrings(String)} are intentional. A plugin that writes
 * {@code SwingUtilities.getWindowAncestor(} in a comment, or a forbidden package name in a string
 * literal, must keep passing.
 */
class JavaSourceScanTest {

    private static final String FIXTURE = "plugins/fixture/src/main/java/Fixture.java";

    private static final String INTERNAL_NOTE = "internal management contracts are not a plugin API";

    @Test
    void confinesHostLiteralsToAdaptersAndTheExactPolicyConstant() {
        String root = "runtime/src/main/java/dev/turboism/";
        String access = "class Fixture { String owner = \"com.live2d.fixture.Host\"; }";
        assertEquals(1, JavaSourceScan.hostPackageLiterals(
                new JavaSourceScan.SourceFile(root + "ui/Fixture.java", List.of(access))).size());
        assertTrue(JavaSourceScan.hostPackageLiterals(
                new JavaSourceScan.SourceFile(root + "adapter/Fixture.java", List.of(access))).isEmpty());
        assertTrue(JavaSourceScan.hostPackageLiterals(
                new JavaSourceScan.SourceFile(root + "mapping/Fixture.java", List.of(access))).isEmpty());
        assertTrue(JavaSourceScan.hostPackageLiterals(new JavaSourceScan.SourceFile(
                root + "automesh/host/Fixture.java", List.of(access))).isEmpty());
        assertTrue(JavaSourceScan.hostPackageLiterals(new JavaSourceScan.SourceFile(
                root + "ui/Fixture.java", List.of("// com.live2d.fixture.Host", "/* \"com/live2d/fixture/Host\" */"))).isEmpty());
        String policy = "public static final String CUBISM_RESOURCE_PREFIX = \"com/live2d/\";";
        assertTrue(JavaSourceScan.hostPackageLiterals(new JavaSourceScan.SourceFile(
                root + "internal/HostPackagePolicy.java", List.of(policy))).isEmpty());
        assertEquals(1, JavaSourceScan.hostPackageLiterals(new JavaSourceScan.SourceFile(
                root + "internal/HostPackagePolicy.java", List.of(access))).size());
        assertEquals(1, JavaSourceScan.hostPackageLiterals(new JavaSourceScan.SourceFile(
                root + "ui/Fixture.java", List.of(policy))).size());
    }

    @Test
    void reportsAForbiddenImport() {
        assertEquals(
                List.of("Forbidden import in " + FIXTURE + ":1: SDK/plugins must not import runtime parameter internals"),
                JavaSourceScan.restrictedImports(
                        source("import dev.turboism.core.parameter.ForbiddenType;", "class Fixture {}")));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "import dev.turboism.internal.core.ShellServices;         |SDK/public modules must not import runtime internal packages",
        "import com.live2d.foo.Bar;                               |SDK/plugins must not import Cubism internal packages (com.live2d)",
        "import static com.live2d.foo.Bar.baz;                    |SDK/plugins must not import Cubism internal packages (com.live2d)",
        "import dev.turboism.core.mesh.MeshOps;                   |SDK/plugins must not import runtime mesh internals",
        "import dev.turboism.core.psd.PsdDocument;                |SDK/plugins must not import runtime PSD internals",
        "import dev.turboism.core.mirror.MirrorOps;               |SDK/plugins must not import runtime mirror internals",
        "import dev.turboism.persistence.config.TypedConfig;      |SDK/plugins must not import the runtime persistence kernel (:persistence-core)",
        "import dev.turboism.compatibility.policy.Policy;         |SDK/plugins must not import the compatibility admission policy (:compatibility-policy)",
        "import dev.turboism.sdk.event.cubism.OldEvent;           |SDK/plugins must not import the retired dev.turboism.sdk.event.cubism package (Cubism events live in dev.turboism.sdk.cubism.event)",
        "import dev.turboism.distribution.preview.PreviewBundle;  |Forbidden distribution import in " + FIXTURE + ":1",
    })
    void reportsEachForbiddenImportFamily(String line, String expected) {
        List<String> violations = JavaSourceScan.restrictedImports(source(line, "class Fixture {}"));

        assertEquals(1, violations.size(), violations.toString());
        // The distribution import has its own message shape, so the expected text is either the
        // whole message or the message suffix the family appends after the line number.
        String violation = violations.get(0);
        assertTrue(violation.equals(expected) || violation.endsWith(": " + expected), violation);
    }

    @Test
    void reportsTheRetiredPackageDeclaration() {
        assertEquals(
                List.of(FIXTURE + ": Retired package dev.turboism.sdk.event.cubism must not be reintroduced "
                        + "(Cubism events live in dev.turboism.sdk.cubism.event)"),
                JavaSourceScan.forbiddenPackageDeclarations(
                        source("package dev.turboism.sdk.event.cubism;", "class Fixture {}")));
    }

    @Test
    void reportsAForbiddenQualifiedReference() {
        assertEquals(
                List.of("Forbidden fully-qualified reference 'dev.turboism.core.parameter.ForbiddenType.value' in "
                        + FIXTURE + ": SDK/plugins must not reference runtime core internals"),
                JavaSourceScan.qualifiedReferences(source(
                        "class Fixture { Object value() { return dev.turboism.core.parameter.ForbiddenType.value; } }")));
    }

    @Test
    void reportsOneReferencePerRule() {
        List<String> violations = JavaSourceScan.qualifiedReferences(source(
                "class Fixture {",
                "    Object a() { return com.live2d.foo.Bar.value; }",
                "    Object b() { return dev.turboism.persistence.config.TypedConfig.value; }",
                "    Object c() { return dev.turboism.compatibility.policy.Policy.value; }",
                "    Object d() { return dev.turboism.distribution.preview.Preview.value; }",
                "}"));

        assertEquals(
                List.of(
                        "Forbidden fully-qualified reference 'com.live2d.foo.Bar.value' in " + FIXTURE
                                + ": SDK/plugins must not reference Cubism internal packages (com.live2d)",
                        "Forbidden fully-qualified reference 'dev.turboism.persistence.config.TypedConfig.value' in "
                                + FIXTURE
                                + ": SDK/plugins must not reference the runtime persistence kernel (:persistence-core)",
                        "Forbidden fully-qualified reference 'dev.turboism.compatibility.policy.Policy.value' in " + FIXTURE
                                + ": SDK/plugins must not reference the compatibility admission policy "
                                + "(:compatibility-policy)",
                        "Forbidden fully-qualified reference 'dev.turboism.distribution.preview.Preview.value' in " + FIXTURE
                                + ": SDK/plugins must not reference distribution internals"),
                violations);
    }

    @Test
    void keepsStaticImportsInTheQualifiedReferenceScan() {
        // A non-static import is filtered out, but `import static` stays: it is the only way a
        // fully-qualified reference can reach the source without repeating the package name.
        assertEquals(List.of(), JavaSourceScan.qualifiedReferences(source("import dev.turboism.core.mesh.MeshOps;")));
        assertEquals(
                List.of("Forbidden fully-qualified reference 'dev.turboism.core.mesh.MeshOps.apply' in " + FIXTURE
                        + ": SDK/plugins must not reference runtime core internals"),
                JavaSourceScan.qualifiedReferences(source("import static dev.turboism.core.mesh.MeshOps.apply;")));
    }

    @Test
    void reportsAnInternalContractImportAndReference() {
        assertEquals(
                List.of("Forbidden internal-contract import in " + FIXTURE + ":1: " + INTERNAL_NOTE),
                JavaSourceScan.internalContractReferences(source(
                        "import dev.turboism.internal.core.ShellServices;", "class Fixture {}")));
        assertEquals(
                List.of("Forbidden internal-contract reference 'dev.turboism.internal.core.ShellServices.instance' in "
                        + FIXTURE + ": " + INTERNAL_NOTE),
                JavaSourceScan.internalContractReferences(source(
                        "class Fixture { Object services = dev.turboism.internal.core.ShellServices.instance(); }")));
    }

    @Test
    void reportsHostUiTraversalThroughWhitespaceVariants() {
        assertEquals(
                List.of("Plugin-owned external Swing views must not discover or mutate host UI trees; "
                        + "forbidden token 'SwingUtilities.getWindowAncestor(' in " + FIXTURE),
                JavaSourceScan.hostUiTraversal(source(
                        "import javax.swing.SwingUtilities;",
                        "class Fixture { void inspect(javax.swing.JComponent c) "
                                + "{ java.awt.Window w = SwingUtilities . getWindowAncestor(c); } }")));
    }

    @Test
    void reportsEachHostUiTraversalToken() {
        assertEquals(
                List.of(
                        "Plugin-owned external Swing views must not discover or mutate host UI trees; "
                                + "forbidden token 'SwingUtilities.getRoot(' in " + FIXTURE,
                        "Plugin-owned external Swing views must not discover or mutate host UI trees; "
                                + "forbidden token '.getTopLevelAncestor()' in " + FIXTURE),
                JavaSourceScan.hostUiTraversal(source(
                        "class Fixture {",
                        "    Object root(javax.swing.JComponent c) { return SwingUtilities.getRoot(c); }",
                        "    java.awt.Container top(java.awt.Component c) { return c.getTopLevelAncestor(); }",
                        "}")));
    }

    @Test
    void ignoresHostUiTokensInCommentsAndStrings() {
        // The fixture that proves the lexer earns its keep.
        assertEquals(
                List.of(),
                JavaSourceScan.hostUiTraversal(source(
                        "class Fixture { // SwingUtilities.getWindowAncestor( stays a comment",
                        "    String note = \"SwingUtilities.getRoot(\";",
                        "}")));
    }

    @Test
    void ignoresForbiddenReferencesInCommentsAndStrings() {
        assertEquals(
                List.of(),
                JavaSourceScan.qualifiedReferences(source(
                        "class Fixture {",
                        "    // dev.turboism.core.mesh.MeshOps is not referenced here",
                        "    /* dev.turboism.persistence.config.TypedConfig neither */",
                        "    String note = \"dev.turboism.internal.core.ShellServices\";",
                        "    char quote = '\\'';",
                        "}")));
    }

    @Test
    void reportsEveryLineOfAFixtureFile() {
        assertEquals(
                List.of(
                        "Forbidden import in " + FIXTURE + ":1: SDK/public modules must not import runtime internal packages",
                        "Forbidden import in " + FIXTURE + ":3: SDK/plugins must not import Cubism internal packages "
                                + "(com.live2d)"),
                JavaSourceScan.restrictedImports(source(
                        "import dev.turboism.internal.core.ShellServices;",
                        "",
                        "import com.live2d.foo.Bar;",
                        "class Fixture {}")));
    }

    @Test
    void blanksCommentsAndLiteralsInPlace() {
        assertEquals("a  \nc", JavaSourceScan.stripCommentsAndStrings("a // b\nc"));
        assertEquals("x  z", JavaSourceScan.stripCommentsAndStrings("x/*y*/z"));
        assertEquals("x  z", JavaSourceScan.stripCommentsAndStrings("x\"y\"z"));
        assertEquals("x  y", JavaSourceScan.stripCommentsAndStrings("x'a'y"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // An escaped closing quote keeps the literal open.
                "x\"a\\\"b\"y",
                // A backslash before a newline does not end the literal.
                "x\"a\\\nb\"y",
            })
    void keepsTheLiteralOpenAcrossEscapes(String input) {
        assertEquals("x  y", JavaSourceScan.stripCommentsAndStrings(input));
    }

    @Test
    void endsAStringLiteralAtANewline() {
        // Kotlin and Java agree that an unterminated literal ends at the line end; the newline is
        // preserved so the next line's line numbers still match the file.
        assertEquals("a \nc ", JavaSourceScan.stripCommentsAndStrings("a\"b\nc\"d"));
    }

    @Test
    void swallowsTheRestOfAnUnterminatedBlockComment() {
        assertEquals("a \n", JavaSourceScan.stripCommentsAndStrings("a/*b\nc"));
    }

    @Test
    void readsATextBlockAsSuccessiveStringLiterals() {
        // Preserved quirk: the lexer has no text-block state, so each `"` opens or closes a literal.
        assertEquals("      ", JavaSourceScan.stripCommentsAndStrings("\"\"\"x\"\"\""));
    }

    private static JavaSourceScan.SourceFile source(String... lines) {
        return new JavaSourceScan.SourceFile(FIXTURE, List.of(lines));
    }
}

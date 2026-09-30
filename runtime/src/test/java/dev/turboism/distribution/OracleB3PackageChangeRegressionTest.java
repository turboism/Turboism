package dev.turboism.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OracleB3PackageChangeRegressionTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsReplacementAfterInitialHash() throws Exception {
        Path input = fixture("one");
        LocalFrameworkPackageInspector inspector =
                new LocalFrameworkPackageInspector(TestPackageAccess.replaceAfterHash(input, fixtureBytes("two")));
        assertChanged(inspector.inspect(input));
    }

    @Test
    void rejectsReplacementAfterInspection() throws Exception {
        Path input = fixture("one");
        LocalFrameworkPackageInspector inspector = new LocalFrameworkPackageInspector(
                TestPackageAccess.replaceAfterInspection(input, fixtureBytes("two")));
        assertChanged(inspector.inspect(input));
    }

    @Test
    void nullInputIsProgrammerError() {
        assertThrows(NullPointerException.class, () -> new LocalFrameworkPackageInspector().inspect(null));
    }

    private void assertChanged(FrameworkPackageInspector.Result result) {
        FrameworkPackageInspector.Rejected rejected =
                assertInstanceOf(FrameworkPackageInspector.Rejected.class, result);
        assertEquals(
                "PACKAGE_CHANGED_DURING_INSPECTION", rejected.problems().get(0).code());
    }

    private Path fixture(String value) throws Exception {
        Path path = tempDir.resolve(value + ".zip");
        Files.write(path, fixtureBytes(value));
        return path;
    }

    private byte[] fixtureBytes(String value) throws Exception {
        return PackageTestFixtures.framework(value);
    }
}

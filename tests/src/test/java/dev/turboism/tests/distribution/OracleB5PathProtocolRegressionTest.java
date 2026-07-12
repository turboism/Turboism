package dev.turboism.tests.distribution;

import org.junit.jupiter.api.Test;

class OracleB5PathProtocolRegressionTest extends DistributionRegressionSupport {
    @Test void rejectsArchiveColon() throws Exception { assertArchive("C:runtime.jar", "ARCHIVE_PATH_UNSAFE"); }
    @Test void rejectsArchiveControl() throws Exception { assertArchive("payload/run\u001ftime.jar", "ARCHIVE_PATH_UNSAFE"); }
    @Test void rejectsArchiveBackslash() throws Exception { assertArchive("payload\\runtime.jar", "ARCHIVE_PATH_UNSAFE"); }
    @Test void rejectsArchiveTrailingDotSegment() throws Exception { assertArchive("payload./runtime.jar", "ARCHIVE_PATH_UNSAFE"); }
    @Test void rejectsArchiveDeviceBasename() throws Exception { assertArchive("payload/Con.jar", "ARCHIVE_PATH_UNSAFE"); }
    @Test void rejectsInstallDrivePath() throws Exception { assertInstall("C:/runtime.jar"); }
    @Test void rejectsInstallUncPath() throws Exception { assertInstall("\\\\server\\runtime.jar"); }
    @Test void rejectsInstallControl() throws Exception { assertInstall("lib/run\u0001time.jar"); }
    @Test void rejectsInstallTrailingSpaceSegment() throws Exception { assertInstall("lib /runtime.jar"); }
    @Test void rejectsInstallDeviceBasename() throws Exception { assertInstall("lib/LPT9.bin"); }

    private void assertArchive(String archive, String code) throws Exception {
        byte[] zip = FrameworkPackageFixtures.frameworkZip(validRuntime(), validSdk(), archive, "lib/runtime.jar", "");
        java.nio.file.Path input = tempDir.resolve("archive-" + System.nanoTime() + ".zip");
        java.nio.file.Files.write(input, zip);
        assertRejected(input, code, archive);
    }

    private void assertInstall(String install) throws Exception {
        byte[] zip = FrameworkPackageFixtures.frameworkZip(validRuntime(), validSdk(), "payload/runtime.jar", install, "");
        java.nio.file.Path input = tempDir.resolve("install-" + System.nanoTime() + ".zip");
        java.nio.file.Files.write(input, zip);
        assertRejected(input, "INSTALL_PATH_UNSAFE", "artifacts[0].installPath");
    }
}

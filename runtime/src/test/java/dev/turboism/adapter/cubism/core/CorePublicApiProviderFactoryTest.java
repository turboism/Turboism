package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorePublicApiProviderFactoryTest {

    @BeforeEach
    void resetSyntheticCore() {
        SyntheticCore.version = new SyntheticVersion(11, 12, 13);
        TestCoreApiFixture.resetVersion();
    }

    @Test
    void safeModeAndFakeProviderExposeOnlyAdapterOwnedValues() {
        final CorePublicApiProvider safeMode = CorePublicApiProvider.safeMode();
        assertFalse(safeMode.available());
        assertEquals(
            CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
            failureCode(safeMode.runtimeVersion())
        );

        final CoreRuntimeVersion fakeVersion = new CoreRuntimeVersion(7, 8, 9);
        final CorePublicApiProvider fake = new TestFakeCorePublicApiProvider(fakeVersion);
        assertTrue(fake.available());
        assertEquals("cubism-core-fake", fake.providerId());
        assertEquals(fakeVersion, fake.runtimeVersion().value().orElseThrow());
        assertEquals(
            CoreRuntimeVersion.class,
            fake.runtimeVersion().value().orElseThrow().getClass()
        );
    }

    @Test
    void selectsBothExactArtifactProfilesWithoutGuessingTheRuntimeTuple() {
        final CoreVersionExpectation expectation = CoreVersionExpectation.exact(11, 12, 13);

        final CorePublicApiProvider provider52 = CorePublicApiProviderFactory.admit(
            resolver("5.2"),
            expectation
        ).value().orElseThrow();
        final CorePublicApiProvider provider53 = CorePublicApiProviderFactory.admit(
            resolver("5.3.02"),
            expectation
        ).value().orElseThrow();

        assertEquals("cubism-core-public-5.2", provider52.providerId());
        assertEquals("5.2", provider52.artifactProfile());
        assertEquals("cubism-core-public-5.3.02", provider53.providerId());
        assertEquals("5.3.02", provider53.artifactProfile());
        assertEquals(new CoreRuntimeVersion(11, 12, 13),
            provider53.runtimeVersion().value().orElseThrow());
    }


    @Test
    void mapsReviewedRecordVersionsToExactCoreRuntimeTuples() {
        assertEquals(
            new CoreRuntimeVersion(5, 0, 256),
            CoreVersionExpectation.reviewedProfile("5.2.0").exactVersion()
        );
        assertEquals(
            new CoreRuntimeVersion(6, 0, 257),
            CoreVersionExpectation.reviewedProfile("5.3.2").exactVersion()
        );
    }


    @Test
    void admitsReviewedRecordVersionsThroughCanonicalArtifactProfiles() {
        final CoreVersionExpectation expectation = CoreVersionExpectation.exact(11, 12, 13);
        final CorePublicApiProvider provider52 = CorePublicApiProviderFactory.admit(
            TestCoreApiFixture.resolverForReviewedVersion("5.2.0", "5.2"),
            expectation
        ).value().orElseThrow();
        final CorePublicApiProvider provider53 = CorePublicApiProviderFactory.admit(
            TestCoreApiFixture.resolverForReviewedVersion("5.3.2", "5.3.02"),
            expectation
        ).value().orElseThrow();

        assertEquals("5.2", provider52.artifactProfile());
        assertEquals("5.3.02", provider53.artifactProfile());
    }

    @Test
    void admittedProvidersExposeVerifiedMocInspection() {
        final CorePublicApiProvider provider = CorePublicApiProviderFactory.admit(
            resolver("5.3.02"),
            CoreVersionExpectation.exact(11, 12, 13)
        ).value().orElseThrow();

        assertTrue(provider.capabilities().mocInspection());
        assertEquals(6, provider.latestMocVersion().value().orElseThrow());
        assertEquals(5, provider.mocVersion(new byte[]{5, 1}).value().orElseThrow());
        assertTrue(provider.hasMocConsistency(new byte[]{5, 1}).value().orElseThrow());
        assertFalse(provider.hasMocConsistency(new byte[]{5, 0}).value().orElseThrow());
    }

    @Test
    void rejectsIncompleteOrUnsupportedEvidenceBeforeInvokingCore() {
        final CoreProviderResult<CorePublicApiProvider> missingAlias =
            CorePublicApiProviderFactory.admit(
                resolver("5.2", CorePublicApiSelectorContract.GET_PATCH),
                CoreVersionExpectation.exact(11, 12, 13)
            );
        final CoreProviderResult<CorePublicApiProvider> unsupportedProfile =
            CorePublicApiProviderFactory.admit(
                resolver("5.4"),
                CoreVersionExpectation.exact(11, 12, 13)
            );

        assertEquals(CoreProviderFailure.Code.EVIDENCE_REJECTED, failureCode(missingAlias));
        assertEquals(
            CoreProviderFailure.Code.EVIDENCE_REJECTED,
            failureCode(unsupportedProfile)
        );
    }

    @Test
    void runtimeVersionMismatchFailsClosed() {
        final CoreProviderResult<CorePublicApiProvider> result =
            CorePublicApiProviderFactory.admit(
                resolver("5.3.02"),
                CoreVersionExpectation.exact(11, 12, 14)
            );

        assertFalse(result.isSuccess());
        assertEquals(CoreProviderFailure.Code.VERSION_MISMATCH, failureCode(result));
    }

    @Test
    void nullAndInvalidVersionObjectsFailClosedWithoutRawValueLeakage() {
        final CoreProviderResult<CorePublicApiProvider> nullResult =
            CorePublicApiProviderFactory.admit(
                resolver(
                    "5.2",
                    NullCore.class,
                    SyntheticVersion.class,
                    descriptor(SyntheticVersion.class),
                    "()I",
                    null,
                    NullCore.class.getClassLoader()
                ),
                CoreVersionExpectation.exact(11, 12, 13)
            );
        final CoreProviderResult<CorePublicApiProvider> invalidScalarResult =
            CorePublicApiProviderFactory.admit(
                resolver(
                    "5.2",
                    WrongScalarCore.class,
                    WrongScalarVersion.class,
                    descriptor(WrongScalarVersion.class),
                    "()Ljava/lang/String;",
                    null,
                    WrongScalarCore.class.getClassLoader()
                ),
                CoreVersionExpectation.exact(11, 12, 13)
            );

        assertEquals(CoreProviderFailure.Code.INVALID_VERSION, failureCode(nullResult));
        assertEquals(
            CoreProviderFailure.Code.INVALID_VERSION,
            failureCode(invalidScalarResult)
        );
        assertTrue(nullResult.value().isEmpty());
        assertTrue(invalidScalarResult.value().isEmpty());
    }

    @Test
    void wrongDescriptorAndWrongClassloaderFailAsResolutionErrors() {
        final CoreProviderResult<CorePublicApiProvider> wrongDescriptor =
            CorePublicApiProviderFactory.admit(
                resolver(
                    "5.2",
                    SyntheticCore.class,
                    SyntheticVersion.class,
                    "()Ljava/lang/Object;",
                    "()I",
                    null,
                    SyntheticCore.class.getClassLoader()
                ),
                CoreVersionExpectation.exact(11, 12, 13)
            );
        final CoreProviderResult<CorePublicApiProvider> wrongClassloader =
            CorePublicApiProviderFactory.admit(
                resolver(
                    "5.2",
                    SyntheticCore.class,
                    SyntheticVersion.class,
                    descriptor(SyntheticVersion.class),
                    "()I",
                    null,
                    ClassLoader.getPlatformClassLoader()
                ),
                CoreVersionExpectation.exact(11, 12, 13)
            );

        assertEquals(CoreProviderFailure.Code.RESOLUTION_FAILED, failureCode(wrongDescriptor));
        assertEquals(CoreProviderFailure.Code.RESOLUTION_FAILED, failureCode(wrongClassloader));
    }

    @Test
    void throwingVersionProbeFailsAsInvocationError() {
        final CoreProviderResult<CorePublicApiProvider> result =
            CorePublicApiProviderFactory.admit(
                resolver(
                    "5.3.02",
                    ThrowingCore.class,
                    SyntheticVersion.class,
                    descriptor(SyntheticVersion.class),
                    "()I",
                    null,
                    ThrowingCore.class.getClassLoader()
                ),
                CoreVersionExpectation.exact(11, 12, 13)
            );

        assertEquals(CoreProviderFailure.Code.INVOCATION_FAILED, failureCode(result));
    }

    @Test
    void negativeVersionComponentsAreRejectedAtTheAdapterBoundary() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new CoreRuntimeVersion(-1, 0, 0)
        );
    }

    private static CoreProviderFailure.Code failureCode(
        final CoreProviderResult<?> result
    ) {
        return result.failure().orElseThrow().code();
    }

    private static VerifiedMemberResolver resolver(final String artifactProfile) {
        return TestCoreApiFixture.resolver(artifactProfile);
    }

    private static VerifiedMemberResolver resolver(
        final String artifactProfile,
        final String omittedAlias
    ) {
        return TestCoreApiFixture.resolver(artifactProfile, omittedAlias);
    }

    private static VerifiedMemberResolver resolver(
        final String artifactProfile,
        final Class<?> coreType,
        final Class<?> versionType,
        final String versionDescriptor,
        final String majorDescriptor,
        final String omittedAlias,
        final ClassLoader classLoader
    ) {
        return TestCoreApiFixture.resolver(
            artifactProfile,
            coreType,
            versionType,
            versionDescriptor,
            majorDescriptor,
            omittedAlias,
            classLoader
        );
    }

    private static String descriptor(final Class<?> returnType) {
        return TestCoreApiFixture.objectDescriptor(returnType);
    }

    public static final class SyntheticCore {
        private static SyntheticVersion version;

        public static SyntheticVersion getVersion() {
            return version;
        }

        public static int getLatestMocVersion() { return 6; }
        public static int getMocVersion(final byte[] bytes) { return bytes.length; }
        public static boolean hasMocConsistency(final byte[] bytes) { return bytes.length > 0; }
    }

    public record SyntheticVersion(int major, int minor, int patch) {
        public int getMajor() {
            return major;
        }

        public int getMinor() {
            return minor;
        }

        public int getPatch() {
            return patch;
        }
    }

    public static final class NullCore {
        public static SyntheticVersion getVersion() {
            return null;
        }

        public static int getLatestMocVersion() { return 6; }
        public static int getMocVersion(final byte[] bytes) { return bytes.length; }
        public static boolean hasMocConsistency(final byte[] bytes) { return bytes.length > 0; }
    }

    public static final class ThrowingCore {
        public static SyntheticVersion getVersion() {
            throw new IllegalStateException("synthetic failure");
        }

        public static int getLatestMocVersion() { return 6; }
        public static int getMocVersion(final byte[] bytes) { return bytes.length; }
        public static boolean hasMocConsistency(final byte[] bytes) { return bytes.length > 0; }
    }

    public static final class WrongScalarCore {
        public static WrongScalarVersion getVersion() {
            return new WrongScalarVersion();
        }

        public static int getLatestMocVersion() { return 6; }
        public static int getMocVersion(final byte[] bytes) { return bytes.length; }
        public static boolean hasMocConsistency(final byte[] bytes) { return bytes.length > 0; }
    }

    public static final class WrongScalarVersion {
        public String getMajor() {
            return "11";
        }

        public int getMinor() {
            return 12;
        }

        public int getPatch() {
            return 13;
        }
    }

    private record TestFakeCorePublicApiProvider(CoreRuntimeVersion version)
        implements CorePublicApiProvider {

        @Override
        public String providerId() {
            return "cubism-core-fake";
        }

        @Override
        public String artifactProfile() {
            return "synthetic";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CoreProviderResult<CoreRuntimeVersion> runtimeVersion() {
            return CoreProviderResult.success(version);
        }
    }
}

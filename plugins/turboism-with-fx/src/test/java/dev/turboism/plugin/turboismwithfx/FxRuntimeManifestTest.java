package dev.turboism.plugin.turboismwithfx;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class FxRuntimeManifestTest {

    @Test
    void pinsOnlyReviewedUpstreamReleasePlatforms() {
        assertEquals(
            Set.of("linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64"),
            FxRuntimeManifest.allEntries().keySet()
        );
        assertFalse(FxRuntimeManifest.allEntries().containsKey("windows-x86_64"));
        assertEquals("0.0.5", FxRuntimeManifest.VERSION);
        assertEquals(
            "df7e6245e1992758d4060c97477ceafa27770551",
            FxRuntimeManifest.SOURCE_COMMIT
        );
    }

    @Test
    void runtimeAndPackagedManifestsHaveTheSameReviewedIdentity() throws Exception {
        final Properties packaged = new Properties();
        try (InputStream input = java.util.Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream(
                "META-INF/turboism/fx-runtime/manifest.properties"
            ),
            "packaged managed fx manifest"
        )) {
            packaged.load(input);
        }
        assertEquals(FxRuntimeManifest.VERSION, packaged.getProperty("fxVersion"));
        assertEquals(FxRuntimeManifest.SOURCE_COMMIT, packaged.getProperty("sourceCommit"));
        assertEquals(
            FxRuntimeManifest.LICENSE.size(),
            Long.parseLong(packaged.getProperty("licenseSize"))
        );
        assertEquals(
            FxRuntimeManifest.LICENSE.sha256(), packaged.getProperty("licenseSha256")
        );
        assertEquals(
            FxRuntimeManifest.THIRD_PARTY_NOTICES.size(),
            Long.parseLong(packaged.getProperty("thirdPartyNoticesSize"))
        );
        assertEquals(
            FxRuntimeManifest.THIRD_PARTY_NOTICES.sha256(),
            packaged.getProperty("thirdPartyNoticesSha256")
        );
        assertEquals(
            FxRuntimeManifest.allEntries().keySet(),
            Set.of(packaged.getProperty("platforms").split(","))
        );
        for (FxRuntimeManifest.Entry entry : FxRuntimeManifest.allEntries().values()) {
            final String prefix = entry.platformId() + ".";
            assertEquals(entry.archiveName(), packaged.getProperty(prefix + "archive"));
            assertEquals(entry.archiveSha256(), packaged.getProperty(prefix + "archiveSha256"));
            assertEquals(entry.archiveSize(), Long.parseLong(packaged.getProperty(prefix + "archiveSize")));
            assertEquals(entry.releaseAssetPath(), packaged.getProperty(prefix + "releaseAssetPath"));
            assertEquals(entry.executableSha256(), packaged.getProperty(prefix + "executableSha256"));
            assertEquals(entry.executableSize(), Long.parseLong(packaged.getProperty(prefix + "executableSize")));
            assertEquals(entry.provenance(), packaged.getProperty(prefix + "provenance"));
        }
    }
}

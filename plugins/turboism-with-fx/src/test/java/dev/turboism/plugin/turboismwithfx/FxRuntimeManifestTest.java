package dev.turboism.plugin.turboismwithfx;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxRuntimeManifestTest {

    @Test
    void pinsDownloadableUnixArchivesAndTheWindowsProductPayload() {
        assertEquals(
            Set.of(
                "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64",
                "windows-x86_64"
            ),
            FxRuntimeManifest.allEntries().keySet()
        );
        final FxRuntimeManifest.Entry windows = FxRuntimeManifest.allEntries().get(
            "windows-x86_64"
        );
        assertEquals(FxRuntimeManifest.Delivery.PRODUCT_PAYLOAD, windows.delivery());
        assertTrue(windows.sourceUri().isEmpty());
        assertEquals(11_144_192L, windows.executableSize());
        assertEquals(
            "a36b0b209d933e4757d7e1a961d259d39a8d370b68cbde8e9cba227603ac63c2",
            windows.executableSha256()
        );
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
            assertEquals(entry.delivery().manifestValue(), packaged.getProperty(prefix + "delivery"));
            if (entry.delivery() == FxRuntimeManifest.Delivery.UPSTREAM_ARCHIVE) {
                assertEquals(entry.archiveName(), packaged.getProperty(prefix + "archive"));
                assertEquals(entry.archiveSha256(), packaged.getProperty(prefix + "archiveSha256"));
                assertEquals(
                    entry.archiveSize(),
                    Long.parseLong(packaged.getProperty(prefix + "archiveSize"))
                );
                assertEquals(
                    entry.releaseAssetPath(), packaged.getProperty(prefix + "releaseAssetPath")
                );
                assertTrue(entry.sourceUri().isPresent());
            } else {
                assertFalse(packaged.containsKey(prefix + "archive"));
                assertFalse(packaged.containsKey(prefix + "archiveSha256"));
                assertFalse(packaged.containsKey(prefix + "archiveSize"));
                assertFalse(packaged.containsKey(prefix + "releaseAssetPath"));
                assertTrue(entry.sourceUri().isEmpty());
            }
            assertEquals(entry.executableSha256(), packaged.getProperty(prefix + "executableSha256"));
            assertEquals(entry.executableSize(), Long.parseLong(packaged.getProperty(prefix + "executableSize")));
            assertEquals(entry.provenance(), packaged.getProperty(prefix + "provenance"));
        }
    }
}

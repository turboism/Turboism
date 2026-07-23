package dev.turboism.plugin.uitheme.b1.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyThemePaletteResolverTest {

    @Test
    void resolvesWhitelistedLegacyKeysAndIgnoresHostSpecificExtras() {
        ThemePackageData data = new ThemePackageData(
            new ThemePackageMetadata(
                "author.demo", "Demo", "", "", "", "", null,
                ThemeBase.DARK, ThemeIcons.LIGHT, false
            ),
            Map.of(
                "CubismCommon.blue", "#112233",
                "CubismCommon.background", "#223344",
                "CubismCommon.gl.viewArea.background", "#334455",
                "Some.Unsafe.Host.Key", "#FFFFFF"
            ),
            Map.of(),
            null,
            null
        );

        var request = LegacyThemePaletteResolver.resolve(data, 7);

        assertEquals("author.demo", request.appearanceId());
        assertEquals(7, request.expectedRevision());
        assertEquals("#112233", request.palette().accent());
        assertEquals("#223344", request.palette().background());
        assertEquals("#334455", request.palette().viewportBackground());
        assertEquals("#3C3C3C", request.palette().surface());
    }
}

package dev.turboism.plugin.uitheme.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BuiltinThemeResourcesTest {

    private static final Map<String, ExpectedResource> EXPECTED = expected();

    @Test
    void preservesFrozenProjectOwnedLegacyThemeBytes() throws Exception {
        for (Map.Entry<String, ExpectedResource> entry : EXPECTED.entrySet()) {
            final byte[] bytes = resource(entry.getKey());
            assertEquals(entry.getValue().size(), bytes.length, entry.getKey());
            assertEquals(entry.getValue().sha256(), sha256(bytes), entry.getKey());
        }
    }

    @Test
    void exposesTheLegacyBuiltinThemeInventory() {
        assertEquals(
            java.util.List.of(
                "turboism.mint",
                "turboism.paper-yellow",
                "turboism.slate",
                "turboism.nord",
                "turboism.cherry-blossom",
                "turboism.sky-blue",
                "__cubism_light__",
                "__cubism_dark__"
            ),
            BuiltinThemeCatalog.entries().stream().map(BuiltinThemeCatalog.Entry::id).toList()
        );
        assertEquals(6, BuiltinThemeCatalog.visibleEntries().size());
    }

    private static byte[] resource(final String path) throws IOException {
        try (InputStream input = BuiltinThemeResourcesTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return input.readAllBytes();
        }
    }

    private static String sha256(final byte[] bytes) throws NoSuchAlgorithmException {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, ExpectedResource> expected() {
        final LinkedHashMap<String, ExpectedResource> values = new LinkedHashMap<>();
        values.put("/themes/cherry-blossom/colors.properties", new ExpectedResource(2171, "717f09a75fe5cfc2012803c034a17cd04039e0af17814ee2404f322039f7659b"));
        values.put("/themes/cherry-blossom/theme.properties", new ExpectedResource(213, "aca0ac5c57c3c5ad87bea1065fe1951e28f37accb5da81ba3ebb98babd18364b"));
        values.put("/themes/cubism-dark/colors.properties", new ExpectedResource(613, "859040cfa1551e17e6603c67d9d076a30fc47e8ab93a3455e34ee6f48431e60e"));
        values.put("/themes/cubism-dark/theme.properties", new ExpectedResource(112, "65846e0c3a896408a81544a48a905e93fa32db6f94a14a579491bc185d4ada1b"));
        values.put("/themes/cubism-light/colors.properties", new ExpectedResource(737, "6f4e020597bf661684f6f19abef4b970a7c2d77b92f5ac3866f4ec7c48c39f55"));
        values.put("/themes/cubism-light/theme.properties", new ExpectedResource(115, "aa56923544143df401b9943dd106ef718a9fb44b742d2494574edfbb326ed215"));
        values.put("/themes/mint/colors.properties", new ExpectedResource(2284, "3d16c2b24f858026f1bc58321f65ad916f6398f22c67bf9f1b7988f026f2bf9c"));
        values.put("/themes/mint/theme.properties", new ExpectedResource(197, "6d0a2965f0bf28c515ab0924b5536aff0e9f0900c9fb87627e942a95322ac199"));
        values.put("/themes/nord/colors.properties", new ExpectedResource(2107, "22fae9f46cd38f8fac06b097b1c5e3536cc889afa1f16e4b640bfda0665fa620"));
        values.put("/themes/nord/theme.properties", new ExpectedResource(186, "e23770f776d197c8f583fdb6cc354ac4d729ffbba3d85719195e06df07c38944"));
        values.put("/themes/paper-yellow/colors.properties", new ExpectedResource(2139, "4ff49fc5728f55c4bfe942ffee5746762b6591a06817d9ca817b6788c03c64d7"));
        values.put("/themes/paper-yellow/theme.properties", new ExpectedResource(200, "2972fb384eb9501ab5ba2ba41d9d50f96b6525e9112efdb90d1eb431cca4667c"));
        values.put("/themes/sky-blue/colors.properties", new ExpectedResource(2093, "4c661af8bf47e23e84027b5eba06484bdeff69c5fd0d8e908dbbb8d578deb23f"));
        values.put("/themes/sky-blue/theme.properties", new ExpectedResource(192, "3b6af1a11abeef599355cd9143b8d8023e9167324b688b43cb4e518c0356980c"));
        values.put("/themes/slate/colors.properties", new ExpectedResource(2120, "dfbfad3aa7015dd89b055a2cd0ee305c30c53356ad8b5e14dd4d8eee0ff6a436"));
        values.put("/themes/slate/theme.properties", new ExpectedResource(184, "c0cd5fcc4f1c625d3462295965fa058b1d50d38a559623b8c5f80c80aaeacd1b"));
        return Map.copyOf(values);
    }

    private record ExpectedResource(int size, String sha256) {
    }
}

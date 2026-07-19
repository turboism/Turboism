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
        values.put("/themes/cherry-blossom/theme.properties", new ExpectedResource(138, "e1c7a7bcf9d00ce10eaf1ae82d6a3cb1dee4bb069ec58419babbad950ac21f85"));
        values.put("/themes/cubism-dark/colors.properties", new ExpectedResource(526, "519f1a9ae511a0decc4298c215e7f26b30ccd893eb3d5c665e7654e5e4b76dad"));
        values.put("/themes/cubism-dark/theme.properties", new ExpectedResource(112, "65846e0c3a896408a81544a48a905e93fa32db6f94a14a579491bc185d4ada1b"));
        values.put("/themes/cubism-light/colors.properties", new ExpectedResource(650, "ea7c006daca3cc83adc26f6c3efd74c44d70e784a23a88c7c6e79e127bb01794"));
        values.put("/themes/cubism-light/theme.properties", new ExpectedResource(115, "aa56923544143df401b9943dd106ef718a9fb44b742d2494574edfbb326ed215"));
        values.put("/themes/mint/colors.properties", new ExpectedResource(2284, "3d16c2b24f858026f1bc58321f65ad916f6398f22c67bf9f1b7988f026f2bf9c"));
        values.put("/themes/mint/theme.properties", new ExpectedResource(122, "2c9e08a92959015932afe7e6a2189db0458cc52054a618e526c6c9ffa221a9d7"));
        values.put("/themes/nord/colors.properties", new ExpectedResource(2107, "22fae9f46cd38f8fac06b097b1c5e3536cc889afa1f16e4b640bfda0665fa620"));
        values.put("/themes/nord/theme.properties", new ExpectedResource(111, "90c8a2a4e1f2b35911793e901d364d66a249994a641d7be905e74a5163358db8"));
        values.put("/themes/paper-yellow/colors.properties", new ExpectedResource(2139, "4ff49fc5728f55c4bfe942ffee5746762b6591a06817d9ca817b6788c03c64d7"));
        values.put("/themes/paper-yellow/theme.properties", new ExpectedResource(125, "041d7146964dd3aef679bb9ae18bc79eb7b3e86e63164ff51f098b856ec6f333"));
        values.put("/themes/sky-blue/colors.properties", new ExpectedResource(2093, "4c661af8bf47e23e84027b5eba06484bdeff69c5fd0d8e908dbbb8d578deb23f"));
        values.put("/themes/sky-blue/theme.properties", new ExpectedResource(117, "7695fc4fbe11eb02422967491dac42f59a17621b4abec1fd5cbc3ce9ae95a5ea"));
        values.put("/themes/slate/colors.properties", new ExpectedResource(2120, "dfbfad3aa7015dd89b055a2cd0ee305c30c53356ad8b5e14dd4d8eee0ff6a436"));
        values.put("/themes/slate/theme.properties", new ExpectedResource(109, "80b8fc7887a262777f19e1e59bc15cd49bf26329633c723979383acba7c79486"));
        return Map.copyOf(values);
    }

    private record ExpectedResource(int size, String sha256) {
    }
}

package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

final class FrameworkUpdateTrustTest {
    @Test void noFrameworkKeyProvisionedDoesNotReusePluginKeys() throws Exception {
        assertTrue(FrameworkUpdateTrust.bundled().activeKeys().isEmpty());
        assertFalse(dev.turboism.pluginmanagement.PluginCatalogVerifier.loadBundledTrustedKeys().isEmpty());
    }

    @Test void rotationRetainsHistoricalKeysButRevocationPreventsNewAuthorization() throws Exception {
        final var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final String encoded = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        final var json = UpdateFileSupport.JSON.createObjectNode().put("schemaVersion", 1);
        json.putObject("keys").put("old", encoded).put("new", encoded);
        json.putArray("revoked").add("old");
        final var trust = FrameworkUpdateTrust.parse(UpdateFileSupport.JSON.writeValueAsBytes(json));
        assertEquals(2, trust.keys().size());
        assertEquals(java.util.Set.of("new"), trust.activeKeys().keySet());
    }

    @Test void invalidKeyringsFailClosed() {
        for (final String json : new String[] {
                "null", "{}", "[]", "{\"schemaVersion\":1,\"keys\":{},\"revoked\":[],\"extra\":1}",
                "{\"schemaVersion\":1,\"keys\":{\"../bad\":\"a\"},\"revoked\":[]}",
                "{\"schemaVersion\":1,\"keys\":{\"key\":\"bad\"},\"revoked\":[]}",
                "{\"schemaVersion\":1,\"keys\":{},\"revoked\":[\"old\",\"old\"]}",
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"keys\":{},\"revoked\":[]}"
        }) {
            assertThrows(IOException.class,
                    () -> FrameworkUpdateTrust.parse(json.getBytes(StandardCharsets.UTF_8)), json);
        }
    }
}

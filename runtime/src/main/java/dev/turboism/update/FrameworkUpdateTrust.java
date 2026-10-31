package dev.turboism.update;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Dedicated framework-update authority. Plugin catalog keys never implicitly authorize agent replacement. */
final class FrameworkUpdateTrust {
    static final String RESOURCE = "/META-INF/turboism/framework-update-trusted-keys.json";
    private static final int LIMIT = 16384;

    record Policy(Map<String, PublicKey> keys, Set<String> revoked) {
        Policy { keys = Map.copyOf(keys); revoked = Set.copyOf(revoked); }
        Map<String, PublicKey> activeKeys() {
            final var active = new HashMap<>(keys);
            revoked.forEach(active::remove);
            return Map.copyOf(active);
        }
    }

    private FrameworkUpdateTrust() {}

    static Policy bundled() throws IOException {
        try (var input = FrameworkUpdateTrust.class.getResourceAsStream(RESOURCE)) {
            // No production signing key has been provisioned for this feature. Missing is disabled, not a
            // reason to borrow the marketplace key or trust a key delivered alongside the manifest.
            return input == null ? new Policy(Map.of(), Set.of()) : parse(input.readNBytes(LIMIT + 1));
        }
    }

    static Policy parse(final byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > LIMIT) throw new IOException("Invalid framework keyring size");
        final var root = UpdateFileSupport.JSON.readTree(UpdateManifest.strictUtf8(bytes));
        if (root == null || !root.isObject() || root.size() != 3
                || !root.path("schemaVersion").isIntegralNumber()
                || !root.path("schemaVersion").canConvertToInt()
                || root.path("schemaVersion").intValue() != 1
                || !root.path("keys").isObject() || root.path("keys").size() > 8
                || !root.path("revoked").isArray() || root.path("revoked").size() > 32) {
            throw new IOException("Invalid framework keyring schema");
        }
        final Map<String, PublicKey> keys = new HashMap<>();
        final var fields = root.path("keys").fields();
        while (fields.hasNext()) {
            final var entry = fields.next();
            if (!validId(entry.getKey()) || !entry.getValue().isTextual()) {
                throw new IOException("Invalid framework key entry");
            }
            try {
                final byte[] encoded = Base64.getDecoder().decode(entry.getValue().textValue());
                if (encoded.length != 44) throw new IllegalArgumentException("Expected Ed25519 SPKI");
                final PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
                keys.put(entry.getKey(), key);
            } catch (java.security.GeneralSecurityException | IllegalArgumentException invalid) {
                throw new IOException("Invalid framework public key", invalid);
            }
        }
        final Set<String> revoked = new HashSet<>();
        for (final var id : root.path("revoked")) {
            if (!id.isTextual() || !validId(id.textValue()) || !revoked.add(id.textValue())) {
                throw new IOException("Invalid revoked framework key");
            }
        }
        return new Policy(keys, revoked);
    }

    private static boolean validId(final String id) {
        return id.length() <= 64 && id.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }
}

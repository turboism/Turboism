package dev.turboism.update;

import java.io.IOException;
import java.net.URI;

/** Client activation is a bundled release decision, never an instruction accepted from the discovery feed. */
final class FrameworkUpdateClientPolicy {
    static final String RESOURCE = "/META-INF/turboism/framework-update-client.json";
    record Policy(boolean enabled, FrameworkUpdateSource source) {}
    private FrameworkUpdateClientPolicy() {}

    static Policy bundled() throws IOException {
        final byte[] bytes = FrameworkUpdateResources.read(RESOURCE, 4096);
        return bytes == null ? new Policy(false, FrameworkUpdateSource.disabled()) : parse(bytes);
    }

    static Policy parse(final byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 4096) throw new IOException("Invalid update client policy size");
        final var node = UpdateFileSupport.JSON.readTree(UpdateManifest.strictUtf8(bytes));
        if (node == null || !node.isObject() || node.size() != 3
                || !node.path("schemaVersion").isIntegralNumber()
                || !node.path("schemaVersion").canConvertToInt() || node.path("schemaVersion").intValue() != 1
                || !node.path("enabled").isBoolean() || !node.path("endpoint").isTextual()) {
            throw new IOException("Invalid update client policy schema");
        }
        try {
            return new Policy(node.path("enabled").booleanValue(),
                    FrameworkUpdateSource.configured(URI.create(node.path("endpoint").textValue())));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Untrusted framework update endpoint policy", invalid);
        }
    }
}

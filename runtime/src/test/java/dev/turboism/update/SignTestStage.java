package dev.turboism.update;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in tool (not a test): signs an update stage with a fresh LOCAL test key. Never uses a production key. */
@EnabledIfEnvironmentVariable(named = "TURBOISM_SIGN_STAGE", matches = ".+")
final class SignTestStage {
    @Test
    void sign() throws Exception {
        final Path stage = Path.of(System.getenv("TURBOISM_SIGN_STAGE"));
        final Path keyDir = Path.of(System.getenv("TURBOISM_SIGN_KEYDIR"));
        final var tree = UpdateFileSupport.JSON.readTree(Files.readAllBytes(stage.resolve("entries.json")));
        final List<UpdateManifestEntry> entries = new ArrayList<>();
        for (final var node : tree) {
            entries.add(new UpdateManifestEntry(
                    node.get("relativePath").asText(),
                    node.get("bytes").asLong(),
                    node.get("sha256").asText(),
                    node.get("contentId").asText()));
        }
        final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final byte[] manifest = new UpdateManifest(
                        1, "local-test", System.getenv("TURBOISM_SIGN_VERSION"), "stable", Long.parseLong(System.getenv("TURBOISM_SIGN_BUILD")), System.getenv("TURBOISM_SIGN_REV").repeat(40), "0.67.0", "windows-x64", entries)
                .toCanonicalBytes();
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(manifest);
        Files.write(stage.resolve("manifest.json"), manifest);
        Files.write(
                stage.resolve("manifest.json.sig"),
                UpdateFileSupport.JSON.writeValueAsBytes(UpdateFileSupport.JSON
                        .createObjectNode()
                        .put("format", UpdateManifestVerifier.SIGNATURE_FORMAT)
                        .put("schemaVersion", 1)
                        .put("algorithm", "Ed25519")
                        .put("keyId", "local-test")
                        .put("manifestSha256", FrameworkUpdateTaskStore.hash(manifest))
                        .put("signature", Base64.getEncoder().encodeToString(signer.sign()))));
        Files.createDirectories(keyDir);
        Files.writeString(
                keyDir.resolve("local-test-public-key.b64"),
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                StandardCharsets.US_ASCII);
        Files.delete(stage.resolve("entries.json"));
        Files.writeString(keyDir.resolve("helper.classpath"), System.getProperty("java.class.path"), StandardCharsets.UTF_8);
        System.out.println("SIGNED " + stage);
    }
}

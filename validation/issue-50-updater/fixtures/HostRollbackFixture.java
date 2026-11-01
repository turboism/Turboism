package dev.turboism.update;

// LAB-ONLY component transaction fixture. Creates a signed README-only "update" transaction that
// leaves FrameworkUpdateApplier in AWAITING_HEALTH so the NEXT controlled managed launch can recover
// it. Does not claim release eligibility, a signed full inventory, or CI provenance. LabHomeGuard
// requires a dedicated marker, confines every path under the repo lab base, and refuses Cubism
// roots, reparse points, and labs referenced by a live JVM.
import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.lab.LabHomeGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class HostRollbackFixture {
    private HostRollbackFixture() {}

    static String sha(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: HostRollbackFixture <root> <stage> <resultFile>");
            System.exit(2);
            return;
        }
        final Path root = LabHomeGuard.validate(Path.of(args[0]));
        final Path stage = LabHomeGuard.confineUnderBase(Path.of(args[1]));
        final Path resultFile = LabHomeGuard.confineUnderBase(Path.of(args[2]));

        LabHomeGuard.assertNoLiveJvm(root);

        final Path work = FrameworkUpdateHealth.workDirectory(root);
        if (Files.exists(work.resolve("journal.json"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(work.resolve("pending.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Existing update state; finish prior recovery first");
        }
        if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("stage must be a fresh directory: " + stage);
        }
        if (Files.exists(resultFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("result file must not already exist: " + resultFile);
        }

        final byte[] original = Files.readAllBytes(root.resolve("README.txt"));
        final byte[] changed = (new String(original, StandardCharsets.UTF_8)
                + "\nLOCAL COMPONENT ROLLBACK PROBE - not a release update\n").getBytes(StandardCharsets.UTF_8);
        final byte[] agent = Files.readAllBytes(root.resolve("turboism-agent.jar"));

        final Path files = stage.resolve("files");
        Files.createDirectories(files);
        Files.write(files.resolve("README.txt"), changed);

        final Properties properties = new Properties();
        try (var jar = new java.util.jar.JarFile(root.resolve("turboism-agent.jar").toFile());
                var in = jar.getInputStream(jar.getJarEntry("META-INF/turboism/framework-version.properties"))) {
            properties.load(in);
        }
        final FrameworkBuildInfo actual = FrameworkBuildInfo.fromProperties(properties);
        final UpdateManifest manifest = new UpdateManifest(1, "local-host-fixture", actual.version(), "stable", 1,
                actual.sourceRevision(), "0.1.0", "windows-x64", List.of(
                        new UpdateManifestEntry("README.txt", changed.length, sha(changed), "sha256:" + sha(changed)),
                        new UpdateManifestEntry("turboism-agent.jar", agent.length, sha(agent), "sha256:" + sha(agent))));
        final byte[] bytes = manifest.toCanonicalBytes();
        final KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(bytes);
        final var envelope = UpdateFileSupport.JSON.createObjectNode().put("format", UpdateManifestVerifier.SIGNATURE_FORMAT)
                .put("schemaVersion", 1).put("algorithm", "Ed25519").put("keyId", "local-host-fixture")
                .put("manifestSha256", sha(bytes))
                .put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        final var verified = new UpdateManifestVerifier(Map.of("local-host-fixture", key.getPublic()))
                .authenticate(bytes, UpdateFileSupport.JSON.writeValueAsBytes(envelope));

        final var result = new FrameworkUpdateApplier(root, work)
                .applyPendingHealth(verified, files, Files.readAllBytes(root.resolve("installed-build.json")));
        if (result.outcome() != FrameworkUpdateApplier.Outcome.AWAITING_HEALTH) {
            throw new IllegalStateException(result.toString());
        }
        if (!sha(Files.readAllBytes(root.resolve("turboism-agent.jar"))).equals(sha(agent))) {
            throw new AssertionError("Agent was changed");
        }

        // Record the pre-existing baselines so the next controlled launch can prove only the README moved.
        final Map<String, String> baseline = new LinkedHashMap<>();
        baseline.put("originalReadmeSha256", sha(original));
        baseline.put("changedReadmeSha256", sha(changed));
        baseline.put("agentSha256", sha(agent));
        baseline.put("configSha256", LabHomeGuard.sha256Hex(root.resolve("config.json")));
        baseline.put("pluginCount", Integer.toString(LabHomeGuard.pluginJars(root).size()));
        for (final Path jar : LabHomeGuard.pluginJars(root)) {
            baseline.put("plugin:" + jar.getFileName(), LabHomeGuard.sha256Hex(jar));
        }
        baseline.put("scope", "authenticated component fixture, README only; not release eligibility");

        final Map<String, Object> report = new LinkedHashMap<>();
        report.putAll(baseline);
        Files.createDirectories(resultFile.getParent());
        UpdateFileSupport.JSON.writeValue(resultFile.toFile(), report);

        System.out.println("COMPONENT_AWAITING_HEALTH: README probe applied, agent reused unchanged, original backup retained");
        System.out.println("BASELINE_RECORDED: " + resultFile + " plugins=" + baseline.get("pluginCount"));
    }
}

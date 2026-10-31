package dev.turboism.update;

// LAB-ONLY component transaction fixture. Does not claim release eligibility or a signed full inventory.
import dev.turboism.core.FrameworkBuildInfo;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class HostRollbackFixture {
    static String sha(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath().normalize(), stage=Path.of(args[1]), resultFile=Path.of(args[2]);
        if(!root.equals(Path.of("D:/turboism-test/issue50-recovery-f173f64").toAbsolutePath().normalize())) throw new IllegalArgumentException("Isolated recovery lab only");
        Path work=FrameworkUpdateHealth.workDirectory(root);
        if(Files.exists(work.resolve("journal.json"))||Files.exists(work.resolve("pending.json"))) throw new IllegalStateException("Existing update state");
        byte[] original=Files.readAllBytes(root.resolve("README.txt"));
        byte[] changed=(new String(original,StandardCharsets.UTF_8)+"\nLOCAL COMPONENT ROLLBACK PROBE - not a release update\n").getBytes(StandardCharsets.UTF_8);
        byte[] agent=Files.readAllBytes(root.resolve("turboism-agent.jar"));
        Path files=stage.resolve("files"); Files.createDirectories(files); Files.write(files.resolve("README.txt"),changed);
        Properties properties=new Properties();
        try(var jar=new java.util.jar.JarFile(root.resolve("turboism-agent.jar").toFile());
                var in=jar.getInputStream(jar.getJarEntry("META-INF/turboism/framework-version.properties"))) { properties.load(in); }
        var actual=FrameworkBuildInfo.fromProperties(properties);
        var manifest=new UpdateManifest(1,"local-host-fixture",actual.version(),"stable",1,
                actual.sourceRevision(),"0.1.0","windows-x64",List.of(
                    new UpdateManifestEntry("README.txt",changed.length,sha(changed),"sha256:"+sha(changed)),
                    new UpdateManifestEntry("turboism-agent.jar",agent.length,sha(agent),"sha256:"+sha(agent))));
        byte[] bytes=manifest.toCanonicalBytes();
        KeyPair key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(bytes);
        var envelope=UpdateFileSupport.JSON.createObjectNode().put("format",UpdateManifestVerifier.SIGNATURE_FORMAT)
                .put("schemaVersion",1).put("algorithm","Ed25519").put("keyId","local-host-fixture")
                .put("manifestSha256",sha(bytes)).put("signature",Base64.getEncoder().encodeToString(signer.sign()));
        var verified=new UpdateManifestVerifier(Map.of("local-host-fixture",key.getPublic()))
                .authenticate(bytes,UpdateFileSupport.JSON.writeValueAsBytes(envelope));
        var result=new FrameworkUpdateApplier(root,work).applyPendingHealth(verified,files,Files.readAllBytes(root.resolve("installed-build.json")));
        if(result.outcome()!=FrameworkUpdateApplier.Outcome.AWAITING_HEALTH) throw new IllegalStateException(result.toString());
        if(!sha(Files.readAllBytes(root.resolve("turboism-agent.jar"))).equals(sha(agent))) throw new AssertionError("Agent was changed");
        UpdateFileSupport.JSON.writeValue(resultFile.toFile(),Map.of("originalReadmeSha256",sha(original),"changedReadmeSha256",sha(changed),"agentSha256",sha(agent),"scope","authenticated component fixture, README only; not release eligibility"));
        System.out.println("COMPONENT_AWAITING_HEALTH: README probe applied, agent reused unchanged, original backup retained");
    }
}

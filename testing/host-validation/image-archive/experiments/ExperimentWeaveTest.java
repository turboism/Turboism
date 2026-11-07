package dev.turboism.validation.experiments;

import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.jar.JarFile;

/** Exact installed-class call-site gate, no native application startup. */
public final class ExperimentWeaveTest {
    private ExperimentWeaveTest() {}
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toRealPath();
        var reference = new HashMap<String, byte[]>();
        try (var zip = new JarFile(jar.toFile())) {
            for (String name : java.util.List.of(HotspotExperimentAgent.IMAGE, HotspotExperimentAgent.ARCHIVE,
                    HotspotExperimentAgent.EXT, HotspotExperimentAgent.FLOAT))
                try (var input = zip.getInputStream(zip.getJarEntry(name + ".class"))) { reference.put(name, input.readAllBytes()); }
        }
        var transformer = new HotspotExperimentAgent.CandidateTransformer(jar, reference);
        var domain = new ProtectionDomain(new CodeSource(jar.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
        for (var entry : reference.entrySet()) {
            byte[] result = transformer.transform(null, ExperimentWeaveTest.class.getClassLoader(), entry.getKey(),
                    null, domain, entry.getValue());
            if (result == null || transformer.error != null) throw new AssertionError("exact weave: " + transformer.error);
        }
        if (HotspotOps.snapshot().get("transformClasses") != 4) throw new AssertionError("incomplete targets");
        byte[] changed = reference.get(HotspotExperimentAgent.FLOAT).clone(); changed[12] ^= 1;
        if (transformer.transform(null, ExperimentWeaveTest.class.getClassLoader(), HotspotExperimentAgent.FLOAT,
                null, domain, changed) != null) throw new AssertionError("mutated input admitted");
        System.out.println("ExperimentWeaveTest PASS");
    }
}

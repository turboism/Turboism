package dev.turboism.validation.tlindex.diagnostic;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

/** Differential ASM-core/tree oracle over SHA-bound class data, never native execution. */
public final class LazyEdgeCompositionReview {
    private LazyEdgeCompositionReview() {}
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 2, "official jar and existing candidate directory required");
        Path jarPath = Path.of(args[0]), directory = Path.of(args[1]);
        Map<String, String> pins = new LinkedHashMap<>();
        for (String line : Files.readAllLines(directory.resolve("pins.txt"))) {
            String[] entry = line.split("=", 2); require(entry.length == 2, "invalid pin entry");
            require(pins.put(entry[0], entry[1]) == null, "duplicate pin entry");
        }
        // These reports follow the SHA-whitelisted core CLI; this oracle is not
        // an admission entry point and never defines any supplied host class.
        require(CoreLazyEdgeBytecodePrototype.sha(Files.readAllBytes(jarPath)).equals(pins.get("jarSha")), "JAR pin drift");
        byte[] baseline = Files.readAllBytes(directory.resolve("h.before-lazy.class"));
        byte[] core = Files.readAllBytes(directory.resolve("h.class"));
        require(CoreLazyEdgeBytecodePrototype.sha(baseline).equals(pins.get("hBeforeLazy")), "baseline pin drift");
        require(CoreLazyEdgeBytecodePrototype.sha(core).equals(pins.get("hOutput")), "core pin drift");
        byte[] tree;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            Function<String, byte[]> definitions = name -> {
                var entry = jar.getEntry(name + ".class");
                if (entry == null) return LazyEdgeBytecodePrototype.platformBytes(name);
                try (InputStream stream = jar.getInputStream(entry)) { return stream.readAllBytes(); }
                catch (Exception failure) { throw new IllegalArgumentException(name, failure); }
            };
            tree = LazyEdgeBytecodePrototype.patchShape(baseline, definitions);
        }
        String coreFingerprint = DefinitionFingerprint.of(core), treeFingerprint = DefinitionFingerprint.of(tree);
        require(coreFingerprint.equals(treeFingerprint), "core/tree executable metadata mismatch");
        Path oracle = directory.resolve("h.tree-oracle.class");
        require(!Files.exists(oracle), "oracle output already exists"); Files.write(oracle, tree);
        String report = "version=" + pins.get("version") + "\ncomposed=" + pins.get("composed")
                + "\ncoreSha=" + CoreLazyEdgeBytecodePrototype.sha(core)
                + "\ntreeSha=" + CoreLazyEdgeBytecodePrototype.sha(tree)
                + "\nsemanticFingerprint=" + coreFingerprint
                + "\ncoreTreeEquivalent=true\nofficialClassesExecuted=false\nproductionAdmissionReady=false\n";
        Files.writeString(directory.resolve("oracle-review.txt"), report);
        System.out.println("LAZY_EDGE_COMPOSITION_ORACLE PASS version=" + pins.get("version")
                + " composed=" + pins.get("composed") + " coreTreeEquivalent=true officialClassesExecuted=false");
    }
}

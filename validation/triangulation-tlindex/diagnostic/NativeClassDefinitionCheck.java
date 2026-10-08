package dev.turboism.validation.tlindex.diagnostic;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.objectweb.asm.*;

/** Defines reviewed official class bodies for verification only; invokes no official code. */
public final class NativeClassDefinitionCheck implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String H = P + "h";
    private static final String VECTOR = "com/live2d/graphics3d/type/GVector2";
    private static final String KOTLIN_SHA = "d46a9d773ffb9dee4ff1a748ac845dc8e50005c589302951760a2b5187bddd19";
    private static final Map<String, String> JARS = Map.of(
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd", "5203",
            "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21", "5302",
            "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166", "5303");
    private static final Map<String, String> OUTPUTS = Map.of(
            "5203:false", "cf6aa58c4a70c7259555938af7c91fe8cad483a7e0de96e9a0d2fd4957fd2a30",
            "5203:true", "fb521f43ea595fa33553df81dd66d29ea4958ce1e75c2fe0355b2ead0d5db133",
            "53:false", "41bfba9a898a92951e1cefb59e8151003b389c4846dc78d40b88542171b488da",
            "53:true", "5d6a54280e10ee492cec0542701fe3cd7d2830b295976dba14192dca8a83c8b2");
    private NativeClassDefinitionCheck() {}
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    private static String sha(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] block = new byte[65536]; int count;
            while ((count = input.read(block)) != -1) digest.update(block, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] classBytes(Path jarPath, String name) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile(), true)) {
            var entry = jar.getJarEntry(name + ".class"); require(entry != null, "missing reviewed class " + name);
            try (InputStream input = jar.getInputStream(entry)) { return input.readAllBytes(); }
        }
    }
    private static Map<String, String> pins(Path path) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            String[] entry = line.split("=", 2); require(entry.length == 2, "invalid pin entry");
            require(result.put(entry[0], entry[1]) == null, "duplicate pin entry");
        }
        return result;
    }
    private static final class DefinitionLoader extends URLClassLoader {
        private final byte[] host;
        private final URL official;
        private final Manifest manifest;
        private final CodeSigner[] signers;
        private final List<String> defined = new ArrayList<>();
        DefinitionLoader(URL[] urls, Path officialJar, byte[] host) throws Exception {
            super(urls, ClassLoader.getPlatformClassLoader());
            this.host = host; official = officialJar.toUri().toURL();
            try (JarFile jar = new JarFile(officialJar.toFile(), true)) {
                manifest = jar.getManifest(); var entry = jar.getJarEntry(H + ".class");
                require(entry != null, "missing signed original host");
                try (InputStream input = jar.getInputStream(entry)) { input.readAllBytes(); }
                // Model the original definition's signer/domain metadata after a
                // transform. This does not claim the altered bytes have a JAR signature.
                signers = entry.getCodeSigners();
            }
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> type;
            if (name.equals(H.replace('/', '.'))) {
                String pkg = name.substring(0, name.lastIndexOf('.'));
                if (getDefinedPackage(pkg) == null) {
                    if (manifest == null) definePackage(pkg, null, null, null, null, null, null, null);
                    else definePackage(pkg, manifest, official);
                }
                type = defineClass(name, host, 0, host.length, new CodeSource(official, signers));
            } else type = super.findClass(name);
            CodeSource source = type.getProtectionDomain().getCodeSource();
            defined.add(name + "\t" + (source == null ? "none" : source.getLocation()));
            return type;
        }
        List<String> definitions() { return List.copyOf(defined); }
    }
    private static Class<?> owned(byte[] bytes) {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
    }
    private static void verificationControls(Instrumentation instrumentation) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, "dev/turboism/validation/owned/NoInitialization",
                null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null); init.visitCode();
        init.visitTypeInsn(NEW, "java/lang/AssertionError"); init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
        init.visitInsn(ATHROW); init.visitMaxs(0, 0); init.visitEnd(); writer.visitEnd();
        byte[] bytes = writer.toByteArray(); Class<?> control = owned(bytes);
        Class.forName(control.getName(), false, control.getClassLoader());
        control.getDeclaredMethods(); control.getDeclaredConstructors(); control.getDeclaredFields();
        try (LiveDefinitionAdmission admission = new LiveDefinitionAdmission(instrumentation)) {
            require(admission.capture(new Class<?>[] {control}, Map.of(control.getName(),
                    DefinitionFingerprint.runtimeOf(bytes))).getAsBoolean(), "owned no-initialization capture failed");
        }
        // If the verification/reflection/retransformation path initialized this control,
        // its deliberately failing initializer would already have aborted it.
        boolean initialized = false;
        try { Class.forName(control.getName(), true, control.getClassLoader()); }
        catch (AssertionError expected) { initialized = true; }
        require(initialized, "owned initialization control was ineffective");
        writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, "dev/turboism/validation/owned/InvalidReturn",
                null, "java/lang/Object", null);
        MethodVisitor broken = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "broken", "()I", null, null);
        broken.visitCode(); broken.visitInsn(RETURN); broken.visitMaxs(0, 0); broken.visitEnd(); writer.visitEnd();
        boolean refused = false;
        try { owned(writer.toByteArray()).getDeclaredMethods(); }
        catch (VerifyError expected) { refused = true; }
        require(refused, "JVM did not reject the owned invalid return");
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 4, "official JAR, candidate directory, frozen Agent and new output directory required");
        Path official = Path.of(args[0]).toRealPath(), candidate = Path.of(args[1]);
        Path agent = Path.of(args[2]).toRealPath(), out = Path.of(args[3]);
        String jarSha = sha(official), version = JARS.get(jarSha); require(version != null, "unreviewed official JAR");
        require(sha(agent).equals(FrozenEdgeTransforms.reviewedSha()), "unreviewed frozen Agent");
        Map<String, String> pins = pins(candidate.resolve("pins.txt"));
        require(jarSha.equals(pins.get("jarSha")) && version.equals(pins.get("version")), "official input pin drift");
        byte[] original = classBytes(official, H), host = Files.readAllBytes(candidate.resolve("h.class"));
        require(sha(original).equals(pins.get(H)), "pristine host pin drift");
        String key = (version.equals("5203") ? version : "53") + ':' + pins.get("composed");
        require(sha(host).equals(OUTPUTS.get(key)) && sha(host).equals(pins.get("hOutput")), "unreviewed candidate output");
        Path kotlin = official.getParent().resolve("kotlin-stdlib-1.7.21.jar");
        require(sha(kotlin).equals(KOTLIN_SHA), "unreviewed Kotlin dependency");
        List<Path> libraries;
        try (var stream = Files.list(official.getParent())) {
            libraries = stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .map(path -> path.toAbsolutePath().normalize()).sorted().toList();
        }
        List<Path> classpath = new ArrayList<>(); classpath.add(official); classpath.add(agent);
        for (Path path : libraries) if (!path.equals(official)) classpath.add(path);
        URL[] urls = new URL[classpath.size()]; List<String> inventory = new ArrayList<>();
        for (int n = 0; n < urls.length; n++) {
            Path path = classpath.get(n); urls[n] = path.toUri().toURL(); inventory.add(path + "\t" + sha(path));
        }
        Instrumentation instrumentation = DefinitionAdmissionSelfCheckAgent.instrumentation();
        require(instrumentation != null, "owned instrumentation Agent required");
        verificationControls(instrumentation);
        Files.createDirectory(out);
        try (DefinitionLoader loader = new DefinitionLoader(urls, official, host)) {
            List<String> dependencies = List.of(H, P + "j", P + "TriPoint", P + "l", P + "r", VECTOR,
                    "kotlin/_Assertions", "kotlin/jvm/internal/Intrinsics");
            List<Class<?>> types = new ArrayList<>(); Map<String, String> expected = new LinkedHashMap<>();
            List<String> metadata = new ArrayList<>();
            for (String name : dependencies) {
                Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
                require(type.getClassLoader() == loader, "dependency loader mismatch " + name);
                Path origin = name.startsWith("kotlin/") ? kotlin : official;
                require(type.getProtectionDomain().getCodeSource().getLocation().equals(origin.toUri().toURL()), "dependency origin mismatch " + name);
                byte[] reviewed = name.equals(H) ? host : classBytes(origin, name);
                Path reviewedPath = out.resolve("reviewed").resolve(name + ".class");
                Files.createDirectories(reviewedPath.getParent()); Files.write(reviewedPath, reviewed);
                expected.put(type.getName(), DefinitionFingerprint.runtimeOf(reviewed)); types.add(type);
                metadata.add(name + "\t" + type.getModifiers() + "\t" + type.getDeclaredMethods().length
                        + "\t" + type.getDeclaredConstructors().length + "\t" + type.getDeclaredFields().length);
            }
            // Only this owned Agent is passed in this isolated process. It has
            // installed no transformer; the observer/collector are our sole ones.
            // This experiment does not close a production Agent's ordering gap.
            String reason;
            Map<String, String> observed = new LinkedHashMap<>();
            Files.writeString(out.resolve("classpath-inventory.tsv"), String.join("\n", inventory) + "\n");
            Files.writeString(out.resolve("verified-metadata.tsv"), String.join("\n", metadata) + "\n");
            try (LiveDefinitionAdmission admission = new LiveDefinitionAdmission(instrumentation)) {
                LiveDefinitionAdmission.Gate gate = admission.capture(types.toArray(Class<?>[]::new), expected, (name, bytes) -> {
                    Path actual = out.resolve("actual").resolve(name.replace('.', '/') + ".class");
                    try { Files.createDirectories(actual.getParent()); Files.write(actual, bytes); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    observed.put(name, DefinitionFingerprint.runtimeOf(bytes));
                });
                require(gate.getAsBoolean(), "controlled actual definition capture failed: " + gate.reason());
                reason = gate.reason();
                for (Class<?> type : types) {
                    type.getDeclaredMethods(); type.getDeclaredConstructors(); type.getDeclaredFields();
                }
                require(gate.getAsBoolean(), "owned capture gate unexpectedly revoked");
            }
            Files.writeString(out.resolve("classpath-inventory.tsv"), String.join("\n", inventory) + "\n");
            Files.writeString(out.resolve("defined-types.tsv"), String.join("\n", loader.definitions()) + "\n");
            Files.writeString(out.resolve("verified-metadata.tsv"), String.join("\n", metadata) + "\n");
            List<String> fingerprints = new ArrayList<>();
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                require(entry.getValue().equals(observed.get(entry.getKey())), "incomplete runtime fingerprint record");
                fingerprints.add(entry.getKey() + "\t" + entry.getValue() + "\t" + observed.get(entry.getKey()));
            }
            Files.writeString(out.resolve("runtime-fingerprints.tsv"), String.join("\n", fingerprints) + "\n");
            String receipt = "version=" + version + "\ncomposed=" + pins.get("composed") + "\njarSha=" + jarSha
                    + "\nhCandidateSha=" + sha(host) + "\nverifiedDependencies=" + dependencies.size()
                    + "\ncontrolledCollector=" + reason + "\nofficialClassesDefined=true"
                    + "\nofficialInitializersOrMethodsInvoked=false\nnativeGeometryExecuted=false"
                    + "\nownedInitializationAndVerifyControls=true\nproductionAdmissionReady=false\nhostTasksSubmitted=0\n";
            Files.writeString(out.resolve("receipt.txt"), receipt);
            System.out.println("NATIVE_CLASS_DEFINITION_CHECK PASS version=" + version + " composed=" + pins.get("composed")
                    + " verifiedDependencies=" + dependencies.size() + " officialCodeInvoked=false productionAdmissionReady=false");
        }
    }
}

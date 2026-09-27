package dev.turboism.validation.atlasimage;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;

/** Test-only first-definition observer. Never transforms or invokes a host class. */
public final class AtlasImageLoadProbeAgent {
    static final String FILTER = "jp/noids/graphics/h";
    static final String SCENE = AtlasImageObserveContract.SCENE;
    static final String OUTPUT_RELATIVE = AtlasImageObserveContract.OUTPUT_RELATIVE;
    static final String FIXTURE_SUFFIX = AtlasImageObserveContract.FIXTURE_SUFFIX;
    static final String NAMED_PREFIX = AtlasImageObserveContract.NAMED_PREFIX;
    static final Map<String, String> REVIEWED = Map.of(
        "5203", "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
        "5302", "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
        "5303", "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166"
    );

    private AtlasImageLoadProbeAgent() {}

    /** Both the historical properties-path entry and the named no-argument entry are supported. */
    public static void premain(final String configPath, final Instrumentation instrumentation) {
        try {
            final ProbeConfig config;
            if (configPath == null || configPath.isBlank()) {
                config = ProbeConfig.fromSystemProperties();
            } else {
                final Properties properties = new Properties();
                try (InputStream in = Files.newInputStream(Path.of(configPath))) {
                    properties.load(in);
                }
                config = ProbeConfig.fromProperties(properties);
            }
            final Map<String, String> hashes = config.verifyArtifact();
            final Path run = config.claimRun();
            final Recorder recorder = new Recorder(hashes, config.artifact.toUri().toString(), FILTER);
            final Properties identity = new Properties();
            identity.setProperty("schemaVersion", "1");
            identity.setProperty("scene", SCENE);
            identity.setProperty("version", config.version);
            identity.setProperty("editorSha256", REVIEWED.get(config.version));
            identity.setProperty("officialJarPath", config.artifact.toString());
            identity.setProperty("runId", run.getFileName().toString());
            identity.setProperty("probeMode", "OBSERVE_ONLY_NO_TRANSFORM");
            identity.setProperty("configMode", config.mode);
            if (config.home != null) {
                identity.setProperty("taskHome", config.home.toString());
                identity.setProperty("outputRelative", OUTPUT_RELATIVE);
                identity.setProperty("fixture", config.fixture);
                identity.setProperty("fixtureName", config.fixtureName);
            }
            if (new ObservationSession(instrumentation, recorder, run, ObservationSession.JVM).start(identity)) {
                System.out.println("ATLAS_IMAGE_LOAD_PROBE_ARMED");
            }
        } catch (Exception failure) {
            // Do not abort the host JVM or misreport readiness if the observer cannot arm.
            System.err.println("ATLAS_IMAGE_LOAD_PROBE_BLOCKED " + failure.getClass().getSimpleName()
                + " reason=" + safeMessage(failure));
        }
    }

    private static String safeMessage(final Exception failure) {
        final String message = failure.getMessage();
        return message == null ? "unspecified" : message.replace('\n', ' ').replace('\r', ' ');
    }

    static List<String> targets(final String version) {
        return List.of(FILTER, "com/live2d/cubism/CEAppCtrl",
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas",
            "com/live2d/cubism/doc/model/texture/modelImage/CModelImage",
            "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel",
            "com/live2d/util/" + (version.equals("5203") ? "e" : "f") + "/g");
    }

    static String required(final Properties properties, final String key) {
        final String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    static String validRunId(final String value) {
        if (!value.matches("[a-z0-9][a-z0-9-]{2,63}")) {
            throw new IllegalArgumentException("invalid runId");
        }
        return value;
    }

    static String validTaskId(final String value) {
        if (!value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid taskId");
        }
        return value;
    }

    /** Resolve only the exact Live2D_Cubism.jar entries already present in the current JVM class path. */
    static Path findUniqueOfficialJar(final String classPath) throws Exception {
        if (classPath == null || classPath.isBlank()) {
            throw new IllegalArgumentException("java.class.path is empty; exact Live2D_Cubism.jar entry is required");
        }
        final List<Path> matches = new ArrayList<>();
        for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
            if (entry.isBlank()) continue;
            final Path normalized = Path.of(entry).toAbsolutePath().normalize();
            if (!"Live2D_Cubism.jar".equals(String.valueOf(normalized.getFileName()))) continue;
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("java.class.path Live2D_Cubism.jar entry is not a regular file: "
                    + normalized);
            }
            matches.add(normalized.toRealPath());
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException("expected exactly one java.class.path Live2D_Cubism.jar entry, found "
                + matches.size());
        }
        return matches.get(0);
    }

    static String fileHash(final Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return streamHash(in);
        }
    }

    static String streamHash(final InputStream in) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        return HexFormat.of().formatHex(digest.digest());
    }

    static void write(final Path file, final Properties values) throws Exception {
        final Path parent = file.getParent();
        if (parent == null) throw new IllegalArgumentException("result path has no parent");
        Files.createDirectories(parent);
        final Path temp = Files.createTempFile(parent, ".atlas-load-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                values.store(out, "test-only observation");
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Map<String, String> targetHashes(final String version, final Path artifact) throws Exception {
        final Map<String, String> hashes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (String name : targets(version)) {
                final var entry = jar.getJarEntry(name + ".class");
                if (entry == null) throw new IllegalArgumentException("missing target " + name);
                try (InputStream in = jar.getInputStream(entry)) {
                    hashes.put(name, streamHash(in));
                }
            }
        }
        return hashes;
    }

    static final class ProbeConfig {
        private final String version;
        private final Path artifact;
        private final Path outputRoot;
        private final String runId;
        private final String mode;
        private final Path home;
        private final String fixture;
        private final String fixtureName;

        private ProbeConfig(final String version, final Path artifact, final Path outputRoot,
                            final String runId, final String mode, final Path home,
                            final String fixture, final String fixtureName) {
            this.version = version;
            this.artifact = artifact;
            this.outputRoot = outputRoot;
            this.runId = runId;
            this.mode = mode;
            this.home = home;
            this.fixture = fixture;
            this.fixtureName = fixtureName;
        }

        static ProbeConfig fromProperties(final Properties properties) {
            final String version = required(properties, "version");
            reviewed(version);
            final Path artifact = Path.of(required(properties, "editorJar")).toAbsolutePath().normalize();
            final Path outputRoot = Path.of(required(properties, "outputRoot")).toAbsolutePath().normalize();
            final String runId = validRunId(required(properties, "runId"));
            return new ProbeConfig(version, artifact, outputRoot, runId,
                "PROPERTIES_PATH", null, "", "");
        }

        static ProbeConfig fromSystemProperties() throws Exception {
            final String homeValue = requiredSystem("turboism.home");
            final String namedHomeValue = requiredSystem(NAMED_PREFIX + "home");
            final Path home = Path.of(namedHomeValue).toAbsolutePath().normalize();
            final Path genericHome = Path.of(homeValue).toAbsolutePath().normalize();
            if (!home.equals(genericHome)) throw new IllegalArgumentException("named home differs from turboism.home");
            if (!Files.isDirectory(home)) throw new IllegalArgumentException("turboism.home is not a directory");

            final String runId = validTaskId(requiredSystem("turboism.validation.runId"));
            final String taskId = validTaskId(requiredSystem(NAMED_PREFIX + "taskId"));
            if (!runId.equals(taskId)) throw new IllegalArgumentException("taskId differs from runId");
            final String version = requiredSystem(NAMED_PREFIX + "version");
            reviewed(version);
            final String hostVersion = System.getProperty("turboism.validation.hostVersion", version);
            if (!version.equals(hostVersion)) throw new IllegalArgumentException("scene version differs from hostVersion");
            final String fixture = requiredSystem(NAMED_PREFIX + "fixture");
            final String fixtureName = requiredSystem(NAMED_PREFIX + "fixtureName");
            AtlasImageObserveContract.requireNamedFixture(fixture, fixtureName, runId);
            final String outputRelative = System.getProperty(NAMED_PREFIX + "outputRelative", OUTPUT_RELATIVE);
            if (!OUTPUT_RELATIVE.equals(outputRelative)) {
                throw new IllegalArgumentException("outputRelative is fixed to " + OUTPUT_RELATIVE);
            }
            final Path artifact = findUniqueOfficialJar(System.getProperty("java.class.path"));
            return new ProbeConfig(version, artifact, home.resolve(OUTPUT_RELATIVE), runId,
                "NAMED_SYSTEM_PROPERTIES", home, fixture, fixtureName);
        }

        private static String requiredSystem(final String key) {
            final String value = System.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
            return value;
        }

        private static void reviewed(final String version) {
            if (!REVIEWED.containsKey(version)) throw new IllegalArgumentException("unsupported version " + version);
        }

        Map<String, String> verifyArtifact() throws Exception {
            if (!"Live2D_Cubism.jar".equals(String.valueOf(artifact.getFileName()))) {
                throw new IllegalArgumentException("official JAR basename must be Live2D_Cubism.jar");
            }
            final Path real = artifact.toRealPath();
            if (!Files.isRegularFile(real)) throw new IllegalArgumentException("official JAR is not a regular file");
            if (!REVIEWED.get(version).equals(fileHash(real))) {
                throw new IllegalArgumentException("JAR identity mismatch for version " + version);
            }
            return targetHashes(version, real);
        }

        Path claimRun() throws Exception {
            final Path output;
            if (home == null) {
                output = outputRoot;
                Files.createDirectories(output);
            } else {
                final Path homeReal = home.toRealPath();
                Files.createDirectories(homeReal.resolve(OUTPUT_RELATIVE));
                output = homeReal.resolve(OUTPUT_RELATIVE).toRealPath();
                if (!output.startsWith(homeReal)) throw new IllegalArgumentException("output escaped task home");
            }
            final Path run = output.resolve(runId).normalize();
            if (!run.startsWith(output)) throw new IllegalArgumentException("run escaped output root");
            // Atomic claim: never overwrite a previous run, including an incomplete one.
            Files.createDirectory(run);
            return run;
        }
    }

    static final class Recorder implements ClassFileTransformer {
        private static final int MAX_EVENTS = 64;
        private final Map<String, String> hashes;
        private final String origin;
        private final String requiredTarget;
        private final List<String> events = new ArrayList<>(MAX_EVENTS);
        private boolean bad;
        private boolean sawRequired;
        private boolean ended;

        Recorder(final Map<String, String> hashes, final String origin, final String requiredTarget) {
            this.hashes = Map.copyOf(hashes);
            this.origin = origin;
            this.requiredTarget = requiredTarget;
        }

        @Override
        public byte[] transform(final ClassLoader loader, final String name, final Class<?> redefining,
                                final ProtectionDomain domain, final byte[] bytes) {
            if (name == null || !hashes.containsKey(name)) return null;
            synchronized (this) {
                if (ended) return null;
                if (events.size() == MAX_EVENTS) {
                    bad = true;
                    return null;
                }
                try {
                    final String actualOrigin = domain == null || domain.getCodeSource() == null
                        ? "" : domain.getCodeSource().getLocation().toURI().toString();
                    final String actualHash = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
                    final boolean matches = java.net.URI.create(origin).equals(java.net.URI.create(actualOrigin))
                        && hashes.get(name).equals(actualHash);
                    if (!matches || redefining != null) bad = true;
                    if (matches && redefining == null && name.equals(requiredTarget)) sawRequired = true;
                    add(name + ";firstDefinition=" + (redefining == null) + ";identity=" + matches
                        + ";source=" + actualOrigin + ";sha256=" + actualHash
                        + ";loaderDiagnostic=" + System.identityHashCode(loader)
                        + ";thread=" + Thread.currentThread().getId() + ";nanoTime=" + System.nanoTime());
                } catch (Throwable failure) {
                    bad = true;
                    add(name + ";observationError=true");
                }
            }
            return null;
        }

        synchronized void alreadyLoaded(final String name) {
            if (hashes.containsKey(name)) {
                bad = true;
                add(name + ";alreadyLoaded=true");
            }
        }

        synchronized void alreadyLoaded(final Class<?> type) {
            final String name = type.getName().replace('.', '/');
            if (!hashes.containsKey(name)) return;
            bad = true;
            try {
                final ProtectionDomain domain = type.getProtectionDomain();
                final String source = domain == null || domain.getCodeSource() == null
                    ? "" : domain.getCodeSource().getLocation().toURI().toString();
                add(name + ";alreadyLoaded=true;source=" + source + ";runtimeByteHash=NOT_OBSERVED"
                    + ";loaderDiagnostic=" + System.identityHashCode(type.getClassLoader())
                    + ";thread=" + Thread.currentThread().getId() + ";nanoTime=" + System.nanoTime());
            } catch (Throwable failure) {
                add(name + ";alreadyLoaded=true;metadata=NOT_OBSERVED");
            }
        }

        private void add(final String event) {
            if (events.size() == MAX_EVENTS) {
                bad = true;
                return;
            }
            events.add(event);
        }

        synchronized boolean finished() {
            return ended;
        }

        synchronized void end() {
            ended = true;
        }

        synchronized Properties freeze() {
            ended = true;
            return snapshot();
        }

        synchronized void reject(final String reason) {
            bad = true;
            add("failure=" + reason);
        }

        synchronized Properties snapshot() {
            final Properties result = new Properties();
            result.setProperty("schemaVersion", "1");
            // PASS only means that the observation window met this narrow contract, not a working guard.
            result.setProperty("status", !bad && sawRequired ? "PASS" : "BLOCKED");
            result.setProperty("assertion", "OBSERVED_REVIEWED_FILTER_FIRST_DEFINITION");
            result.setProperty("expected", "reviewed required target definition callback; no recorded conflicts");
            result.setProperty("actual.requiredTargetObserved", Boolean.toString(sawRequired));
            result.setProperty("actual.conflictOrOverflow", Boolean.toString(bad));
            result.setProperty("documentModelIdentity", "NOT_ACQUIRED_OBSERVE_CLASS_LOADING_ONLY");
            result.setProperty("definitionSucceeded", "NOT_OBSERVED");
            result.setProperty("retransformationCoverage", "NOT_OBSERVED");
            result.setProperty("optimizationReadiness", "NOT_EVALUATED");
            result.setProperty("guardInstalled", "false");
            result.setProperty("hostMethodsInvokedByProbe", "false");
            result.setProperty("eventCount", Integer.toString(events.size()));
            result.setProperty("expectedSource", origin);
            hashes.forEach((name, hash) -> result.setProperty("expectedClassSha256." + name, hash));
            for (int i = 0; i < events.size(); i++) result.setProperty("event." + i, events.get(i));
            return result;
        }
    }
}

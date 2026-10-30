package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.FrameworkBuildInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Read-only historical receipt authentication. No receipt writer or production trust configuration. */
public final class FrameworkInstallationReceiptLoader {
    static final String FORMAT = "turboism-installed-receipt-v1";
    static final String LAYOUT = "windows-standard-zip";
    static final Path STATE = Path.of("state/runtime/update");
    private final Path root;
    private final UpdateManifestVerifier verifier;
    private final Supplier<FrameworkBuildInfo> loaded;
    private final Supplier<Path> loadedArtifact;

    private FrameworkInstallationReceiptLoader() {
        root = null;
        verifier = null;
        loaded = FrameworkBuildInfo::current;
        loadedArtifact = () -> null;
    }

    /** Default until the trusted installer, keyring and Windows file layer are deployed. */
    public static FrameworkInstallationReceiptLoader disabled() {
        return new FrameworkInstallationReceiptLoader();
    }

    /** Trusted configuration seam; never populated from feed, local index or installed-build.json. */
    FrameworkInstallationReceiptLoader(final Path root, final UpdateManifestVerifier verifier) {
        this(root, verifier, FrameworkBuildInfo::current, FrameworkInstallationReceiptLoader::runtimeArtifact);
    }

    FrameworkInstallationReceiptLoader(
            final Path root, final UpdateManifestVerifier verifier, final Supplier<FrameworkBuildInfo> loaded) {
        this(root, verifier, loaded, () -> root.toAbsolutePath().normalize().resolve("turboism-agent.jar"));
    }

    FrameworkInstallationReceiptLoader(
            final Path root,
            final UpdateManifestVerifier verifier,
            final Supplier<FrameworkBuildInfo> loaded,
            final Supplier<Path> loadedArtifact) {
        this.loadedArtifact = Objects.requireNonNull(loadedArtifact, "loadedArtifact");
        this.root = root.toAbsolutePath().normalize();
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    /** Performs IO and full old-file hashes on the caller's worker. Never scans for ownership. */
    public Evidence load()
            throws IOException, InterruptedException, UpdateManifestVerifier.UpdateManifestVerificationException {
        if (root == null)
            throw new IOException("Trusted installation receipt configuration is unavailable; use full install");
        if (SwingUtilities.isEventDispatchThread()) throw new IOException("Receipt IO cannot run on EDT");
        if (!root.resolve("turboism-agent.jar").equals(loadedArtifact.get())) {
            throw new IOException("Loaded runtime artifact is outside the configured standard ZIP installation");
        }
        final FrameworkBuildInfo info = loaded.get();
        if (!info.buildKind().equals("ci")
                || info.dirty()
                || info.buildNumber().isEmpty()
                || info.channel() == FrameworkBuildInfo.Channel.UNKNOWN
                || !info.sourceRevision().matches("[a-f0-9]{40}")) {
            throw new IOException("Loaded framework has no clean numbered source identity");
        }
        final FrameworkInstallationFiles files = new FrameworkInstallationFiles.Posix(root);
        final String rootIdentity = files.rootIdentity();
        final Path indexPath = root.resolve(STATE).resolve("installed-receipt.json");
        final byte[] indexBytes = files.document(indexPath, 4096);
        final JsonNode parsed = UpdateFileSupport.JSON.readTree(UpdateManifest.strictUtf8(indexBytes));
        if (!(parsed instanceof ObjectNode index)
                || index.size() != 5
                || !Set.of("format", "schemaVersion", "layout", "manifestSha256", "rootIdentity").stream()
                        .allMatch(index::hasNonNull)
                || !index.path("schemaVersion").isIntegralNumber()
                || !index.path("schemaVersion").canConvertToInt()
                || index.path("schemaVersion").intValue() != 1
                || !text(index, "format", FORMAT)
                || !text(index, "layout", LAYOUT)
                || !text(index, "rootIdentity", rootIdentity)
                || !index.path("manifestSha256").isTextual()
                || !index.path("manifestSha256").textValue().matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid installation receipt index/root binding");
        }
        final String digest = index.path("manifestSha256").textValue();
        final Path directory = root.resolve(STATE).resolve("receipts").resolve(digest);
        final byte[] manifestBytes =
                files.document(directory.resolve("manifest.json"), UpdateManifest.MAX_MANIFEST_BYTES);
        final byte[] envelopeBytes =
                files.document(directory.resolve("manifest.json.sig"), UpdateManifestVerifier.MAX_ENVELOPE_BYTES);
        if (!FrameworkUpdateTaskStore.hash(manifestBytes).equals(digest))
            throw new IOException("Receipt document digest mismatch");
        final UpdateManifest manifest = verifier.authenticate(manifestBytes, envelopeBytes);
        if (!manifest.version().equals(info.version())
                || !manifest.channel().equals(info.channel().wireName())
                || manifest.buildNumber() != info.buildNumber().orElseThrow()
                || !manifest.sourceRevision().equals(info.sourceRevision())
                || !manifest.targetPlatform().equals("windows-x64")) {
            throw new IOException("Signed receipt differs from loaded framework identity");
        }
        validateLayout(manifest);
        final Map<String, String> hashes = new TreeMap<>();
        long total = 0;
        for (final UpdateManifestEntry entry : manifest.entries()) {
            total += entry.bytes();
            if (total > 1024L * 1024 * 1024) throw new IOException("Receipt expanded size exceeds budget");
            files.verify(entry);
            hashes.put(entry.relativePath(), entry.sha256());
        }
        if (!root.resolve("turboism-agent.jar").equals(loadedArtifact.get())
                || !info.equals(loaded.get())
                || !rootIdentity.equals(files.rootIdentity())
                || !java.util.Arrays.equals(indexBytes, files.document(indexPath, 4096))
                || !java.util.Arrays.equals(
                        manifestBytes,
                        files.document(directory.resolve("manifest.json"), UpdateManifest.MAX_MANIFEST_BYTES))
                || !java.util.Arrays.equals(
                        envelopeBytes,
                        files.document(
                                directory.resolve("manifest.json.sig"), UpdateManifestVerifier.MAX_ENVELOPE_BYTES))) {
            throw new IOException("Installation receipt changed during verification");
        }
        final String binding =
                FrameworkUpdateTaskStore.hash((rootIdentity + "\n" + FrameworkUpdateTaskStore.hash(indexBytes) + "\n"
                                + digest + "\n" + FrameworkUpdateTaskStore.hash(envelopeBytes))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Evidence(root, info, hashes, binding);
    }

    private static Path runtimeArtifact() {
        try {
            final var source = FrameworkBuildInfo.class.getProtectionDomain().getCodeSource();
            if (source == null || !"file".equals(source.getLocation().getProtocol())) return null;
            return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (java.net.URISyntaxException | SecurityException failure) {
            return null;
        }
    }

    private static boolean text(final ObjectNode index, final String field, final String expected) {
        return index.path(field).isTextual()
                && expected.equals(index.path(field).textValue());
    }

    private static void validateLayout(final UpdateManifest manifest) throws IOException {
        final Set<String> paths = manifest.entries().stream()
                .map(UpdateManifestEntry::relativePath)
                .collect(java.util.stream.Collectors.toSet());
        if (!paths.containsAll(UpdateManifestEntry.FRAMEWORK_ROOT_FILES)
                || !paths.contains("graal/lib/sdk-" + manifest.version() + ".jar")
                || paths.stream()
                                .filter(p -> p.matches(
                                        "graal/lib/graal-host-" + java.util.regex.Pattern.quote(manifest.version())
                                                + "(?:-[A-Za-z0-9._-]+)?\\.jar"))
                                .count()
                        != 1) {
            throw new IOException("Receipt lacks complete standard ZIP framework layout");
        }
    }

    /** Immutable observation of this verification, never sufficient without a fresh load at use. */
    public static final class Evidence {
        private final Path root;
        private final FrameworkBuildInfo loaded;
        private final Map<String, String> hashes;
        private final String binding;

        private Evidence(
                final Path root,
                final FrameworkBuildInfo loaded,
                final Map<String, String> hashes,
                final String binding) {
            this.root = root;
            this.loaded = loaded;
            this.hashes = Map.copyOf(hashes);
            this.binding = binding;
        }

        Path root() {
            return root;
        }

        FrameworkBuildInfo loaded() {
            return loaded;
        }

        Map<String, String> hashes() {
            return hashes;
        }

        String binding() {
            return binding;
        }
    }
}

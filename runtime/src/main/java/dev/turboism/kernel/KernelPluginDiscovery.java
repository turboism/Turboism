package dev.turboism.kernel;

import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.descriptor.CubismVersionSidecar;
import dev.turboism.core.descriptor.CubismVersionSidecar.Read;
import dev.turboism.core.descriptor.DescriptorParseException;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.plugin.PluginJarContract;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.sdk.plugin.PluginDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Discovers valid preview plugin candidates with deterministic duplicate-ID selection. */
final class KernelPluginDiscovery {

    private static final String DESCRIPTOR_PATH = "META-INF/turboism/plugin.json";
    /** Never-shipped schema-v5 embedded contract artifact directory. */
    private static final String CONTRACT_DIRECTORY = "META-INF/turboism/contracts/";

    private static final PluginDescriptorParser DESCRIPTOR_PARSER = new PluginDescriptorParser();

    private static final PluginVersion TURBOISM_API_VERSION =
            FrameworkBuildInfo.current().apiVersion();

    private final Path pluginDirectory;
    private final KernelLog log;

    KernelPluginDiscovery(final Path pluginDirectory, final KernelLog log) {
        this.pluginDirectory = pluginDirectory;
        this.log = log;
    }

    // The winning candidate is skipped by identity: equal-value losers must still be reported.
    @SuppressWarnings("ReferenceEquality")
    Map<String, PreviewPluginCandidate> discover(final List<LocalPluginRuntime.PluginFailure> failures) {
        final Map<String, List<PreviewPluginCandidate>> grouped = new LinkedHashMap<>();
        try {
            final List<Path> jars = jarFiles();
            // toList preserves encounter order even when parsing completes out of order.
            final List<CandidateReadResult> results = (jars.size() > 1 ? jars.parallelStream() : jars.stream())
                    .map(this::readCandidateResult)
                    .toList();
            for (CandidateReadResult result : results) {
                failures.addAll(result.failures());
                result.logs().forEach(this::writeDiscoveryLog);
                final PreviewPluginCandidate candidate = result.candidate();
                if (candidate == null) {
                    continue;
                }
                grouped.computeIfAbsent(candidate.descriptor().id(), id -> new ArrayList<>())
                        .add(candidate);
            }
        } catch (IOException exception) {
            failures.add(new LocalPluginRuntime.PluginFailure(
                    "<discovery>", pluginDirectory, "PLUGIN_DIRECTORY_FAILED", exception.getMessage()));
            log.error("plugin-loader", "Plugin discovery failed", exception);
            return Map.of();
        }

        final Map<String, PreviewPluginCandidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, List<PreviewPluginCandidate>> entry : grouped.entrySet()) {
            final List<PreviewPluginCandidate> versions = entry.getValue();
            final PreviewPluginCandidate winner = preferred(versions);
            candidates.put(entry.getKey(), winner);
            for (PreviewPluginCandidate version : versions) {
                if (version == winner) continue;
                failures.add(new LocalPluginRuntime.PluginFailure(
                        version.descriptor().id(),
                        version.jar(),
                        "DUPLICATE_PLUGIN_ID",
                        "Plugin ID already provided by " + winner.jar().getFileName()));
            }
        }
        return candidates;
    }

    private List<Path> jarFiles() throws IOException {
        Files.createDirectories(pluginDirectory);
        try (var entries = Files.list(pluginDirectory)) {
            return entries.filter(this::isJar)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private boolean isJar(final Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static PreviewPluginCandidate preferred(final List<PreviewPluginCandidate> versions) {
        PreviewPluginCandidate best = versions.get(0);
        for (int index = 1; index < versions.size(); index++) {
            final PreviewPluginCandidate candidate = versions.get(index);
            if (preferredOver(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean preferredOver(
            final PreviewPluginCandidate candidate, final PreviewPluginCandidate previous) {
        final int versionComparison = PluginVersion.parse(candidate.descriptor().version())
                .compareTo(PluginVersion.parse(previous.descriptor().version()));
        if (versionComparison != 0) {
            return versionComparison > 0;
        }
        return candidate
                        .jar()
                        .getFileName()
                        .toString()
                        .compareTo(previous.jar().getFileName().toString())
                < 0;
    }

    PreviewPluginCandidate readCandidate(final Path jar, final List<LocalPluginRuntime.PluginFailure> failures) {
        return readCandidate(jar, failures, this::writeDiscoveryLog);
    }

    private CandidateReadResult readCandidateResult(final Path jar) {
        final List<LocalPluginRuntime.PluginFailure> failures = new ArrayList<>();
        final List<DiscoveryLog> logs = new ArrayList<>();
        final PreviewPluginCandidate candidate = readCandidate(jar, failures, logs::add);
        return new CandidateReadResult(candidate, List.copyOf(failures), List.copyOf(logs));
    }

    private PreviewPluginCandidate readCandidate(
            final Path jar, final List<LocalPluginRuntime.PluginFailure> failures, final Consumer<DiscoveryLog> logs) {
        try (JarFile archive = new JarFile(jar.toFile())) {
            final JarEntry entry = archive.getJarEntry(DESCRIPTOR_PATH);
            if (entry == null || entry.isDirectory()) {
                failures.add(new LocalPluginRuntime.PluginFailure(
                        "<unknown>", jar, "PLUGIN_DESCRIPTOR_MISSING", DESCRIPTOR_PATH + " is required"));
                return null;
            }
            return parseCandidate(archive, entry, jar, failures, logs);
        } catch (DescriptorParseException exception) {
            failures.add(
                    new LocalPluginRuntime.PluginFailure("<invalid>", jar, exception.code(), exception.getMessage()));
        } catch (IOException | RuntimeException exception) {
            failures.add(new LocalPluginRuntime.PluginFailure(
                    "<invalid>", jar, "PLUGIN_JAR_READ_FAILED", exception.getMessage()));
        }
        return null;
    }

    private PreviewPluginCandidate parseCandidate(
            final JarFile archive,
            final JarEntry entry,
            final Path jar,
            final List<LocalPluginRuntime.PluginFailure> failures,
            final Consumer<DiscoveryLog> logs)
            throws IOException, DescriptorParseException {
        try (InputStream source = archive.getInputStream(entry)) {
            final PluginDescriptor descriptor = DESCRIPTOR_PARSER.parse(source);
            if (dev.turboism.internal.core.CorePluginManagement.CORE_PLUGIN_ID.equals(descriptor.id())) {
                failures.add(new LocalPluginRuntime.PluginFailure(
                        descriptor.id(),
                        jar,
                        "PLUGIN_RESERVED_ID",
                        "External plugin packages cannot declare the Runtime-owned core ID."));
                return null;
            }
            final Set<String> names = new HashSet<>();
            boolean hasContractArtifacts = false;
            final var entries = archive.entries();
            while (entries.hasMoreElements()) {
                final JarEntry candidate = entries.nextElement();
                if (!candidate.isDirectory()) {
                    final String name = candidate.getName();
                    names.add(name);
                    hasContractArtifacts |= name.startsWith(CONTRACT_DIRECTORY);
                }
            }
            // validate's Set.copyOf can reuse this immutable set without another content copy.
            final Set<String> entryNames = Set.copyOf(names);
            if (!descriptor.eventContracts().isEmpty() || hasContractArtifacts) {
                // Embedded public-event-contract artifacts were never shipped and are
                // not supported: the descriptor declaration and any embedded artifact
                // both fail discovery outright, same verdict as managed admission.
                failures.add(new LocalPluginRuntime.PluginFailure(
                        descriptor.id(),
                        jar,
                        "PLUGIN_CONTRACT_ARTIFACTS_REMOVED",
                        "Embedded public-event-contract artifacts were never shipped and are not supported"));
                return null;
            }
            try {
                PluginJarContract.validate(descriptor, entryNames, jar.toString());
            } catch (PluginJarContract.PluginJarContractException exception) {
                failures.add(
                        new LocalPluginRuntime.PluginFailure(descriptor.id(), jar, exception.code(), exception.path()));
                return null;
            }
            if (!supportsCurrentApi(descriptor)) {
                failures.add(new LocalPluginRuntime.PluginFailure(
                        descriptor.id(),
                        jar,
                        "TURBOISM_API_INCOMPATIBLE",
                        "Plugin requires Turboism API " + descriptor.turboismApi() + ", runtime is "
                                + TURBOISM_API_VERSION));
                return null;
            }
            noteCubismVersionDeclaration(archive, descriptor, logs);
            return new PreviewPluginCandidate(jar.toAbsolutePath().normalize(), descriptor);
        }
    }

    private static boolean supportsCurrentApi(final PluginDescriptor descriptor) {
        try {
            return VersionRange.parse(descriptor.turboismApi()).contains(TURBOISM_API_VERSION);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Reports the plugin's Cubism Editor version declaration state. A plugin that declares
     * {@code requiresCubism} but ships no {@code cubism-versions.json} sidecar is treated as
     * undeclared: it still loads (its SDK calls stay gated by @CubismEditor at invocation
     * time), and the absence is logged once so the gap is visible instead of silent. A
     * malformed sidecar degrades to a warning the same way; neither case fails admission.
     */
    private void noteCubismVersionDeclaration(
            final JarFile archive, final PluginDescriptor descriptor, final Consumer<DiscoveryLog> logs) {
        if (!descriptor.environment().requiresCubism()) {
            return;
        }
        final Read declaration = CubismVersionSidecar.read(archive, descriptor.id());
        if (declaration instanceof Read.Absent) {
            logs.accept(new DiscoveryLog(
                    false,
                    "Plugin " + descriptor.id() + " declares no Cubism Editor version support ("
                            + CubismVersionSidecar.ENTRY + " absent); treated as undeclared — see "
                            + "compatibility/cubism/compatibility-matrix.json for derived support"));
        } else if (declaration instanceof Read.Malformed) {
            logs.accept(new DiscoveryLog(
                    true,
                    "Plugin " + descriptor.id() + " ships an unreadable " + CubismVersionSidecar.ENTRY
                            + "; treated as undeclared"));
        }
    }

    private void writeDiscoveryLog(final DiscoveryLog entry) {
        if (entry.warning()) {
            log.warn("plugin-loader", entry.message());
        } else {
            log.info("plugin-loader", entry.message());
        }
    }

    private record DiscoveryLog(boolean warning, String message) {}

    private record CandidateReadResult(
            PreviewPluginCandidate candidate,
            List<LocalPluginRuntime.PluginFailure> failures,
            List<DiscoveryLog> logs) {}
}

record PreviewPluginCandidate(Path jar, PluginDescriptor descriptor) {}

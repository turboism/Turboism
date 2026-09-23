package dev.turboism.core.event;

import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Session-scoped catalog binding published public event contract artifacts to shared
 * class loaders.
 *
 * <p>Every plugin that participates in a third-party public event route embeds the exact
 * same published contract artifact under {@code META-INF/turboism/contracts/} and declares
 * it in {@code eventContracts}. {@link #acquire(PluginDescriptor, Path)} validates the
 * declared artifacts against the plugin JAR, binds them by content hash to one
 * {@link ContractArtifactClassLoader} per artifact and returns a lease; all plugins bound
 * to the same bytes share the contract's {@link Class} identity. Bindings are reference
 * counted: a contract loader closes when its last lease releases, allowing a later
 * generation to bind a different artifact. A still-leased binding survives
 * {@link #close()} — retained plugin generations may still execute against its classes —
 * and is released normally when the final lease lets go.</p>
 *
 * <p>Conflicts are deterministic admission failures: the same contract id bound to
 * different bytes, or a member class already owned by another bound artifact, rejects the
 * incoming plugin with a diagnostic naming both sides.</p>
 */
public final class PublicEventContractCatalog implements AutoCloseable {

    /** Plugin JAR directory that carries embedded contract artifacts. */
    public static final String CONTRACT_DIRECTORY = "META-INF/turboism/contracts/";

    /** Test seam: how the catalog materializes a loader for verified artifact bytes. */
    @FunctionalInterface
    interface ContractLoaderFactory {
        ContractArtifactClassLoader open(URL artifact, Set<String> classNames)
            throws IOException;
    }

    private final Path extractionDir;
    private final ContractLoaderFactory loaderFactory;
    private final Map<String, BoundContract> contractsById = new HashMap<>();
    private final Map<String, BoundContract> contractsByHash = new HashMap<>();
    private final Map<String, BoundContract> contractClasses = new HashMap<>();
    /**
     * Failed disposals leave the healthy lookup maps but stay referenced here for the
     * rest of the session: the quarantined binding keeps its loader and its recorded
     * first failure, is never re-closed, and is never handed out by {@link #acquire}.
     */
    private final List<BoundContract> quarantined = new ArrayList<>();
    private boolean closed;

    public PublicEventContractCatalog(final Path extractionDir) {
        this(extractionDir, ContractArtifactClassLoader::new);
    }

    PublicEventContractCatalog(
        final Path extractionDir,
        final ContractLoaderFactory loaderFactory
    ) {
        this.extractionDir = Objects.requireNonNull(extractionDir, "extractionDir");
        this.loaderFactory = Objects.requireNonNull(loaderFactory, "loaderFactory");
    }

    /**
     * Binds the declared contract artifacts of one plugin load attempt.
     *
     * @param descriptor the plugin's validated descriptor
     * @param pluginJar the plugin JAR the declared artifacts are embedded in
     * @return a lease whose delegates map contract member names to bound loaders; release
     *     it when the plugin class loader closes
     * @throws IllegalArgumentException when an artifact is missing, mismatched, malformed
     *     or conflicts with a session binding
     * @throws IllegalStateException when the catalog is closed or artifacts cannot be read
     */
    public synchronized ContractLease acquire(
        final PluginDescriptor descriptor,
        final Path pluginJar
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(pluginJar, "pluginJar");
        if (closed) {
            throw new IllegalStateException("public event contract catalog is closed");
        }
        if (descriptor.eventContracts().isEmpty()) {
            return ContractLease.EMPTY;
        }
        final List<ArtifactSpec> specs = readDeclaredArtifacts(descriptor, pluginJar);
        verifyNoConflicts(descriptor, specs);
        final List<BoundContract> bound = new ArrayList<>(specs.size());
        try {
            for (final ArtifactSpec spec : specs) {
                BoundContract contract = contractsByHash.get(spec.sha256());
                if (contract == null) {
                    contract = new BoundContract(
                        spec.id(),
                        spec.sha256(),
                        extract(spec),
                        spec.classNames()
                    );
                    contractsByHash.put(spec.sha256(), contract);
                }
                contractsById.putIfAbsent(spec.id(), contract);
                for (final String className : spec.classNames()) {
                    contractClasses.put(className, contract);
                }
                contract.leases++;
                bound.add(contract);
            }
        } catch (RuntimeException failure) {
            for (final BoundContract contract : bound) {
                try {
                    releaseLocked(contract);
                } catch (IOException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            throw failure;
        }
        return new ContractLease(this, bound);
    }

    /**
     * @return the bound contract loader owning {@code binaryName}, or {@code null} when the
     *     name is not a contract member
     */
    public synchronized ClassLoader contractLoaderFor(final String binaryName) {
        final BoundContract contract = contractClasses.get(binaryName);
        return contract == null ? null : contract.loader;
    }

    /**
     * @return {@code true} when {@code binaryName} is a member of a bound contract artifact
     */
    public synchronized boolean isContractBound(final String binaryName) {
        return contractClasses.containsKey(binaryName);
    }

    /**
     * Retires admission. Unleased bindings close immediately; leased bindings stay fully
     * usable for retained generations and close when their last lease releases. A
     * disposal failure on an unleased binding has no releasing caller to surface
     * through, so it is reported to {@link RuntimeDiagnostics}; the failed binding is
     * still retired from every lookup.
     */
    @Override
    public synchronized void close() {
        closed = true;
        for (final BoundContract contract : Set.copyOf(contractsByHash.values())) {
            if (contract.leases == 0) {
                try {
                    closeBinding(contract);
                } catch (IOException reported) {
                    // closeBinding already diagnosed; admission retirement continues.
                }
            }
        }
    }

    /**
     * @return the number of bindings quarantined after a failed disposal; they remain
     *     referenced (with their recorded failure) for the rest of the session
     */
    synchronized int quarantinedBindingCount() {
        return quarantined.size();
    }

    private void releaseLocked(final BoundContract contract) throws IOException {
        if (contract.leases > 0) {
            contract.leases--;
        }
        if (contract.leases == 0) {
            closeBinding(contract);
        }
    }

    /**
     * Disposes one fully released binding. The outcome is sticky: {@code closeAttempted}
     * never resets, a repeated close rethrows the recorded first failure instead of
     * reporting an empty success, and the binding leaves every lookup map whether or
     * not the loader closed cleanly — a failed binding is never handed out by
     * {@link #acquire} again; the next acquire binds a fresh generation. The disposal
     * failure propagates to the releasing caller (the plugin class loader's close
     * path, which owns the lifecycle failure reporting) and is also reported through
     * the process-wide {@link RuntimeDiagnostics} channel.
     */
    private void closeBinding(final BoundContract contract) throws IOException {
        if (contract.closeAttempted) {
            if (contract.closeFailure != null) {
                throw contract.closeFailure;
            }
            return;
        }
        contract.closeAttempted = true;
        try {
            contract.loader.close();
        } catch (IOException failure) {
            contract.closeFailure = failure;
            quarantined.add(contract);
            RuntimeDiagnostics.error(
                "dev.turboism.core.event.PublicEventContractCatalog",
                "failed to close public event contract " + contract.contractId
                    + " (sha256 " + contract.sha256
                    + "); the failed binding is quarantined and will not be reused",
                failure
            );
            throw failure;
        } finally {
            contractsByHash.remove(contract.sha256, contract);
            contractsById.values().removeIf(bound -> bound == contract);
            contract.classNames.forEach(contractClasses::remove);
        }
    }

    private List<ArtifactSpec> readDeclaredArtifacts(
        final PluginDescriptor descriptor,
        final Path pluginJar
    ) {
        final List<ArtifactSpec> specs = new ArrayList<>();
        try (JarFile jar = new JarFile(pluginJar.toFile())) {
            final Set<String> declaredArtifacts = new HashSet<>();
            final List<String> looseClasses = new ArrayList<>();
            final Deque<JarEntry> entries = new ArrayDeque<>();
            jar.stream().forEach(entries::add);
            for (final JarEntry entry : entries) {
                final String name = entry.getName();
                if (name.startsWith(CONTRACT_DIRECTORY) && name.endsWith(".jar")) {
                    declaredArtifacts.add(name);
                } else if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                    looseClasses.add(PublicEventContractPreflight.binaryName(name));
                }
            }
            for (final PluginDescriptor.EventContract contract : descriptor.eventContracts()) {
                final String artifact = contract.artifact();
                final JarEntry entry = jar.getJarEntry(artifact);
                if (entry == null) {
                    throw new IllegalArgumentException(
                        "plugin " + descriptor.id() + " declares event contract "
                            + contract.id() + " at " + artifact
                            + " but the plugin JAR does not contain that artifact"
                    );
                }
                declaredArtifacts.remove(artifact);
                specs.add(readArtifact(descriptor, contract, jar, entry, looseClasses));
            }
            if (!declaredArtifacts.isEmpty()) {
                throw new IllegalArgumentException(
                    "plugin " + descriptor.id() + " JAR contains undeclared public event"
                        + " contract artifact " + declaredArtifacts.iterator().next()
                );
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                "failed to read event contract artifacts from " + pluginJar,
                failure
            );
        }
        return specs;
    }

    /**
     * Re-verifies one declared contract artifact at bind time through the shared
     * no-execution preflight — the same rules managed admission already enforced —
     * so a plugin JAR staged before the rules applied, or assembled outside the
     * managed paths, cannot smuggle an artifact the catalog never re-checked.
     */
    private ArtifactSpec readArtifact(
        final PluginDescriptor descriptor,
        final PluginDescriptor.EventContract contract,
        final JarFile pluginJar,
        final JarEntry entry,
        final List<String> looseClasses
    ) throws IOException {
        if (entry.getSize() > PublicEventContractPreflight.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(
                "public event contract " + contract.id() + " artifact exceeds the "
                    + PublicEventContractPreflight.MAX_ARTIFACT_BYTES + " byte limit"
            );
        }
        final byte[] bytes;
        try (InputStream stream = pluginJar.getInputStream(entry)) {
            bytes = stream.readAllBytes();
        }
        final PublicEventContractPreflight.Inspection inspection;
        try {
            inspection = PublicEventContractPreflight.verify(
                descriptor.id(),
                contract.id(),
                contract.artifact(),
                contract.sha256(),
                bytes,
                looseClasses,
                payloadSeeds(descriptor)
            );
        } catch (final PublicEventContractPreflight.ContractViolation violation) {
            throw new IllegalArgumentException(violation.getMessage(), violation);
        }
        return new ArtifactSpec(
            contract.id(),
            contract.version(),
            inspection.sha256(),
            bytes,
            inspection.classNames()
        );
    }

    /**
     * The event types this descriptor pins for route binding; artifact members among
     * them anchor the payload closure walk during preflight.
     */
    private static Set<String> payloadSeeds(final PluginDescriptor descriptor) {
        final Set<String> seeds = new HashSet<>();
        descriptor.eventExports().forEach(export -> seeds.add(export.eventType()));
        descriptor.eventImports().forEach(imports -> seeds.add(imports.eventType()));
        return seeds;
    }

    private void verifyNoConflicts(
        final PluginDescriptor descriptor,
        final List<ArtifactSpec> specs
    ) {
        final Set<String> acquiredClasses = new HashSet<>();
        for (final ArtifactSpec spec : specs) {
            final BoundContract existing = contractsById.get(spec.id());
            if (existing != null && !existing.sha256.equals(spec.sha256())) {
                throw new IllegalArgumentException(
                    "public event contract " + spec.id() + " is already bound to a"
                        + " different artifact (declared sha256 " + spec.sha256()
                        + ", bound sha256 " + existing.sha256 + ")"
                );
            }
            for (final String className : spec.classNames()) {
                final BoundContract owner = contractClasses.get(className);
                if (owner != null && !owner.sha256.equals(spec.sha256())) {
                    throw new IllegalArgumentException(
                        "public event contract type " + className
                            + " is already provided by contract " + owner.contractId
                    );
                }
                if (!acquiredClasses.add(className)) {
                    throw new IllegalArgumentException(
                        "public event contract type " + className
                            + " is declared by more than one artifact of plugin "
                            + descriptor.id()
                    );
                }
            }
        }
    }

    private ContractArtifactClassLoader extract(final ArtifactSpec spec) {
        try {
            Files.createDirectories(extractionDir);
            final Path target = extractionDir.resolve(spec.sha256() + ".jar");
            if (!cachedArtifactMatches(target, spec.sha256())) {
                final Path temp = Files.createTempFile(
                    extractionDir, "contract-", ".jar.tmp"
                );
                Files.write(temp, spec.bytes());
                try {
                    Files.move(
                        temp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (IOException moveFailure) {
                    Files.deleteIfExists(temp);
                    throw moveFailure;
                }
            }
            return loaderFactory.open(
                target.toUri().toURL(),
                spec.classNames()
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                "failed to extract public event contract " + spec.id(),
                failure
            );
        }
    }

    /**
     * The extraction cache is trusted only after re-verification: an existing file whose
     * bytes do not hash to the declared {@code sha256} is treated as corrupt and replaced
     * atomically with the verified embedded bytes.
     */
    private static boolean cachedArtifactMatches(final Path target, final String sha256) {
        try {
            if (!Files.isRegularFile(target)) {
                return false;
            }
            return sha256Hex(Files.readAllBytes(target)).equals(sha256);
        } catch (IOException failure) {
            return false;
        }
    }

    private static String sha256Hex(final byte[] bytes) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
        final byte[] hash = digest.digest(bytes);
        final StringBuilder hex = new StringBuilder(hash.length * 2);
        for (final byte value : hash) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    private record ArtifactSpec(
        String id,
        String version,
        String sha256,
        byte[] bytes,
        Set<String> classNames
    ) {
    }

    private static final class BoundContract {
        private final String contractId;
        private final String sha256;
        private final ContractArtifactClassLoader loader;
        private final Set<String> classNames;
        private int leases;
        private boolean closeAttempted;
        private IOException closeFailure;

        private BoundContract(
            final String contractId,
            final String sha256,
            final ContractArtifactClassLoader loader,
            final Set<String> classNames
        ) {
            this.contractId = contractId;
            this.sha256 = sha256;
            this.loader = loader;
            this.classNames = classNames;
        }
    }

    /**
     * One plugin generation's hold on a set of bound contract artifacts. Releasing the
     * lease decrements each binding's reference count; the last release closes the
     * contract loader and frees the contract id for rebinding.
     */
    public static final class ContractLease implements AutoCloseable {

        private static final ContractLease EMPTY = new ContractLease(null, List.of());

        /** @return a lease holding no contract bindings */
        public static ContractLease empty() {
            return EMPTY;
        }

        private final PublicEventContractCatalog catalog;
        private final List<BoundContract> contracts;
        private final Map<String, ClassLoader> delegates;
        private boolean released;
        private IOException firstFailure;

        private ContractLease(
            final PublicEventContractCatalog catalog,
            final List<BoundContract> contracts
        ) {
            this.catalog = catalog;
            this.contracts = List.copyOf(contracts);
            final Map<String, ClassLoader> map = new HashMap<>();
            for (final BoundContract contract : contracts) {
                for (final String className : contract.classNames) {
                    map.put(className, contract.loader);
                }
            }
            this.delegates = Map.copyOf(map);
        }

        /** @return contract member binary names mapped to their bound contract loaders */
        public Map<String, ClassLoader> delegates() {
            return delegates;
        }

        /** @return {@code true} when no contract artifacts were bound */
        public boolean isEmpty() {
            return contracts.isEmpty();
        }

        /**
         * Releases every bound contract exactly once and surfaces disposal failures.
         * The outcome is sticky: a first close that failed rethrows the same recorded
         * {@link IOException} on every retry, so an empty retry can never erase an
         * unproven disposal. When several bindings fail, later failures are
         * suppressed onto the first; every binding is still released.
         */
        @Override
        public void close() throws IOException {
            synchronized (this) {
                if (released) {
                    if (firstFailure != null) {
                        throw firstFailure;
                    }
                    return;
                }
                released = true;
                if (catalog == null) {
                    return;
                }
                IOException failure = null;
                synchronized (catalog) {
                    for (final BoundContract contract : contracts) {
                        try {
                            catalog.releaseLocked(contract);
                        } catch (IOException releaseFailure) {
                            if (failure == null) {
                                failure = releaseFailure;
                            } else {
                                failure.addSuppressed(releaseFailure);
                            }
                        }
                    }
                }
                firstFailure = failure;
                if (failure != null) {
                    throw failure;
                }
            }
        }
    }
}

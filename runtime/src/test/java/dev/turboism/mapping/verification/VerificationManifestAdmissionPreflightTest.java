package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline admission preflight for the pinned verification trust roots.
 *
 * <p>Replays everything {@link PinnedVerifiedResolverWorkflow} can decide without a host
 * artifact — record schema, the record-byte pin, manifest field equality and
 * capability/alias coverage — for every generated {@code *VerificationManifest} and every
 * record under {@code compatibility/cubism/verification}. This is the check that would
 * have caught the pre-7ade4e071 ui-main-toolbar wall (records missing the
 * {@code ui.mesh-toolbar.contribute} capability their manifest requires) without a single
 * real-host launch. Run it alone via {@code ./gradlew :runtime:admissionPreflightTest} or
 * {@code ./gradlew :runtime:test --tests '*AdmissionPreflight*'}.
 */
final class VerificationManifestAdmissionPreflightTest {

    private static final Path RECORD_DIR =
            EditorSelectorContractTestPaths.projectRoot().resolve("compatibility/cubism/verification");
    private static final String MANIFEST_PACKAGE = "dev.turboism.mapping.verification";
    private static final java.util.regex.Pattern RECORD_FILE =
            java.util.regex.Pattern.compile("cubism-\\d+\\.\\d+\\.\\d+-.+\\.json");

    /**
     * Slices whose records are deliberately not admitted through
     * {@link PinnedVerifiedResolverWorkflow}: {@code core-model-read} binds through
     * {@code CorePublicApiTrustRoots} and the structural compatibility path, and
     * {@code performance-render-scene} through the FPS probe targets. Any other record
     * left unclaimed by a generated manifest is a preflight failure, so a new family
     * cannot silently sit outside the pinned path.
     */
    private static final Set<String> UNPINNED_SLICE_IDS =
            Set.of("adapter.core-model.readonly", "adapter.cubism.performance.fps-hook");

    private final ObjectMapper mapper = new ObjectMapper();
    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();
    private final StaticVerificationRecordLoader loader = new StaticVerificationRecordLoader();

    @TempDir
    Path tempDir;

    @Test
    void everyReviewedRecordMatchesItsGeneratedManifest() throws Exception {
        final List<ManifestEntry> manifests = manifestIndex();
        assertFalse(manifests.isEmpty(), "no generated *VerificationManifest classes were found on the classpath");
        final Map<String, Loaded> byVerificationId = loadRecords();
        final List<HostArtifactDigest> reviewedArtifacts = reviewedArtifacts();
        final List<String> failures = new ArrayList<>();
        final Set<String> checked = new HashSet<>();
        final Set<String> claimedVerificationIds = new HashSet<>();
        final Map<String, Integer> admittedPerManifest = new LinkedHashMap<>();

        // Direction 1: every artifact a manifest pins must resolve to a record file on
        // disk that passes the production record gate and authorization predicates.
        for (final ManifestEntry entry : manifests) {
            for (final HostArtifactDigest artifact : reviewedArtifacts) {
                final PinnedVerifiedResolverWorkflow.Manifest manifest = forArtifact(entry, artifact, failures);
                if (manifest == null) {
                    continue;
                }
                admittedPerManifest.merge(entry.name(), 1, Integer::sum);
                claimedVerificationIds.add(manifest.verificationId());
                final String label = manifest.cubismVersion() + " x " + entry.name();
                final Loaded loaded = byVerificationId.get(manifest.verificationId());
                if (loaded == null) {
                    failures.add(label + ": no record under " + RECORD_DIR.getFileName()
                            + " declares pinned verificationId " + manifest.verificationId());
                    continue;
                }
                if (checked.add(loaded.name() + "|" + entry.name())) {
                    checkRecordAgainstManifest(loaded, manifest, label, failures);
                }
            }
        }

        // Direction 2: every record file must be claimed — pinned by the manifest that
        // resolved its artifact — or belong to a slice deliberately admitted outside
        // the pinned workflow. Anything else cannot silently sit outside the gate.
        for (final Loaded loaded : byVerificationId.values()) {
            if (!claimedVerificationIds.contains(loaded.record().verificationId())
                    && !UNPINNED_SLICE_IDS.contains(loaded.record().adapterSliceId())) {
                failures.add(loaded.name() + ": record is not pinned by any generated manifest "
                        + "(adapterSliceId " + loaded.record().adapterSliceId()
                        + "); bind it or register the slice as unpinned");
            }
        }
        for (final ManifestEntry entry : manifests) {
            if (admittedPerManifest.getOrDefault(entry.name(), 0) == 0) {
                failures.add(entry.name() + ": manifest admits no reviewed artifact; record coverage is vacuous");
            }
        }
        assertFalse(checked.isEmpty(), "no record x manifest pairs were exercised; the preflight is vacuous");
        if (!failures.isEmpty()) {
            fail("offline admission preflight found " + failures.size() + " mismatch(es):\n  - "
                    + String.join("\n  - ", failures));
        }
    }

    @Test
    void recordMissingManifestCapabilityFailsLikeTheMeshToolbarRegression() throws Exception {
        // Hermetic replay of the pre-7ade4e071 wall: the ui-main-toolbar records
        // predating MeshToolbarSelectorContract lacked the ui.mesh-toolbar.contribute
        // capability the manifest requires. Re-pinning the stripped bytes is exactly
        // how that broken state looked: a consistent sha pin over mismatched fields.
        final Path source = RECORD_DIR.resolve("cubism-5.2.01-ui-main-toolbar.json");
        final byte[] mutated = removeCapability(source, dev.turboism.ui.mesh.MeshToolbarSelectorContract.CAPABILITY_ID);
        final Path copy = tempDir.resolve(source.getFileName().toString());
        Files.write(copy, mutated);

        final StaticVerificationRecord record = loader.load(copy).record();
        final PinnedVerifiedResolverWorkflow.Manifest reviewed =
                MainToolbarVerificationManifest.forArtifact(new HostArtifactDigest(
                        record.artifact().size(), record.artifact().sha256()));
        final PinnedVerifiedResolverWorkflow.Manifest repinned = repin(reviewed, sha256(mutated));

        final IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> workflow.requireReviewedRecord(copy, repinned));
        assertEquals("verification record fields do not match the reviewed manifest", failure.getMessage());
    }

    @Test
    void recordMissingManifestSelectorAliasFailsAuthorization() throws Exception {
        // The same regression, one step later in create(): a record that keeps the
        // capability list but drops the selectors the manifest requires must fail the
        // authorization predicates instead of failing on real hosts.
        final Path source = RECORD_DIR.resolve("cubism-5.2.01-ui-main-toolbar.json");
        final byte[] mutated = removeAliases(source, dev.turboism.ui.mesh.MeshToolbarSelectorContract.REQUIRED_ALIASES);
        final Path copy = tempDir.resolve(source.getFileName().toString());
        Files.write(copy, mutated);

        final StaticVerificationRecordLoader.LoadedRecord loaded = loader.load(copy);
        final StaticVerificationRecord record = loaded.record();
        final PinnedVerifiedResolverWorkflow.Manifest reviewed =
                MainToolbarVerificationManifest.forArtifact(new HostArtifactDigest(
                        record.artifact().size(), record.artifact().sha256()));
        workflow.requireReviewedRecord(copy, repin(reviewed, loaded.sha256()));

        final VerifiedAccessPlan plan = VerifiedAccessPlan.from(record, fullyVerifiedReport(record));
        assertFalse(
                plan.authorizesFeatureSet(
                        reviewed.adapterSliceId(), reviewed.capabilityIds(), reviewed.requiredAliases()),
                "a record missing manifest-required aliases must fail feature-set authorization");
        assertFalse(
                plan.authorizes(reviewed.adapterSliceId(), reviewed.capabilityIds(), reviewed.requiredAliases()),
                "a record missing manifest-required aliases must fail exact authorization");
    }

    /**
     * Runs the production record gate ({@code requireReviewedRecord} — schema, byte pin,
     * field equality) and the production authorization predicates
     * ({@code authorizes}/{@code authorizesFeatureSet}) against a plan built from a
     * synthetic all-verified report, the strongest evidence an offline check can produce
     * without the reviewed jar.
     */
    private void checkRecordAgainstManifest(
            final Loaded loaded,
            final PinnedVerifiedResolverWorkflow.Manifest manifest,
            final String label,
            final List<String> failures) {
        try {
            workflow.requireReviewedRecord(loaded.path(), manifest);
        } catch (final IOException | IllegalArgumentException failure) {
            failures.add(label + ": " + describeFieldMismatch(loaded, manifest, failure));
            return;
        }
        final VerifiedAccessPlan plan;
        try {
            plan = VerifiedAccessPlan.from(loaded.record(), fullyVerifiedReport(loaded.record()));
        } catch (final IllegalArgumentException failure) {
            failures.add(label + ": verified plan construction failed: " + failure.getMessage());
            return;
        }
        if (plan.authorizes(manifest.adapterSliceId(), manifest.capabilityIds(), manifest.requiredAliases())) {
            return;
        }
        if (!plan.authorizesFeatureSet(
                manifest.adapterSliceId(), manifest.capabilityIds(), manifest.requiredAliases())) {
            failures.add(label + ": record does not cover the manifest (missing aliases "
                    + difference(manifest.requiredAliases(), selectorAliases(loaded.record()))
                    + ", missing capabilities "
                    + difference(
                            manifest.capabilityIds(), Set.copyOf(loaded.record().capabilityIds())) + ")");
            return;
        }
        if (!isRuntimeScopedAdmission(manifest)) {
            failures.add(label + ": record exceeds the manifest (extra aliases "
                    + difference(selectorAliases(loaded.record()), manifest.requiredAliases())
                    + "); unscoped admission fails closed, and this record is not admitted "
                    + "through a runtime scope");
        }
    }

    /**
     * Whether production admits this manifest through a runtime scope — currently only
     * {@link VerifiedEditorModelResolverFactory} scopes exact Cubism 5.3.03 to a subset of
     * its record, so strict alias equality is not required there. Mirrors the artifact
     * check that factory performs; if another family grows a scoped admission this
     * fail-closed list must be revisited.
     */
    private static boolean isRuntimeScopedAdmission(final PinnedVerifiedResolverWorkflow.Manifest manifest) {
        return manifest.adapterSliceId().equals(EditorModelVerificationManifest.ADAPTER_SLICE_ID)
                && ReviewedHostArtifacts.CUBISM_5_3_03.size() == manifest.artifactSize()
                && ReviewedHostArtifacts.CUBISM_5_3_03.sha256().equals(manifest.artifactSha256());
    }

    /**
     * A {@link StaticVerificationReport} that stands in for {@code verifier.verify} where
     * every reviewed selector resolved {@code VERIFIED_STATIC}.
     * {@code VerifiedAccessPlan.from} still validates coverage, selector equality and
     * fingerprints, so malformed records (duplicate aliases, missing results) still fail
     * here.
     */
    private static StaticVerificationReport fullyVerifiedReport(final StaticVerificationRecord record) {
        return new StaticVerificationReport(
                record.artifact(),
                new HostArtifactFingerprint(
                        record.cubismVersion(),
                        record.artifact().size(),
                        record.artifact().sha256()),
                true,
                record.selectors().stream()
                        .map(selector -> new StaticSelectorResult(
                                selector,
                                StaticVerificationStatus.VERIFIED_STATIC,
                                "admission preflight assumes the reviewed artifact"))
                        .toList());
    }

    /** Names the specific manifest fields the record fails, for actionable reports. */
    private static String describeFieldMismatch(
            final Loaded loaded, final PinnedVerifiedResolverWorkflow.Manifest manifest, final Exception failure) {
        final StaticVerificationRecord record = loaded.record();
        final List<String> diffs = new ArrayList<>();
        if (!loaded.sha256().equals(manifest.recordSha256())) {
            diffs.add("recordSha256");
        }
        if (!record.verificationId().equals(manifest.verificationId())) {
            diffs.add("verificationId");
        }
        if (!record.adapterSliceId().equals(manifest.adapterSliceId())) {
            diffs.add("adapterSliceId");
        }
        if (!record.cubismVersion().equals(manifest.cubismVersion())) {
            diffs.add("cubismVersion");
        }
        if (!record.profileId().equals(manifest.profileId())) {
            diffs.add("profileId");
        }
        final Set<String> recordCapabilities = Set.copyOf(record.capabilityIds());
        if (!recordCapabilities.equals(manifest.capabilityIds())) {
            diffs.add("capabilityIds (missing " + difference(manifest.capabilityIds(), recordCapabilities)
                    + ", unexpected " + difference(recordCapabilities, manifest.capabilityIds()) + ")");
        }
        if (record.artifact().size() != manifest.artifactSize()) {
            diffs.add("artifact.size");
        }
        if (!record.artifact().sha256().equals(manifest.artifactSha256())) {
            diffs.add("artifact.sha256");
        }
        return failure.getMessage() + (diffs.isEmpty() ? "" : " [fields: " + String.join(", ", diffs) + "]");
    }

    private static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
            final ManifestEntry entry, final HostArtifactDigest digest, final List<String> failures) {
        try {
            return (PinnedVerifiedResolverWorkflow.Manifest) entry.forArtifact().invoke(null, digest);
        } catch (final InvocationTargetException exception) {
            if (!(exception.getCause() instanceof IllegalArgumentException)) {
                failures.add(entry.name() + ": forArtifact threw " + exception.getCause());
            }
            return null;
        } catch (final IllegalAccessException exception) {
            throw new IllegalStateException("manifest forArtifact is not accessible", exception);
        }
    }

    /** Every reviewed artifact identity declared by production, for the pin matrix. */
    private static List<HostArtifactDigest> reviewedArtifacts() throws Exception {
        final List<HostArtifactDigest> artifacts = new ArrayList<>();
        for (final Field field : ReviewedHostArtifacts.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && HostArtifactDigest.class.equals(field.getType())) {
                artifacts.add((HostArtifactDigest) field.get(null));
            }
        }
        assertFalse(artifacts.isEmpty(), "no reviewed host artifact constants were found");
        return artifacts;
    }

    /**
     * All generated {@code *VerificationManifest} classes on the test classpath that carry
     * a pinned record surface: the {@code forArtifact} factory
     * {@code SliceResolverFactory} implementations call. Manifests without it (validation
     * scopes, helper contracts) are not record-bound and are skipped.
     */
    private static List<ManifestEntry> manifestIndex() throws Exception {
        final Set<String> classNames = new TreeSet<>();
        final String packageDir = MANIFEST_PACKAGE.replace('.', '/');
        for (final String element : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            final Path directory = Path.of(element).resolve(packageDir);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> files = Files.list(directory)) {
                files.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith("VerificationManifest.class") && !name.contains("$"))
                        .forEach(name -> classNames.add(
                                MANIFEST_PACKAGE + "." + name.substring(0, name.length() - ".class".length())));
            }
        }
        final List<ManifestEntry> manifests = new ArrayList<>();
        for (final String className : classNames) {
            final Class<?> type = Class.forName(className);
            final Method forArtifact;
            try {
                forArtifact = type.getDeclaredMethod("forArtifact", HostArtifactDigest.class);
            } catch (final NoSuchMethodException exception) {
                continue;
            }
            if (!Modifier.isStatic(forArtifact.getModifiers())
                    || !PinnedVerifiedResolverWorkflow.Manifest.class.equals(forArtifact.getReturnType())) {
                continue;
            }
            forArtifact.setAccessible(true);
            manifests.add(new ManifestEntry(type.getSimpleName(), forArtifact));
        }
        return manifests;
    }

    private Map<String, Loaded> loadRecords() {
        final Map<String, Loaded> records = new LinkedHashMap<>();
        final List<Path> files;
        try (Stream<Path> paths = Files.list(RECORD_DIR)) {
            files = paths.filter(path ->
                            RECORD_FILE.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (final IOException exception) {
            throw new IllegalStateException("verification record directory is unavailable: " + RECORD_DIR, exception);
        }
        assertFalse(files.isEmpty(), "no verification records found under " + RECORD_DIR);
        for (final Path file : files) {
            final Loaded loaded;
            try {
                loaded = new Loaded(file, loader.load(file));
            } catch (final IOException | IllegalArgumentException exception) {
                fail(file.getFileName() + ": record failed to load: " + exception.getMessage());
                throw new AssertionError("unreachable");
            }
            final Loaded previous = records.put(loaded.record().verificationId(), loaded);
            assertNull(
                    previous,
                    "duplicate verificationId across record files: "
                            + loaded.record().verificationId());
        }
        return records;
    }

    private byte[] removeCapability(final Path recordFile, final String capabilityId) throws IOException {
        final ObjectNode root = (ObjectNode) mapper.readTree(Files.readAllBytes(recordFile));
        final ArrayNode capabilityIds = (ArrayNode) root.get("capabilityIds");
        for (int index = 0; index < capabilityIds.size(); index++) {
            if (capabilityId.equals(capabilityIds.get(index).asText())) {
                capabilityIds.remove(index);
                break;
            }
        }
        ((ObjectNode) root.get("capabilityConditions")).remove(capabilityId);
        return mapper.writeValueAsBytes(root);
    }

    private byte[] removeAliases(final Path recordFile, final Set<String> aliases) throws IOException {
        final ObjectNode root = (ObjectNode) mapper.readTree(Files.readAllBytes(recordFile));
        final ArrayNode selectors = (ArrayNode) root.get("selectors");
        final List<JsonNode> retained = new ArrayList<>();
        selectors.forEach(node -> {
            if (!aliases.contains(node.get("alias").asText())) {
                retained.add(node);
            }
        });
        selectors.removeAll();
        retained.forEach(selectors::add);
        return mapper.writeValueAsBytes(root);
    }

    private static PinnedVerifiedResolverWorkflow.Manifest repin(
            final PinnedVerifiedResolverWorkflow.Manifest manifest, final String recordSha256) {
        return new PinnedVerifiedResolverWorkflow.Manifest(
                manifest.verificationId(),
                recordSha256,
                manifest.cubismVersion(),
                manifest.profileId(),
                manifest.artifactSize(),
                manifest.artifactSha256(),
                manifest.adapterSliceId(),
                manifest.capabilityIds(),
                manifest.requiredAliases());
    }

    private static String sha256(final byte[] bytes) {
        return HexFormat.of().formatHex(HostArtifactDigest.sha256Digest().digest(bytes));
    }

    private static Set<String> selectorAliases(final StaticVerificationRecord record) {
        return record.selectors().stream().map(StaticSelector::alias).collect(Collectors.toSet());
    }

    private static Set<String> difference(final Set<String> left, final Set<String> right) {
        final Set<String> diff = new TreeSet<>(left);
        diff.removeAll(right);
        return diff;
    }

    private record ManifestEntry(String name, Method forArtifact) {}

    private record Loaded(Path path, StaticVerificationRecordLoader.LoadedRecord loaded) {
        String name() {
            return path.getFileName().toString();
        }

        String sha256() {
            return loaded.sha256();
        }

        StaticVerificationRecord record() {
            return loaded.record();
        }
    }
}

package dev.turboism.adapter.cubism.editor.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins which authoring writes declare {@link EditorAuthoringWrite.Postcondition#ADMISSION_ONLY}.
 *
 * <p>Those writes report {@code applied = true} without reading the changed native value back, so
 * the runtime can report a write the user cannot see applied. That is a deliberate, enumerated
 * exception (design C2 §7 Q2): the point of this test is that a <em>new</em> write cannot join the
 * exception silently. Adding a site, or flipping an existing site to {@code admissionVerified()},
 * has to change this list in the same commit.</p>
 */
class EditorWritePostconditionAllowlistTest {

    /**
     * Every site that only verifies admission, keyed {@code SimpleClassName#operationId}. A site is
     * pinned individually because one operation identity may be shared with a readback site.
     */
    private static final Set<String> ADMISSION_ONLY_SITES = Set.of(
            "EditorObjectHierarchyEditAccess#cubism.hierarchy.write",
            "EditorObjectInspectorAccess#cubism.object.inspector-write",
            "EditorObjectInspectorAccess#cubism.deformer.change-target",
            "EditorObjectWriteAccess#cubism.object.write-envelope",
            "EditorObjectWriteAccess#cubism.art-mesh.set-target-deformer",
            "EditorParameterBindingBatchAccess#cubism.parameter-binding.mutate-morph",
            "EditorParameterBindingBatchAccess#cubism.parameter-binding.mutate-batch",
            "EditorParameterCombinedAccess#cubism.parameter.combine-edit",
            "EditorParameterStructureAccess#cubism.parameter.structure-edit",
            "EditorParameterStructureAccess#cubism.parameter.structure-edit-batch",
            "EditorPartOpacityAccess#cubism.part.inspector-write",
            "EditorPartOpacityAccess#cubism.part.basic-setting-write",
            "EditorPartStructureAccess#cubism.part.structure-edit",
            "EditorTextureLibraryAccess#cubism.texture.write");

    /** The same exception expressed as operation identities. */
    private static final Set<String> ADMISSION_ONLY_OPERATION_IDS = Set.of(
            "cubism.hierarchy.write",
            "cubism.object.inspector-write",
            "cubism.deformer.change-target",
            "cubism.object.write-envelope",
            "cubism.art-mesh.set-target-deformer",
            "cubism.parameter-binding.mutate-morph",
            "cubism.parameter-binding.mutate-batch",
            "cubism.parameter.combine-edit",
            "cubism.parameter.structure-edit",
            "cubism.parameter.structure-edit-batch",
            "cubism.part.inspector-write",
            "cubism.part.basic-setting-write",
            "cubism.part.structure-edit",
            "cubism.texture.write");

    /** Every write that reads its result back, so a missing readback is visible as a site change. */
    private static final Set<String> READBACK_SITES = Set.of(
            "EditorDefaultKeyformLockAccess#cubism.model.set-default-keyform-locked",
            "EditorParameterBindingAccess#operationId",
            "EditorParameterInterpolationAccess#cubism.art-mesh.parameter-interpolation",
            "EditorModelProfileAccess#cubism.model.set-name",
            "EditorMorphTargetAccess#cubism.morph-target.set-parameter",
            "EditorNativeControlAppearanceAccess#cubism.model.set-native-label-color",
            "EditorPartOpacityAccess#cubism.part.set-opacity",
            "EditorTextureAtlasAppendAccess#cubism.texture.write");

    /**
     * Sites whose operation id is computed per target family (one write shape for several
     * {@code cubism.<family>.parameter-binding} identities). Each is keyed by its local variable
     * name; any other non-constant first argument still fails the scan.
     */
    private static final Set<String> COMPUTED_OPERATION_ID_SITES = Set.of("EditorParameterBindingAccess#operationId");

    @Test
    void everyAuthoringWriteDeclaresExactlyOnePostcondition() throws IOException {
        final Map<String, Postcondition> sites = scanSites();
        final List<String> undeclared = new ArrayList<>();
        for (final Map.Entry<String, Postcondition> site : sites.entrySet()) {
            if (site.getValue() == Postcondition.UNDECLARED) undeclared.add(site.getKey());
        }
        assertEquals(List.of(), undeclared, "an authoring write must declare its postcondition explicitly");
    }

    @Test
    void admissionOnlySitesAreExactlyTheAllowlistedOnes() throws IOException {
        final Map<String, Postcondition> sites = scanSites();
        assertEquals(new TreeSet<>(ADMISSION_ONLY_SITES), new TreeSet<>(sitesOf(sites, Postcondition.ADMISSION_ONLY)));
        assertEquals(
                new TreeSet<>(ADMISSION_ONLY_OPERATION_IDS),
                new TreeSet<>(operationIdsOf(sites, Postcondition.ADMISSION_ONLY)));
        assertEquals(new TreeSet<>(READBACK_SITES), new TreeSet<>(sitesOf(sites, Postcondition.READBACK)));
    }

    /** Every postcondition-less site has to be listed above, and no site may be missing a probe. */
    @Test
    void admissionOnlyIsNeverTheDefaultOfAFreshWrite() throws IOException {
        final Map<String, Postcondition> sites = scanSites();
        assertTrue(sites.size() >= ADMISSION_ONLY_SITES.size() + READBACK_SITES.size());
        for (final String site : ADMISSION_ONLY_SITES) {
            assertEquals(
                    Postcondition.ADMISSION_ONLY,
                    sites.get(site),
                    site + " is allowlisted as admission-only but now declares another postcondition");
        }
    }

    private static List<String> sitesOf(final Map<String, Postcondition> sites, final Postcondition postcondition) {
        final List<String> values = new ArrayList<>();
        for (final Map.Entry<String, Postcondition> site : sites.entrySet()) {
            if (site.getValue() == postcondition) values.add(site.getKey());
        }
        return values;
    }

    private static List<String> operationIdsOf(
            final Map<String, Postcondition> sites, final Postcondition postcondition) {
        final Set<String> values = new TreeSet<>();
        for (final Map.Entry<String, Postcondition> site : sites.entrySet()) {
            if (site.getValue() == postcondition) values.add(operationId(site.getKey()));
        }
        return new ArrayList<>(values);
    }

    private static String operationId(final String site) {
        return site.substring(site.indexOf('#') + 1);
    }

    /**
     * Reads every production {@code EditorAuthoringWrite.of(...)} building site and classifies the
     * postcondition it declares, resolving the operation identity through the declaring file's
     * {@code static final String} constants.
     */
    private static Map<String, Postcondition> scanSites() throws IOException {
        final Path sources = repositoryRoot().resolve("runtime/src/main/java");
        final List<Path> files;
        try (Stream<Path> paths = Files.walk(sources)) {
            files = paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        final Map<String, Postcondition> sites = new LinkedHashMap<>();
        for (final Path file : files) {
            final String source = Files.readString(file);
            if (!source.contains("EditorAuthoringWrite.of(")) continue;
            final Map<String, String> constants = constants(source);
            final String simpleName = file.getFileName().toString().replace(".java", "");
            final Matcher writer =
                    Pattern.compile("EditorAuthoringWrite\\s*\\.\\s*of\\s*\\(").matcher(source);
            while (writer.find()) {
                final String body = statement(source, writer.end());
                final String site = simpleName + "#" + operationId(simpleName, body, constants);
                sites.put(site, postcondition(body));
            }
        }
        return sites;
    }

    /** The builder chain of one write: everything up to and including {@code .build()}. */
    private static String statement(final String source, final int start) {
        final int end = source.indexOf(".build()", start);
        if (end < 0) {
            throw new IllegalStateException("Authoring write without .build() near offset " + start);
        }
        return source.substring(start, end);
    }

    private static String operationId(final String simpleName, final String body, final Map<String, String> constants) {
        final String firstArgument = firstArgument(body);
        if (firstArgument.startsWith("\"")) {
            return firstArgument.substring(1, firstArgument.length() - 1);
        }
        if (COMPUTED_OPERATION_ID_SITES.contains(simpleName + "#" + firstArgument)) return firstArgument;
        final String value = constants.get(firstArgument);
        if (value == null) {
            throw new IllegalStateException(
                    "Authoring write operation id constant is not a local String: " + firstArgument);
        }
        return value;
    }

    private static String firstArgument(final String body) {
        final int comma = body.indexOf(',');
        if (comma < 0) {
            throw new IllegalStateException("Authoring write without an operation id argument: " + body);
        }
        return body.substring(0, comma).strip();
    }

    private static Postcondition postcondition(final String body) {
        final boolean admissionOnly = body.contains(".admissionVerified()");
        final boolean readback = body.contains(".verifyApplied(");
        if (admissionOnly == readback) return Postcondition.UNDECLARED;
        return admissionOnly ? Postcondition.ADMISSION_ONLY : Postcondition.READBACK;
    }

    private static Map<String, String> constants(final String source) {
        final Map<String, String> values = new LinkedHashMap<>();
        final Matcher matcher = Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"")
                .matcher(source);
        while (matcher.find()) {
            values.put(matcher.group(1), matcher.group(2));
        }
        return values;
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from user.dir");
        }
        return current;
    }

    /** How one authoring write proves the mutation reached the native model. */
    private enum Postcondition {
        READBACK,
        ADMISSION_ONLY,
        UNDECLARED
    }
}

package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorEditDeformerSelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditParameterKeySelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditParameterStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditPartObjectSelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditSelectionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Admission guard for the ported external-application editing surface (spec 046, Phase 1).
 *
 * <p>All thirty-six {@code cubism.editor-model.edit.*} capability rows are bound on the three
 * exact reviewed records: the selection rows (T5), the {@code GetObject} row (Phase 3b), and
 * the remaining session, parameter-key, parameter-structure, part-object, and deformer rows
 * (T7 record binding, including the {@code main-frame.main-window} / {@code main-frame.jframe}
 * UI-lock chain). The {@code cubism.editor-model.undo.revert} row is bound on all three
 * records — static bytecode verification of {@code CUndoManager.revert()V}; direct host
 * evidence is deferred to the next host run. This test pins both halves of that contract:</p>
 *
 * <ul>
 *   <li>every declared member alias is bound by the committed records exactly where the
 *   feasibility matrix claims (READY rows are fully bound on all three versions; ADJACENT and
 *   field-restricted sets keep their documented unbound members), and</li>
 *   <li>real resolvers admitted against the exact 5.2.03 and 5.3.02 host artifacts admit every
 *   bound edit row, admit the now-verified revert row, and authorize a verified control
 *   capability.</li>
 * </ul>
 */
final class EditorEditSelectorContractTest {

    private static final Path PROJECT_ROOT = projectRoot();
    private static final Path LEGACY_EVIDENCE = legacyEvidence();

    private static final List<VersionCase> VERSIONS = List.of(
        new VersionCase("5.2.03", "cubism-5.2.03-editor-model.json", "Cubism-5.2.03"),
        new VersionCase("5.3.02", "cubism-5.3.02-editor-model.json", "Cubism-5.3.02"),
        new VersionCase("5.3.03", "cubism-5.3.03-editor-model.json", null)
    );

    /** Every edit capability row and the member set its READY surface requires. */
    private static final Map<String, Set<String>> EDIT_ROWS = editRows();

    private static Map<String, Set<String>> editRows() {
        final Map<String, Set<String>> rows = new LinkedHashMap<>();
        rows.put(
            EditorEditSessionSelectorContract.GET_IS_EDIT_APPROVAL_CAPABILITY_ID,
            EditorEditSessionSelectorContract.SESSION_NAVIGATION_ALIASES
        );
        rows.put(
            EditorEditSessionSelectorContract.EDIT_BEGIN_CAPABILITY_ID,
            EditorEditSessionSelectorContract.EDIT_BEGIN_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditSessionSelectorContract.EDIT_END_CAPABILITY_ID,
            EditorEditSessionSelectorContract.EDIT_END_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditSessionSelectorContract.EDIT_SEND_LOG_CAPABILITY_ID,
            EditorEditSessionSelectorContract.SESSION_NAVIGATION_ALIASES
        );
        rows.put(
            EditorEditSessionSelectorContract.EDIT_SEND_PROGRESS_CAPABILITY_ID,
            EditorEditSessionSelectorContract.EDIT_SEND_PROGRESS_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditSessionSelectorContract.NOTIFY_UNDO_CANCEL_CAPABILITY_ID,
            EditorEditSessionSelectorContract.NOTIFY_UNDO_CANCEL_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterKeySelectorContract.ADD_PARAMETER_KEY_CAPABILITY_ID,
            EditorEditParameterKeySelectorContract.ADD_PARAMETER_KEY_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterKeySelectorContract.DELETE_PARAMETER_KEY_CAPABILITY_ID,
            EditorEditParameterKeySelectorContract.DELETE_PARAMETER_KEY_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterKeySelectorContract.MOVE_PARAMETER_KEY_CAPABILITY_ID,
            EditorEditParameterKeySelectorContract.MOVE_PARAMETER_KEY_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterKeySelectorContract.GET_PARAMETER_KEYS_CAPABILITY_ID,
            EditorEditParameterKeySelectorContract.GET_PARAMETER_KEYS_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterKeySelectorContract.GET_OBJECTS_BY_PARAMETER_KEYS_CAPABILITY_ID,
            EditorEditParameterKeySelectorContract.GET_OBJECTS_BY_PARAMETER_KEYS_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterStructureSelectorContract.GET_PARAMETER_STRUCTURE_CAPABILITY_ID,
            EditorEditParameterStructureSelectorContract.GET_PARAMETER_STRUCTURE_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterStructureSelectorContract.ADD_PARAMETER_CAPABILITY_ID,
            EditorEditParameterStructureSelectorContract.ADD_PARAMETER_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterStructureSelectorContract.ADD_PARAMETER_GROUP_CAPABILITY_ID,
            EditorEditParameterStructureSelectorContract.ADD_PARAMETER_GROUP_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_CAPABILITY_ID,
            EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_GROUP_CAPABILITY_ID,
            EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_GROUP_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_CAPABILITY_ID,
            EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_GROUP_CAPABILITY_ID,
            EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_GROUP_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_CAPABILITY_ID,
            EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_GROUP_CAPABILITY_ID,
            EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_GROUP_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditSelectionSelectorContract.GET_SELECTED_OBJECTS_CAPABILITY_ID,
            EditorEditSelectionSelectorContract.GET_SELECTED_OBJECTS_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditSelectionSelectorContract.ADD_SELECTED_OBJECTS_CAPABILITY_ID,
            EditorEditSelectionSelectorContract.ADD_SELECTED_OBJECTS_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditSelectionSelectorContract.CLEAR_SELECTED_OBJECTS_CAPABILITY_ID,
            EditorEditSelectionSelectorContract.CLEAR_SELECTED_OBJECTS_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditPartObjectSelectorContract.GET_PART_STRUCTURE_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.GET_PART_STRUCTURE_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditPartObjectSelectorContract.GET_OBJECT_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.GET_OBJECT_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditPartObjectSelectorContract.DELETE_OBJECT_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.DELETE_OBJECT_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditPartObjectSelectorContract.MOVE_OBJECT_ON_PARTS_PALETTE_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.MOVE_OBJECT_ON_PARTS_PALETTE_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditPartObjectSelectorContract.ADD_PART_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.ADD_PART_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditPartObjectSelectorContract.EDIT_PART_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.EDIT_PART_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditPartObjectSelectorContract.EDIT_ART_MESH_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.EDIT_ART_MESH_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditPartObjectSelectorContract.EDIT_GLUE_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.EDIT_GLUE_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditDeformerSelectorContract.GET_DEFORMER_STRUCTURE_CAPABILITY_ID,
            EditorEditDeformerSelectorContract.GET_DEFORMER_STRUCTURE_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditDeformerSelectorContract.ADD_ROTATION_DEFORMER_CAPABILITY_ID,
            EditorEditDeformerSelectorContract.ADD_ROTATION_DEFORMER_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditDeformerSelectorContract.ADD_WARP_DEFORMER_CAPABILITY_ID,
            EditorEditDeformerSelectorContract.ADD_WARP_DEFORMER_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditDeformerSelectorContract.EDIT_ROTATION_DEFORMER_CAPABILITY_ID,
            EditorEditDeformerSelectorContract.EDIT_ROTATION_DEFORMER_REQUIRED_ALIASES
        );
        rows.put(
            EditorEditDeformerSelectorContract.EDIT_WARP_DEFORMER_CAPABILITY_ID,
            EditorEditDeformerSelectorContract.EDIT_WARP_DEFORMER_REQUIRED_ALIASES
        );
        return Map.copyOf(rows);
    }

    @Test
    void declaresThirtySixDistinctEditCapabilityRowsOnTheEditorModelSlice() {
        assertEquals(36, EDIT_ROWS.size());
        for (String capabilityId : EDIT_ROWS.keySet()) {
            assertTrue(capabilityId.startsWith("cubism.editor-model.edit."));
        }
        assertEquals(
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            EditorEditSessionSelectorContract.ADAPTER_SLICE_ID
        );
    }

    /**
     * Every edit row is bound into every supported record: the three selection rows (T5), the
     * {@code GetObject} read row (Phase 3b bytecode verification), and the remaining
     * session/parameter-key/parameter-structure/part-object/deformer rows (T7 record binding).
     */
    private static final Set<String> VERIFIED_ROWS = EDIT_ROWS.keySet();

    @Test
    void committedRecordsDeclareEveryEditRow() throws Exception {
        for (VersionCase version : VERSIONS) {
            final StaticVerificationRecord record = loadRecord(version);
            for (Map.Entry<String, Set<String>> row : EDIT_ROWS.entrySet()) {
                assertEquals(
                    VERIFIED_ROWS.contains(row.getKey()),
                    record.capabilityIds().contains(row.getKey()),
                    version.version() + " capability declaration mismatch for " + row.getKey()
                );
            }
            assertTrue(
                record.capabilityIds().contains(
                    EditorEditSessionSelectorContract.UNDO_REVERT_CAPABILITY_ID
                ),
                version.version() + " must declare the verified revert row"
            );
        }
    }

    @Test
    void readyRowMemberAliasesAreBoundInEverySupportedRecord() throws Exception {
        // Rows whose alias sets the feasibility matrix marks READY: every member is bound in
        // every supported record — including the selection rows T5 verified on all artifacts.
        final Map<String, Set<String>> ready = new LinkedHashMap<>(EDIT_ROWS);
        for (VersionCase version : VERSIONS) {
            final Set<String> aliases = recordAliases(version);
            for (Map.Entry<String, Set<String>> row : ready.entrySet()) {
                final Set<String> missing = new HashSet<>(row.getValue());
                missing.removeAll(aliases);
                assertTrue(
                    missing.isEmpty(),
                    version.version() + " " + row.getKey() + " has unbound members " + missing
                );
            }
        }
    }

    @Test
    void matrixDocumentedUnboundMembersStayUnboundPerVersion() throws Exception {
        final Set<String> aliases5203 = recordAliases(VERSIONS.get(0));
        final Set<String> aliases5302 = recordAliases(VERSIONS.get(1));
        final Set<String> aliases5303 = recordAliases(VERSIONS.get(2));

        // GetSelectedObjects: T5 bytecode verification bound the selection guid list on all
        // three exact artifacts, so the whole read row is now bound everywhere.
        assertBound(aliases5203, EditorEditSelectionSelectorContract.GET_SELECTED_OBJECTS_REQUIRED_ALIASES);
        assertBound(aliases5302, EditorEditSelectionSelectorContract.GET_SELECTED_OBJECTS_REQUIRED_ALIASES);
        assertBound(aliases5303, EditorEditSelectionSelectorContract.GET_SELECTED_OBJECTS_REQUIRED_ALIASES);

        // EditParameterGroup NewId: parameter-group.set-id is unverified on every host.
        for (Set<String> aliases : List.of(aliases5203, aliases5302, aliases5303)) {
            assertUnbound(aliases,
                EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_GROUP_NEW_ID_ALIASES,
                "cubism.editor-model.parameter-group.set-id");
        }

        // Keyform conditions: the keyform-on-grid members land only on 5.3.03.
        assertUnbound(aliases5203, EditorEditPartObjectSelectorContract.KEYFORM_CONDITION_ALIASES,
            "cubism.editor-model.keyform-grid.keyforms-on-grid",
            "cubism.editor-model.keyform-on-grid.form-guid",
            "cubism.editor-model.part.current-keyform");
        assertUnbound(aliases5302, EditorEditPartObjectSelectorContract.KEYFORM_CONDITION_ALIASES,
            "cubism.editor-model.keyform-grid.keyforms-on-grid",
            "cubism.editor-model.keyform-on-grid.form-guid");
        assertBound(aliases5303, EditorEditPartObjectSelectorContract.KEYFORM_CONDITION_ALIASES);

        // MoveObjectOnPartsPalette reparent via remove-child lands only on 5.3.03.
        assertUnbound(aliases5203, EditorEditPartObjectSelectorContract.MOVE_OBJECT_REMOVE_CHILD_ALIASES,
            "cubism.editor-model.part-source.remove-child");
        assertUnbound(aliases5302, EditorEditPartObjectSelectorContract.MOVE_OBJECT_REMOVE_CHILD_ALIASES,
            "cubism.editor-model.part-source.remove-child");
        assertBound(aliases5303, EditorEditPartObjectSelectorContract.MOVE_OBJECT_REMOVE_CHILD_ALIASES);

        // 5.3-only Part read fields for GetObject: the 5.2.03 host lacks the offscreen/clip/
        // blend part readers, the part-form visual reads, and the alpha-composition carrier.
        assertBound(aliases5302, EditorEditPartObjectSelectorContract.GET_OBJECT_PART_EXTENDED_ALIASES);
        assertBound(aliases5303, EditorEditPartObjectSelectorContract.GET_OBJECT_PART_EXTENDED_ALIASES);
        assertUnbound(aliases5203, EditorEditPartObjectSelectorContract.GET_OBJECT_PART_EXTENDED_ALIASES,
            "cubism.editor-model.part-source.use-offscreen",
            "cubism.editor-model.part-source.clip-guid-list",
            "cubism.editor-model.part-source.invert-clipping-mask",
            "cubism.editor-model.part-source.color-composition",
            "cubism.editor-model.part-source.alpha-composition",
            "cubism.editor-model.alpha-composition.values",
            "cubism.editor-model.part-form.class",
            "cubism.editor-model.part-form.opacity",
            "cubism.editor-model.part-form.multiply-color",
            "cubism.editor-model.part-form.screen-color");

        // GetObject ArtMesh: only the AlphaComposition carrier is missing on 5.2.03.
        assertBound(aliases5302, EditorEditPartObjectSelectorContract.GET_OBJECT_ART_MESH_EXTENDED_ALIASES);
        assertBound(aliases5303, EditorEditPartObjectSelectorContract.GET_OBJECT_ART_MESH_EXTENDED_ALIASES);
        assertUnbound(aliases5203, EditorEditPartObjectSelectorContract.GET_OBJECT_ART_MESH_EXTENDED_ALIASES,
            "cubism.editor-model.art-mesh-source.alpha-composition",
            "cubism.editor-model.alpha-composition.values");

        // GetObject Warp/Rotation members are bound on every supported host.
        for (Set<String> aliases : List.of(aliases5203, aliases5302, aliases5303)) {
            assertBound(aliases, EditorEditPartObjectSelectorContract.GET_OBJECT_WARP_ALIASES);
            assertBound(aliases, EditorEditPartObjectSelectorContract.GET_OBJECT_REQUIRED_ALIASES);
        }

        // GetObject Glue members — the glue enumeration, local name, live-instance form
        // chain, and intensity read — are bound on every supported host.
        for (Set<String> aliases : List.of(aliases5203, aliases5302, aliases5303)) {
            assertBound(aliases, EditorEditPartObjectSelectorContract.GET_OBJECT_GLUE_ALIASES);
        }

        // 5.3-only EditPart rendering fields.
        assertBound(aliases5302, EditorEditPartObjectSelectorContract.EDIT_PART_EXTENDED_ALIASES);
        assertBound(aliases5303, EditorEditPartObjectSelectorContract.EDIT_PART_EXTENDED_ALIASES);
        assertUnbound(aliases5203, EditorEditPartObjectSelectorContract.EDIT_PART_EXTENDED_ALIASES,
            "cubism.editor-model.part-source.use-offscreen",
            "cubism.editor-model.part-source.clip-guid-list",
            "cubism.editor-model.part-source.alpha-composition",
            "cubism.editor-model.part-source.set-alpha-composition",
            "cubism.editor-model.part-form.class",
            "cubism.editor-model.part-form.opacity",
            "cubism.editor-model.part-form.set-opacity",
            "cubism.editor-model.alpha-composition.class",
            "cubism.editor-model.alpha-composition.values",
            "cubism.editor-model.alpha-composition.over",
            "cubism.editor-model.alpha-composition.atop",
            "cubism.editor-model.alpha-composition.out",
            "cubism.editor-model.alpha-composition.conjoint",
            "cubism.editor-model.alpha-composition.disjoint");

        // EditArtMesh AlphaBlend stays blocked on 5.2.03.
        assertBound(aliases5302, EditorEditPartObjectSelectorContract.EDIT_ART_MESH_ALPHA_BLEND_ALIASES);
        assertBound(aliases5303, EditorEditPartObjectSelectorContract.EDIT_ART_MESH_ALPHA_BLEND_ALIASES);
        assertUnbound(aliases5203, EditorEditPartObjectSelectorContract.EDIT_ART_MESH_ALPHA_BLEND_ALIASES,
            "cubism.editor-model.art-mesh-source.set-alpha-composition",
            "cubism.editor-model.alpha-composition.class",
            "cubism.editor-model.alpha-composition.values",
            "cubism.editor-model.alpha-composition.over",
            "cubism.editor-model.alpha-composition.atop",
            "cubism.editor-model.alpha-composition.out",
            "cubism.editor-model.alpha-composition.conjoint",
            "cubism.editor-model.alpha-composition.disjoint");
    }

    @Test
    void realResolversAdmitEveryBoundRowAndTheVerifiedRevertRow() throws Exception {
        assumeTrue(LEGACY_EVIDENCE != null,
            "legacy Cubism evidence is not staged on this machine; resolver admission skips");
        for (VersionCase version : VERSIONS) {
            if (version.artifactDir() == null) {
                continue;
            }
            final Path artifact = LEGACY_EVIDENCE
                .resolve(version.artifactDir())
                .resolve("jars/Live2D_Cubism.jar");
            final VerifiedMemberResolver resolver = new VerifiedEditorModelResolverFactory().create(
                PROJECT_ROOT.resolve("compatibility/cubism/verification/" + version.recordName()),
                artifact,
                loader(artifact)
            );

            for (Map.Entry<String, Set<String>> row : EDIT_ROWS.entrySet()) {
                final boolean admitted = resolver.authorizesFeature(
                    EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
                    row.getKey(),
                    row.getValue()
                );
                assertTrue(
                    admitted,
                    version.version() + " must admit verified row " + row.getKey()
                );
            }
            assertTrue(
                resolver.authorizesFeature(
                    EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
                    EditorEditSessionSelectorContract.UNDO_REVERT_CAPABILITY_ID,
                    EditorEditSessionSelectorContract.UNDO_REVERT_REQUIRED_ALIASES
                ),
                version.version() + " must admit the verified revert row"
            );
            assertTrue(
                resolver.authorizesFeature(
                    EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                    EditorObjectReadSelectorContract.CAPABILITY_ID,
                    EditorObjectReadSelectorContract.REQUIRED_ALIASES
                ),
                version.version() + " must admit the verified control capability"
            );
        }
    }

    private static StaticVerificationRecord loadRecord(final VersionCase version) throws Exception {
        return new StaticVerificationRecordLoader()
            .load(PROJECT_ROOT.resolve("compatibility/cubism/verification/" + version.recordName()))
            .record();
    }

    private static Set<String> recordAliases(final VersionCase version) throws Exception {
        final Set<String> aliases = new HashSet<>();
        for (StaticSelector selector : loadRecord(version).selectors()) {
            aliases.add(selector.alias());
        }
        return aliases;
    }

    private static void assertBound(final Set<String> recordAliases, final Set<String> required) {
        final Set<String> missing = new HashSet<>(required);
        missing.removeAll(recordAliases);
        assertTrue(missing.isEmpty(), "expected fully bound set, unbound: " + missing);
    }

    private static void assertUnbound(
        final Set<String> recordAliases,
        final Set<String> required,
        final String... expectedMissing
    ) {
        final Set<String> missing = new HashSet<>(required);
        missing.removeAll(recordAliases);
        assertEquals(Set.of(expectedMissing), missing);
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("project root is unavailable");
        }
        return current;
    }

    /**
     * Resolves the machine-local legacy Cubism evidence directory, or {@code null} when it is
     * neither configured nor staged on this machine; evidence-backed tests skip in that case.
     */
    private static Path legacyEvidence() {
        final String configured = System.getenv("TURBOISM_LEGACY_CUBISM_REF");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = PROJECT_ROOT;
        while (current != null) {
            final Path candidate = current.resolveSibling("turboism-legacy/cubism-ref");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        try (Stream<Path> files = Files.list(artifact.getParent())) {
            final URL[] classpath = files
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted()
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (java.net.MalformedURLException exception) {
                        throw new IllegalArgumentException(exception);
                    }
                })
                .toArray(URL[]::new);
            return new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader());
        }
    }

    private record VersionCase(String version, String recordName, String artifactDir) {
    }
}

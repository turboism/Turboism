package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for the parameter-structure family of the
 * external-application editing surface: {@code GetParameterStructure}, {@code AddParameter},
 * {@code AddParameterGroup}, {@code EditParameter}, {@code EditParameterGroup},
 * {@code DeleteParameter}, {@code DeleteParameterGroup}, {@code MoveParameter}, and
 * {@code MoveParameterGroup}.
 *
 * <p>Members are drawn from the verified parameter-structure, parameter-group, and
 * parameter-definition surfaces of the exact Cubism 5.2.03, 5.3.02, and 5.3.03 host artifacts.
 * The capability ids are declared ahead of their verification records; {@code authorizesFeature}
 * rejects every row until then.</p>
 */
public final class EditorEditParameterStructureSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    public static final String GET_PARAMETER_STRUCTURE_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-structure.get-parameter-structure";
    public static final String ADD_PARAMETER_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-structure.add-parameter";
    public static final String ADD_PARAMETER_GROUP_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-structure.add-parameter-group";
    public static final String EDIT_PARAMETER_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-structure.edit-parameter";
    public static final String EDIT_PARAMETER_GROUP_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-structure.edit-parameter-group";
    public static final String DELETE_PARAMETER_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-structure.delete-parameter";
    public static final String DELETE_PARAMETER_GROUP_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-structure.delete-parameter-group";
    public static final String MOVE_PARAMETER_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-structure.move-parameter";
    public static final String MOVE_PARAMETER_GROUP_CAPABILITY_ID =
        "cubism.editor-model.edit.parameter-structure.move-parameter-group";

    /** Members that read a label-color payload (preset type plus optional custom color). */
    private static final Set<String> LABEL_COLOR_READ_ALIASES = Set.of(
        "cubism.editor-model.label-color.class",
        "cubism.editor-model.label-color.label-type",
        "cubism.editor-model.label-color.customized-color",
        "cubism.editor-model.label-color.color",
        "cubism.editor-model.color.class",
        "cubism.editor-model.color.red",
        "cubism.editor-model.color.green",
        "cubism.editor-model.color.blue",
        "cubism.editor-model.color.alpha"
    );

    /** Members that write a label-color payload. */
    private static final Set<String> LABEL_COLOR_WRITE_ALIASES = Set.of(
        "cubism.editor-model.label-color.class",
        "cubism.editor-model.label-color.label-type",
        "cubism.editor-model.label-color.customized-color",
        "cubism.editor-model.label-color.set-label-type",
        "cubism.editor-model.label-color.set-color",
        "cubism.editor-model.color.class",
        "cubism.editor-model.color.create",
        "cubism.editor-model.color.red",
        "cubism.editor-model.color.green",
        "cubism.editor-model.color.blue",
        "cubism.editor-model.color.alpha"
    );

    /** Members that locate parameter and parameter-group sources inside the model. */
    private static final Set<String> PARAMETER_TREE_LOOKUP_ALIASES = Set.of(
        "cubism.editor-model.model-source.handler",
        "cubism.editor-model.model-handler.class",
        "cubism.editor-model.model-source.root-parameter-group",
        "cubism.editor-model.model-source.parameter-source-set",
        "cubism.editor-model.parameter-source-set.class",
        "cubism.editor-model.parameter-source-set.get",
        "cubism.editor-model.parameter-source-set.get-by-id",
        "cubism.editor-model.parameter-source.class",
        "cubism.editor-model.parameter-source.id",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.parameter-source.parent-group",
        "cubism.editor-model.parameter-group.class",
        "cubism.editor-model.parameter-group.handler",
        "cubism.editor-model.parameter-group-handler.class",
        "cubism.editor-model.parameter-group.id",
        "cubism.editor-model.parameter-group.children",
        "cubism.editor-model.parameter-group.parent",
        "cubism.editor-model.id.value",
        "cubism.editor-model.guid.value"
    );

    /**
     * {@code GetParameterStructure}: read the parameter tree including ranges, repeat and
     * morph-target flags, and group label colors.
     */
    public static final Set<String> GET_PARAMETER_STRUCTURE_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.SESSION_NAVIGATION_ALIASES,
        EditorParameterGroupsReadSelectorContract.REQUIRED_ALIASES,
        LABEL_COLOR_READ_ALIASES,
        Set.of(
            "cubism.editor-model.parameter-source.name",
            "cubism.editor-model.parameter-source.minimum",
            "cubism.editor-model.parameter-source.maximum",
            "cubism.editor-model.parameter-source.default",
            "cubism.editor-model.parameter-source.repeat",
            "cubism.editor-model.parameter-source.morph-target",
            "cubism.editor-model.parameter-source.param-type",
            "cubism.editor-model.parameter-source.parent-group",
            "cubism.editor-model.parameter-source.guid",
            "cubism.editor-model.parameter-group.label-color",
            "cubism.editor-model.parameter-source-set.get",
            "cubism.editor-model.parameter-source-set.class",
            "cubism.editor-model.model-source.parameter-source-set"
        )
    );

    /** {@code AddParameter}: the verified parameter-create path. */
    public static final Set<String> ADD_PARAMETER_REQUIRED_ALIASES = unionAll(
        EditorParameterStructureSelectorContract.PARAMETER_CREATE_REQUIRED_ALIASES
    );

    /** {@code AddParameterGroup}: the verified group-create path. */
    public static final Set<String> ADD_PARAMETER_GROUP_REQUIRED_ALIASES = unionAll(
        EditorParameterStructureSelectorContract.FOLDER_CREATE_REQUIRED_ALIASES
    );

    /**
     * {@code EditParameter}: the definition-update path covers NewId/Name/Min/Max/Default plus
     * the repeat toggle.
     */
    public static final Set<String> EDIT_PARAMETER_REQUIRED_ALIASES = unionAll(
        EditorParameterDefinitionWriteSelectorContract.REQUIRED_ALIASES,
        Set.of("cubism.editor-model.parameter-source.set-repeat")
    );

    /**
     * {@code EditParameterGroup}: rename plus label color. The {@code NewId} field rides on
     * {@link #EDIT_PARAMETER_GROUP_NEW_ID_ALIASES}, which stays unadmitted because the member is
     * not yet in any verification record.
     */
    public static final Set<String> EDIT_PARAMETER_GROUP_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        PARAMETER_TREE_LOOKUP_ALIASES,
        LABEL_COLOR_WRITE_ALIASES,
        Set.of(
            "cubism.editor-model.parameter-group.name",
            "cubism.editor-model.parameter-group.set-name",
            "cubism.editor-model.parameter-group.label-color",
            "cubism.editor-model.complete-pack.update-parameter"
        )
    );

    /**
     * Extended set for {@code EditParameterGroup} requests that rename the group identifier
     * ({@code NewId}). {@code parameter-group.set-id} exists in the host jars but is not yet in
     * any verification record, so this row stays rejected until the record lands.
     */
    public static final Set<String> EDIT_PARAMETER_GROUP_NEW_ID_ALIASES = unionAll(
        EDIT_PARAMETER_GROUP_REQUIRED_ALIASES,
        Set.of("cubism.editor-model.parameter-group.set-id")
    );

    /** {@code DeleteParameter}: remove one parameter source through the model handler. */
    public static final Set<String> DELETE_PARAMETER_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        PARAMETER_TREE_LOOKUP_ALIASES,
        Set.of(
            "cubism.editor-model.model-handler.remove-parameter",
            "cubism.editor-model.complete-pack.update-parameter"
        )
    );

    /** {@code DeleteParameterGroup}: remove a group subtree through the group handler. */
    public static final Set<String> DELETE_PARAMETER_GROUP_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        PARAMETER_TREE_LOOKUP_ALIASES,
        Set.of(
            "cubism.editor-model.parameter-group-handler.remove-descendant",
            "cubism.editor-model.complete-pack.update-parameter"
        )
    );

    /** {@code MoveParameter}: move a parameter into a group, optionally at an index. */
    public static final Set<String> MOVE_PARAMETER_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        PARAMETER_TREE_LOOKUP_ALIASES,
        Set.of(
            "cubism.editor-model.model-handler.move-parameter",
            "cubism.editor-model.parameter-group-handler.add-parameter-child",
            "cubism.editor-model.complete-pack.update-parameter"
        )
    );

    /** {@code MoveParameterGroup}: reorder a group by remove + add at index. */
    public static final Set<String> MOVE_PARAMETER_GROUP_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        PARAMETER_TREE_LOOKUP_ALIASES,
        Set.of(
            "cubism.editor-model.parameter-group.remove",
            "cubism.editor-model.parameter-group-handler.add-group-child",
            "cubism.editor-model.complete-pack.update-parameter"
        )
    );

    private static Set<String> unionAll(final Set<String>... sets) {
        final HashSet<String> values = new HashSet<>();
        for (final Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }

    private EditorEditParameterStructureSelectorContract() {
    }
}

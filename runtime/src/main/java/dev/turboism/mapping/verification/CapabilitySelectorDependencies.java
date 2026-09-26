package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorAnimationReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationSceneOperationSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationTimelineEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationTimelineReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAutoYureReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorClipMaskReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorDefaultKeyformLockReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorDefaultKeyformLockWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditDeformerSelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditParameterKeySelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditParameterStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditPartObjectSelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditSelectionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorGlueInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryMoveSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryIngressSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorIntegrationSettingsDialogSelectorContract;
import dev.turboism.mapping.verification.selector.EditorIntegrationWebSocketSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelEditLevelReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelEditLevelWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelInstanceReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelNameWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelProfileSelectorContract;
import dev.turboism.mapping.verification.selector.EditorMorphTargetSelectorContract;
import dev.turboism.mapping.verification.selector.EditorNativeControlAppearanceReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorNativeControlAppearanceWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterBindingBatchWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterBindingReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterBindingWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterCombinedWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterDefinitionWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterGroupsReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterValueWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartBasicSettingsSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartInspectorIdWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartOpacityReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartOpacitySelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartTreeSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPhysicsReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPsdSnapshotSelectorContract;
import dev.turboism.mapping.verification.selector.EditorSelectionReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorWarpMirrorSelectorContract;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Complete selector requirements for independently degradable Editor features.
 * Unlisted capabilities conservatively require their entire reviewed record.
 * Entries reuse the runtime adapters' contracts, including their write envelopes;
 * no capability is inferred from an alias prefix or a percentage of matches.
 */
final class CapabilitySelectorDependencies {
    private static final Set<String> MODEL_BINDING = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.model.class",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.guid.value"
    );
    private static final Map<String, Set<String>> EDITOR = editorRequirements();

    private CapabilitySelectorDependencies() { }

    private static Map<String, Set<String>> editorRequirements() {
        final Map<String, Set<String>> result = new HashMap<>();
        add(result, "cubism.editor-model.read", MODEL_BINDING);
        add(result, EditorAnimationReadSelectorContract.CAPABILITY_ID, EditorAnimationReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorAnimationSceneOperationSelectorContract.EVAL_CAPABILITY_ID, EditorAnimationSceneOperationSelectorContract.EVAL_REQUIRED_ALIASES);
        add(result, EditorAnimationSceneOperationSelectorContract.PLAYBACK_CAPABILITY_ID, EditorAnimationSceneOperationSelectorContract.PLAYBACK_REQUIRED_ALIASES);
        add(result, EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_CAPABILITY_ID, EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_REQUIRED_ALIASES);
        add(result, EditorAnimationTimelineEditSelectorContract.WRITE_CAPABILITY_ID, EditorAnimationTimelineEditSelectorContract.WRITE_REQUIRED_ALIASES);
        add(result, EditorAnimationTimelineReadSelectorContract.CAPABILITY_ID, EditorAnimationTimelineReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorAutoYureReadSelectorContract.CAPABILITY_ID, EditorAutoYureReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorClipMaskReadSelectorContract.CAPABILITY_ID, EditorClipMaskReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorDefaultKeyformLockReadSelectorContract.CAPABILITY_ID, EditorDefaultKeyformLockReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorDefaultKeyformLockWriteSelectorContract.CAPABILITY_ID, EditorDefaultKeyformLockWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorDeformerInspectorSelectorContract.CAPABILITY_ID, EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES);
        add(result, EditorEditDeformerSelectorContract.ADD_ROTATION_DEFORMER_CAPABILITY_ID, EditorEditDeformerSelectorContract.ADD_ROTATION_DEFORMER_REQUIRED_ALIASES);
        add(result, EditorEditDeformerSelectorContract.ADD_WARP_DEFORMER_CAPABILITY_ID, EditorEditDeformerSelectorContract.ADD_WARP_DEFORMER_REQUIRED_ALIASES);
        add(result, EditorEditDeformerSelectorContract.EDIT_ROTATION_DEFORMER_CAPABILITY_ID, EditorEditDeformerSelectorContract.EDIT_ROTATION_DEFORMER_REQUIRED_ALIASES);
        add(result, EditorEditDeformerSelectorContract.EDIT_WARP_DEFORMER_CAPABILITY_ID, EditorEditDeformerSelectorContract.EDIT_WARP_DEFORMER_REQUIRED_ALIASES);
        add(result, EditorEditDeformerSelectorContract.GET_DEFORMER_STRUCTURE_CAPABILITY_ID, EditorEditDeformerSelectorContract.GET_DEFORMER_STRUCTURE_REQUIRED_ALIASES);
        add(result, EditorEditParameterKeySelectorContract.ADD_PARAMETER_KEY_CAPABILITY_ID, EditorEditParameterKeySelectorContract.ADD_PARAMETER_KEY_REQUIRED_ALIASES);
        add(result, EditorEditParameterKeySelectorContract.DELETE_PARAMETER_KEY_CAPABILITY_ID, EditorEditParameterKeySelectorContract.DELETE_PARAMETER_KEY_REQUIRED_ALIASES);
        add(result, EditorEditParameterKeySelectorContract.GET_OBJECTS_BY_PARAMETER_KEYS_CAPABILITY_ID, EditorEditParameterKeySelectorContract.GET_OBJECTS_BY_PARAMETER_KEYS_REQUIRED_ALIASES);
        add(result, EditorEditParameterKeySelectorContract.GET_PARAMETER_KEYS_CAPABILITY_ID, EditorEditParameterKeySelectorContract.GET_PARAMETER_KEYS_REQUIRED_ALIASES);
        add(result, EditorEditParameterKeySelectorContract.MOVE_PARAMETER_KEY_CAPABILITY_ID, EditorEditParameterKeySelectorContract.MOVE_PARAMETER_KEY_REQUIRED_ALIASES);
        add(result, EditorEditParameterStructureSelectorContract.ADD_PARAMETER_CAPABILITY_ID, EditorEditParameterStructureSelectorContract.ADD_PARAMETER_REQUIRED_ALIASES);
        add(result, EditorEditParameterStructureSelectorContract.ADD_PARAMETER_GROUP_CAPABILITY_ID, EditorEditParameterStructureSelectorContract.ADD_PARAMETER_GROUP_REQUIRED_ALIASES);
        add(result, EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_CAPABILITY_ID, EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_REQUIRED_ALIASES);
        add(result, EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_GROUP_CAPABILITY_ID, EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_GROUP_REQUIRED_ALIASES);
        add(result, EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_CAPABILITY_ID, EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_REQUIRED_ALIASES);
        add(result, EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_GROUP_CAPABILITY_ID, EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_GROUP_REQUIRED_ALIASES);
        add(result, EditorEditParameterStructureSelectorContract.GET_PARAMETER_STRUCTURE_CAPABILITY_ID, EditorEditParameterStructureSelectorContract.GET_PARAMETER_STRUCTURE_REQUIRED_ALIASES);
        add(result, EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_CAPABILITY_ID, EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_REQUIRED_ALIASES);
        add(result, EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_GROUP_CAPABILITY_ID, EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_GROUP_REQUIRED_ALIASES);
        add(result, EditorEditPartObjectSelectorContract.ADD_PART_CAPABILITY_ID, EditorEditPartObjectSelectorContract.ADD_PART_REQUIRED_ALIASES);
        add(result, EditorEditPartObjectSelectorContract.DELETE_OBJECT_CAPABILITY_ID, EditorEditPartObjectSelectorContract.DELETE_OBJECT_REQUIRED_ALIASES);
        add(result, EditorEditPartObjectSelectorContract.EDIT_ART_MESH_CAPABILITY_ID, EditorEditPartObjectSelectorContract.EDIT_ART_MESH_REQUIRED_ALIASES);
        add(result, EditorEditPartObjectSelectorContract.EDIT_GLUE_CAPABILITY_ID, EditorEditPartObjectSelectorContract.EDIT_GLUE_REQUIRED_ALIASES);
        add(result, EditorEditPartObjectSelectorContract.EDIT_PART_CAPABILITY_ID, EditorEditPartObjectSelectorContract.EDIT_PART_REQUIRED_ALIASES);
        add(result, EditorEditPartObjectSelectorContract.GET_OBJECT_CAPABILITY_ID, EditorEditPartObjectSelectorContract.GET_OBJECT_REQUIRED_ALIASES);
        add(result, EditorEditPartObjectSelectorContract.GET_PART_STRUCTURE_CAPABILITY_ID, EditorEditPartObjectSelectorContract.GET_PART_STRUCTURE_REQUIRED_ALIASES);
        add(result, EditorEditPartObjectSelectorContract.MOVE_OBJECT_ON_PARTS_PALETTE_CAPABILITY_ID, EditorEditPartObjectSelectorContract.MOVE_OBJECT_ON_PARTS_PALETTE_REQUIRED_ALIASES);
        add(result, EditorEditSelectionSelectorContract.ADD_SELECTED_OBJECTS_CAPABILITY_ID, EditorEditSelectionSelectorContract.ADD_SELECTED_OBJECTS_REQUIRED_ALIASES);
        add(result, EditorEditSelectionSelectorContract.CLEAR_SELECTED_OBJECTS_CAPABILITY_ID, EditorEditSelectionSelectorContract.CLEAR_SELECTED_OBJECTS_REQUIRED_ALIASES);
        add(result, EditorEditSelectionSelectorContract.GET_SELECTED_OBJECTS_CAPABILITY_ID, EditorEditSelectionSelectorContract.GET_SELECTED_OBJECTS_REQUIRED_ALIASES);
        add(result, EditorEditSessionSelectorContract.EDIT_BEGIN_CAPABILITY_ID, EditorEditSessionSelectorContract.EDIT_BEGIN_REQUIRED_ALIASES);
        add(result, EditorEditSessionSelectorContract.EDIT_END_CAPABILITY_ID, EditorEditSessionSelectorContract.EDIT_END_REQUIRED_ALIASES);
        add(result, EditorEditSessionSelectorContract.EDIT_SEND_LOG_CAPABILITY_ID, EditorEditSessionSelectorContract.EDIT_SEND_LOG_REQUIRED_ALIASES);
        add(result, EditorEditSessionSelectorContract.EDIT_SEND_PROGRESS_CAPABILITY_ID, EditorEditSessionSelectorContract.EDIT_SEND_PROGRESS_REQUIRED_ALIASES);
        add(result, EditorEditSessionSelectorContract.GET_IS_EDIT_APPROVAL_CAPABILITY_ID, EditorEditSessionSelectorContract.GET_IS_EDIT_APPROVAL_REQUIRED_ALIASES);
        add(result, EditorEditSessionSelectorContract.NOTIFY_UNDO_CANCEL_CAPABILITY_ID, EditorEditSessionSelectorContract.NOTIFY_UNDO_CANCEL_REQUIRED_ALIASES);
        add(result, EditorEditSessionSelectorContract.UNDO_REVERT_CAPABILITY_ID, EditorEditSessionSelectorContract.UNDO_REVERT_REQUIRED_ALIASES);
        add(result, EditorGlueInspectorSelectorContract.CAPABILITY_ID, EditorGlueInspectorSelectorContract.REQUIRED_ALIASES);
        add(result, EditorHistoryMoveSelectorContract.CAPABILITY_ID, EditorHistoryMoveSelectorContract.REQUIRED_ALIASES);
        add(result, EditorHistoryReadSelectorContract.CAPABILITY_ID, EditorHistoryReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID, EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorIntegrationSettingsDialogSelectorContract.EDIT_TOGGLE_CAPABILITY_ID, EditorIntegrationSettingsDialogSelectorContract.REQUIRED_ALIASES);
        add(result, EditorIntegrationWebSocketSelectorContract.DISPATCH_CAPABILITY_ID, EditorIntegrationWebSocketSelectorContract.REQUIRED_ALIASES);
        add(result, EditorModelEditLevelReadSelectorContract.CAPABILITY_ID, EditorModelEditLevelReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorModelEditLevelWriteSelectorContract.CAPABILITY_ID, EditorModelEditLevelWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorModelInstanceReadSelectorContract.CAPABILITY_ID, EditorModelInstanceReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorModelNameWriteSelectorContract.CAPABILITY_ID, EditorModelNameWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorModelProfileSelectorContract.NAME_WRITE_CAPABILITY_ID, EditorModelProfileSelectorContract.NAME_WRITE_REQUIRED_ALIASES);
        add(result, EditorModelProfileSelectorContract.PROFILE_READ_CAPABILITY_ID, EditorModelProfileSelectorContract.PROFILE_READ_REQUIRED_ALIASES);
        add(result, EditorMorphTargetSelectorContract.READ_CAPABILITY_ID, EditorMorphTargetSelectorContract.READ_REQUIRED_ALIASES);
        add(result, EditorMorphTargetSelectorContract.WRITE_CAPABILITY_ID, EditorMorphTargetSelectorContract.WRITE_REQUIRED_ALIASES);
        add(result, EditorNativeControlAppearanceReadSelectorContract.CAPABILITY_ID, EditorNativeControlAppearanceReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorNativeControlAppearanceWriteSelectorContract.CAPABILITY_ID, EditorNativeControlAppearanceWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_CAPABILITY_ID, EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_REQUIRED_ALIASES);
        add(result, EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID, EditorObjectHierarchyEditSelectorContract.REQUIRED_ALIASES);
        add(result, EditorObjectHierarchyEditSelectorContract.RENAME_CAPABILITY_ID, EditorObjectHierarchyEditSelectorContract.RENAME_REQUIRED_ALIASES);
        add(result, EditorObjectReadSelectorContract.CAPABILITY_ID, EditorObjectReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorObjectReadSelectorContract.STATISTICS_CAPABILITY_ID, EditorObjectReadSelectorContract.STATISTICS_ALIASES);
        add(result, EditorObjectWriteSelectorContract.ART_MESH_CAPABILITY_ID, EditorObjectWriteSelectorContract.ART_MESH_REQUIRED_ALIASES);
        add(result, EditorObjectWriteSelectorContract.CLIP_MASK_CAPABILITY_ID, EditorObjectWriteSelectorContract.CLIP_MASK_REQUIRED_ALIASES);
        add(result, EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID, EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES);
        add(result, EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID, EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES);
        add(result, EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID, EditorParameterBindingBatchWriteSelectorContract.INVERT_REQUIRED_ALIASES);
        add(result, EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID, EditorParameterBindingBatchWriteSelectorContract.TRANSFER_REQUIRED_ALIASES);
        add(result, EditorParameterBindingReadSelectorContract.CAPABILITY_ID, EditorParameterBindingReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID, EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID, EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID, EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorParameterCombinedWriteSelectorContract.CAPABILITY_ID, EditorParameterCombinedWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorParameterDefinitionWriteSelectorContract.CAPABILITY_ID, EditorParameterDefinitionWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorParameterGroupsReadSelectorContract.CAPABILITY_ID, EditorParameterGroupsReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorParameterStructureSelectorContract.CAPABILITY_ID, EditorParameterStructureSelectorContract.REQUIRED_ALIASES);
        add(result, EditorParameterValueWriteSelectorContract.CAPABILITY_ID, EditorParameterValueWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorPartBasicSettingsSelectorContract.READ_CAPABILITY_ID, EditorPartBasicSettingsSelectorContract.READ_REQUIRED_ALIASES);
        add(result, EditorPartBasicSettingsSelectorContract.WRITE_CAPABILITY_ID, EditorPartBasicSettingsSelectorContract.WRITE_REQUIRED_ALIASES);
        add(result, EditorPartInspectorIdWriteSelectorContract.CAPABILITY_ID, EditorPartInspectorIdWriteSelectorContract.REQUIRED_ALIASES);
        add(result, EditorPartInspectorSelectorContract.CAPABILITY_ID, EditorPartInspectorSelectorContract.REQUIRED_ALIASES);
        add(result, EditorPartNameSelectorContract.CAPABILITY_ID, EditorPartNameSelectorContract.REQUIRED_ALIASES);
        add(result, EditorPartNameSelectorContract.WRITE_CAPABILITY_ID, EditorPartNameSelectorContract.WRITE_REQUIRED_ALIASES);
        add(result, EditorPartOpacityReadSelectorContract.CAPABILITY_ID, EditorPartOpacityReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorPartOpacitySelectorContract.CAPABILITY_ID, EditorPartOpacitySelectorContract.REQUIRED_ALIASES);
        add(result, EditorPartStructureSelectorContract.CAPABILITY_ID, EditorPartStructureSelectorContract.REQUIRED_ALIASES);
        add(result, EditorPartTreeSelectorContract.CAPABILITY_ID, EditorPartTreeSelectorContract.REQUIRED_ALIASES);
        add(result, EditorPhysicsReadSelectorContract.CAPABILITY_ID, EditorPhysicsReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorPsdSnapshotSelectorContract.CAPABILITY_ID, EditorPsdSnapshotSelectorContract.REQUIRED_ALIASES);
        add(result, EditorSelectionReadSelectorContract.CAPABILITY_ID, EditorSelectionReadSelectorContract.REQUIRED_ALIASES);
        add(result, EditorTextureSelectorContract.READ_CAPABILITY_ID, EditorTextureSelectorContract.READ_REQUIRED_ALIASES);
        add(result, EditorTextureSelectorContract.WRITE_CAPABILITY_ID, EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES);
        add(result, EditorWarpMirrorSelectorContract.CAPABILITY_ID, EditorWarpMirrorSelectorContract.REQUIRED_ALIASES);
        return Map.copyOf(result);
    }

    private static void add(final Map<String, Set<String>> result, final String id, final Set<String> aliases) {
        final Set<String> combined = new HashSet<>(result.getOrDefault(id, Set.of()));
        combined.addAll(aliases);
        result.put(id, Set.copyOf(combined));
    }

    static Set<String> allAliases(final StaticVerificationRecord record) {
        return record.selectors().stream().map(StaticSelector::alias).collect(Collectors.toUnmodifiableSet());
    }

    static Set<String> capabilities(final StaticVerificationRecord record, final Set<String> verified) {
        final Set<String> all = allAliases(record);
        if (verified.containsAll(all)) return Set.copyOf(record.capabilityIds());
        return record.capabilityIds().stream()
            .filter(id -> verified.containsAll(requiredAliases(record, id)))
            .collect(Collectors.toUnmodifiableSet());
    }

    static Set<String> requiredAliases(final StaticVerificationRecord record, final String capability) {
        final Set<String> required = EDITOR.get(capability);
        if (!"adapter.editor-model.readwrite".equals(record.adapterSliceId()) || required == null) {
            return allAliases(record);
        }
        final Set<String> aliases = new HashSet<>(required);
        aliases.addAll(MODEL_BINDING);
        if (capability.equals(EditorTextureSelectorContract.WRITE_CAPABILITY_ID)) {
            aliases.addAll(record.cubismVersion().equals("5.2.03")
                ? EditorTextureSelectorContract.REMOVE_RAW_IMAGE_5203_ALIASES
                : EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES);
        }
        if (capability.equals(EditorModelInstanceReadSelectorContract.CAPABILITY_ID)
            && record.cubismVersion().equals("5.2.03")) {
            aliases.removeAll(EditorModelInstanceReadSelectorContract.ONION_SKIN_ALIASES);
        }
        if (capability.startsWith("cubism.editor-model.edit.")) {
            aliases.addAll(EditorEditSessionSelectorContract.SESSION_ADMISSION_REQUIRED_ALIASES);
            aliases.addAll(EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES);
        }
        if (record.capabilityConditions().getOrDefault(capability, java.util.List.of())
            .contains("hook:native-edit-begin")) {
            aliases.addAll(EditorHistoryReadSelectorContract.REQUIRED_ALIASES);
            aliases.addAll(EditorHistoryIngressSelectorContract.REQUIRED_ALIASES);
            aliases.add(EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS);
        }
        // Inheritance claims belong to the types a feature actually consumes. Follow
        // both owner and signature types, then their required ancestor relationships.
        final Set<String> types = new HashSet<>();
        for (final StaticSelector selector : record.selectors()) {
            if (aliases.contains(selector.alias())) {
                types.add(selector.ownerInternalName());
                final var matcher = java.util.regex.Pattern.compile("L([^;]+);").matcher(selector.descriptor());
                while (matcher.find()) types.add(matcher.group(1));
            }
        }
        boolean changed;
        do {
            changed = false;
            for (final StaticSelector selector : record.selectors()) {
                if (selector.kind() == StaticSelector.Kind.INHERITS && types.contains(selector.ownerInternalName())) {
                    aliases.add(selector.alias());
                    changed |= types.add(selector.memberName());
                }
            }
        } while (changed);
        return Set.copyOf(aliases);
    }
}

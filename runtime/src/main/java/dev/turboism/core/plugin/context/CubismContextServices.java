package dev.turboism.core.plugin.context;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupService;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.plugin.PluginService;
import java.util.Objects;

record CubismContextServices(
        CubismFacade cubismFacade,
        SelectionQueryService selectionQueryService,
        ModelHierarchyQueryService modelHierarchyQueryService,
        CubismReadCapabilityService cubismReadCapabilityService,
        ModelObjectService modelObjectService,
        PhysicsEditorService physicsEditorService,
        CubismClipMaskService cubismClipMaskService,
        EditorCommandService editorCommandService,
        EditorAutoBackupService backupService)
        implements PluginCapabilityBundle {
    CubismContextServices {
        cubismFacade = Objects.requireNonNull(cubismFacade, "cubismFacade");
        selectionQueryService = Objects.requireNonNull(selectionQueryService, "selectionQueryService");
        modelHierarchyQueryService = Objects.requireNonNull(modelHierarchyQueryService, "modelHierarchyQueryService");
        cubismReadCapabilityService =
                Objects.requireNonNull(cubismReadCapabilityService, "cubismReadCapabilityService");
        modelObjectService = Objects.requireNonNull(modelObjectService, "modelObjectService");
        physicsEditorService = Objects.requireNonNull(physicsEditorService, "physicsEditorService");
        cubismClipMaskService = Objects.requireNonNull(cubismClipMaskService, "cubismClipMaskService");
        editorCommandService = Objects.requireNonNull(editorCommandService, "editorCommandService");
        backupService = Objects.requireNonNull(backupService, "backupService");
    }

    @Override
    public void registerServices(final RuntimePluginServiceDirectory directory) {
        directory.installWhenPresent(PluginService.SELECTION_QUERY, this::selectionQueryService);
        directory.installWhenPresent(PluginService.MODEL_HIERARCHY_QUERY, this::modelHierarchyQueryService);
        directory.installWhenPresent(PluginService.CUBISM_READ, this::cubismReadCapabilityService);
        directory.install(PluginService.MODEL_OBJECTS, this::modelObjectService, ModelObjectService::unavailable);
        directory.installWhenPresent(PluginService.CUBISM_CLIP_MASKS, this::cubismClipMaskService);
        directory.install(PluginService.PHYSICS_EDITOR, this::physicsEditorService, PhysicsEditorService::unavailable);
        directory.install(PluginService.EDITOR_COMMANDS, this::editorCommandService, EditorCommandService::unavailable);
        directory.install(PluginService.BACKUP, this::backupService, EditorAutoBackupService::unavailable);
    }
}

package dev.turboism.adapter.cubism.command;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorParameterizedCommand;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.sdk.permission.PermissionIds;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Plugin-scoped permission and lifecycle boundary for semantic Editor commands. */
public final class RuntimeEditorCommandService implements EditorCommandService {
    public static final String READ_PERMISSION = CubismFacadeImpl.MODEL_READ_PERMISSION;
    public static final String WRITE_PERMISSION = CubismFacadeImpl.MODEL_WRITE_PERMISSION;

    private final EditorCommandAdapter adapter;
    private final CubismPermissionGate permissions;
    private final EditorFileCommandResolver fileResolver;
    private final BooleanSupplier active;

    public RuntimeEditorCommandService(
        final EditorCommandAdapter adapter,
        final CubismPermissionGate permissions
    ) {
        this(adapter, permissions, EditorFileCommandResolver.unavailable());
    }

    public RuntimeEditorCommandService(
        final EditorCommandAdapter adapter,
        final CubismPermissionGate permissions,
        final EditorFileCommandResolver fileResolver
    ) {
        this(adapter, permissions, fileResolver, () -> true);
    }

    public RuntimeEditorCommandService(
        final EditorCommandAdapter adapter,
        final CubismPermissionGate permissions,
        final EditorFileCommandResolver fileResolver,
        final BooleanSupplier active
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.fileResolver = Objects.requireNonNull(fileResolver, "fileResolver");
        this.active = Objects.requireNonNull(active, "active");
    }

    @Override
    public Set<EditorCommand> available() {
        if (!active.getAsBoolean()) return Set.of();
        final EnumSet<EditorCommand> admitted = EnumSet.noneOf(EditorCommand.class);
        try {
            for (EditorCommand command : adapter.available()) {
                if (hasPermissions(command)) admitted.add(command);
            }
        } catch (RuntimeException exception) {
            return Set.of();
        }
        return Set.copyOf(admitted);
    }

    @Override
    public EditorCommandResult execute(final EditorCommand command) {
        Objects.requireNonNull(command, "command");
        if (!active.getAsBoolean()) return result(command, EditorCommandResult.Status.UNAVAILABLE);
        try {
            requirePermissions(command);
        } catch (CubismPermissionException exception) {
            return result(command, EditorCommandResult.Status.PERMISSION_DENIED);
        }
        try {
            return adapter.execute(command);
        } catch (RuntimeException exception) {
            return result(command, EditorCommandResult.Status.FAILED);
        }
    }

    @Override
    public EditorCommandResult execute(final EditorFileCommandRequest request) {
        Objects.requireNonNull(request, "request");
        if (!active.getAsBoolean()) return result(request.commandId(), EditorCommandResult.Status.UNAVAILABLE);
        try {
            permissions.require(
                request.command().mode() == UserFileMode.READ ? READ_PERMISSION : WRITE_PERMISSION,
                "cubism.editor-command." + request.commandId()
            );
            permissions.require(
                request.command().mode() == UserFileMode.READ
                    ? PermissionIds.TURBOISM_FILE_READ : PermissionIds.TURBOISM_FILE_WRITE,
                "cubism.editor-command." + request.commandId()
            );
        } catch (CubismPermissionException exception) {
            return result(request.commandId(), EditorCommandResult.Status.PERMISSION_DENIED);
        }
        final ResolvedEditorFileCommand resolved;
        try {
            resolved = fileResolver.resolve(request);
        } catch (RuntimeException exception) {
            return result(request.commandId(), EditorCommandResult.Status.REJECTED);
        }
        if (resolved == null) {
            return result(request.commandId(), EditorCommandResult.Status.REJECTED);
        }
        try {
            return adapter.execute(resolved);
        } catch (RuntimeException exception) {
            return result(request.commandId(), EditorCommandResult.Status.FAILED);
        }
    }

    @Override
    public EditorCommandResult execute(final EditorParameterizedRequest request) {
        Objects.requireNonNull(request, "request");
        if (!active.getAsBoolean()) return result(request.commandId(), EditorCommandResult.Status.UNAVAILABLE);
        try {
            permissions.require(
                parameterizedPermission(request),
                "cubism.editor-command." + request.commandId()
            );
            if (request.command() == EditorParameterizedCommand.EXTERNAL_APP_SETTING) {
                permissions.require(PermissionIds.TURBOISM_PROCESS, "cubism.editor-command." + request.commandId());
            }
        } catch (CubismPermissionException exception) {
            return result(request.commandId(), EditorCommandResult.Status.PERMISSION_DENIED);
        }
        try {
            return adapter.execute(request);
        } catch (RuntimeException exception) {
            return result(request.commandId(), EditorCommandResult.Status.FAILED);
        }
    }

    private static String parameterizedPermission(final EditorParameterizedRequest request) {
        return switch (request.command()) {
            case EXTERNAL_APP_SETTING -> PermissionIds.TURBOISM_NETWORK;
            case GRID_SETTING, MODELING_STATISTICS -> READ_PERMISSION;
            default -> WRITE_PERMISSION;
        };
    }

    private boolean hasPermissions(final EditorCommand command) {
        for (String permission : permissions(command)) {
            if (!permissions.hasPermission(permission)) return false;
        }
        return true;
    }

    private void requirePermissions(final EditorCommand command) {
        for (String permission : permissions(command)) {
            permissions.require(permission, "cubism.editor-command." + command.id());
        }
    }

    private static Set<String> permissions(final EditorCommand command) {
        return switch (command) {
            case SAVE -> Set.of(WRITE_PERMISSION, PermissionIds.TURBOISM_FILE_WRITE);
            case OPEN_COMMUNITY_PAGE, OPEN_DOWNLOAD_PAGE, OPEN_FAQ_PAGE, OPEN_HOME_PAGE,
                 OPEN_LIVE2D_HELP_PAGE, OPEN_MANUAL_PAGE, OPEN_PRODUCT_PAGE,
                 OPEN_SAMPLE_MODEL_PAGE, OPEN_STORE_PAGE, SHOW_TUTORIAL_VIDEO
                 -> Set.of(READ_PERMISSION, PermissionIds.TURBOISM_NETWORK);
            case OPEN_BACKUP_DIR, OPEN_LOG_FILE
                 -> Set.of(READ_PERMISSION, PermissionIds.TURBOISM_PROCESS);
            case FOCUS_ON_SELECTED_OBJECTS,
                 MOVE_START, MOVE_END, MOVE_START_WORKSPACE, MOVE_END_WORKSPACE,
                 MOVE_TAB_NEXT, MOVE_TAB_PREV, NEXT_FRAME, PREV_FRAME,
                 NEXT_KEYFRAME, PREV_KEYFRAME, NEXT_TRACK_KEYFRAME, PREV_TRACK_KEYFRAME,
                 NEXT_TIMELINE_MARKER, PREV_TIMELINE_MARKER,
                 NEXT_ONIONSKIN_MARKER, PREV_ONIONSKIN_MARKER,
                 SET_ALL_VIEWS_TO_SAME_DISPLAY_POSITION,
                 SHOW_FULL_SCENE, SHOW_FULL_WORKSPACE, SHOW_DEFAULT_ZOOM,
                 SHOW_DEFORMER_PALETTE, SHOW_INSPECTOR_PALETTE, SHOW_LOG_PALETTE,
                 SHOW_PARAMETER_PALETTE, SHOW_PARTS_PALETTE, SHOW_PROJECT_PALETTE,
                 SHOW_SCENE_PALETTE, SHOW_TEMPLATE_PALETTE, SHOW_TIMELINE_PALETTE,
                 SHOW_TOOL_PALETTE, SHOW_HOME_DIALOG, OPEN_ABOUT,
                 SHOW_ARTMESH_POINT, SHOW_CHILD_UNDER_WARP_DEFORMER_CTRL_MODE,
                 SHOW_GUI_GRID, SHOW_GUI_GUIDE, SHOW_GUI_GUIDE_FOR_MODELING,
                 SHOW_MODEL_BY_RAW_IMAGE, SHOW_MODEL_BY_TEXTURE_ATLAS,
                 SHOW_PARAMETER_BOOKMARK_FORM_SELECTED_ATTRIBUTE,
                 SHOW_PARAM_CONTROLLER_TARGET_TRACKING, SHOW_PARAM_CTRL_MARKER,
                 SHOW_POPUP_HOVERING_OBJECT, SHOW_USER_OPERATION, SHOW_VERTEX_INDEX
                 -> Set.of(READ_PERMISSION);
            default -> Set.of(WRITE_PERMISSION);
        };
    }

    private static EditorCommandResult result(
        final EditorCommand command,
        final EditorCommandResult.Status status
    ) {
        return new EditorCommandResult(status, command.id());
    }

    private static EditorCommandResult result(
        final String commandId,
        final EditorCommandResult.Status status
    ) {
        return new EditorCommandResult(status, commandId);
    }
}

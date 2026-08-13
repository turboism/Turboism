package dev.turboism.adapter.cubism.command;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorExternalAppSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorGridSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.cubism.command.EditorResizeModelRequest;
import dev.turboism.sdk.cubism.model.Color;

import java.io.File;
import java.util.Objects;

/**
 * Typed no-dialog host operations for parameterized and file Editor commands. Every operation
 * mirrors the exact observable call sequence the reviewed native dialog/handler applies, using
 * only exact-version verified selectors. Operations never open dialogs, never simulate menu
 * clicks, and fail closed with sanitized results. Each command is admitted per exact version;
 * missing selector tuples or unsupported versions fail closed.
 *
 * <p>Safety guarantees:
 * <ul>
 *   <li>Every selector used by the operation AND by its rollback is resolved before the first
 *       mutation, so a missing or drifted selector tuple can never leave partial host state.</li>
 *   <li>Authoring sequences register the live object in a native transaction and, on any
 *       mid-sequence failure, close the transaction and apply the native Undo path. The rollback
 *       is then verified by readback (Undo position, edit state, canvas dimensions); if the
 *       rollback itself fails or the readback disagrees, the command fails closed with a
 *       {@link RollbackFailed} diagnostic and no safety claim is made.</li>
 *   <li>Preference writes snapshot the exact observed originals (port/remote/config or grid
 *       spacing/color) before mutating and restore them on failure, stopping the server and
 *       re-running the refresh; the restored values are read back and must match exactly.</li>
 *   <li>No-op inputs short-circuit without creating Undo entries, dirty state, or side effects.</li>
 * </ul>
 */
final class VerifiedTypedEditorCommandOperations {

    /** Exact selector aliases pinned in the reviewed top-menu verification record. */
    static final class Aliases {
        static final String EXTERNAL_MANAGER = "cubism.editor-command.external-app.manager";
        static final String EXTERNAL_INSTANCE = "cubism.editor-command.external-app.instance";
        static final String EXTERNAL_COMPANION = "cubism.editor-command.external-app.companion";
        static final String EXTERNAL_GET_PORT = "cubism.editor-command.external-app.get-port";
        static final String EXTERNAL_SET_PORT = "cubism.editor-command.external-app.set-port";
        static final String EXTERNAL_GET_REMOTE = "cubism.editor-command.external-app.get-remote";
        static final String EXTERNAL_SET_REMOTE = "cubism.editor-command.external-app.set-remote";
        static final String EXTERNAL_CONNECTED = "cubism.editor-command.external-app.connected";
        static final String EXTERNAL_START = "cubism.editor-command.external-app.start";
        static final String EXTERNAL_STOP = "cubism.editor-command.external-app.stop";

        static final String CONFIG_INSTANCE = "cubism.editor-command.config.instance";
        static final String CONFIG_READ = "cubism.editor-command.config.read";
        static final String CONFIG_WRITE = "cubism.editor-command.config.write";

        static final String GRID_DEVELOPER_SETTING = "cubism.editor-command.grid.developer-setting";
        static final String GRID_GET_COLOR = "cubism.editor-command.grid.get-color";
        static final String GRID_SET_COLOR = "cubism.editor-command.grid.set-color";
        static final String GRID_COLOR_CREATE = "cubism.editor-command.grid.color-create";
        static final String GRID_GET_JCOLOR = "cubism.editor-command.grid.get-jcolor";
        static final String GRID_ENTITY = "cubism.editor-command.grid.entity";
        static final String GRID_GET_SPACING = "cubism.editor-command.grid.get-spacing";
        static final String GRID_GET_BOLD = "cubism.editor-command.grid.get-bold";
        static final String GRID_SET_SPACING = "cubism.editor-command.grid.set-spacing";
        static final String GRID_ALL_VIEW_CONTEXTS = "cubism.editor-command.grid.all-view-contexts";
        static final String GRID_MODELING_DRAW = "cubism.editor-command.grid.modeling-draw";
        static final String GRID_ENTITY_FROM_DRAW = "cubism.editor-command.grid.entity-from-draw";
        static final String GRID_SET_RESET = "cubism.editor-command.grid.set-reset";
        static final String GRID_UPDATE_MANAGER = "cubism.editor-command.grid.update-manager";
        static final String GRID_REPAINT_DEFAULT = "cubism.editor-command.grid.repaint-default";

        static final String CANVAS_CURRENT_VIEW_CONTEXT = "cubism.editor-command.canvas.current-view-context";
        static final String CANVAS_MODELING_VIEW = "cubism.editor-command.canvas.modeling-view";
        static final String CANVAS_MODELING_DOC = "cubism.editor-command.canvas.modeling-doc";
        static final String CANVAS_EDIT_MODE = "cubism.editor-command.canvas.edit-mode";
        static final String CANVAS_EDIT_MODE_MAIN = "cubism.editor-command.canvas.edit-mode-main";
        static final String CANVAS_MODEL_SOURCE = "cubism.editor-command.canvas.model-source";
        static final String CANVAS_CANVAS = "cubism.editor-command.canvas.canvas";
        static final String CANVAS_PIXEL_WIDTH = "cubism.editor-command.canvas.pixel-width";
        static final String CANVAS_PIXEL_HEIGHT = "cubism.editor-command.canvas.pixel-height";
        static final String CANVAS_SET_PIXEL_WIDTH = "cubism.editor-command.canvas.set-pixel-width";
        static final String CANVAS_SET_PIXEL_HEIGHT = "cubism.editor-command.canvas.set-pixel-height";
        static final String CANVAS_BEGIN_EDIT = "cubism.editor-command.canvas.begin-edit";
        static final String CANVAS_GROUP_ADD = "cubism.editor-command.canvas.group-add";
        static final String CANVAS_SIMPLE_UNDO = "cubism.editor-command.canvas.simple-undo";
        static final String CANVAS_COMPANION = "cubism.editor-command.canvas.companion";
        static final String CANVAS_NOTIFY_SIZE = "cubism.editor-command.canvas.notify-size";
        static final String CANVAS_MODEL = "cubism.editor-command.canvas.model";
        static final String CANVAS_HANDLER = "cubism.editor-command.canvas.handler";
        static final String CANVAS_SCALE_WITH_ANCHOR = "cubism.editor-command.canvas.scale-with-anchor";
        static final String CANVAS_VECTOR2 = "cubism.editor-command.canvas.vector2";
        static final String CANVAS_VECTOR2_ZERO = "cubism.editor-command.canvas.vector2-zero";
        static final String CANVAS_END_EDIT_DEFAULT = "cubism.editor-command.canvas.end-edit-default";
        static final String CANVAS_MARK_DIRTY = "cubism.editor-command.canvas.mark-dirty";
        static final String CANVAS_DOC_SIZE = "cubism.editor-command.canvas.doc-size";
        static final String CANVAS_SIZE_WIDTH = "cubism.editor-command.canvas.size-width";
        static final String CANVAS_COMPLETE_PACK = "cubism.editor-command.canvas.complete-pack";
        static final String CANVAS_SIZE_HEIGHT = "cubism.editor-command.canvas.size-height";
        static final String CANVAS_IS_EDITING = "cubism.editor-command.canvas.is-editing";
        static final String CANVAS_UNDO_MANAGER = "cubism.editor-command.canvas.undo-manager";
        static final String CANVAS_UNDO_POS = "cubism.editor-command.canvas.undo-pos";
        static final String CANVAS_UNDO = "cubism.editor-command.canvas.undo";

        static final String RESIZE_GUARD = "cubism.editor-command.resize.guard";
        static final String RESIZE_GUARD_CURRENT = "cubism.editor-command.resize.guard-current";
        static final String RESIZE_GUARD_ACTIVE = "cubism.editor-command.resize.guard-active";
        static final String RESIZE_SCALE_MODEL = "cubism.editor-command.resize.scale-model";

        static final String FILE_SCENE_DOCUMENT = "cubism.editor-command.file.scene-document";
        static final String FILE_SCENE_CONTENT = "cubism.editor-command.file.scene-content";
        static final String FILE_SAVE_SCENE = "cubism.editor-command.file.save-scene";
        static final String FILE_SAVE_MODEL = "cubism.editor-command.file.save-model";

        private Aliases() {
        }
    }

    private static final String APP_INSTANCE = "cubism.ui-top-menu.app-controller.instance";
    static final String EXTERNAL_APP_REMOTE_CONFIG_KEY = "CExternalAppSettingDialog.RemoteConnect";
    static final String CANVAS_EDIT_LABEL = "Change Canvas Size";

    private final VerifiedMemberResolver resolver;

    VerifiedTypedEditorCommandOperations(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    EditorCommandResult execute(final EditorParameterizedRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (request.command()) {
            case EXTERNAL_APP_SETTING -> externalAppSetting((EditorExternalAppSettingsRequest) request);
            case GRID_SETTING -> gridSetting((EditorGridSettingsRequest) request);
            case MODEL_SETTING -> canvasSetting((EditorCanvasSettingsRequest) request);
            case RESIZE_MODEL_DOCUMENT -> resizeModelDocument((EditorResizeModelRequest) request);
            default -> new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, request.commandId());
        };
    }

    EditorCommandResult execute(final ResolvedEditorFileCommand command) {
        Objects.requireNonNull(command, "command");
        if (!command.command().supports(resolver.cubismVersion())) {
            return new EditorCommandResult(EditorCommandResult.Status.UNSUPPORTED_VERSION, command.commandId());
        }
        // Only SAVE_AS is admitted. OPEN and every other file command fail closed: OPEN's
        // first-open behavior is not fully verified on either exact version.
        return switch (command.command()) {
            case SAVE_AS -> saveAs(command);
            default -> new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, command.commandId());
        };
    }

    private EditorCommandResult externalAppSetting(final EditorExternalAppSettingsRequest request) {
        // Every selector used by the operation and its rollback resolves before the first mutation.
        requireResolvable(
            Aliases.EXTERNAL_MANAGER, Aliases.EXTERNAL_INSTANCE, Aliases.EXTERNAL_CONNECTED,
            Aliases.EXTERNAL_GET_PORT, Aliases.EXTERNAL_GET_REMOTE, Aliases.EXTERNAL_SET_PORT,
            Aliases.EXTERNAL_SET_REMOTE, Aliases.EXTERNAL_START, Aliases.EXTERNAL_STOP,
            Aliases.EXTERNAL_COMPANION, Aliases.CONFIG_INSTANCE, Aliases.CONFIG_READ, Aliases.CONFIG_WRITE
        );
        final Object manager = resolver.readStaticField(Aliases.EXTERNAL_MANAGER);
        final Object instance = resolver.invoke(Aliases.EXTERNAL_INSTANCE, manager);
        final boolean connected = (Boolean) resolver.invoke(Aliases.EXTERNAL_CONNECTED, instance);
        if (connected) {
            // The native dialog disables the port field while connected; the typed request bundles
            // port and remote, so a connected server fails closed for the whole request.
            return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE, request.commandId());
        }
        // Snapshot the exact observed originals before the first mutation.
        final int oldPort = (Integer) resolver.invoke(Aliases.EXTERNAL_GET_PORT, instance);
        final boolean oldRemote = (Boolean) resolver.invokeStatic(Aliases.EXTERNAL_GET_REMOTE);
        final Object config = resolver.readStaticField(Aliases.CONFIG_INSTANCE);
        final Object oldConfigRemote =
            resolver.invoke(Aliases.CONFIG_READ, config, EXTERNAL_APP_REMOTE_CONFIG_KEY, false);
        final Object companion = resolver.readStaticField(Aliases.EXTERNAL_COMPANION);
        try {
            resolver.invoke(Aliases.EXTERNAL_SET_PORT, instance, request.port());
            // The native checkbox listener applies the flag through the companion; the instance
            // boolean setter touches an unused constructor field and must not be used.
            resolver.invoke(Aliases.EXTERNAL_SET_REMOTE, companion, request.allowRemoteConnections());
            resolver.invoke(Aliases.CONFIG_WRITE, config, EXTERNAL_APP_REMOTE_CONFIG_KEY,
                request.allowRemoteConnections());
            resolver.invoke(Aliases.EXTERNAL_START, instance);
        } catch (RuntimeException | Error failure) {
            try {
                rollbackExternal(manager, instance, companion, config, oldPort, oldRemote, oldConfigRemote);
            } catch (RollbackFailed rollbackFailure) {
                rollbackFailure.addSuppressed(failure);
                throw rollbackFailure;
            }
            throw failure;
        }
        return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
    }

    /**
     * Restores the exact observed originals and verifies them by readback. Any failure or
     * mismatch throws {@link RollbackFailed} so the command fails closed without claiming safety.
     */
    private void rollbackExternal(
        final Object manager,
        final Object instance,
        final Object companion,
        final Object config,
        final int oldPort,
        final boolean oldRemote,
        final Object oldConfigRemote
    ) {
        Throwable failure = null;
        try {
            resolver.invoke(Aliases.EXTERNAL_STOP, manager);
        } catch (RuntimeException stopFailure) {
            failure = stopFailure;
        }
        try {
            resolver.invoke(Aliases.EXTERNAL_SET_PORT, instance, oldPort);
            resolver.invoke(Aliases.EXTERNAL_SET_REMOTE, companion, oldRemote);
            resolver.invoke(Aliases.CONFIG_WRITE, config, EXTERNAL_APP_REMOTE_CONFIG_KEY, oldConfigRemote);
        } catch (RuntimeException restoreFailure) {
            failure = failure == null ? restoreFailure : failure;
        }
        final boolean portRestored;
        final boolean remoteRestored;
        final boolean configRestored;
        try {
            portRestored = (Integer) resolver.invoke(Aliases.EXTERNAL_GET_PORT, instance) == oldPort;
            remoteRestored = (Boolean) resolver.invokeStatic(Aliases.EXTERNAL_GET_REMOTE) == oldRemote;
            configRestored = Objects.equals(
                resolver.invoke(Aliases.CONFIG_READ, config, EXTERNAL_APP_REMOTE_CONFIG_KEY, false),
                oldConfigRemote
            );
        } catch (RuntimeException readbackFailure) {
            failure = failure == null ? readbackFailure : failure;
            throw new RollbackFailed("external-app rollback could not be read back", failure);
        }
        if (failure != null || !portRestored || !remoteRestored || !configRestored) {
            throw new RollbackFailed(
                "external-app rollback unverified: failure=" + failure
                    + " port=" + portRestored + " remote=" + remoteRestored + " config=" + configRestored,
                failure
            );
        }
    }

    private EditorCommandResult gridSetting(final EditorGridSettingsRequest request) {
        requireResolvable(
            Aliases.GRID_ENTITY, Aliases.GRID_DEVELOPER_SETTING, Aliases.GRID_GET_SPACING,
            Aliases.GRID_GET_COLOR, Aliases.GRID_GET_JCOLOR, Aliases.GRID_GET_BOLD,
            Aliases.GRID_SET_SPACING, Aliases.GRID_SET_COLOR, Aliases.GRID_COLOR_CREATE,
            Aliases.CANVAS_CURRENT_VIEW_CONTEXT, Aliases.CANVAS_MODELING_VIEW,
            Aliases.CANVAS_DOC_SIZE, Aliases.CANVAS_SIZE_WIDTH, Aliases.CANVAS_SIZE_HEIGHT,
            Aliases.CANVAS_COMPLETE_PACK, Aliases.GRID_ALL_VIEW_CONTEXTS, Aliases.GRID_MODELING_DRAW,
            Aliases.GRID_ENTITY_FROM_DRAW, Aliases.GRID_SET_RESET, Aliases.GRID_UPDATE_MANAGER,
            Aliases.GRID_REPAINT_DEFAULT,
            APP_INSTANCE
        );
        final Object entity = resolver.readStaticField(Aliases.GRID_ENTITY);
        final Object developerSetting = resolver.readStaticField(Aliases.GRID_DEVELOPER_SETTING);
        // Active-document/edit admission BEFORE the no-op short-circuit: a missing or stale
        // modeling document must never receive a false EXECUTED result.
        final Object viewContext = resolver.invoke(Aliases.CANVAS_CURRENT_VIEW_CONTEXT, appInstance());
        final Object document = modelingDocument(viewContext);
        if (document == null) {
            return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE, request.commandId());
        }
        // No-op short-circuit: identical spacing and color produce no repaint/reset/persist side
        // effects and no synthetic activity.
        final int currentSpacing = (Integer) resolver.invoke(Aliases.GRID_GET_SPACING, entity);
        final int currentRgb = currentColorRgb(developerSetting);
        if (currentSpacing == request.spacingPixels() && currentRgb == rgb(request.color())) {
            return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
        }
        // Host-valid range admission before any mutation: the native dialog slider bounds the
        // spacing to [GridPanelEntity$a.b(), min(documentWidth, documentHeight)].
        final int minimum = (Integer) resolver.invoke(Aliases.GRID_GET_BOLD, entity);
        if (request.spacingPixels() < minimum) {
            return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE, request.commandId());
        }
        final Object documentSize = resolver.invoke(Aliases.CANVAS_DOC_SIZE, viewContext);
        final int documentWidth = (Integer) resolver.invoke(Aliases.CANVAS_SIZE_WIDTH, documentSize);
        final int documentHeight = (Integer) resolver.invoke(Aliases.CANVAS_SIZE_HEIGHT, documentSize);
        if (request.spacingPixels() > Math.min(documentWidth, documentHeight)) {
            return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE, request.commandId());
        }
        final Object hostColor = resolver.construct(
            Aliases.GRID_COLOR_CREATE,
            Math.round(request.color().red() * 255.0f),
            Math.round(request.color().green() * 255.0f),
            Math.round(request.color().blue() * 255.0f)
        );
        try {
            resolver.invoke(Aliases.GRID_SET_COLOR, developerSetting, hostColor);
            resolver.invoke(Aliases.GRID_SET_SPACING, entity, request.spacingPixels());
            refreshGrid();
        } catch (RuntimeException | Error failure) {
            try {
                rollbackGrid(developerSetting, entity, currentSpacing, currentRgb);
            } catch (RollbackFailed rollbackFailure) {
                rollbackFailure.addSuppressed(failure);
                throw rollbackFailure;
            }
            throw failure;
        }
        return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
    }

    /** Restores grid spacing/color, re-runs the refresh, and verifies both values by readback. */
    private void rollbackGrid(
        final Object developerSetting,
        final Object entity,
        final int oldSpacing,
        final int oldRgb
    ) {
        Throwable failure = null;
        try {
            final Object oldColor = resolver.construct(
                Aliases.GRID_COLOR_CREATE, (oldRgb >> 16) & 0xFF, (oldRgb >> 8) & 0xFF, oldRgb & 0xFF
            );
            resolver.invoke(Aliases.GRID_SET_COLOR, developerSetting, oldColor);
            resolver.invoke(Aliases.GRID_SET_SPACING, entity, oldSpacing);
        } catch (RuntimeException restoreFailure) {
            failure = restoreFailure;
        }
        try {
            refreshGrid();
        } catch (RuntimeException refreshFailure) {
            failure = failure == null ? refreshFailure : failure;
        }
        final boolean spacingRestored;
        final boolean colorRestored;
        try {
            spacingRestored = (Integer) resolver.invoke(Aliases.GRID_GET_SPACING, entity) == oldSpacing;
            colorRestored = currentColorRgb(developerSetting) == oldRgb;
        } catch (RuntimeException readbackFailure) {
            failure = failure == null ? readbackFailure : failure;
            throw new RollbackFailed("grid rollback could not be read back", failure);
        }
        if (failure != null || !spacingRestored || !colorRestored) {
            throw new RollbackFailed(
                "grid rollback unverified: failure=" + failure
                    + " spacing=" + spacingRestored + " color=" + colorRestored,
                failure
            );
        }
    }

    private void refreshGrid() {
        final Object app = appInstance();
        final Object pack = resolver.invoke(Aliases.CANVAS_COMPLETE_PACK, app);
        final java.util.List<?> viewContexts =
            (java.util.List<?>) resolver.invoke(Aliases.GRID_ALL_VIEW_CONTEXTS, pack);
        for (Object candidate : viewContexts) {
            if (resolver.isInstance(Aliases.CANVAS_MODELING_VIEW, candidate)) {
                final Object drawImpl = resolver.invoke(Aliases.GRID_MODELING_DRAW, candidate);
                final Object gridEntity = resolver.invoke(Aliases.GRID_ENTITY_FROM_DRAW, drawImpl);
                resolver.invoke(Aliases.GRID_SET_RESET, gridEntity, true);
            }
        }
        final Object updateManager = resolver.invoke(Aliases.GRID_UPDATE_MANAGER, pack);
        resolver.invokeStatic(Aliases.GRID_REPAINT_DEFAULT, updateManager, null, 1, null);
    }

    private EditorCommandResult canvasSetting(final EditorCanvasSettingsRequest request) {
        requireResolvable(
            Aliases.CANVAS_CURRENT_VIEW_CONTEXT, Aliases.CANVAS_MODELING_VIEW, Aliases.CANVAS_MODELING_DOC,
            Aliases.CANVAS_EDIT_MODE, Aliases.CANVAS_EDIT_MODE_MAIN, Aliases.CANVAS_MODEL_SOURCE,
            Aliases.CANVAS_CANVAS, Aliases.CANVAS_MODEL, Aliases.CANVAS_IS_EDITING,
            Aliases.CANVAS_PIXEL_WIDTH, Aliases.CANVAS_PIXEL_HEIGHT, Aliases.CANVAS_UNDO_MANAGER,
            Aliases.CANVAS_UNDO_POS, Aliases.CANVAS_BEGIN_EDIT, Aliases.CANVAS_SIMPLE_UNDO,
            Aliases.CANVAS_GROUP_ADD, Aliases.CANVAS_COMPANION, Aliases.CANVAS_NOTIFY_SIZE,
            Aliases.CANVAS_SET_PIXEL_WIDTH, Aliases.CANVAS_SET_PIXEL_HEIGHT, Aliases.CANVAS_VECTOR2,
            Aliases.CANVAS_VECTOR2_ZERO, Aliases.CANVAS_HANDLER, Aliases.CANVAS_SCALE_WITH_ANCHOR,
            Aliases.CANVAS_END_EDIT_DEFAULT, Aliases.CANVAS_MARK_DIRTY, Aliases.CANVAS_UNDO,
            APP_INSTANCE
        );
        final ModelingContext context = new ModelingContext();
        if ((Boolean) resolver.invoke(Aliases.CANVAS_IS_EDITING, context.editMode)) {
            // An unrelated edit is in progress; fail closed before the first mutation.
            return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE, request.commandId());
        }
        final int oldWidth = (Integer) resolver.invoke(Aliases.CANVAS_PIXEL_WIDTH, context.canvas);
        final int oldHeight = (Integer) resolver.invoke(Aliases.CANVAS_PIXEL_HEIGHT, context.canvas);
        if (oldWidth == request.widthPixels() && oldHeight == request.heightPixels()) {
            // No-op short-circuit: no beginEdit, no Undo entry, no dirty state, no side effects.
            return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
        }
        final int undoPosBefore = undoPosition(context.editMode);
        try {
            applyCanvasSize(context, request, oldWidth, oldHeight);
        } catch (RuntimeException | Error failure) {
            try {
                // Rollback through the native Undo path: close the transaction and undo the partial
                // group so no partial canvas/model state and no open edit remain.
                rollbackEdit(context.editMode, undoPosBefore);
            } catch (RollbackFailed rollbackFailure) {
                rollbackFailure.addSuppressed(failure);
                throw rollbackFailure;
            }
            // The native Undo must restore the exact original canvas dimensions.
            final int widthAfter = (Integer) resolver.invoke(Aliases.CANVAS_PIXEL_WIDTH, context.canvas);
            final int heightAfter = (Integer) resolver.invoke(Aliases.CANVAS_PIXEL_HEIGHT, context.canvas);
            if (widthAfter != oldWidth || heightAfter != oldHeight) {
                throw new RollbackFailed(
                    "canvas rollback unverified: canvas=" + widthAfter + "x" + heightAfter
                        + " expected=" + oldWidth + "x" + oldHeight,
                    failure
                );
            }
            throw failure;
        }
        return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
    }

    private void applyCanvasSize(
        final ModelingContext context,
        final EditorCanvasSettingsRequest request,
        final int oldWidth,
        final int oldHeight
    ) {
        final Object group = resolver.invoke(
            Aliases.CANVAS_BEGIN_EDIT, context.editMode, CANVAS_EDIT_LABEL
        );
        // The native dialog registers the LIVE canvas as the SimpleUndo target; the undo
        // snapshot is captured inside the constructor (undoData = target.deepCopy()).
        final Object undo = resolver.construct(Aliases.CANVAS_SIMPLE_UNDO, "canvas", context.canvas, null);
        resolver.invoke(Aliases.CANVAS_GROUP_ADD, group, undo);
        final Object companion = resolver.readStaticField(Aliases.CANVAS_COMPANION);
        resolver.invoke(Aliases.CANVAS_NOTIFY_SIZE, companion, request.widthPixels(), request.heightPixels());
        resolver.invoke(Aliases.CANVAS_SET_PIXEL_WIDTH, context.canvas, request.widthPixels());
        resolver.invoke(Aliases.CANVAS_SET_PIXEL_HEIGHT, context.canvas, request.heightPixels());
        // Center anchor (dialog default): content translates by half the size delta on each axis.
        final float deltaX = (request.widthPixels() - oldWidth) / 2.0f;
        final float deltaY = (request.heightPixels() - oldHeight) / 2.0f;
        final Object translate = resolver.construct(Aliases.CANVAS_VECTOR2, deltaX, deltaY);
        final Object zero = resolver.construct(Aliases.CANVAS_VECTOR2_ZERO);
        final Object handler = resolver.invoke(Aliases.CANVAS_HANDLER, context.modelSource);
        resolver.invoke(
            Aliases.CANVAS_SCALE_WITH_ANCHOR,
            handler,
            context.model,
            1.0f,
            translate,
            zero,
            group
        );
        resolver.invokeStatic(
            Aliases.CANVAS_END_EDIT_DEFAULT, context.editMode, false, null, 1, null
        );
        resolver.invoke(Aliases.CANVAS_MARK_DIRTY, context.document);
    }

    private EditorCommandResult resizeModelDocument(final EditorResizeModelRequest request) {
        requireResolvable(
            Aliases.CANVAS_CURRENT_VIEW_CONTEXT, Aliases.CANVAS_MODELING_VIEW, Aliases.CANVAS_MODELING_DOC,
            Aliases.CANVAS_EDIT_MODE, Aliases.CANVAS_EDIT_MODE_MAIN, Aliases.CANVAS_MODEL_SOURCE,
            Aliases.CANVAS_CANVAS, Aliases.CANVAS_MODEL, Aliases.CANVAS_IS_EDITING,
            Aliases.CANVAS_UNDO_MANAGER, Aliases.CANVAS_UNDO_POS, Aliases.RESIZE_GUARD,
            Aliases.RESIZE_GUARD_CURRENT, Aliases.RESIZE_GUARD_ACTIVE, Aliases.CANVAS_HANDLER,
            Aliases.CANVAS_COMPLETE_PACK, Aliases.RESIZE_SCALE_MODEL, Aliases.CANVAS_MARK_DIRTY,
            Aliases.CANVAS_UNDO, Aliases.CANVAS_END_EDIT_DEFAULT,
            APP_INSTANCE
        );
        final ModelingContext context = new ModelingContext();
        if ((Boolean) resolver.invoke(Aliases.CANVAS_IS_EDITING, context.editMode)) {
            return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE, request.commandId());
        }
        if (request.percent() == 100) {
            // No-op short-circuit after admission: a 100% resize would wrap the model in a
            // redundant deformer wrapper; the typed op applies no side effects.
            return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
        }
        final Object guard = resolver.readStaticField(Aliases.RESIZE_GUARD);
        final Object current = resolver.invoke(Aliases.RESIZE_GUARD_CURRENT, guard);
        if ((Boolean) resolver.invoke(Aliases.RESIZE_GUARD_ACTIVE, guard, current)) {
            return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE, request.commandId());
        }
        final Object handler = resolver.invoke(Aliases.CANVAS_HANDLER, context.modelSource);
        final Object pack = resolver.invoke(Aliases.CANVAS_COMPLETE_PACK, appInstance());
        final float scale = request.percent() / 100.0f;
        final int undoPosBefore = undoPosition(context.editMode);
        try {
            resolver.invokeStatic(
                Aliases.RESIZE_SCALE_MODEL, handler, pack, context.editMode, context.model,
                scale, null, null, 48, null
            );
        } catch (RuntimeException | Error failure) {
            try {
                rollbackEdit(context.editMode, undoPosBefore);
            } catch (RollbackFailed rollbackFailure) {
                rollbackFailure.addSuppressed(failure);
                throw rollbackFailure;
            }
            throw failure;
        }
        if ((Boolean) resolver.invoke(Aliases.CANVAS_IS_EDITING, context.editMode)) {
            // The native ModelHandler.a swallowed an internal failure and left the group open;
            // close the transaction, undo the partial group, and report the sanitized failure.
            try {
                rollbackEdit(context.editMode, undoPosBefore);
            } catch (RollbackFailed rollbackFailure) {
                throw rollbackFailure;
            }
            return new EditorCommandResult(EditorCommandResult.Status.FAILED, request.commandId());
        }
        resolver.invoke(Aliases.CANVAS_MARK_DIRTY, context.document);
        return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
    }

    private EditorCommandResult saveAs(final ResolvedEditorFileCommand command) {
        requireResolvable(
            Aliases.FILE_SCENE_DOCUMENT, Aliases.FILE_SCENE_CONTENT, Aliases.FILE_SAVE_SCENE,
            Aliases.FILE_SAVE_MODEL, Aliases.CANVAS_CURRENT_VIEW_CONTEXT,
            APP_INSTANCE
        );
        final EditorFileUsePointGuard.Result admission = EditorFileUsePointGuard.admit(command);
        if (!admission.allowed()) {
            return new EditorCommandResult(EditorCommandResult.Status.REJECTED, command.commandId());
        }
        final Object viewContext = resolver.invoke(
            Aliases.CANVAS_CURRENT_VIEW_CONTEXT, appInstance()
        );
        final File target = command.file().toFile();
        if (resolver.isInstance(Aliases.FILE_SCENE_DOCUMENT, viewContext)) {
            final Object content = resolver.invoke(Aliases.FILE_SCENE_CONTENT, viewContext);
            final boolean saved = (Boolean) resolver.invoke(Aliases.FILE_SAVE_SCENE, content, target, true);
            return saved
                ? new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.commandId())
                : new EditorCommandResult(EditorCommandResult.Status.FAILED, command.commandId());
        }
        final Object document = modelingDocument(viewContext);
        if (document == null) {
            return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE, command.commandId());
        }
        final boolean saved = (Boolean) resolver.invoke(Aliases.FILE_SAVE_MODEL, document, target, true);
        return saved
            ? new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.commandId())
            : new EditorCommandResult(EditorCommandResult.Status.FAILED, command.commandId());
    }

    private int undoPosition(final Object editMode) {
        final Object undoManager = resolver.invoke(Aliases.CANVAS_UNDO_MANAGER, editMode);
        return (Integer) resolver.invoke(Aliases.CANVAS_UNDO_POS, undoManager);
    }

    /**
     * Closes the transaction and undoes the partial group, then verifies by readback that the
     * Undo position and the edit state are back to the observed originals. Any failure or
     * mismatch throws {@link RollbackFailed}; rollback is never silently best-effort.
     */
    private void rollbackEdit(final Object editMode, final int undoPosBefore) {
        Throwable failure = null;
        try {
            resolver.invokeStatic(Aliases.CANVAS_END_EDIT_DEFAULT, editMode, false, null, 1, null);
        } catch (RuntimeException endFailure) {
            failure = endFailure;
        }
        try {
            final Object undoManager = resolver.invoke(Aliases.CANVAS_UNDO_MANAGER, editMode);
            final int posAfter = (Integer) resolver.invoke(Aliases.CANVAS_UNDO_POS, undoManager);
            if (posAfter > undoPosBefore) {
                resolver.invoke(Aliases.CANVAS_UNDO, undoManager);
            }
        } catch (RuntimeException undoFailure) {
            failure = failure == null ? undoFailure : failure;
        }
        final boolean posRestored;
        final boolean editingClosed;
        try {
            final Object undoManager = resolver.invoke(Aliases.CANVAS_UNDO_MANAGER, editMode);
            posRestored = (Integer) resolver.invoke(Aliases.CANVAS_UNDO_POS, undoManager) == undoPosBefore;
            editingClosed = !(Boolean) resolver.invoke(Aliases.CANVAS_IS_EDITING, editMode);
        } catch (RuntimeException readbackFailure) {
            failure = failure == null ? readbackFailure : failure;
            throw new RollbackFailed("edit rollback could not be read back", failure);
        }
        if (failure != null || !posRestored || !editingClosed) {
            throw new RollbackFailed(
                "edit rollback unverified: failure=" + failure
                    + " undoPos=" + posRestored + " editingClosed=" + editingClosed,
                failure
            );
        }
    }

    private int currentColorRgb(final Object developerSetting) {
        final Object hostColor = resolver.invoke(Aliases.GRID_GET_COLOR, developerSetting);
        final Object jColor = resolver.invoke(Aliases.GRID_GET_JCOLOR, hostColor);
        return ((java.awt.Color) jColor).getRGB() & 0xFFFFFF;
    }

    private static int rgb(final Color color) {
        return (Math.round(color.red() * 255.0f) << 16)
            | (Math.round(color.green() * 255.0f) << 8)
            | Math.round(color.blue() * 255.0f);
    }

    private Object appInstance() {
        return resolver.invokeStatic(APP_INSTANCE);
    }

    private Object modelingDocument(final Object viewContext) {
        if (!resolver.isInstance(Aliases.CANVAS_MODELING_VIEW, viewContext)) {
            return null;
        }
        return resolver.invoke(Aliases.CANVAS_MODELING_DOC, viewContext);
    }

    /**
     * Resolves every selector the operation and its rollback may need BEFORE the first mutation.
     * A missing or drifted alias throws before any host state can change; the caller sanitizes
     * the failure to a closed result.
     */
    private void requireResolvable(final String... aliases) {
        for (String alias : aliases) {
            final StaticSelector selector = resolver.verifiedSelector(alias);
            switch (selector.kind()) {
                case METHOD -> {
                    if ((selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) != 0) {
                        resolver.bindStatic(alias);
                    } else {
                        resolver.bind(alias);
                    }
                }
                case FIELD -> {
                    if ((selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
                        throw new IllegalStateException(
                            "instance-field alias is not supported by typed editor commands: " + alias
                        );
                    }
                    resolver.readStaticField(alias);
                }
                case CONSTRUCTOR, CLASS -> {
                    // Plan-attested selectors: prove the owner class loads from the attested host
                    // classloader without invoking anything.
                    try {
                        Class.forName(
                            selector.ownerInternalName().replace('/', '.'),
                            false,
                            resolver.hostClassLoader()
                        );
                    } catch (ClassNotFoundException | LinkageError failure) {
                        throw new IllegalStateException(
                            "verified host type cannot be loaded for alias " + alias,
                            failure
                        );
                    }
                }
            }
        }
    }

    /** Shared modeling-context resolution with the exact native preconditions. */
    private final class ModelingContext {
        final Object viewContext;
        final Object document;
        final Object editMode;
        final Object modelSource;
        final Object canvas;
        final Object model;

        private ModelingContext() {
            this.viewContext = resolver.invoke(Aliases.CANVAS_CURRENT_VIEW_CONTEXT, appInstance());
            if (!resolver.isInstance(Aliases.CANVAS_MODELING_VIEW, viewContext)) {
                throw new InvalidState();
            }
            this.document = resolver.invoke(Aliases.CANVAS_MODELING_DOC, viewContext);
            this.editMode = resolver.invoke(Aliases.CANVAS_EDIT_MODE, document);
            if (!resolver.isInstance(Aliases.CANVAS_EDIT_MODE_MAIN, editMode)) {
                throw new InvalidState();
            }
            this.modelSource = resolver.invoke(Aliases.CANVAS_MODEL_SOURCE, document);
            this.canvas = resolver.invoke(Aliases.CANVAS_CANVAS, modelSource);
            this.model = resolver.invoke(Aliases.CANVAS_MODEL, viewContext);
        }
    }

    /** Internal marker for a sanitized INVALID_STATE outcome. */
    static final class InvalidState extends RuntimeException {
        InvalidState() {
            super("host state does not admit the typed editor command");
        }
    }

    /**
     * Internal marker for an operation failure whose rollback could not be verified. The command
     * fails closed: the sanitized result is FAILED and no partial-state safety is claimed.
     */
    static final class RollbackFailed extends RuntimeException {
        RollbackFailed(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}

package dev.turboism.adapter.cubism.temporarydeform;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformContext;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformCursor;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformPointerEvent;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformRenderScene;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformSession;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformTool;
import dev.turboism.sdk.ui.appearance.UiColor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dynamic proxy adapting Cubism's {@code ITemporaryHandler} contract to one
 * {@link TemporaryDeformTool} / {@link TemporaryDeformSession}.
 */
final class NativeTemporaryDeformHandlerAdapter {

    private static final String LOG_COMPONENT = "temporary-deform";
    private static final String WARP_DEFORMER_CLASS = "com.live2d.cubism.doc.model.deformer.warp.CWarpDeformer";
    private static final String ACTION_CONTEXT_CLASS = "com.live2d.cubism.view.context.actionManager.aG";
    private static final String ACTION_BASE_CLASS = "com.live2d.cubism.view.context.actionManager.N";
    private static final String DRAW_HELPER_CLASS = "com.live2d.graphics3d.a.d";
    private static final String MODELING_VIEW_CLASS = "com.live2d.cubism.view.context.CEViewContext_ModelingView";
    private static final String GVECTOR2_CLASS = "com.live2d.graphics3d.type.GVector2";
    private static final String SIMPLE_LINES_ENTITY_CLASS = "com.live2d.graphics3d.geometry.GSimpleLinesEntity";
    private static final String SIMPLE_POINTS_ENTITY_CLASS = "com.live2d.graphics3d.geometry.GSimplePointsEntity";
    private static final String SHADER_HOLDER_CLASS = "com.live2d.graphics3d.shader.a";
    private static final String GSHADER_CLASS = "com.live2d.graphics3d.shader.GShader";
    private static final String CCOLOR_CLASS = "com.live2d.type.CColor";
    private static final String GVECTOR4_CLASS = "com.live2d.graphics3d.type.GVector4";
    private static final String GMATERIAL_CLASS = "com.live2d.graphics3d.material.GMaterial";
    private static final String SORTING_LAYER_CLASS = "com.live2d.graphics3d.component.a.c";
    private static final String SORTING_LAYER_UI_CLASS = "com.live2d.graphics3d.component.a.c$c";
    private static final String ENTITIES_CLASS = "com.live2d.graphics3d.entity.Entities";
    private static final String GENTITY_CLASS = "com.live2d.graphics3d.entity.GEntity";
    private static final String MODIFY_TARGET_CLASS =
            "com.live2d.cubism.doc.model.extension.controller.CEAct_Controller_ModifyTarget";
    private static final String PARAM_CONTROLLABLE_HANDLER_CLASS =
            "com.live2d.cubism.doc.model.ACParameterControllableHandler";
    private static final String UNDOABLE_CLASS = "com.live2d.undo.ACUndoable";

    private static final String LINE_ENTITY_NAME = "TurboismTemporaryDeformLines";
    private static final String POINT_ENTITY_NAME = "TurboismTemporaryDeformPoints";
    private static final String LINES_SHADER_METHOD = "g";
    private static final String WARP_HANDLER_ENUM_CONSTANT = "WARP_HANDLER";
    private static final String DEFAULT_COLOR_NAME = "a";
    private static final String UNIFORM_INNER_COLOR = "innerColor";
    private static final String UNIFORM_OUTER_COLOR = "outerColor";
    private static final String UNIFORM_POINT_STEPS = "pointSteps";

    private static final int LINE_RENDER_ORDER = 1000;
    private static final int LINE_DRAW_MODE = 2;
    private static final int POINT_STEP_2D = 2;
    private static final int FLOAT_RGBA_STRIDE = 4;
    private static final int CREATE_UNDO_DEFAULT_MASK = 1;
    private static final int REFRESH_ACTION_DEFAULT_MASK = 3;
    private static final int ADD_ENTITY_DEFAULT_MASK = 2;
    private static final int PREMULTIPLY_DEFAULT_MASK = 15;
    private static final int MATERIAL_SET_DEFAULT_MASK = 4;
    private static final float POINT_STEP_0 = 0.5F;
    private static final float POINT_STEP_1 = 0.45F;
    private static final float POINT_STEP_2 = 0.25F;
    private static final float POINT_STEP_3 = 0.2F;

    private final TemporaryDeformHostProfile profile;
    private final ClassLoader hostLoader;
    private final TemporaryDeformTool tool;
    private final Runnable refreshToolDetailsCallback;
    private final Runnable requestRepaintCallback;
    private final Runnable unbindCallback;
    private final Object handlerProxy;

    private final List<Object> lineEntities = new ArrayList<>();
    private final List<Object> pointEntities = new ArrayList<>();

    private TemporaryDeformSession session;
    private List<NativeWarpDeformerTargetAdapter> targets = List.of();
    private Object boundModelingView;
    private boolean disposed;
    private boolean dragActive;

    NativeTemporaryDeformHandlerAdapter(
            final TemporaryDeformHostProfile profile,
            final ClassLoader hostLoader,
            final TemporaryDeformTool tool,
            final Runnable refreshToolDetailsCallback,
            final Runnable requestRepaintCallback,
            final Runnable unbindCallback)
            throws ClassNotFoundException {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.hostLoader = Objects.requireNonNull(hostLoader, "hostLoader");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.refreshToolDetailsCallback = Objects.requireNonNull(refreshToolDetailsCallback, "refreshToolDetails");
        this.requestRepaintCallback = Objects.requireNonNull(requestRepaintCallback, "requestRepaint");
        this.unbindCallback = Objects.requireNonNull(unbindCallback, "unbind");

        final Class<?> iface =
                Class.forName(profile.handlerInterfaceInternalName().replace('/', '.'), false, hostLoader);
        this.handlerProxy = Proxy.newProxyInstance(hostLoader, new Class<?>[] {iface}, this::dispatchProxyCall);
    }

    /** Returns the host-loader {@code ITemporaryHandler} proxy object. */
    Object hostProxy() {
        return handlerProxy;
    }

    /** Returns the active plugin session if initialized. */
    TemporaryDeformSession session() {
        return session;
    }

    /** Propagates a Tool Details option change into both the tool and active session. */
    void notifyOptionChanged(final String optionId, final String value) {
        if (disposed) {
            return;
        }
        try {
            tool.onOptionChanged(optionId, value);
            if (session != null) {
                session.onOptionChanged(optionId, value);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Option update failed safely: " + failure.getClass().getSimpleName());
        }
    }

    /** Detaches native GL entities and terminates the active plugin session cleanly. */
    void deactivate() {
        if (disposed) {
            return;
        }
        disposed = true;
        dragActive = false;
        detachSceneEntities();
        for (final NativeWarpDeformerTargetAdapter target : targets) {
            target.clearStrokeBaseline();
        }
        if (session != null) {
            try {
                session.deactivate();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                RuntimeDiagnostics.warn(
                        LOG_COMPONENT,
                        "Session deactivate failed safely: "
                                + failure.getClass().getSimpleName());
            }
            session = null;
        }
        targets = List.of();
        lineEntities.clear();
        pointEntities.clear();
        boundModelingView = null;
    }

    private Object dispatchProxyCall(final Object proxy, final Method method, final Object[] args) throws Throwable {
        final String name = method.getName();
        final int paramCount = method.getParameterCount();
        if ("equals".equals(name) && paramCount == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(name) && paramCount == 0) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(name) && paramCount == 0) {
            return "TurboismTemporaryDeformHandler(" + tool.id() + ")";
        }
        if (profile.hasHandlerTypeMethod() && "d".equals(name) && paramCount == 0) {
            return resolveWarpHandlerType();
        }
        if (disposed) {
            return "d".equals(name) && paramCount == 2 ? resolveNativeCursor(TemporaryDeformCursor.DEFAULT) : null;
        }
        if ("a".equals(name) && paramCount == 1) {
            final Class<?> paramType = method.getParameterTypes()[0];
            if (ACTION_CONTEXT_CLASS.equals(paramType.getName())) {
                initializeSession(args[0]);
                return null;
            }
            if (MODELING_VIEW_CLASS.equals(paramType.getName())) {
                attachToModelingView(args[0]);
                return null;
            }
            return null;
        }
        if ("a".equals(name) && paramCount == 5) {
            initializeSession(args[0]);
            return null;
        }
        if ("a".equals(name) && paramCount == 3 && method.getParameterTypes()[0] == float.class) {
            final float scale = ((Number) args[0]).floatValue();
            final boolean valid = Boolean.TRUE.equals(args[2]);
            renderScene(scale, valid);
            return null;
        }
        if ("d".equals(name) && paramCount == 2) {
            return handleHover(args[0], args[1]);
        }
        if ("a".equals(name) && paramCount == 2) {
            handlePress(args[0], args[1]);
            return null;
        }
        if ("b".equals(name) && paramCount == 2) {
            handleDrag(args[0], args[1]);
            return null;
        }
        if ("c".equals(name) && paramCount == 2) {
            handleRelease(args[0], args[1]);
            return null;
        }
        return null;
    }

    private void initializeSession(final Object actionContext) {
        try {
            if (actionContext == null) {
                return;
            }
            final Object model = actionContext.getClass().getMethod("i").invoke(actionContext);
            final Object modelingView = actionContext.getClass().getMethod("c").invoke(actionContext);
            boundModelingView = modelingView;
            final int editLevel = readEditLevel(modelingView);
            final Object selectedObj = actionContext.getClass().getMethod("r").invoke(actionContext);
            final List<NativeWarpDeformerTargetAdapter> captured = new ArrayList<>();
            if (selectedObj instanceof Iterable<?> iterable) {
                final Class<?> warpClass = Class.forName(WARP_DEFORMER_CLASS, false, hostLoader);
                for (final Object obj : iterable) {
                    if (warpClass.isInstance(obj)) {
                        final NativeWarpDeformerTargetAdapter adapter =
                                new NativeWarpDeformerTargetAdapter(obj, model, editLevel, hostLoader);
                        adapter.setActionContext(actionContext);
                        captured.add(adapter);
                    }
                }
            }
            this.targets = List.copyOf(captured);
            final TemporaryDeformContext sdkContext = new TemporaryDeformContext() {
                @Override
                public List<WarpDeformerTarget> selectedWarpDeformers() {
                    if (disposed) {
                        throw new IllegalStateException("temporary deform session is inactive");
                    }
                    return List.copyOf(targets);
                }

                @Override
                public void refreshToolDetails() {
                    if (disposed) {
                        return;
                    }
                    refreshToolDetailsCallback.run();
                }

                @Override
                public void requestRepaint() {
                    if (disposed) {
                        return;
                    }
                    requestRepaintCallback.run();
                }

                @Override
                public void unbind() {
                    unbindCallback.run();
                }
            };
            this.session = Objects.requireNonNull(tool.createSession(sdkContext), "tool.createSession()");
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Session initialization failed safely: "
                            + failure.getClass().getSimpleName());
        }
    }

    private void attachToModelingView(final Object modelingView) {
        if (modelingView == null) {
            return;
        }
        boundModelingView = modelingView;
        ensureSceneEntities(1, 1);
        attachEntityListToView(modelingView, lineEntities);
        attachEntityListToView(modelingView, pointEntities);
    }

    private Object handleHover(final Object posVec, final Object actionContext) {
        TemporaryDeformCursor cursor = TemporaryDeformCursor.DEFAULT;
        if (session != null && posVec != null && actionContext != null) {
            try {
                final TemporaryDeformPointerEvent event = buildPointerEvent(posVec, actionContext);
                cursor = Objects.requireNonNull(session.onPointerHover(event), "onPointerHover");
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                RuntimeDiagnostics.warn(
                        LOG_COMPONENT, "Hover callback failed safely: " + formatFailureSummary(failure));
            }
        }
        return resolveNativeCursor(cursor);
    }

    private void handlePress(final Object posVec, final Object actionContext) {
        if (session == null || posVec == null || actionContext == null) {
            return;
        }
        try {
            beginNativeUndoStroke(actionContext);
            for (final NativeWarpDeformerTargetAdapter target : targets) {
                target.setActionContext(actionContext);
                target.snapshotStrokeBaseline();
            }
            dragActive = true;
            session.onPointerPress(buildPointerEvent(posVec, actionContext));
            notifyLiveUpdate(actionContext);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(LOG_COMPONENT, "Press callback failed safely: " + formatFailureSummary(failure));
        }
    }

    private void handleDrag(final Object posVec, final Object actionContext) {
        if (session == null || !dragActive || posVec == null || actionContext == null) {
            return;
        }
        try {
            for (final NativeWarpDeformerTargetAdapter target : targets) {
                target.setActionContext(actionContext);
            }
            session.onPointerDrag(buildPointerEvent(posVec, actionContext));
            notifyLiveUpdate(actionContext);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(LOG_COMPONENT, "Drag callback failed safely: " + formatFailureSummary(failure));
        }
    }

    private void handleRelease(final Object posVec, final Object actionContext) {
        if (session == null || posVec == null || actionContext == null) {
            dragActive = false;
            return;
        }
        try {
            for (final NativeWarpDeformerTargetAdapter target : targets) {
                target.setActionContext(actionContext);
            }
            if (dragActive) {
                session.onPointerRelease(buildPointerEvent(posVec, actionContext));
            }
            finishNativeUndoStroke(actionContext);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(LOG_COMPONENT, "Release callback failed safely: " + formatFailureSummary(failure));
        } finally {
            dragActive = false;
            for (final NativeWarpDeformerTargetAdapter target : targets) {
                target.clearStrokeBaseline();
            }
        }
    }

    private static String formatFailureSummary(final Throwable failure) {
        final Throwable cause = failure.getCause();
        if (cause == null || cause == failure) {
            return failure.getClass().getSimpleName();
        }
        final String causeType = cause.getClass().getSimpleName();
        final String causeMessage = cause.getMessage();
        return failure.getClass().getSimpleName()
                + " <- "
                + (causeMessage != null && !causeMessage.isBlank() ? causeType + ": " + causeMessage : causeType);
    }

    private void renderScene(final float cameraScale, final boolean valid) {
        final TemporaryDeformRenderScene scene = resolveRenderScene(cameraScale, valid);
        final List<TemporaryDeformRenderScene.LineLayer> lineLayers = scene.lineLayers();
        final List<TemporaryDeformRenderScene.PointLayer> pointLayers = scene.pointLayers();
        final int prevLines = lineEntities.size();
        final int prevPoints = pointEntities.size();
        try {
            ensureSceneEntities(lineLayers.size(), pointLayers.size());
            if (boundModelingView != null && lineEntities.size() > prevLines) {
                attachEntityListToView(boundModelingView, lineEntities.subList(prevLines, lineEntities.size()));
            }
            if (boundModelingView != null && pointEntities.size() > prevPoints) {
                attachEntityListToView(boundModelingView, pointEntities.subList(prevPoints, pointEntities.size()));
            }
            for (int i = 0; i < lineEntities.size(); i++) {
                final Object entity = lineEntities.get(i);
                if (i < lineLayers.size()) {
                    uploadLineLayer(entity, lineLayers.get(i));
                } else {
                    clearLineEntity(entity);
                }
            }
            for (int i = 0; i < pointEntities.size(); i++) {
                final Object entity = pointEntities.get(i);
                if (i < pointLayers.size()) {
                    uploadPointLayer(entity, pointLayers.get(i));
                } else {
                    clearPointEntity(entity);
                }
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Scene upload failed safely: " + failure.getClass().getSimpleName());
        }
    }

    private TemporaryDeformRenderScene resolveRenderScene(final float cameraScale, final boolean valid) {
        if (session == null || disposed) {
            return TemporaryDeformRenderScene.EMPTY;
        }
        try {
            final TemporaryDeformRenderScene rendered = session.render(cameraScale, valid);
            return rendered != null ? rendered : TemporaryDeformRenderScene.EMPTY;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Session render failed safely: " + failure.getClass().getSimpleName());
            return TemporaryDeformRenderScene.EMPTY;
        }
    }

    private TemporaryDeformPointerEvent buildPointerEvent(final Object posVec, final Object actionContext)
            throws ReflectiveOperationException {
        final float x = ((Number) posVec.getClass().getMethod("getX").invoke(posVec)).floatValue();
        final float y = ((Number) posVec.getClass().getMethod("getY").invoke(posVec)).floatValue();
        final float hitRadius = ((Number) actionContext
                        .getClass()
                        .getMethod(profile.hitRadiusMethodName())
                        .invoke(actionContext))
                .floatValue();
        final boolean shiftDown = Boolean.TRUE.equals(
                actionContext.getClass().getMethod(profile.shiftMethodName()).invoke(actionContext));
        final boolean controlDown = Boolean.TRUE.equals(
                actionContext.getClass().getMethod(profile.ctrlMethodName()).invoke(actionContext));
        return new TemporaryDeformPointerEvent(x, y, hitRadius, shiftDown, controlDown, false);
    }

    private void beginNativeUndoStroke(final Object actionContext) throws ReflectiveOperationException {
        final Object groupUndo =
                actionContext.getClass().getMethod("a", String.class).invoke(actionContext, tool.label());
        if (groupUndo == null) {
            return;
        }
        final Object model = actionContext.getClass().getMethod("i").invoke(actionContext);
        final Class<?> paramHandlerClass = Class.forName(PARAM_CONTROLLABLE_HANDLER_CLASS, false, hostLoader);
        final Method createKeyformUndo = paramHandlerClass.getMethod(
                "createUndo_forKeyformEdit$default", paramHandlerClass, String.class, int.class, Object.class);
        final Class<?> undoableClass = Class.forName(UNDOABLE_CLASS, false, hostLoader);
        final Method plusAssign = groupUndo.getClass().getMethod("plusAssign", undoableClass);
        for (final NativeWarpDeformerTargetAdapter target : targets) {
            final Object handler =
                    target.source().getClass().getMethod("getHandler").invoke(target.source());
            final Object undoEdit = createKeyformUndo.invoke(null, handler, null, CREATE_UNDO_DEFAULT_MASK, null);
            if (undoEdit != null) {
                plusAssign.invoke(groupUndo, undoEdit);
            }
        }
        final Object drawables = actionContext.getClass().getMethod("C").invoke(actionContext);
        if (drawables instanceof Iterable<?> iterable && model != null) {
            final List<Object> guids = new ArrayList<>();
            for (final Object drawable : iterable) {
                if (drawable != null) {
                    guids.add(drawable.getClass().getMethod("getGuid").invoke(drawable));
                }
            }
            if (!guids.isEmpty()) {
                final Class<?> modifyTargetClass = Class.forName(MODIFY_TARGET_CLASS, false, hostLoader);
                final Object instance = modifyTargetClass.getField("a").get(null);
                instance.getClass()
                        .getMethod("a", actionContext.getClass(), model.getClass(), List.class)
                        .invoke(instance, actionContext, model, guids);
            }
        }
    }

    private void notifyLiveUpdate(final Object actionContext) throws ReflectiveOperationException {
        final Object view = actionContext.getClass().getMethod("c").invoke(actionContext);
        final Object pack = view.getClass().getMethod("getCompletePack").invoke(view);
        pack.getClass().getMethod("updateParameter", boolean.class).invoke(pack, false);
        requestRepaintCallback.run();
    }

    private void finishNativeUndoStroke(final Object actionContext) throws ReflectiveOperationException {
        final Class<?> baseClass = Class.forName(ACTION_BASE_CLASS, false, hostLoader);
        final Class<?> fn1Class = Class.forName("kotlin.jvm.functions.Function1", false, hostLoader);
        final Method commit =
                baseClass.getMethod("a", baseClass, boolean.class, boolean.class, fn1Class, int.class, Object.class);
        commit.invoke(null, actionContext, false, false, null, REFRESH_ACTION_DEFAULT_MASK, null);
    }

    private void ensureSceneEntities(final int lineCount, final int pointCount) {
        try {
            if (lineEntities.size() < lineCount) {
                final Class<?> linesClass = Class.forName(SIMPLE_LINES_ENTITY_CLASS, false, hostLoader);
                final Class<?> shaderClass = Class.forName(GSHADER_CLASS, false, hostLoader);
                final Object shader = resolveLineShader();
                final Constructor<?> ctor = linesClass.getConstructor(String.class, shaderClass);
                while (lineEntities.size() < lineCount) {
                    final Object entity = ctor.newInstance(LINE_ENTITY_NAME, shader);
                    final Object renderer =
                            entity.getClass().getMethod("getRenderer").invoke(entity);
                    applyOverlaySortingLayer(renderer);
                    renderer.getClass()
                            .getMethod("setOrderInLayer", int.class)
                            .invoke(renderer, LINE_RENDER_ORDER + lineEntities.size());
                    lineEntities.add(entity);
                }
            }
            if (pointEntities.size() < pointCount) {
                final Class<?> pointsClass = Class.forName(SIMPLE_POINTS_ENTITY_CLASS, false, hostLoader);
                final Class<?> shaderClass = Class.forName(GSHADER_CLASS, false, hostLoader);
                final Object shader = resolvePointShader();
                final Constructor<?> ctor = pointsClass.getConstructor(String.class, shaderClass);
                while (pointEntities.size() < pointCount) {
                    final Object entity = ctor.newInstance(POINT_ENTITY_NAME, shader);
                    final Object renderer =
                            entity.getClass().getMethod("getPointRenderer").invoke(entity);
                    renderer.getClass()
                            .getMethod("setOrderInLayer", int.class)
                            .invoke(renderer, LINE_RENDER_ORDER + lineCount + pointEntities.size() + 1);
                    pointEntities.add(entity);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("failed to allocate overlay GL entities", failure);
        }
    }

    private void attachEntityListToView(final Object modelingView, final List<Object> entities) {
        if (modelingView == null || entities.isEmpty()) {
            return;
        }
        try {
            final Object model = modelingView.getClass().getMethod("getModel").invoke(modelingView);
            final Object children = model.getClass().getMethod("getChildren").invoke(model);
            final Object sceneGraph =
                    modelingView.getClass().getMethod("getSceneGraph").invoke(modelingView);
            final Class<?> entitiesClass = Class.forName(ENTITIES_CLASS, false, hostLoader);
            final Class<?> entityClass = Class.forName(GENTITY_CLASS, false, hostLoader);
            final Method addDefault = entitiesClass.getMethod(
                    "add$default", entitiesClass, entityClass, int.class, int.class, Object.class);
            final Method setVolatile = sceneGraph.getClass().getMethod("setVolatile", entityClass);
            for (final Object entity : entities) {
                addDefault.invoke(null, children, entity, 0, ADD_ENTITY_DEFAULT_MASK, null);
                setVolatile.invoke(sceneGraph, entity);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Failed attaching GL entity cleanly: " + failure.getClass().getSimpleName());
        }
    }

    private void detachSceneEntities() {
        if (boundModelingView == null) {
            return;
        }
        try {
            final Object model =
                    boundModelingView.getClass().getMethod("getModel").invoke(boundModelingView);
            final Object children = model.getClass().getMethod("getChildren").invoke(model);
            final Method removeMethod = children.getClass().getMethod("remove", Object.class);
            for (final Object entity : lineEntities) {
                removeMethod.invoke(children, entity);
            }
            for (final Object entity : pointEntities) {
                removeMethod.invoke(children, entity);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void uploadLineLayer(final Object entity, final TemporaryDeformRenderScene.LineLayer layer)
            throws ReflectiveOperationException {
        final Class<?> vecClass = Class.forName(GVECTOR2_CLASS, false, hostLoader);
        final Constructor<?> vecCtor = vecClass.getConstructor(float.class, float.class);
        final ArrayList<ArrayList<Object>> nativeLines =
                new ArrayList<>(layer.polylines().size());
        for (final List<Point2> polyline : layer.polylines()) {
            final ArrayList<Object> nativeLine = new ArrayList<>(polyline.size());
            for (final Point2 p : polyline) {
                nativeLine.add(vecCtor.newInstance(p.x(), p.y()));
            }
            nativeLines.add(nativeLine);
        }
        final Object color = toNativeColor(layer.color());
        final Object shader = resolveLineShader();
        entity.getClass()
                .getMethod(
                        "setupLineFromLines",
                        ArrayList.class,
                        color.getClass(),
                        float.class,
                        shader.getClass(),
                        int.class)
                .invoke(entity, nativeLines, color, layer.width(), shader, LINE_DRAW_MODE);
    }

    private void clearLineEntity(final Object entity) throws ReflectiveOperationException {
        final Object color = toNativeColor(new UiColor(0.0F, 0.0F, 0.0F, 0.0F));
        final Object shader = resolveLineShader();
        entity.getClass()
                .getMethod(
                        "setupLineFromLines",
                        ArrayList.class,
                        color.getClass(),
                        float.class,
                        shader.getClass(),
                        int.class)
                .invoke(entity, new ArrayList<>(), color, 1.0F, shader, LINE_DRAW_MODE);
    }

    private void uploadPointLayer(final Object entity, final TemporaryDeformRenderScene.PointLayer layer)
            throws ReflectiveOperationException {
        final Object renderer = entity.getClass().getMethod("getPointRenderer").invoke(entity);
        final List<Point2> pts = layer.points();
        final int count = pts.size();
        final float[] positions = new float[count * POINT_STEP_2D];
        final int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            final Point2 p = pts.get(i);
            positions[i * POINT_STEP_2D] = p.x();
            positions[i * POINT_STEP_2D + 1] = p.y();
            indices[i] = i;
        }
        renderer.getClass().getMethod("setStep", int.class).invoke(renderer, POINT_STEP_2D);
        renderer.getClass().getMethod("setPointCount", int.class).invoke(renderer, count);
        renderer.getClass().getMethod("setPointSize", float.class).invoke(renderer, (float) layer.pixelSize());
        renderer.getClass().getMethod("setPositions", float[].class).invoke(renderer, positions);
        renderer.getClass().getMethod("setIndices", int[].class).invoke(renderer, indices);
        applyOverlaySortingLayer(renderer);
        refreshPointMeshAndBuffers(renderer);
        applyPointMaterial(renderer, layer.fillColor(), layer.borderColor());
    }

    private void clearPointEntity(final Object entity) throws ReflectiveOperationException {
        final Object renderer = entity.getClass().getMethod("getPointRenderer").invoke(entity);
        renderer.getClass().getMethod("setPointCount", int.class).invoke(renderer, 0);
        renderer.getClass().getMethod("setPositions", float[].class).invoke(renderer, new float[0]);
        renderer.getClass().getMethod("setIndices", int[].class).invoke(renderer, new int[0]);
        refreshPointMeshAndBuffers(renderer);
    }

    private void applyOverlaySortingLayer(final Object renderer) {
        if (renderer == null) {
            return;
        }
        try {
            final Class<?> layerBase = Class.forName(SORTING_LAYER_CLASS, false, hostLoader);
            final Class<?> uiLayer = Class.forName(SORTING_LAYER_UI_CLASS, true, hostLoader);
            final Object instance = uiLayer.getField(DEFAULT_COLOR_NAME).get(null);
            renderer.getClass().getMethod("setSortingLayer", layerBase).invoke(renderer, instance);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void refreshPointMeshAndBuffers(final Object renderer) throws ReflectiveOperationException {
        try {
            renderer.getClass().getMethod("updateMeshData").invoke(renderer);
        } catch (NoSuchMethodException fallback) {
            final Object meshUpdated =
                    renderer.getClass().getMethod("getMeshUpdated").invoke(renderer);
            meshUpdated.getClass().getMethod("invoke").invoke(meshUpdated);
        }
        try {
            final Object posBuffer =
                    renderer.getClass().getMethod("getPositionsBuffer").invoke(renderer);
            if (posBuffer != null) {
                posBuffer.getClass().getMethod("l").invoke(posBuffer);
            }
        } catch (NoSuchMethodException ignored) {
        }
    }

    private void applyPointMaterial(final Object renderer, final UiColor fillColor, final UiColor borderColor)
            throws ReflectiveOperationException {
        final Object material = renderer.getClass().getMethod("getMaterial").invoke(renderer);
        if (material == null) {
            return;
        }
        final Class<?> materialClass = Class.forName(GMATERIAL_CLASS, false, hostLoader);
        final Class<?> colorClass = Class.forName(CCOLOR_CLASS, false, hostLoader);
        final Class<?> vec4Class = Class.forName(GVECTOR4_CLASS, false, hostLoader);
        final Method premultiply = colorClass.getMethod(
                "toPremultiplied$default",
                colorClass,
                float.class,
                float.class,
                float.class,
                float.class,
                int.class,
                Object.class);
        final Object inner = premultiply.invoke(
                null, toNativeColor(fillColor), 0.0F, 0.0F, 0.0F, 0.0F, PREMULTIPLY_DEFAULT_MASK, null);
        final Object outer = premultiply.invoke(
                null, toNativeColor(borderColor), 0.0F, 0.0F, 0.0F, 0.0F, PREMULTIPLY_DEFAULT_MASK, null);
        final Method setColor = materialClass.getMethod(
                "setColor$default", materialClass, String.class, colorClass, Integer.class, int.class, Object.class);
        setColor.invoke(null, material, UNIFORM_INNER_COLOR, inner, null, MATERIAL_SET_DEFAULT_MASK, null);
        setColor.invoke(null, material, UNIFORM_OUTER_COLOR, outer, null, MATERIAL_SET_DEFAULT_MASK, null);

        // Cubism point shaders read 4 normalized step thresholds for inner/outer ring blending.
        final Object steps = vec4Class.getConstructor().newInstance();
        final Method setCoord = vec4Class.getMethod("set", int.class, float.class);
        setCoord.invoke(steps, 0, POINT_STEP_0);
        setCoord.invoke(steps, 1, POINT_STEP_1);
        setCoord.invoke(steps, 2, POINT_STEP_2);
        setCoord.invoke(steps, 3, POINT_STEP_3);
        final Method setVector4 = materialClass.getMethod(
                "setVector4$default", materialClass, String.class, vec4Class, Integer.class, int.class, Object.class);
        setVector4.invoke(null, material, UNIFORM_POINT_STEPS, steps, null, MATERIAL_SET_DEFAULT_MASK, null);
    }

    private Object resolveLineShader() throws ReflectiveOperationException {
        final Class<?> holder = Class.forName(SHADER_HOLDER_CLASS, true, hostLoader);
        final Object instance = holder.getField(DEFAULT_COLOR_NAME).get(null);
        return instance.getClass().getMethod(LINES_SHADER_METHOD).invoke(instance);
    }

    private Object resolvePointShader() throws ReflectiveOperationException {
        final Class<?> holder = Class.forName(profile.pointShaderClassName(), true, hostLoader);
        final Object instance = holder.getField(DEFAULT_COLOR_NAME).get(null);
        return instance.getClass().getMethod(profile.pointShaderMethodName()).invoke(instance);
    }

    private Object resolveNativeCursor(final TemporaryDeformCursor cursor) {
        try {
            final Class<?> holder = Class.forName(profile.cursorClassName(), true, hostLoader);
            final Object instance = holder.getField(DEFAULT_COLOR_NAME).get(null);
            final String methodName = cursor == TemporaryDeformCursor.DEFAULT
                    ? profile.defaultCursorMethodName()
                    : profile.hoverCursorMethodName();
            return instance.getClass().getMethod(methodName).invoke(instance);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private Object resolveWarpHandlerType() {
        try {
            final Class<?> enumClass = Class.forName(profile.enumInternalName().replace('/', '.'), false, hostLoader);
            final Field constant = enumClass.getField(WARP_HANDLER_ENUM_CONSTANT);
            return constant.get(null);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private Object toNativeColor(final UiColor color) throws ReflectiveOperationException {
        final Class<?> cColorClass = Class.forName(CCOLOR_CLASS, false, hostLoader);
        return cColorClass
                .getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(color.red(), color.green(), color.blue(), color.alpha());
    }

    private static int readEditLevel(final Object modelingView) {
        if (modelingView == null) {
            return 2;
        }
        try {
            final Object pack =
                    modelingView.getClass().getMethod("getCompletePack").invoke(modelingView);
            if (pack != null) {
                return ((Number) pack.getClass().getMethod("getEditLevel").invoke(pack)).intValue();
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
        return 2;
    }
}

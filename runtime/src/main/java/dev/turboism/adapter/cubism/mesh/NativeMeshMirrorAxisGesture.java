package dev.turboism.adapter.cubism.mesh;

import static dev.turboism.adapter.cubism.mesh.NativeMeshMirrorReflectionOps.*;

import dev.turboism.adapter.cubism.mesh.MeshMirrorGeometry.Line;
import dev.turboism.adapter.cubism.mesh.MeshMirrorGeometry.Point;
import java.awt.event.MouseEvent;

/** Keeps the rendered handles and their input targets in the same camera space. */
final class NativeMeshMirrorAxisGesture {
    private static final String CAMERA = "getCameraManager";
    private static final String EDIT_MODE = "getCurrentEditMode";
    private static final String SAFE_PLANE = "isSafeEditOnPlaneXY";
    private static final String TO_COMPONENT = "documentToComponent";
    private static final String TO_DOCUMENT = "componentToDocument";
    private static final String MOUSE_EVENT = "f";
    private static final String COMPONENT_POINT = "K";
    private static final String X = "getX";
    private static final String Y = "getY";
    private static final String AXIS_POSITION_INPUT = "getAxisSlidable";
    private static final String SET_VALUE = "setValue";
    private static final String GET_VALUE = "getValue";
    private static final String MIRROR_METHOD = "a";
    private static final String MIRROR_POSITION = "c";
    private static final Class<?>[] FLOAT_PARAMETER = {float.class};
    private static final String REPAINT = "repaintCanvas";
    private static final float ROTATION_ARM = 64.0f;
    private static final float MOVE_RADIUS = 9.0f;
    private static final float ROTATE_RADIUS = 8.0f;
    private static final float HIT_PADDING = 4.0f;
    private static final float PROJECTION_PROBE = 1024.0f;
    private static final float MARKER_WIDTH = 2.0f;
    private static final float MARKER_DEPTH = 0.0f;

    private volatile Guide guide;
    private volatile Drag drag;

    void clear() {
        guide = null;
        drag = null;
    }

    void draw(
            final RuntimeMeshMirrorAxisService axis,
            final Line line,
            final Object view,
            final Object drawer,
            final Object color) {
        if (view == null || line == null) return;
        try {
            final Object camera = invoke(view, CAMERA);
            if (!Boolean.TRUE.equals(invoke(camera, SAFE_PLANE))) {
                guide = null;
                return;
            }
            final Point center = axis.handleCenter(line);
            final Handles handles = projectHandles(view, camera, center, line.direction());
            final Point move = handles.move();
            final Point rotate = handles.rotate();
            drawMarker(view, camera, drawer, color, new Point[] {
                new Point(move.x() - MOVE_RADIUS, move.y()),
                new Point(move.x(), move.y() - MOVE_RADIUS),
                new Point(move.x() + MOVE_RADIUS, move.y()),
                new Point(move.x(), move.y() + MOVE_RADIUS)
            });
            drawMarker(view, camera, drawer, color, new Point[] {
                new Point(rotate.x() - ROTATE_RADIUS, rotate.y() - ROTATE_RADIUS),
                new Point(rotate.x() + ROTATE_RADIUS, rotate.y() - ROTATE_RADIUS),
                new Point(rotate.x() + ROTATE_RADIUS, rotate.y() + ROTATE_RADIUS),
                new Point(rotate.x() - ROTATE_RADIUS, rotate.y() + ROTATE_RADIUS)
            });
            guide = new Guide(view, invoke(view, EDIT_MODE), center, line.direction());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            guide = null;
        }
    }

    boolean input(
            final Object view,
            final Object event,
            final Object panel,
            final RuntimeMeshMirrorAxisService axis,
            final RuntimeMeshEditUiService ui) {
        final Guide visible = guide;
        final Drag captured = drag;
        if (event == null || (visible == null && captured == null)) return false;
        try {
            if (!(invoke(event, MOUSE_EVENT) instanceof MouseEvent mouse)) return false;
            final int id = mouse.getID();
            final boolean sameGesture = captured != null && captured.guide().view() == view;
            if (id == MouseEvent.MOUSE_RELEASED && mouse.getButton() == MouseEvent.BUTTON1 && sameGesture) {
                drag = null;
                return true;
            }
            if (id != MouseEvent.MOUSE_PRESSED && id != MouseEvent.MOUSE_DRAGGED) return false;
            final boolean pressed = id == MouseEvent.MOUSE_PRESSED;
            if (pressed) drag = null;
            else if (!sameGesture) return false;
            final boolean consume = !pressed && sameGesture;
            if (visible == null
                    || visible.view() != view
                    || visible.editMode() == null
                    || ui.contribution() == null
                    || panel == null) return consume;
            if (pressed
                    && (mouse.getButton() != MouseEvent.BUTTON1
                            || mouse.isAltDown()
                            || mouse.isControlDown()
                            || mouse.isShiftDown()
                            || mouse.isMetaDown())) return false;
            if (invoke(view, EDIT_MODE) != visible.editMode()) return consume;
            final Object mirror = NativeMeshMirrorCounterpartOps.hostMirror(view);
            if (mirror == null || !Boolean.TRUE.equals(invoke(mirror, MIRROR_METHOD, visible.editMode())))
                return consume;
            final Object camera = invoke(view, CAMERA);
            if (!Boolean.TRUE.equals(invoke(camera, SAFE_PLANE))) return consume;
            final Object componentPoint = invoke(event, COMPONENT_POINT);
            final Point document = read(invoke(camera, TO_DOCUMENT, componentPoint));
            if (pressed) {
                final Handles handles = projectHandles(view, camera, visible.center(), visible.direction());
                final Point screen = read(componentPoint);
                final boolean rotate = hits(screen, handles.rotate(), ROTATE_RADIUS);
                if (!rotate && !hits(screen, handles.move(), MOVE_RADIUS)) return false;
                drag = new Drag(visible, document, axis.currentAngleDegrees(), rotate);
                return true;
            }
            if (captured.guide().editMode() == visible.editMode()) {
                update(captured, document, panel, mirror, axis, ui);
                invoke(view, REPAINT);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return captured != null && captured.guide().view() == view;
        }
    }

    private static void update(
            final Drag drag,
            final Point target,
            final Object panel,
            final Object mirror,
            final RuntimeMeshMirrorAxisService axis,
            final RuntimeMeshEditUiService ui)
            throws ReflectiveOperationException {
        final Point start = drag.start();
        final Point pivot = drag.guide().center();
        final Point center = drag.rotate()
                ? pivot
                : new Point(pivot.x() + (target.x() - start.x()), pivot.y() + (target.y() - start.y()));
        final float angle = drag.rotate() ? angleAt(drag.angle(), pivot, start, target) : drag.angle();
        final RuntimeMeshMirrorAxisService.Placement placement = axis.placement(center, angle);
        if (placement == null || !Float.isFinite(placement.position())) return;
        axis.applyPlacement(placement, syncPositionInput(panel, mirror, placement.position()), angle);
        if (drag.rotate()) ui.syncAngleInput(panel, axis.currentAngleDegrees());
    }

    static float syncPositionInput(final Object panel, final Object mirror, final float position)
            throws ReflectiveOperationException {
        final float previous = number(invoke(mirror, MIRROR_POSITION)).floatValue();
        if (position == previous) return previous;
        final Object positionInput = invoke(panel, AXIS_POSITION_INPUT);
        invoke(positionInput, SET_VALUE, position);
        final float actual = number(invoke(positionInput, GET_VALUE)).floatValue();
        if (actual != number(invoke(mirror, MIRROR_POSITION)).floatValue()) {
            call(mirror, MIRROR_METHOD, FLOAT_PARAMETER, actual);
        }
        return actual;
    }

    static float angleAt(final float initial, final Point pivot, final Point start, final Point target) {
        final double startX = start.x() - pivot.x();
        final double startY = start.y() - pivot.y();
        final double targetX = target.x() - pivot.x();
        final double targetY = target.y() - pivot.y();
        return initial
                + (float) Math.toDegrees(
                        Math.atan2(startX * targetY - startY * targetX, startX * targetX + startY * targetY));
    }

    private static Handles projectHandles(
            final Object view, final Object camera, final Point center, final Point direction)
            throws ReflectiveOperationException {
        final Point move = read(invoke(camera, TO_COMPONENT, vector(view, center)));
        final Point probe =
                new Point(center.x() + direction.x() * PROJECTION_PROBE, center.y() + direction.y() * PROJECTION_PROBE);
        final Point end = read(invoke(camera, TO_COMPONENT, vector(view, probe)));
        final float dx = end.x() - move.x();
        final float dy = end.y() - move.y();
        final double length = Math.hypot(dx, dy);
        if (!(length > 0.0)) throw new IllegalStateException("Mirror handle projection has no length");
        final float scale = (float) (ROTATION_ARM / length);
        return new Handles(move, new Point(move.x() + dx * scale, move.y() + dy * scale));
    }

    private static void drawMarker(
            final Object view, final Object camera, final Object drawer, final Object color, final Point[] vertices)
            throws ReflectiveOperationException {
        final Object[] points = new Object[vertices.length];
        for (int index = 0; index < vertices.length; index++) {
            final Point vertex = vertices[index];
            points[index] = invoke(
                    camera,
                    TO_DOCUMENT,
                    point(view.getClass().getClassLoader(), Math.round(vertex.x()), Math.round(vertex.y())));
        }
        for (int index = 0; index < points.length; index++) {
            invoke(
                    drawer,
                    MIRROR_METHOD,
                    points[index],
                    points[(index + 1) % points.length],
                    color,
                    MARKER_WIDTH,
                    MARKER_DEPTH);
        }
    }

    private static boolean hits(final Point mouse, final Point handle, final float radius) {
        return Math.hypot(mouse.x() - handle.x(), mouse.y() - handle.y()) <= radius + HIT_PADDING;
    }

    private static Point read(final Object point) throws ReflectiveOperationException {
        return new Point(coordinate(point, X), coordinate(point, Y));
    }

    private record Guide(Object view, Object editMode, Point center, Point direction) {}

    private record Handles(Point move, Point rotate) {}

    private record Drag(Guide guide, Point start, float angle, boolean rotate) {}
}

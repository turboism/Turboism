package dev.turboism.validation.modelupdate;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Test-only human input trace: record real AWT events with their OS timestamps and
 * replay them through the real system {@link java.awt.EventQueue} at the recorded
 * pacing. Unlike the legacy workloads this never waits for a paint between events,
 * so input backlog, paint coalescing and EDT scheduling happen exactly as they do
 * for a human.
 *
 * <p>Events are recorded after AWT dispatch, not at the native peer, so the trace
 * captures what the host actually receives. Replay resolves the recorded source by
 * class and screen position; camera events fall back to the task canvas.</p>
 */
final class HumanTrace {
    private static final String HEADER = "# turboism-human-trace v1";
    private static final String EVENT_HEADER =
        "# kind\twhenMillis\tnanoOffset\tid\tx\ty\tabsX\tabsY\tscrollAmount"
            + "\twheelRotation\tpreciseWheelRotation\tmodifiers\tbutton\tclickCount"
            + "\tkeyCode\tkeyChar\tsourceClass";

    enum Kind { WHEEL, MOUSE, KEY }

    record Event(
        Kind kind,
        long whenMillis,
        long nanoOffset,
        int id,
        int x,
        int y,
        int absX,
        int absY,
        int scrollAmount,
        int wheelRotation,
        double preciseWheelRotation,
        int modifiers,
        int button,
        int clickCount,
        int keyCode,
        char keyChar,
        String sourceClass) { }

    record Posted(AWTEvent event, Event source, long targetNanos, long postNanos) { }

    private final String kind;
    private final String fixture;
    private final int canvasWidth;
    private final int canvasHeight;
    private final String zoomBefore;
    private final List<Event> events;

    private HumanTrace(final String kind, final String fixture, final int canvasWidth,
                       final int canvasHeight, final String zoomBefore, final List<Event> events) {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("trace kind absent");
        if (events.isEmpty()) throw new IllegalArgumentException("trace has no events");
        this.kind = kind;
        this.fixture = fixture == null ? "" : fixture;
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.zoomBefore = zoomBefore == null ? "" : zoomBefore;
        this.events = List.copyOf(events);
    }

    String kind() { return kind; }
    String fixture() { return fixture; }
    int canvasWidth() { return canvasWidth; }
    int canvasHeight() { return canvasHeight; }
    String zoomBefore() { return zoomBefore; }
    List<Event> events() { return events; }

    long durationNanos() {
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (Event event : events) {
            first = Math.min(first, event.nanoOffset());
            last = Math.max(last, event.nanoOffset());
        }
        return Math.max(0L, last - first);
    }

    int wheelNotches() {
        int total = 0;
        for (Event event : events) {
            if (event.kind() == Kind.WHEEL) total += Math.abs(event.wheelRotation());
        }
        return total;
    }

    static Event singleWheel(final int rotation, final long nanoOffset, final String sourceClass) {
        return new Event(Kind.WHEEL, System.currentTimeMillis(), nanoOffset, MouseEvent.MOUSE_WHEEL,
            0, 0, 0, 0, Math.max(1, Math.abs(rotation) * 3), rotation, rotation,
            0, MouseEvent.NOBUTTON, 0, 0, '\0', sourceClass);
    }

    static HumanTrace read(final Path path) throws IOException {
        final List<String> lines = Files.readAllLines(path);
        String kind = null;
        String fixture = "";
        int width = 0, height = 0;
        String zoom = "";
        final List<Event> events = new ArrayList<>();
        boolean inEvents = false;
        for (String raw : lines) {
            final String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith(HEADER) || line.equals(EVENT_HEADER)
                || line.startsWith("# kind\t")) {
                inEvents = line.startsWith("# kind\t");
                continue;
            }
            if (!inEvents && line.contains("=")) {
                final int separator = line.indexOf('=');
                final String key = line.substring(0, separator).trim();
                final String value = line.substring(separator + 1).trim();
                switch (key) {
                    case "kind" -> kind = value;
                    case "fixture" -> fixture = value;
                    case "canvas.width" -> width = Integer.parseInt(value);
                    case "canvas.height" -> height = Integer.parseInt(value);
                    case "zoom.before" -> zoom = value;
                    default -> { }
                }
                continue;
            }
            if (line.startsWith("#")) continue;
            final String[] fields = line.split("\t", -1);
            if (fields.length < 17) {
                throw new IllegalArgumentException("trace event has " + fields.length
                    + " fields, expected 17: " + line);
            }
            final Kind eventKind = switch (fields[0]) {
                case "W", "WHEEL" -> Kind.WHEEL;
                case "M", "MOUSE" -> Kind.MOUSE;
                case "K", "KEY" -> Kind.KEY;
                default -> throw new IllegalArgumentException("unknown trace event kind: " + fields[0]);
            };
            events.add(new Event(eventKind,
                Long.parseLong(fields[1]),
                Long.parseLong(fields[2]),
                Integer.parseInt(fields[3]),
                Integer.parseInt(fields[4]),
                Integer.parseInt(fields[5]),
                Integer.parseInt(fields[6]),
                Integer.parseInt(fields[7]),
                Integer.parseInt(fields[8]),
                Integer.parseInt(fields[9]),
                Double.parseDouble(fields[10]),
                Integer.parseInt(fields[11]),
                Integer.parseInt(fields[12]),
                Integer.parseInt(fields[13]),
                Integer.parseInt(fields[14]),
                fields[15].isEmpty() ? '\0' : fields[15].charAt(0),
                fields[16]));
        }
        if (kind == null) throw new IllegalArgumentException("trace kind absent: " + path);
        events.sort(Comparator.comparingLong(Event::nanoOffset));
        return new HumanTrace(kind, fixture, width, height, zoom, events);
    }

    static void write(final Path path, final HumanTrace trace) throws IOException {
        final StringBuilder text = new StringBuilder(HEADER).append('\n')
            .append("kind=").append(trace.kind()).append('\n')
            .append("fixture=").append(trace.fixture()).append('\n')
            .append("canvas.width=").append(trace.canvasWidth()).append('\n')
            .append("canvas.height=").append(trace.canvasHeight()).append('\n')
            .append("zoom.before=").append(trace.zoomBefore()).append('\n')
            .append("event.count=").append(trace.events().size()).append('\n')
            .append(EVENT_HEADER).append('\n');
        for (Event event : trace.events()) {
            text.append(switch (event.kind()) {
                case WHEEL -> "W";
                case MOUSE -> "M";
                case KEY -> "K";
            }).append('\t')
                .append(event.whenMillis()).append('\t')
                .append(event.nanoOffset()).append('\t')
                .append(event.id()).append('\t')
                .append(event.x()).append('\t')
                .append(event.y()).append('\t')
                .append(event.absX()).append('\t')
                .append(event.absY()).append('\t')
                .append(event.scrollAmount()).append('\t')
                .append(event.wheelRotation()).append('\t')
                .append(String.format(Locale.ROOT, "%.6f", event.preciseWheelRotation())).append('\t')
                .append(event.modifiers()).append('\t')
                .append(event.button()).append('\t')
                .append(event.clickCount()).append('\t')
                .append(event.keyCode()).append('\t')
                .append(event.keyChar() == '\0' ? "" : String.valueOf(event.keyChar())).append('\t')
                .append(event.sourceClass() == null ? "" : event.sourceClass()).append('\n');
        }
        Files.writeString(path, text.toString());
    }

    /**
     * Records the user's real input for {@code seconds}. The listener only appends
     * to a synchronized list; no I/O or timing work happens on the EDT.
     */
    static HumanTrace record(final Path output, final long seconds, final String kind,
                             final JComponent canvas, final Frame window,
                             final Supplier<String> zoom, final Path progress) throws Exception {
        if (seconds < 1L || seconds > 3600L) throw new IllegalArgumentException("record seconds out of range");
        final long start = System.nanoTime();
        final String zoomBefore = zoom == null ? "" : zoom.get();
        final List<Event> events = Collections.synchronizedList(new ArrayList<>());
        final long[] lastMove = {Long.MIN_VALUE};
        final AWTEventListener listener = event -> {
            try {
                if (!(event instanceof MouseEvent) && !(event instanceof KeyEvent)) return;
                final Component source = event.getSource() instanceof Component component ? component : null;
                if (source == null || SwingUtilities.getWindowAncestor(source) != window) return;
                final long offset = System.nanoTime() - start;
                if (event instanceof KeyEvent key) {
                    if (key.getID() != KeyEvent.KEY_PRESSED && key.getID() != KeyEvent.KEY_RELEASED) return;
                    events.add(new Event(Kind.KEY, key.getWhen(), offset, key.getID(),
                        0, 0, 0, 0, 0, 0, 0.0d, key.getModifiersEx(),
                        MouseEvent.NOBUTTON, 0, key.getKeyCode(), key.getKeyChar(),
                        source.getClass().getName()));
                    return;
                }
                final MouseEvent mouse = (MouseEvent) event;
                switch (mouse.getID()) {
                    case MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_EXITED -> {
                        return;
                    }
                    case MouseEvent.MOUSE_MOVED -> {
                        // Hover is real input but 125 Hz moves would dominate the
                        // trace; 60 Hz is plenty to reproduce selection/hover work.
                        final long now = System.nanoTime();
                        if (now - lastMove[0] < 16_000_000L) return;
                        lastMove[0] = now;
                    }
                    default -> { }
                }
                if (mouse instanceof MouseWheelEvent wheel) {
                    events.add(new Event(Kind.WHEEL, wheel.getWhen(), offset, wheel.getID(),
                        wheel.getX(), wheel.getY(), wheel.getXOnScreen(), wheel.getYOnScreen(),
                        wheel.getScrollAmount(), wheel.getWheelRotation(),
                        wheel.getPreciseWheelRotation(), wheel.getModifiersEx(),
                        wheel.getButton(), wheel.getClickCount(), 0, '\0',
                        source.getClass().getName()));
                } else {
                    events.add(new Event(Kind.MOUSE, mouse.getWhen(), offset, mouse.getID(),
                        mouse.getX(), mouse.getY(), mouse.getXOnScreen(), mouse.getYOnScreen(),
                        0, 0, 0.0d, mouse.getModifiersEx(), mouse.getButton(),
                        mouse.getClickCount(), 0, '\0', source.getClass().getName()));
                }
            } catch (Throwable ignored) {
                // A recorder must never disturb the measured session.
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,
            AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        try {
            final long deadline = start + seconds * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (progress != null) {
                    final long remaining = Math.max(0L, (deadline - System.nanoTime()) / 1_000_000L);
                    Files.writeString(progress, "stage=recording\nremainingMillis=" + remaining
                        + "\nevents=" + events.size() + "\n");
                }
                Thread.sleep(250L);
            }
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        }
        synchronized (events) {
            events.sort(Comparator.comparingLong(Event::nanoOffset));
            final HumanTrace trace = new HumanTrace(kind, window.getTitle(), canvas.getWidth(),
                canvas.getHeight(), zoomBefore, events);
            write(output, trace);
            return trace;
        }
    }

    /**
     * Replays a trace at its recorded pacing through the system event queue. This
     * method blocks the calling (non-EDT) thread until every event is posted; it
     * never waits for a paint. Paint coalescing, input backlog and EDT scheduling
     * are therefore the host's own.
     */
    static List<Posted> replay(final HumanTrace trace, final Component canvas,
                               final long leadInNanos, final double speed,
                               final BooleanSupplier cancelled,
                               final Consumer<Posted> observer) {
        if (speed <= 0.0d || !Double.isFinite(speed)) throw new IllegalArgumentException("invalid replay speed");
        final List<Posted> posted = new ArrayList<>(trace.events().size());
        final long firstOffset = trace.events().get(0).nanoOffset();
        final long started = System.nanoTime() + Math.max(0L, leadInNanos);
        for (Event source : trace.events()) {
            if (cancelled != null && cancelled.getAsBoolean()) break;
            final long target = started + (long) ((source.nanoOffset() - firstOffset) / speed);
            while (true) {
                final long now = System.nanoTime();
                final long remaining = target - now;
                if (remaining <= 0L) break;
                LockSupport.parkNanos(Math.min(remaining, 2_000_000L));
                if (Thread.currentThread().isInterrupted()) return List.copyOf(posted);
            }
            final AWTEvent event = toAwt(source, canvas);
            if (event == null) continue;
            final long postNanos = System.nanoTime();
            Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(event);
            final Posted record = new Posted(event, source, target, postNanos);
            posted.add(record);
            if (observer != null) observer.accept(record);
        }
        return List.copyOf(posted);
    }

    private static AWTEvent toAwt(final Event event, final Component canvas) {
        final Component source = resolveSource(canvas, event);
        if (source == null) return null;
        final int absX;
        final int absY;
        if (source == canvas) {
            // Keep the trace portable across window positions: the recorded
            // absolute point belongs to the recording session, not this run.
            final java.awt.Point origin = canvas.getLocationOnScreen();
            absX = origin.x + event.x();
            absY = origin.y + event.y();
        } else {
            absX = event.absX();
            absY = event.absY();
        }
        return switch (event.kind()) {
            case WHEEL -> new MouseWheelEvent(source, MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), event.modifiers(), event.x(), event.y(),
                absX, absY, Math.max(0, event.clickCount()), false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, Math.max(1, event.scrollAmount()),
                event.wheelRotation(), event.preciseWheelRotation());
            case MOUSE -> new MouseEvent(source, event.id(), System.currentTimeMillis(),
                event.modifiers(), event.x(), event.y(), absX, absY,
                Math.max(0, event.clickCount()), false, event.button());
            case KEY -> new KeyEvent(source, event.id(), System.currentTimeMillis(),
                event.modifiers(), event.keyCode(), event.keyChar());
        };
    }

    /**
     * Finds the recorded component by class and screen position. Camera events use
     * the canvas even when a child renderer held focus, because the host's wheel
     * listener is registered on the canvas.
     */
    private static Component resolveSource(final Component canvas, final Event event) {
        if (canvas == null) return null;
        final Window window = SwingUtilities.getWindowAncestor(canvas);
        if (window == null) return canvas;
        final String sourceClass = event.sourceClass();
        if (sourceClass == null || sourceClass.isBlank()
            || sourceClass.equals(canvas.getClass().getName())) return canvas;
        final Component found = find(window, sourceClass, event.absX(), event.absY(), 0);
        return found == null ? canvas : found;
    }

    private static Component find(final Component root, final String className,
                                  final int absX, final int absY, final int depth) {
        if (depth > 40) return null;
        if (className.equals(root.getClass().getName()) && root.isShowing()) {
            final java.awt.Point origin = root.getLocationOnScreen();
            if (absX >= origin.x && absY >= origin.y
                && absX < origin.x + root.getWidth() && absY < origin.y + root.getHeight()) {
                return root;
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final Component found = find(child, className, absX, absY, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Per-display completion timeline of the task canvas. */
    static final class FrameTimeline implements AutoCloseable {
        record Frame(long nanoTime, String zoom) { }

        private final JComponent canvas;
        private final Supplier<String> zoom;
        private final java.lang.reflect.Method remove;
        private final List<Frame> frames = Collections.synchronizedList(new ArrayList<>());
        private volatile long count;
        private volatile Object listener;

        private FrameTimeline(final JComponent canvas, final Supplier<String> zoom,
                              final java.lang.reflect.Method remove) {
            this.canvas = canvas;
            this.zoom = zoom;
            this.remove = remove;
        }

        static FrameTimeline attach(final JComponent canvas, final Supplier<String> zoom) throws Exception {
            if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("frame timeline must attach on EDT");
            final ClassLoader loader = canvas.getClass().getClassLoader();
            final Class<?> drawable = Class.forName("com.jogamp.opengl.GLAutoDrawable", false, loader);
            final Class<?> callback = Class.forName("com.jogamp.opengl.GLEventListener", false, loader);
            final FrameTimeline timeline = new FrameTimeline(canvas, zoom,
                drawable.getMethod("removeGLEventListener", callback));
            final Object proxy = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{callback},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> instance == args[0];
                            case "hashCode" -> System.identityHashCode(instance);
                            default -> "TurboismHumanFrameTimeline";
                        };
                    }
                    if (method.getName().equals("display")) {
                        final long now = System.nanoTime();
                        final String value = zoom == null ? "" : zoom.get();
                        timeline.frames.add(new Frame(now, value));
                        timeline.count++;
                    }
                    return null;
                });
            timeline.listener = proxy;
            drawable.getMethod("addGLEventListener", callback).invoke(canvas, proxy);
            return timeline;
        }

        long count() { return count; }

        List<Frame> snapshot() {
            synchronized (frames) {
                return List.copyOf(frames);
            }
        }

        void clear() {
            synchronized (frames) {
                frames.clear();
            }
        }

        @Override public void close() throws Exception {
            if (listener != null) remove.invoke(canvas, listener);
        }
    }

    /** Post-host wheel timeline: the zoom label already reflects this notch. */
    static final class WheelObserver implements AutoCloseable {
        record Applied(long nanoTime, String zoom, int rotation, int scrollAmount) { }

        private final JComponent canvas;
        private final Supplier<String> zoom;
        private final java.awt.event.MouseWheelListener listener;
        private final List<Applied> applied = Collections.synchronizedList(new ArrayList<>());

        private WheelObserver(final JComponent canvas, final Supplier<String> zoom) {
            this.canvas = canvas;
            this.zoom = zoom;
            this.listener = event -> {
                final String value = zoom == null ? "" : zoom.get();
                applied.add(new Applied(System.nanoTime(), value, event.getWheelRotation(),
                    event.getScrollAmount()));
            };
        }

        static WheelObserver attach(final JComponent canvas, final Supplier<String> zoom) {
            if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("wheel observer must attach on EDT");
            final WheelObserver observer = new WheelObserver(canvas, zoom);
            canvas.addMouseWheelListener(observer.listener);
            return observer;
        }

        List<Applied> snapshot() {
            synchronized (applied) {
                return List.copyOf(applied);
            }
        }

        void clear() {
            synchronized (applied) {
                applied.clear();
            }
        }

        @Override public void close() {
            canvas.removeMouseWheelListener(listener);
        }
    }

    /** Post-host mouse timeline used by pan and ArtMesh traces. */
    static final class MouseObserver implements AutoCloseable {
        record Moved(long nanoTime, int id, int x, int y, int modifiers) { }

        private final JComponent canvas;
        private final java.awt.event.MouseMotionListener motion;
        private final java.awt.event.MouseListener mouse;
        private final List<Moved> moved = Collections.synchronizedList(new ArrayList<>());

        private MouseObserver(final JComponent canvas) {
            this.canvas = canvas;
            this.motion = new java.awt.event.MouseMotionAdapter() {
                @Override public void mouseDragged(final MouseEvent event) {
                    moved.add(new Moved(System.nanoTime(), event.getID(), event.getX(), event.getY(),
                        event.getModifiersEx()));
                }
            };
            this.mouse = new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(final MouseEvent event) {
                    moved.add(new Moved(System.nanoTime(), event.getID(), event.getX(), event.getY(),
                        event.getModifiersEx()));
                }
                @Override public void mouseReleased(final MouseEvent event) {
                    moved.add(new Moved(System.nanoTime(), event.getID(), event.getX(), event.getY(),
                        event.getModifiersEx()));
                }
            };
        }

        static MouseObserver attach(final JComponent canvas) {
            if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("mouse observer must attach on EDT");
            final MouseObserver observer = new MouseObserver(canvas);
            canvas.addMouseMotionListener(observer.motion);
            canvas.addMouseListener(observer.mouse);
            return observer;
        }

        List<Moved> snapshot() {
            synchronized (moved) {
                return List.copyOf(moved);
            }
        }

        void clear() {
            synchronized (moved) {
                moved.clear();
            }
        }

        @Override public void close() {
            canvas.removeMouseMotionListener(motion);
            canvas.removeMouseListener(mouse);
        }
    }
}

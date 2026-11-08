package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Session owner of the native Auto Mesh selector rows.
 *
 * <p>One instance serves the whole Editor session. It creates one {@link AutoMeshSelectorRow} per
 * constructed container and releases that row when the host discards the container. The rows are
 * keyed weakly, so a container the Editor drops never keeps this host alive.</p>
 *
 * <p>The host is bound as the bridge's attach handler by the bootstrap installer. Until one is
 * bound the native containers construct exactly as before, because the bridge answers {@code null}
 * for a missing handler.</p>
 */
public final class AutoMeshSelectorHost implements AutoCloseable {

    private final AutoMeshSelectorSource source;
    private final Locale locale;
    private final Consumer<String> diagnostics;
    private final Map<Object, AutoMeshSelectorRow> rows = new WeakHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates the session host.
     *
     * @param source the session listing and selection the rows read and write
     * @param locale the host-effective locale for the framework's own text
     * @param diagnostics sink for every refused attach; it must not throw
     */
    public AutoMeshSelectorHost(
            final AutoMeshSelectorSource source, final Locale locale, final Consumer<String> diagnostics) {
        this.source = Objects.requireNonNull(source, "source");
        this.locale = locale;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** @return the session selection the selector writes and the dispatcher reads */
    public AutoMeshSelectionState selection() {
        return source.selection();
    }

    /** @return the source the row controller reads */
    public AutoMeshSelectorSource source() {
        return source;
    }

    /**
     * Decorates one freshly constructed native container.
     *
     * <p>Called from transformed host bytecode through the bridge, after the container's own
     * constructor finished. A closed host, a container that already carries a row, and a session
     * that lists no plugin algorithm are all ignored: the last one is what keeps the native popup
     * byte-identical for a user without an algorithm plugin.</p>
     *
     * @param instance the live container widget
     */
    public void attach(final Object instance) {
        if (closed.get() || instance == null) {
            return;
        }
        synchronized (rows) {
            if (rows.containsKey(instance)) {
                return;
            }
        }
        final AutoMeshSelectorRow row =
                new AutoMeshSelectorRow(new AutoMeshSelectorController(source, locale), locale, diagnostics);
        if (!row.wouldAttach()) {
            // No row is created, so no bookkeeping entry may exist either.
            return;
        }
        synchronized (rows) {
            if (rows.putIfAbsent(instance, row) != null) {
                return;
            }
        }
        row.attach(instance);
    }

    /**
     * Releases the bookkeeping of a container the host is discarding.
     *
     * <p>The container is going away with its widget tree, so the row needs no removal: dropping the
     * entry is what stops a later attach from reusing a stale row. The entry is weakly keyed, so a
     * container the Editor drops without notice is collected anyway.</p>
     *
     * @param instance the container whose row is forgotten
     */
    public void release(final Object instance) {
        synchronized (rows) {
            rows.remove(instance);
        }
    }

    /** @return the row of one container, or empty when it carries none */
    public Optional<AutoMeshSelectorRow> rowOf(final Object instance) {
        synchronized (rows) {
            return Optional.ofNullable(rows.get(instance));
        }
    }

    /** @return the number of containers that currently carry a row */
    public int attachedCount() {
        synchronized (rows) {
            return rows.size();
        }
    }

    /** @return every algorithm the row would offer now, the Editor's generator first */
    public List<AutoMeshAlgorithm> algorithms() {
        return source.algorithms();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (rows) {
            rows.clear();
        }
    }
}

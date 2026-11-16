package dev.turboism.adapter.cubism.optimization.paramrow;

import dev.turboism.core.runtime.work.FatalErrors;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Synchronizes Parameter Palette rows incrementally across unchanged parameters and elides
 * redundant Swing repaints and double-row layout scans.
 *
 * <p>The bridge owns one lifecycle: it resolves the reviewed host members, publishes three
 * {@link System#getProperties()} slots, keeps one pass state per palette controller and reports
 * diagnostic counters. Host access lives in {@link ParameterRowHostAccess}, the hashing in
 * {@link ParameterRowKeyformHasher}, the repaint and value decisions in
 * {@link ParameterRowVisualSync}, and one incremental pass in {@link ParameterRowPass}.</p>
 *
 * <p>Woven host bytecode reaches the bridge only through those slots, so the host never holds a
 * reference to this class: {@link #SELECTION_UPDATE_PROPERTY} gates a palette selection update,
 * {@link #INVALIDATE_PROPERTY} drops a controller's state after a structure rebuild, and
 * {@link #STATS_PROPERTY} exposes the counters.</p>
 */
public final class ParameterRowRepaintBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = ParameterRowRepaintTarget.ENABLE_PROPERTY;

    /** Property key for the {@code Predicate<Object[]>} incremental selection update callback. */
    public static final String SELECTION_UPDATE_PROPERTY = "turboism.param-row.update-selection";

    /** Property key for the {@code Consumer<Object>} structure invalidation callback. */
    public static final String INVALIDATE_PROPERTY = "turboism.param-row.invalidate";

    /** Property key exposing diagnostic counters. */
    public static final String STATS_PROPERTY = "turboism.param-row.stats";

    private static final int UPDATE_ARG_COUNT = 3;
    private static final int ARG_CONTROLLER_INDEX = 0;
    private static final int ARG_DOCUMENT_INDEX = 1;
    private static final int ARG_MODEL_INDEX = 2;

    private static final String STAT_INVOCATIONS = "invocations";
    private static final String STAT_BASELINE_PASSES = "baselinePasses";
    private static final String STAT_INCREMENTAL_PASSES = "incrementalPasses";
    private static final String STAT_KEYFORM_HASHED_PASSES = "keyformHashedPasses";
    private static final String STAT_KEYFORM_ELIDED_PASSES = "keyformElidedPasses";
    private static final String STAT_ELIDED_ROWS = "elidedRows";
    private static final String STAT_REPAINTED_ROWS = "repaintedRows";
    private static final String STAT_RESIZED_DOUBLES = "resizedDoubles";
    private static final String STAT_INVALIDATIONS = "invalidations";
    private static final String STAT_FAILURES = "failures";

    private static final String INSTALLED_TWICE_MESSAGE = "parameter row repaint bridge already installed";
    private static final String SLOTS_OCCUPIED_MESSAGE = "parameter row repaint bridge slots occupied";

    private final ParameterRowPass pass;

    private final AtomicBoolean active = new AtomicBoolean();
    private final Object lock = new Object();
    private final Map<Object, ParameterRowPass.ControllerSnapshot> cache = new WeakHashMap<>();

    private final Predicate<Object[]> selectionCallback = this::updateSelection;
    private final Consumer<Object> invalidateCallback = this::invalidateController;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    private Properties installedProperties;

    private long invocations;
    private long baselinePasses;
    private long incrementalPasses;
    private long keyformHashedPasses;
    private long keyformElidedPasses;
    private long elidedRows;
    private long repaintedRows;
    private long resizedDoubles;
    private long invalidations;
    private long failures;

    /**
     * Resolves every reviewed host member of one admitted artifact.
     *
     * @param loader admitted host class loader
     * @param target reviewed version target descriptors
     * @throws ReflectiveOperationException when any reviewed member is unavailable
     */
    public ParameterRowRepaintBridge(final ClassLoader loader, final ParameterRowRepaintTarget target)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        final ParameterRowHostAccess host =
                new ParameterRowHostAccess(loader, Objects.requireNonNull(target, "target"));
        pass = new ParameterRowPass(host, new ParameterRowKeyformHasher(host), new ParameterRowVisualSync(host));
    }

    /** Publishes incremental selection and invalidation callbacks into {@link System#getProperties()}. */
    public synchronized void install() {
        if (active.get()) {
            throw new IllegalStateException(INSTALLED_TWICE_MESSAGE);
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(SELECTION_UPDATE_PROPERTY)
                    || properties.containsKey(INVALIDATE_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException(SLOTS_OCCUPIED_MESSAGE);
            }
            properties.put(SELECTION_UPDATE_PROPERTY, selectionCallback);
            properties.put(INVALIDATE_PROPERTY, invalidateCallback);
            properties.put(STATS_PROPERTY, statistics);
            installedProperties = properties;
            synchronized (lock) {
                cache.clear();
            }
            active.set(true);
        }
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        synchronized (lock) {
            cache.clear();
        }
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(SELECTION_UPDATE_PROPERTY, selectionCallback);
                properties.remove(INVALIDATE_PROPERTY, invalidateCallback);
                properties.remove(STATS_PROPERTY, statistics);
            }
        }
    }

    /** Returns an immutable snapshot of diagnostic counters. */
    public Map<String, Long> snapshot() {
        synchronized (lock) {
            return Map.of(
                    STAT_INVOCATIONS, invocations,
                    STAT_BASELINE_PASSES, baselinePasses,
                    STAT_INCREMENTAL_PASSES, incrementalPasses,
                    STAT_KEYFORM_HASHED_PASSES, keyformHashedPasses,
                    STAT_KEYFORM_ELIDED_PASSES, keyformElidedPasses,
                    STAT_ELIDED_ROWS, elidedRows,
                    STAT_REPAINTED_ROWS, repaintedRows,
                    STAT_RESIZED_DOUBLES, resizedDoubles,
                    STAT_INVALIDATIONS, invalidations,
                    STAT_FAILURES, failures);
        }
    }

    private void invalidateController(final Object controller) {
        if (!active.get() || controller == null) {
            return;
        }
        synchronized (lock) {
            cache.remove(controller);
            invalidations++;
        }
    }

    private boolean updateSelection(final Object[] args) {
        if (!active.get() || args == null || args.length != UPDATE_ARG_COUNT) {
            return false;
        }
        final Object controller = args[ARG_CONTROLLER_INDEX];
        final Object doc = args[ARG_DOCUMENT_INDEX];
        final Object model = args[ARG_MODEL_INDEX];
        if (controller == null || doc == null || model == null) {
            return false;
        }
        try {
            synchronized (lock) {
                invocations++;
                final ParameterRowPass.Outcome outcome = pass.run(controller, doc, model, cache.get(controller));
                if (outcome == null) {
                    return false;
                }
                cache.put(controller, outcome.snapshot());
                record(outcome);
                return true;
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            synchronized (lock) {
                failures++;
                cache.remove(controller);
            }
            return false;
        }
    }

    private void record(final ParameterRowPass.Outcome outcome) {
        if (outcome.baseline()) {
            baselinePasses++;
        } else {
            incrementalPasses++;
        }
        if (outcome.keyformHashed()) {
            keyformHashedPasses++;
        } else {
            keyformElidedPasses++;
        }
        repaintedRows += outcome.repaintedRows();
        elidedRows += outcome.elidedRows();
        resizedDoubles += outcome.resizedDoubles();
    }
}

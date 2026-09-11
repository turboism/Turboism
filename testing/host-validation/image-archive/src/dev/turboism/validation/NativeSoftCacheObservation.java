package dev.turboism.validation;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;

/**
 * Test-only, bounded audit of the static soft cache that keeps native image resources softly reachable.
 *
 * <p>Bytecode of the exact host JAR shows that the {@code CImageResource} constructor appends a
 * {@code CImageResource$b} holder - which carries only a {@code SoftReference} to that resource - into a static
 * {@code cacheList}, and that the resource strongly references its {@code ICImageResourceUser} records. Soft
 * reachability is transitive, so a cached resource also keeps its users and, through them, the model source and its
 * document softly reachable, which would explain why weak references survive both close and an explicit collection.
 *
 * <p>Read-only: the static field is read, never written, holders are read with the declared public accessor and the
 * cache's {@code SoftReference}s are never dereferenced - they are only compared with {@link Reference#refersTo}, a
 * pointer comparison. The comparison identity comes from dereferencing the audit's <em>own</em> cohort
 * {@code WeakReference}s, where the referent is held only for the duration of one loop iteration; that can only extend,
 * never shorten, the life of an object the cohort already tracks, and no {@code SoftReference.get()} is performed
 * anywhere. No host {@code equals}/{@code hashCode}/{@code toString} is called and nothing is removed, disposed, cleared
 * or collected. Only scalars escape.
 *
 * <p>Reading the static field may initialize its declaring class. The workload has already constructed native image
 * resources before any capture point, so the class is necessarily initialized already; the audit is also ordered
 * after the ownership and static-root captures of the same phase so that nothing it triggers can affect them.
 *
 * <p>See specs/030-soft-cache-observation/ for the argument and its limits.
 */
final class NativeSoftCacheObservation {
    private static final String CACHE_OWNER = "com.live2d.graphics.CImageResource";
    private static final String CACHE_FIELD = "cacheList";
    private static final String HOLDER = "com.live2d.graphics.CImageResource$b";
    private static final String HOLDER_ACCESSOR = "a";
    private static final String SOFT_REFERENCE = "java.lang.ref.SoftReference";
    private static final int ENTRY_LIMIT = 8192;
    private static final int COMPARISON_LIMIT = 4_000_000;
    private static final String[] COUNT_KEYS =
            {"entries", "visited", "cohortSize", "matchedCohort", "matchedDistinct"};

    private NativeSoftCacheObservation() {
    }

    /** Only scalars escape; counters stay staged until the whole audit completed for one consistent size. */
    static final class Result {
        final Properties values = new Properties();
        private final Properties counts = new Properties();
        private final TreeSet<String> matched = new TreeSet<>();

        Result() {
            for (String key : COUNT_KEYS) counts.setProperty(key, "0");
            values.setProperty("schemaVersion", "1");
            values.setProperty("coverage", "static-image-resource-soft-cache");
            values.setProperty("consistency", "non-atomic-size-checked");
            values.setProperty("status", "COMPLETE");
            values.setProperty("reason", "none");
        }

        void add(String key, long count) {
            counts.setProperty(key, Long.toString(Long.parseLong(counts.getProperty(key, "0")) + count));
        }

        void note(String label) {
            matched.add(label);
        }

        /** Counts are published only for a complete audit; a partial one must not report zeros. */
        void commit() {
            for (String key : counts.stringPropertyNames()) values.setProperty(key, counts.getProperty(key));
            values.setProperty("matchedDistinct", Integer.toString(matched.size()));
        }

        void write(Properties target, String prefix) {
            for (String key : values.stringPropertyNames()) {
                target.setProperty(prefix + "." + key, values.getProperty(key));
            }
        }
    }

    record Limits(int entries, int comparisons, long durationNs) {
        Limits {
            if (entries < 0 || comparisons < 0 || durationNs < 0) throw new IllegalArgumentException("negative limits");
        }
    }

    /** Caller has verified the document state and passes the retained cohort of the 024 before-close snapshot. */
    static Result auditHost(Class<?> verifiedHost, List<? extends Reference<?>> cohort) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("soft cache audit requires EDT");
        long start = System.nanoTime();
        Result result;
        try {
            ClassLoader loader = verifiedHost.getClassLoader();
            var origin = verifiedHost.getProtectionDomain().getCodeSource();
            Class<?> owner = Class.forName(CACHE_OWNER, false, loader);
            Class<?> holder = Class.forName(HOLDER, false, loader);
            for (Class<?> type : new Class<?>[]{owner, holder}) {
                if (type.getClassLoader() != loader || !same(origin, type)) {
                    throw new ClassNotFoundException("origin mismatch");
                }
            }
            long remaining = Math.max(0, 250_000_000L - (System.nanoTime() - start));
            result = audit(loader, owner, holder, cohort, new Limits(ENTRY_LIMIT, COMPARISON_LIMIT, remaining),
                    System::nanoTime);
        } catch (ReflectiveOperationException | SecurityException problem) {
            result = new Result();
            result.values.setProperty("status", "UNSUPPORTED");
            result.values.setProperty("reason", "layout-or-origin");
            result.values.setProperty("failureKind", problem.getClass().getSimpleName());
        }
        result.values.setProperty("durationNs", Long.toString(System.nanoTime() - start));
        return result;
    }

    /** Types are injected only for synthetic regression; the exact-host path always resolves the frozen names. */
    static Result audit(ClassLoader loader, Class<?> owner, Class<?> holder, List<? extends Reference<?>> cohort,
                        Limits limits, LongSupplier clock) {
        Result result = new Result();
        long start = clock.getAsLong();
        try {
            Field field = owner.getDeclaredField(CACHE_FIELD);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || !field.trySetAccessible()) {
                throw new NoSuchFieldException(CACHE_FIELD);
            }
            Object cache = field.get(null);
            Method accessor = holder.getMethod(HOLDER_ACCESSOR);
            if (Modifier.isStatic(accessor.getModifiers()) || accessor.getParameterCount() != 0
                    || !SOFT_REFERENCE.equals(accessor.getReturnType().getName())) {
                throw new NoSuchMethodException(HOLDER_ACCESSOR);
            }
            // The holder class is package-private, so a public accessor still needs the member to be made accessible.
            if (!accessor.trySetAccessible()) throw new NoSuchMethodException(HOLDER_ACCESSOR);
            if (cache == null) {
                result.add("cohortSize", cohort.size());
                result.commit();
                result.values.setProperty("durationNs", Long.toString(clock.getAsLong() - start));
                return result;
            }
            if (!(cache instanceof List<?> list)) throw new IllegalStateException("unexpected cache shape");
            int sizeBefore = list.size();
            result.add("cohortSize", cohort.size());
            long comparisons = 0;
            int visited = 0;
            while (visited < sizeBefore) {
                tick(start, limits, clock);
                if (visited >= limits.entries()) throw new Stop("PARTIAL", "entry-limit");
                Object entry = list.get(visited);
                visited++;
                result.add("visited", 1);
                if (entry == null || entry.getClass() != holder) continue;
                Object reference = accessor.invoke(entry);
                if (!(reference instanceof SoftReference)) continue;
                Reference<?> soft = (Reference<?>) reference;
                for (int index = 0; index < cohort.size(); index++) {
                    Reference<?> candidate = cohort.get(index);
                    if (candidate == null) continue;
                    Object referent = candidate.get();
                    if (referent == null) continue;
                    comparisons++;
                    if (comparisons > limits.comparisons()) throw new Stop("PARTIAL", "comparison-limit");
                    if (index % 512 == 0) tick(start, limits, clock);
                    if (!refersTo(soft, referent)) continue;
                    result.add("matchedCohort", 1);
                    result.note("entry:" + visited);
                    break;
                }
            }
            if (list.size() != sizeBefore) throw new Stop("PARTIAL", "unstable");
            result.add("entries", sizeBefore);
            result.commit();
        } catch (Stop stop) {
            result.values.setProperty("status", stop.status);
            result.values.setProperty("reason", stop.reason);
        } catch (ReflectiveOperationException | IllegalArgumentException | IllegalStateException
                 | ClassCastException problem) {
            result.values.setProperty("status", "UNSUPPORTED");
            result.values.setProperty("reason", "access");
            result.values.setProperty("failureKind", problem.getClass().getSimpleName());
        }
        result.values.setProperty("durationNs", Long.toString(clock.getAsLong() - start));
        return result;
    }

    private static final class Stop extends RuntimeException {
        final String status, reason;

        Stop(String status, String reason) { super(reason, null, false, false); this.status = status; this.reason = reason; }
    }

    /** Identity comparison of a reference the audit owns against a referent; no host method is involved. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean refersTo(Reference<?> reference, Object referent) {
        return ((Reference) reference).refersTo(referent);
    }

    private static void tick(long start, Limits limits, LongSupplier clock) {
        if (clock.getAsLong() - start >= limits.durationNs()) throw new Stop("PARTIAL", "time-limit");
    }

    private static boolean same(java.security.CodeSource origin, Class<?> type) {
        if (origin == null) return false;
        var actual = type.getProtectionDomain().getCodeSource();
        if (actual == null) return false;
        var left = origin.getLocation();
        var right = actual.getLocation();
        return left != null && right != null && left.toExternalForm().contentEquals(right.toExternalForm());
    }
}

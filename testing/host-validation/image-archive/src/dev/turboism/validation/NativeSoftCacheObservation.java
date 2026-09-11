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
    private static final String DECODED_IMAGE = "image";
    private static final String ARCHIVE_BYTES = "imageFileBuf";
    private static final String WIDTH = "width";
    private static final String HEIGHT = "height";
    private static final int ENTRY_LIMIT = 8192;
    private static final int COMPARISON_LIMIT = 20_000_000;
    /** Diagnostic budget for one capture. O(entries) once the O(entries x cohort) matching work completes. */
    private static final long CAPTURE_BUDGET_NS = 1_000_000_000L;
    /**
     * The host's own public static counters. Reading these quantifies the whole process without dereferencing a single
     * soft reference, and they are the only way to see the entries outside the retained cohort.
     */
    private static final String[] HOST_COUNTERS = {
            "access$getCreatedCount$cp", "access$getDisposedCount$cp", "access$getByteDataBytes$cp",
            "access$getDEBUG$cp", "access$getDEBUG_IMAGES$cp"};
    private static final String[] COUNT_KEYS =
            {"entries", "visited", "cohortSize", "matchedCohort", "matchedDistinct", "matchedDecoded",
             "matchedArchived", "matchedArchivedBytes", "matchedWithArchiveBytes", "matchedUnreadable",
             "matchedDecodedPixels", "matchedDecodedBytes"};

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
            long remaining = Math.max(0, CAPTURE_BUDGET_NS - (System.nanoTime() - start));
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
            readHostCounters(owner, result);
            Object cache = field.get(null);
            Method accessor = holder.getMethod(HOLDER_ACCESSOR);
            if (Modifier.isStatic(accessor.getModifiers()) || accessor.getParameterCount() != 0
                    || !SOFT_REFERENCE.equals(accessor.getReturnType().getName())) {
                throw new NoSuchMethodException(HOLDER_ACCESSOR);
            }
            // The holder class is package-private, so a public accessor still needs the member to be made accessible.
            if (!accessor.trySetAccessible()) throw new NoSuchMethodException(HOLDER_ACCESSOR);
            // Best-effort resource-state fields: a resource's decoded pixels and its archived PNG bytes.
            Field decoded = optional(owner, DECODED_IMAGE);
            Field archived = optional(owner, ARCHIVE_BYTES);
            Field width = optional(owner, WIDTH);
            Field height = optional(owner, HEIGHT);
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
            // A cohort member is resolved once: further entries cannot add information about it.
            boolean[] resolved = new boolean[cohort.size()];
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
                    if (resolved[index]) continue;
                    Reference<?> candidate = cohort.get(index);
                    if (candidate == null) continue;
                    Object referent = candidate.get();
                    if (referent == null) continue;
                    comparisons++;
                    if (comparisons > limits.comparisons()) throw new Stop("PARTIAL", "comparison-limit");
                    if ((comparisons & 511) == 0) tick(start, limits, clock);
                    if (!refersTo(soft, referent)) continue;
                    resolved[index] = true;
                    result.add("matchedCohort", 1);
                    result.note("entry:" + visited);
                    recordResourceState(referent, decoded, archived, width, height, result);
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

    /**
     * Reads the host's own aggregate counters. These are public static accessors on a public class, so this is a plain
     * reflective call on a synthetic accessor: no host instance is touched and no soft reference is dereferenced.
     */
    private static void readHostCounters(Class<?> owner, Result result) {
        for (String name : HOST_COUNTERS) {
            String key = switch (name) {
                case "access$getCreatedCount$cp" -> "hostCreated";
                case "access$getDisposedCount$cp" -> "hostDisposed";
                case "access$getByteDataBytes$cp" -> "hostArchivedBytes";
                case "access$getDEBUG$cp" -> "hostDebugEnabled";
                default -> "hostDebugImages";
            };
            try {
                Method accessor = owner.getMethod(name);
                accessor.setAccessible(true);
                Object value = accessor.invoke(null);
                if (value instanceof Number number) result.values.setProperty(key, Long.toString(number.longValue()));
                else if (value instanceof Boolean flag) result.values.setProperty(key, flag.toString());
                else if (value instanceof java.util.List<?> list) result.values.setProperty(key, Integer.toString(list.size()));
                else result.values.setProperty(key, "unsupported");
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                result.values.setProperty(key, "unreadable");
            }
        }
        try {
            long created = Long.parseLong(result.values.getProperty("hostCreated", ""));
            long disposed = Long.parseLong(result.values.getProperty("hostDisposed", ""));
            result.values.setProperty("hostLive", Long.toString(created - disposed));
        } catch (NumberFormatException unavailable) {
            result.values.setProperty("hostLive", "unreadable");
        }
    }

    /** A missing or inaccessible field is not an error: the resource-state counters are best effort. */
    private static Field optional(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            return field.trySetAccessible() ? field : null;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /**
     * Classifies one matched resource: still holding decoded pixels, or archived down to PNG bytes. Only field reads
     * are performed and the decoded image object is never touched beyond the null check.
     */
    private static void recordResourceState(Object resource, Field decoded, Field archived, Field width, Field height,
                                            Result result) {
        try {
            if (decoded == null || archived == null) { result.add("matchedUnreadable", 1); return; }
            boolean live = decoded.get(resource) != null;
            if (live) {
                result.add("matchedDecoded", 1);
                // A decoded ARGB surface costs width*height*4 bytes; this is the pixel cost the cache keeps alive.
                if (width != null && height != null && width.getInt(resource) > 0 && height.getInt(resource) > 0) {
                    long pixels = (long) width.getInt(resource) * height.getInt(resource);
                    result.add("matchedDecodedPixels", pixels);
                    result.add("matchedDecodedBytes", pixels * 4);
                }
            } else {
                result.add("matchedArchived", 1);
            }
            Object bytes = archived.get(resource);
            if (bytes instanceof byte[] buffer) {
                result.add("matchedWithArchiveBytes", 1);
                result.add("matchedArchivedBytes", buffer.length);
            }
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            result.add("matchedUnreadable", 1);
        }
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

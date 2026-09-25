package dev.turboism.validation;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Properties;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;

/** Test-only, non-atomic observation of already-materialized model-image resources. */
final class NativeImageRetainObservation {
    private static final String MODEL = "com.live2d.cubism.doc.model.";
    private static final String IMAGE = MODEL + "texture.modelImage.";
    private static final String[] COUNTS = {"groups", "images", "missingImages", "resources",
            "activeRecords", "releasedRecords", "releasedRecordsAbsentActive", "releasedUsers",
            "modelUsersSameSource", "modelUsersDifferentSource", "modelUsersNullSource"};

    /** Only scalars and weak references escape capture; never store Layout or a callback here. */
    static final class Snapshot {
        final Properties values = new Properties();
        final List<WeakReference<?>> resources = new ArrayList<>();
        final List<WeakReference<?>> users = new ArrayList<>();

        Snapshot() {
            values.setProperty("schemaVersion", "1");
            values.setProperty("coverage", "model-image-filtered-resources");
            values.setProperty("consistency", "non-atomic-identity-checked");
            values.setProperty("status", "COMPLETE");
            values.setProperty("reason", "none");
            for (String key : COUNTS) values.setProperty(key, "0");
        }
        void add(String key, long count) {
            values.setProperty(key, Long.toString(Long.parseLong(values.getProperty(key)) + count));
        }
        void write(Properties target, String prefix) {
            for (String key : values.stringPropertyNames()) target.setProperty(prefix + "." + key, values.getProperty(key));
        }
        void writeWeak(Properties target, String prefix) {
            weak(target, prefix + ".resources", resources);
            weak(target, prefix + ".users", users);
        }
        private static void weak(Properties target, String prefix, List<WeakReference<?>> refs) {
            long cleared = 0;
            for (WeakReference<?> ref : refs) if (ref.refersTo(null)) cleared++;
            target.setProperty(prefix + ".total", Integer.toString(refs.size()));
            target.setProperty(prefix + ".cleared", Long.toString(cleared));
            target.setProperty(prefix + ".notCleared", Long.toString(refs.size() - cleared));
        }
    }

    record Limits(int groups, int images, int records, long durationNs) {
        Limits {
            if (groups < 0 || images < 0 || records < 0 || durationNs < 0) throw new IllegalArgumentException("negative limits");
        }
    }

    /** Package-private synthetic layout injection only; no alternate CLI or production host routing. */
    static final class Layout {
        final Class<?> document, source, manager, group, image, resource, record, user, groupList;
        final Field modelSource, textureManager, groups, images, filtered, imageSource, active, released, recordUser;
        Layout(Class<?> document, Class<?> source, Class<?> manager, Class<?> group, Class<?> image,
               Class<?> resource, Class<?> record, Class<?> user, Class<?> groupList) throws ReflectiveOperationException {
            this.document = document; this.source = source; this.manager = manager; this.group = group;
            this.image = image; this.resource = resource; this.record = record; this.user = user; this.groupList = groupList;
            modelSource = field(document, "_modelSource", source);
            textureManager = field(source, "textureManager", manager);
            groups = field(manager, "_modelImageGroups", groupList);
            images = field(group, "_modelImages", groupList);
            filtered = field(image, "_filteredImage", resource);
            imageSource = field(image, "_modelSource", source);
            active = field(resource, "retainCounter", ArrayList.class);
            released = field(resource, "releasedRetainUserData_forDebug", ArrayList.class);
            recordUser = field(record, "a", user);
            if (!Modifier.isFinal(recordUser.getModifiers()) || !List.class.isAssignableFrom(groupList)) {
                throw new NoSuchFieldException("unsupported ownership/list shape");
            }
        }
        private static Field field(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(name);
            if (field.getType() != type || Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                throw new NoSuchFieldException("unsupported field shape: " + name);
            }
            return field;
        }
    }

    /** Caller has verified the exact JAR hash and the active task document. */
    static Snapshot captureHost(Object document, Class<?> verifiedHost) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("observation requires EDT");
        long start = System.nanoTime();
        Snapshot result;
        try {
            ClassLoader loader = document.getClass().getClassLoader();
            String[] names = {"com.live2d.cubism.doc.modeling.CModelingDocument", MODEL + "CModelSource",
                    MODEL + "texture.CTextureManager", IMAGE + "CModelImageGroup", IMAGE + "CModelImage",
                    "com.live2d.graphics.CImageResource", "com.live2d.graphics.CImageResource$c",
                    "com.live2d.graphics.ICImageResourceUser", "com.live2d.type.CArrayList"};
            Class<?>[] types = new Class<?>[names.length];
            var origin = verifiedHost.getProtectionDomain().getCodeSource();
            var documentOrigin = document.getClass().getProtectionDomain().getCodeSource();
            if (origin == null || documentOrigin == null || verifiedHost.getClassLoader() != loader
                    || !origin.getLocation().equals(documentOrigin.getLocation())) {
                throw new ClassNotFoundException("verified host origin mismatch");
            }
            for (int i = 0; i < names.length; i++) {
                types[i] = Class.forName(names[i], false, loader);
                var actual = types[i].getProtectionDomain().getCodeSource();
                if (types[i].getClassLoader() != loader || actual == null || !origin.getLocation().equals(actual.getLocation())) {
                    throw new ClassNotFoundException("origin mismatch");
                }
            }
            Layout layout = new Layout(types[0], types[1], types[2], types[3], types[4], types[5], types[6], types[7], types[8]);
            long remaining = Math.max(0, 250_000_000L - (System.nanoTime() - start));
            result = capture(document, layout, new Limits(4096, 4096, 16384, remaining), System::nanoTime);
        } catch (ReflectiveOperationException | SecurityException problem) {
            result = new Snapshot();
            result.values.setProperty("status", "UNSUPPORTED");
            result.values.setProperty("reason", "layout-or-origin");
        }
        result.values.setProperty("durationNs", Long.toString(System.nanoTime() - start));
        return result;
    }

    private static final class Stop extends RuntimeException {
        final String status, reason;
        Stop(String status, String reason) { super(reason, null, false, false); this.status = status; this.reason = reason; }
    }
    private static void unsupported(boolean valid) {
        if (!valid) throw new Stop("UNSUPPORTED", "shape");
    }
    private static void stable(boolean valid) {
        if (!valid) throw new Stop("PARTIAL", "unstable");
    }
    private static void limit(boolean valid, String reason) {
        if (!valid) throw new Stop("PARTIAL", reason);
    }
    private static final class Budget {
        final Limits limits;
        final LongSupplier clock;
        final long start;
        int images, records;
        Budget(Limits limits, LongSupplier clock) { this.limits = limits; this.clock = clock; start = clock.getAsLong(); }
        void tick() { limit(clock.getAsLong() - start < limits.durationNs(), "time-limit"); }
    }
    private static Object read(Field field, Object target, Budget budget) throws IllegalAccessException {
        budget.tick();
        return field.get(target);
    }
    private static List<?> list(Object value, Class<?> expected) {
        unsupported(value != null && value.getClass() == expected);
        return (List<?>) value;
    }
    private static Object[] copy(List<?> list, int maximum, String reason, Budget budget) {
        budget.tick();
        int size = list.size();
        limit(size <= maximum, reason);
        Object[] result = new Object[size];
        for (int i = 0; i < size; i++) { budget.tick(); result[i] = list.get(i); }
        verify(list, result, budget);
        return result;
    }
    private static void verify(List<?> list, Object[] values, Budget budget) {
        budget.tick(); stable(list.size() == values.length);
        for (int i = 0; i < values.length; i++) { budget.tick(); stable(list.get(i) == values[i]); }
        stable(list.size() == values.length);
    }

    static Snapshot capture(Object document, Layout layout, Limits limits, LongSupplier clock) {
        Snapshot result = new Snapshot();
        Budget budget = new Budget(limits, clock);
        IdentityHashMap<Object, Boolean> resources = new IdentityHashMap<>(), users = new IdentityHashMap<>();
        try {
            unsupported(document != null && document.getClass() == layout.document);
            Object source = read(layout.modelSource, document, budget);
            unsupported(source != null && source.getClass() == layout.source);
            Object manager = read(layout.textureManager, source, budget);
            unsupported(manager != null && manager.getClass() == layout.manager);
            List<?> groups = list(read(layout.groups, manager, budget), layout.groupList);
            Object[] groupCopy = copy(groups, limits.groups(), "group-limit", budget);
            for (Object group : groupCopy) {
                budget.tick(); unsupported(group != null && group.getClass() == layout.group);
                result.add("groups", 1);
                List<?> images = list(read(layout.images, group, budget), layout.groupList);
                Object[] imageCopy = copy(images, limits.images() - budget.images, "image-limit", budget);
                budget.images += imageCopy.length;
                for (Object image : imageCopy) {
                    budget.tick(); unsupported(image != null && image.getClass() == layout.image);
                    result.add("images", 1);
                    Object resource = read(layout.filtered, image, budget);
                    if (resource == null) { result.add("missingImages", 1); continue; }
                    unsupported(resource.getClass() == layout.resource);
                    if (!resources.containsKey(resource)) {
                        observeResource(resource, source, layout, budget, result, users);
                        resources.put(resource, Boolean.TRUE);
                    }
                    stable(read(layout.filtered, image, budget) == resource);
                }
                verify(images, imageCopy, budget);
                stable(read(layout.images, group, budget) == images);
            }
            verify(groups, groupCopy, budget);
            stable(read(layout.groups, manager, budget) == groups);
            stable(read(layout.textureManager, source, budget) == manager);
            stable(read(layout.modelSource, document, budget) == source);
        } catch (Stop stop) {
            result.values.setProperty("status", stop.status); result.values.setProperty("reason", stop.reason);
        } catch (IndexOutOfBoundsException | java.util.ConcurrentModificationException problem) {
            result.values.setProperty("status", "PARTIAL"); result.values.setProperty("reason", "unstable");
        } catch (IllegalAccessException | IllegalArgumentException problem) {
            result.values.setProperty("status", "UNSUPPORTED"); result.values.setProperty("reason", "field-access");
        }
        result.values.setProperty("durationNs", Long.toString(clock.getAsLong() - budget.start));
        result.values.setProperty("recordsExamined", Integer.toString(budget.records));
        return result;
    }

    private static void observeResource(Object resource, Object source, Layout layout, Budget budget,
                                        Snapshot result, IdentityHashMap<Object, Boolean> allUsers) throws IllegalAccessException {
        List<?> active = list(read(layout.active, resource, budget), ArrayList.class);
        List<?> released = list(read(layout.released, resource, budget), ArrayList.class);
        Object[] activeRecords = copy(active, budget.limits.records() - budget.records, "record-limit", budget);
        budget.records += activeRecords.length;
        Object[] releasedRecords = copy(released, budget.limits.records() - budget.records, "record-limit", budget);
        budget.records += releasedRecords.length;
        IdentityHashMap<Object, Boolean> activeUsers = new IdentityHashMap<>();
        Object[] activeUserCopy = users(activeRecords, layout, budget);
        Object[] releasedUserCopy = users(releasedRecords, layout, budget);
        Object[] sources = new Object[releasedUserCopy.length];
        for (Object user : activeUserCopy) { budget.tick(); activeUsers.put(user, Boolean.TRUE); }
        long absent = 0;
        for (int i = 0; i < releasedUserCopy.length; i++) {
            budget.tick(); Object user = releasedUserCopy[i];
            if (!activeUsers.containsKey(user)) absent++;
            if (user.getClass() == layout.image) sources[i] = read(layout.imageSource, user, budget);
        }
        verify(active, activeRecords, budget); verify(released, releasedRecords, budget);
        stable(read(layout.active, resource, budget) == active && read(layout.released, resource, budget) == released);
        for (int i = 0; i < activeRecords.length; i++) stable(read(layout.recordUser, activeRecords[i], budget) == activeUserCopy[i]);
        for (int i = 0; i < releasedRecords.length; i++) {
            Object user = releasedUserCopy[i];
            stable(read(layout.recordUser, releasedRecords[i], budget) == user);
            if (user.getClass() == layout.image) stable(read(layout.imageSource, user, budget) == sources[i]);
        }
        // Stage scalars/weak refs with deadline checks, then commit the whole resource or none of it.
        Snapshot staged = new Snapshot();
        staged.add("resources", 1); staged.add("activeRecords", activeRecords.length);
        staged.add("releasedRecords", releasedRecords.length); staged.add("releasedRecordsAbsentActive", absent);
        staged.resources.add(new WeakReference<>(resource));
        for (int i = 0; i < releasedUserCopy.length; i++) {
            budget.tick(); Object user = releasedUserCopy[i];
            if (allUsers.put(user, Boolean.TRUE) != null) continue;
            staged.add("releasedUsers", 1); staged.users.add(new WeakReference<>(user));
            if (user.getClass() == layout.image) staged.add(sources[i] == null ? "modelUsersNullSource"
                    : sources[i] == source ? "modelUsersSameSource" : "modelUsersDifferentSource", 1);
        }
        verify(active, activeRecords, budget); verify(released, releasedRecords, budget);
        budget.tick();
        for (String key : COUNTS) result.add(key, Long.parseLong(staged.values.getProperty(key)));
        result.resources.addAll(staged.resources); result.users.addAll(staged.users);
    }
    private static Object[] users(Object[] records, Layout layout, Budget budget) throws IllegalAccessException {
        Object[] result = new Object[records.length];
        for (int i = 0; i < records.length; i++) {
            budget.tick(); unsupported(records[i] != null && records[i].getClass() == layout.record);
            result[i] = read(layout.recordUser, records[i], budget);
            unsupported(result[i] != null && layout.user.isInstance(result[i]));
        }
        return result;
    }
}

package dev.turboism.bootstrap;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One enumeration of the JVM's loaded classes, indexed by binary name, so an install pass
 * resolves its targets without rescanning every loaded class per hook.
 *
 * <p>{@link Instrumentation#getAllLoadedClasses()} walks the global system dictionary on every
 * call. A launch installs dozens of hooks and most of them only need to know whether a handful of
 * reviewed owners are already defined, so one shared enumeration per install phase replaces one
 * full scan per hook.</p>
 *
 * <p>A snapshot is only valid while no code between the scan and the use can define a class the
 * pass looks for. {@link LoadedClassSnapshot} therefore drops the phase sample when a hook defines
 * a class the sample cannot answer for, and every phase pass starts from a fresh sample.</p>
 */
final class LoadedClassIndex {

    private final Class<?>[] classes;
    private final Map<String, List<Class<?>>> byName;

    private LoadedClassIndex(final Class<?>[] classes) {
        this.classes = classes;
        this.byName = index(classes);
    }

    /**
     * Captures the currently loaded classes.
     *
     * @param instrumentation the instrumentation to enumerate through
     * @return the snapshot for this pass
     */
    static LoadedClassIndex snapshot(final Instrumentation instrumentation) {
        return new LoadedClassIndex(instrumentation.getAllLoadedClasses());
    }

    /**
     * Takes one enumeration and indexes it by binary name, so a name lookup costs the number of
     * names asked for instead of the number of loaded classes.
     */
    private static Map<String, List<Class<?>>> index(final Class<?>[] classes) {
        final Map<String, List<Class<?>>> names = new HashMap<>(classes.length * 2);
        for (final Class<?> loaded : classes) {
            if (loaded == null) continue;
            names.computeIfAbsent(loaded.getName(), ignored -> new ArrayList<>(1))
                    .add(loaded);
        }
        names.replaceAll((name, owners) -> List.copyOf(owners));
        return Map.copyOf(names);
    }

    /**
     * Returns the loaded classes that {@code filter} accepts under {@code loader}.
     *
     * @param loader the defining loader every accepted class must have
     * @param filter additional acceptance test, such as an owner-name check
     * @return the accepted classes in enumeration order
     */
    List<Class<?>> select(final ClassLoader loader, final Predicate<Class<?>> filter) {
        final List<Class<?>> selected = new ArrayList<>();
        for (final Class<?> loaded : classes) {
            if (loaded != null && loaded.getClassLoader() == loader && filter.test(loaded)) {
                selected.add(loaded);
            }
        }
        return selected;
    }

    /**
     * Returns the loaded classes whose binary name is in {@code binaryNames} and whose defining
     * loader is {@code loader}, for a caller that must transform each of them.
     *
     * @param binaryNames the binary class names to look for
     * @param loader the defining loader every returned class must have
     * @return the matching classes, in the iteration order of {@code binaryNames}
     */
    List<Class<?>> select(final Collection<String> binaryNames, final ClassLoader loader) {
        final List<Class<?>> selected = new ArrayList<>(binaryNames.size());
        for (final String binaryName : binaryNames) {
            for (final Class<?> loaded : named(binaryName)) {
                if (loaded.getClassLoader() == loader) selected.add(loaded);
            }
        }
        return selected;
    }

    /**
     * Reports whether any loaded class has the exact binary name, whatever its loader.
     *
     * @param binaryName the binary class name to look for
     * @return {@code true} when that name is already defined
     */
    boolean containsName(final String binaryName) {
        return byName.containsKey(binaryName);
    }

    /**
     * Reports whether {@code loader} defines a class with the exact binary name.
     *
     * @param binaryName the binary class name to look for
     * @param loader the defining loader to match
     * @return {@code true} when that loader already defined the name
     */
    boolean containsName(final String binaryName, final ClassLoader loader) {
        for (final Class<?> loaded : named(binaryName)) {
            if (loaded.getClassLoader() == loader) return true;
        }
        return false;
    }

    /**
     * @param binaryName the binary class name to look for
     * @return every loaded class with that name, in enumeration order
     */
    List<Class<?>> named(final String binaryName) {
        return byName.getOrDefault(binaryName, List.of());
    }

    /** @return the enumeration size this snapshot was built from */
    int size() {
        return classes.length;
    }

    /**
     * @return every class of the enumeration, including {@code null} entries the JVM may report
     */
    Class<?>[] all() {
        return classes.clone();
    }
}

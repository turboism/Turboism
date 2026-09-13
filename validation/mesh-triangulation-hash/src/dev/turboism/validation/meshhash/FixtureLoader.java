package dev.turboism.validation.meshhash;

import java.util.Map;

/**
 * Defines the fixture package from supplied bytes so the same behaviour harness can run against the
 * original and the patched class in one JVM. Only the fixture package is child-defined; everything
 * else delegates to the parent, so no general-purpose loader capability is provided.
 */
final class FixtureLoader extends ClassLoader {
    private final Map<String, byte[]> classes;
    private final String childPrefix;

    FixtureLoader(final ClassLoader parent, final String childPrefix,
                  final Map<String, byte[]> classes) {
        super(parent);
        this.childPrefix = childPrefix;
        this.classes = classes;
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
            throws ClassNotFoundException {
        if (!name.startsWith(childPrefix)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            final Class<?> already = findLoadedClass(name);
            if (already != null) return already;
            final byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException("fixture class is not supplied: " + name);
            }
            final Class<?> defined = defineClass(name, bytes, 0, bytes.length);
            if (resolve) resolveClass(defined);
            return defined;
        }
    }
}
